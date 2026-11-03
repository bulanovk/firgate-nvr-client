package com.frigate.android.blackbox

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Основа black-box тестов: управляют приложением только через UI (UiAutomator) и общаются с mock Frigate
 * по loopback. Значения приходят аргументами instrumentation (`-e`), секретов среди них нет:
 * логин и пароль mock читает сам тест у mock-сервера.
 */
abstract class BlackboxTest {
    /** При падении добавляет к ошибке то, что реально было на экране: иначе причину приходится угадывать. */
    @get:Rule
    val screenOnFailure = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    throw AssertionError("${t.message}\nна экране: ${visibleTexts()}", t)
                }
            }
        }
    }

    /** Каждый тест начинает в естественной ориентации и возвращает автоповорот: упавший тест не должен оставлять экран повёрнутым. */
    @Before
    fun naturalOrientation() {
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    @After
    fun restoreRotationAfterTest() {
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    private fun visibleTexts(): String = try {
        val out = ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        Regex("(?:text|content-desc)=\"([^\"]+)\"").findAll(out.toString()).map { it.groupValues[1] }
            .distinct().take(40).joinToString(" | ")
    } catch (e: Exception) {
        "нет данных (${e.javaClass.simpleName})"
    }

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.context
    private val args = InstrumentationRegistry.getArguments()

    protected val appId: String = requireArg("bbAppId")
    protected val mockUrl: String = "http://${requireArg("bbMockHost")}:${requireArg("bbMockPort")}"

    private fun requireArg(name: String): String =
        args.getString(name)?.takeIf { it.isNotBlank() } ?: error("не задан аргумент instrumentation $name")

    protected fun control(path: String): String {
        val connection = URL(mockUrl + path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** POST к управлению mock: тело — готовое сообщение (например, кадр WebSocket, который mock разошлёт приложению). */
    protected fun controlPost(path: String, body: String): String {
        val connection = URL(mockUrl + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Запросы, которые приложение прислало mock с момента reset (без путей управления). */
    protected fun requestLog(): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(control("/__control/log")).map { it.groupValues[1] }.toList()

    protected fun requestCount(request: String): Int = requestLog().count { it == request }

    /** Ждёт, пока число запросов станет не меньше [atLeast]; экран держится включённым. */
    protected fun awaitRequests(request: String, atLeast: Int, timeoutMs: Long = 20_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (requestCount(request) >= atLeast) return
            keepScreenOn()
            SystemClock.sleep(300)
        }
        error(
            "mock получил ${requestCount(request)} из $atLeast запросов «$request» за $timeoutMs мс; " +
                "последние запросы: ${requestLog().takeLast(25)}",
        )
    }

    protected fun credentials(): Pair<String, String> {
        val body = control("/__control/credentials")
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
            ?: error("mock не вернул $name")
        return field("user") to field("password")
    }

    /** Чистое состояние: данные только blackbox-приложения (id проверен раннером), затем запуск. */
    protected fun freshLaunch() {
        require(appId.endsWith(".blackbox")) { "clearState допустим только для blackbox-приложения" }
        control("/__control/reset")
        device.executeShellCommand("pm clear $appId")
        launch()
    }

    protected fun launch() {
        // Экран мог погаснуть, пока ставился APK или шёл предыдущий тест: без пробуждения скриншот чёрный.
        val intent = context.packageManager.getLaunchIntentForPackage(appId)
            ?: error("приложение $appId не установлено")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // Сразу после установки APK или смены теста экран иногда ещё просыпается: запуск повторяется, а не валит тест.
        repeat(3) { attempt ->
            device.wakeUp()
            context.startActivity(intent)
            if (device.wait(Until.hasObject(By.pkg(appId).depth(0)), 15_000)) return
            if (attempt < 2) keepScreenOn()
        }
        error("приложение не вышло на передний план")
    }

    /** Возврат к уже запущенному приложению без пересоздания (как тап по его значку): задача поднимается, а не сбрасывается. */
    protected fun bringToFront() {
        val intent = context.packageManager.getLaunchIntentForPackage(appId) ?: error("приложение $appId не установлено")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(intent)
    }

    protected fun text(value: String): BySelector = By.pkg(appId).text(value)
    protected fun desc(value: String): BySelector = By.pkg(appId).desc(value)

    protected fun waitFor(selector: BySelector, timeoutMs: Long = 15_000): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs)
            ?: error("не найден элемент $selector за $timeoutMs мс")

    protected fun assertVisible(selector: BySelector, timeoutMs: Long = 10_000) {
        assertNotNull("ожидался элемент $selector", device.wait(Until.findObject(selector), timeoutMs))
    }

    protected fun assertGone(selector: BySelector, timeoutMs: Long = 5_000) {
        device.wait(Until.gone(selector), timeoutMs)
        assertNull("элемент $selector не должен быть виден", device.findObject(selector))
    }

    /** Compose перерисовывает узлы, поэтому устаревший узел ищется заново, а не считается ошибкой приложения. */
    protected fun tap(selector: BySelector, timeoutMs: Long = 15_000) {
        repeat(3) {
            try {
                waitFor(selector, timeoutMs).click()
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                SystemClock.sleep(300)
            }
        }
        waitFor(selector, timeoutMs).click()
    }

    private fun editFields(): List<UiObject2> =
        device.findObjects(By.pkg(appId).clazz("android.widget.EditText"))

    /**
     * Текст задаётся прямо в поле ввода (accessibility SET_TEXT), без тапа и клавиатуры: клавиатура Samsung
     * перекрывала форму, а Back для её закрытия без IME сворачивал приложение.
     */
    protected fun setField(index: Int, value: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val fields = editFields()
            if (fields.size > index) {
                try {
                    fields[index].text = value
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // экран перерисовался, ищем поля заново
                }
            }
            SystemClock.sleep(200)
        }
        error("поле ввода №$index не найдено")
    }

    /** Прокручивает экран свайпом вверх, пока элемент не появится: как это делает пользователь. */
    protected fun scrollTo(selector: BySelector, maxSwipes: Int = 6) {
        repeat(maxSwipes) {
            if (device.wait(Until.hasObject(selector), 1_500)) return
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe(w / 2, h * 6 / 10, w / 2, h * 3 / 10, 20)
        }
        assertVisible(selector, 3_000)
    }

    protected fun signIn() {
        val (user, password) = credentials()
        // Онбординг: поля «Server name» (0) и «Server URL» (1). URL — адрес mock на устройстве (adb reverse).
        waitFor(text("Server name"))
        setField(1, mockUrl)
        tap(text("Continue"))
        // Экран входа: «Username» (0) и «Password» (1).
        waitFor(text("Sign in"), 20_000)
        setField(0, user)
        setField(1, password)
        tap(text("Sign in"))
        waitFor(text("Live"), 20_000)
    }

    // --- Проверка кадра по пикселям: фреймворк снимает экран снаружи процесса приложения ---

    /** Видео mock доступно, только если раннер сгенерировал его (BB_MEDIA_PYTHON). */
    protected fun requireMedia() = assumeTrue("mock без видео (не задан BB_MEDIA_PYTHON)", args.getString("bbMedia") == "1")

    /** Яркий пурпурный квадрат тестового видео; такого цвета в интерфейсе и в mock-снимке latest.webp нет. */
    data class Square(val pixels: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }

    /** Скриншот упавшей проверки: лежит в файлах тестового приложения (adb pull), чтобы причину видеть, а не гадать. */
    protected fun saveEvidence(bitmap: Bitmap, name: String) {
        val dir = context.getExternalFilesDir(null) ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    protected fun saveEvidence(text: String, name: String) {
        val dir = context.getExternalFilesDir(null) ?: return
        java.io.File(dir, "$name.txt").writeText(text)
    }

    protected fun screenshot(): Bitmap = instrumentation.uiAutomation.takeScreenshot()
        ?: error("снимок экрана недоступен")

    /**
     * [minChannel] — порог красного и синего каналов. Поверх карточки событий лежит градиентная подложка,
     * которая приглушает кадр, поэтому для карточек порог ниже, чем для полноэкранного видео.
     */
    protected fun findSquare(
        bitmap: Bitmap,
        step: Int = 3,
        region: android.graphics.Rect? = null,
        minChannel: Int = 190,
    ): Square? {
        var count = 0
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1
        val row = IntArray(bitmap.width)
        val yRange = (region?.top?.coerceAtLeast(0) ?: 0) until (region?.bottom?.coerceAtMost(bitmap.height) ?: bitmap.height)
        val xRange = (region?.left?.coerceAtLeast(0) ?: 0) until (region?.right?.coerceAtMost(bitmap.width) ?: bitmap.width)
        for (y in yRange step step) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in xRange step step) {
                val c = row[x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                if (r > minChannel && b > minChannel && g < minChannel / 2) {
                    count++
                    if (x < left) left = x; if (x > right) right = x
                    if (y < top) top = y; if (y > bottom) bottom = y
                }
            }
        }
        return if (count >= 20) Square(count, left, top, right, bottom) else null
    }

    /** Доля синих пикселей миниатюры обзора (mock отдаёт сплошной синий PNG) внутри области экрана. */
    protected fun blueShare(bitmap: Bitmap, region: android.graphics.Rect): Double {
        var blue = 0; var total = 0
        for (y in region.top.coerceAtLeast(0) until region.bottom.coerceAtMost(bitmap.height) step 6) {
            for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(bitmap.width) step 6) {
                val c = bitmap.getPixel(x, y)
                total++
                if ((c shr 16 and 0xFF) < 60 && (c shr 8 and 0xFF) < 60 && (c and 0xFF) > 190) blue++
            }
        }
        return if (total == 0) 0.0 else blue.toDouble() / total
    }

    /** Ждёт декодированный кадр: пурпурный квадрат на экране. */
    protected fun awaitSquare(timeoutMs: Long = 40_000, region: android.graphics.Rect? = null, minChannel: Int = 190): Square {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            keepScreenOn()
            val shot = screenshot()
            val square = findSquare(shot, region = region, minChannel = minChannel)
            shot.recycle()
            if (square != null) return square
            SystemClock.sleep(500)
        }
        screenshot().let { saveEvidence(it, "no-frame"); it.recycle() }
        error("за $timeoutMs мс на экране не появился кадр видео (пурпурный квадрат)")
    }

    /**
     * Устойчивое воспроизведение: каждый из [samples] снимков с интервалом [intervalMs] содержит кадр,
     * а положение квадрата меняется — картинка движется, а не застыла.
     */
    /**
     * Скриншоты не считаются действием пользователя, и за 30 с экран гаснет (приложение уходит в ON_STOP и
     * освобождает плеер). Нажатие Shift — действие пользователя, которое сбрасывает таймаут экрана и ничего
     * не делает в приложении; настройки устройства не меняются.
     */
    protected fun keepScreenOn() {
        device.executeShellCommand("input keyevent KEYCODE_SHIFT_LEFT")
    }

    protected fun assertPlaying(
        samples: Int = 8,
        intervalMs: Long = 1_000,
        region: android.graphics.Rect? = null,
        minChannel: Int = 190,
    ) {
        val seen = mutableListOf<Square>()
        repeat(samples) {
            keepScreenOn()
            val shot = screenshot()
            val square = findSquare(shot, region = region, minChannel = minChannel)
            if (square == null) saveEvidence(shot, "lost-frame")
            shot.recycle()
            org.junit.Assert.assertNotNull("кадр пропал на отсчёте ${it + 1} из $samples", square)
            seen += square!!
            SystemClock.sleep(intervalMs)
        }
        val positions = seen.map { it.centerX to it.centerY }.distinct().size
        org.junit.Assert.assertTrue("картинка застыла: $positions позиций на $samples отсчётов", positions >= samples / 2)
    }

    /**
     * Воспроизведение без привязки к положению квадрата: центр экрана зелёный (фон тестового видео), а его
     * оттенок меняется от кадра к кадру. Нужен там, где часть картинки скрыта окном (альбомная компоновка).
     */
    protected fun assertPlayingByBackground(samples: Int = 6, intervalMs: Long = 1_000) {
        val shades = mutableListOf<Int>()
        // После поворота поверхность на мгновение пересоздаётся: первый кадр ждём, а не требуем сразу.
        val settle = SystemClock.uptimeMillis() + 10_000
        fun greenAt(shot: Bitmap) = listOf(2, 3, 4).map { shot.getPixel(shot.width * it / 6, shot.height / 2) }.firstOrNull {
            val r = (it shr 16) and 0xFF; val g = (it shr 8) and 0xFF; val b = it and 0xFF
            g in 25..160 && r < 60 && b < 60
        }
        while (SystemClock.uptimeMillis() < settle) {
            val shot = screenshot(); val ok = greenAt(shot) != null; shot.recycle()
            if (ok) break
            SystemClock.sleep(300)
        }
        repeat(samples) {
            keepScreenOn()
            val shot = screenshot()
            // Несколько точек у центра: в одну из них может попасть пурпурный квадрат, фон виден в остальных.
            val green = listOf(2, 3, 4).map { shot.getPixel(shot.width * it / 6, shot.height / 2) }.firstOrNull {
                val r = (it shr 16) and 0xFF; val g = (it shr 8) and 0xFF; val b = it and 0xFF
                g in 25..160 && r < 60 && b < 60
            }
            shot.recycle()
            if (green == null) org.junit.Assert.fail("в центре экрана нет кадра видео на отсчёте ${it + 1} из $samples")
            val g = (green!! shr 8) and 0xFF
            shades += g
            SystemClock.sleep(intervalMs)
        }
        org.junit.Assert.assertTrue("картинка застыла: ${shades.distinct().size} оттенков на $samples отсчётов",
            shades.distinct().size >= samples / 2)
    }

    // --- Ползунки: положение читается и задаётся через accessibility, как это делает служба доступности ---

    private fun sliders(): List<android.view.accessibility.AccessibilityNodeInfo> {
        val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
            node ?: return
            if (node.packageName?.toString() == appId && node.rangeInfo != null &&
                node.actionList.contains(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            ) result += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }

    /** Текущее значение ползунка (единственного на экране или с описанием [description]); null, пока его нет. */
    protected fun sliderValue(description: String? = null): Float? =
        sliders().firstOrNull { description == null || it.contentDescription?.toString() == description }?.rangeInfo?.current

    protected fun sliderMax(description: String? = null): Float? =
        sliders().firstOrNull { description == null || it.contentDescription?.toString() == description }?.rangeInfo?.max

    protected fun setSliderValue(value: Float, description: String? = null) {
        val node = sliders().firstOrNull { description == null || it.contentDescription?.toString() == description }
            ?: error("ползунок ${description ?: ""} не найден")
        val args = android.os.Bundle().apply {
            putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }
        check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)) {
            "ползунок не принял значение $value"
        }
    }

    protected fun awaitSlider(description: String? = null, timeoutMs: Long = 30_000): Float {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            sliderValue(description)?.let { return it }
            keepScreenOn()
            SystemClock.sleep(300)
        }
        error("ползунок ${description ?: ""} не появился за $timeoutMs мс")
    }

    /** Число пикселей региона, близких к [argb] (допуск [tolerance] по каждому каналу). */
    protected fun pixelsNear(bitmap: Bitmap, region: android.graphics.Rect, argb: Int, tolerance: Int = 10): Int {
        var count = 0
        for (y in region.top.coerceAtLeast(0) until region.bottom.coerceAtMost(bitmap.height)) {
            for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                if (kotlin.math.abs((c shr 16 and 0xFF) - (argb shr 16 and 0xFF)) <= tolerance &&
                    kotlin.math.abs((c shr 8 and 0xFF) - (argb shr 8 and 0xFF)) <= tolerance &&
                    kotlin.math.abs((c and 0xFF) - (argb and 0xFF)) <= tolerance
                ) count++
            }
        }
        return count
    }

    /** Цвет пикселя в центре [region] на свежем снимке экрана. */
    protected fun colorAtCenter(region: android.graphics.Rect): Int {
        val shot = screenshot()
        val color = shot.getPixel(region.centerX().coerceIn(0, shot.width - 1), region.centerY().coerceIn(0, shot.height - 1))
        shot.recycle()
        return color
    }

    /**
     * Плитка камеры сетки Live: пока играет снимок, у неё описание «Yard snapshot», а у играющей камеры снимка нет, остаётся
     * крупный кликабельный блок. Ищем по любому из двух признаков.
     */
    protected fun findCameraTile(timeoutMs: Long = 20_000): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                device.findObject(desc("Yard snapshot"))?.let { it.visibleBounds; return it }
                // Играющая или загружающаяся плитка: большой кликабельный блок (кнопки вкладок и шапок уже и ниже).
                device.findObjects(By.pkg(appId).clickable(true)).firstOrNull {
                    val b = it.visibleBounds; b.width() > 800 && b.height() > 400
                }?.let { return it }
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                // Compose перерисовал узлы: ищем заново
            }
            SystemClock.sleep(300)
        }
        error("плитка камеры Yard не найдена за $timeoutMs мс")
    }

    protected fun tapCameraTile() {
        repeat(3) {
            try {
                findCameraTile().click()
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                SystemClock.sleep(300)
            }
        }
        findCameraTile().click()
    }

    protected fun assertGridShown() {
        findCameraTile(10_000)
        assertGone(desc("Back to cameras"))
    }
}
