package com.frigate.android.blackbox

import android.os.SystemClock
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Экран погашен кнопкой питания, приложение осталось сверху. Пока экран не горит, приложение не держит
 * декодеры и ничего не запрашивает у сервера; после включения всё возобновляется само. Состояние
 * проверяется снаружи процесса: декодеры по `media.resource_manager`, запросы по журналу mock.
 */
class ScreenOffTest : BlackboxTest() {
    private fun awaitDecodersReleasedWhileOff(timeoutMs: Long = 10_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline && videoDecoders() > 0) SystemClock.sleep(500)
        assertEquals("при погашенном экране декодер должен быть освобождён", 0, videoDecoders())
    }

    @Test
    fun liveReleasesTheDecoderWhileTheScreenIsOffAndPlaysAgainAfter() {
        requireMedia()
        freshLaunch()
        signIn()
        tapCameraTile()
        assertVisible(desc("Back to cameras"))
        awaitSquare()
        assertTrue("камера должна занимать декодер", videoDecoders() > 0)

        screenOff()
        awaitDecodersReleasedWhileOff()

        screenOn()
        awaitSquare(30_000)
        assertPlaying(samples = 4, intervalMs = 1_000)
    }

    /** Часы курсора шкалы в секундах дня: на панели Timeline это единственный текст вида HH:mm:ss. */
    private fun playheadSeconds(): Int? = try {
        device.findObject(By.pkg(appId).text(Pattern.compile("\\d\\d:\\d\\d:\\d\\d")))?.text
            ?.split(":")?.map(String::toInt)?.let { (h, m, s) -> h * 3600 + m * 60 + s }
    } catch (_: androidx.test.uiautomator.StaleObjectException) {
        null
    }

    private fun awaitPlayhead(atLeast: Int, timeoutMs: Long = 20_000): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            playheadSeconds()?.let { if (it >= atLeast) return it }
            keepScreenOn()
            SystemClock.sleep(300)
        }
        val clocks = device.findObjects(By.pkg(appId).text(Pattern.compile("\\d\\d:\\d\\d(:\\d\\d)?"))).map { it.text + "@" + it.visibleBounds }
        error("курсор не дошёл до $atLeast с за $timeoutMs мс: ${playheadSeconds()}; часы на экране: $clocks")
    }

    /** После включения экрана архив продолжается с курсора, а не с места, куда пользователь переходил последним. */
    @Test
    fun historyReleasesTheDecoderWhileTheScreenIsOffAndContinuesAfter() {
        requireMedia()
        freshLaunch()
        // С фиксированным временем обзора у mock есть запись вокруг него, и курсор идёт вместе с видео.
        control("/__control/review/${System.currentTimeMillis() / 1000 - 300}")
        signIn()
        assertVisible(text("Unreviewed"), 20_000)
        tap(By.pkg(appId).text(Pattern.compile(".* ago")))
        assertVisible(desc("Back 10 seconds"), 15_000)
        awaitSquare()
        val opened = awaitPlayhead(0)
        val played = awaitPlayhead(opened + 5)
        assertTrue("архив должен занимать декодер", videoDecoders() > 0)

        screenOff()
        awaitDecodersReleasedWhileOff()

        screenOn()
        // Курсор читается после первого кадра нового плеера: до него часы показывают сохранённое место.
        awaitSquare(30_000)
        val resumed = awaitPlayhead(0)
        assertTrue("архив начался заново: курсор $resumed, до погасания экрана $played, открыт на $opened", resumed >= played - 1)
        assertPlaying(samples = 4, intervalMs = 1_000)
    }

    /** Дольше периода сверки карусели (30 с): за это время Live, будь он активен, спросил бы сервер хотя бы раз. */
    @Test
    fun nothingIsRequestedWhileTheScreenIsOff() {
        freshLaunch()
        signIn()
        assertVisible(text("Unreviewed"), 20_000)

        screenOff()
        // Запросы, начатые до остановки, успевают завершиться.
        SystemClock.sleep(2_000)
        val before = requestLog().size
        SystemClock.sleep(35_000)
        val whileOff = requestLog().drop(before)
        assertTrue("пока экран погашен, приложение не должно ничего запрашивать: $whileOff", whileOff.isEmpty())

        val reviews = requestCount("GET /api/review")
        screenOn()
        awaitRequests("GET /api/review", reviews + 1, timeoutMs = 20_000)
    }

    /**
     * Человек был в кадре, пока экран горел. После включения экрана сервер ещё недоступен (сеть или туннель
     * поднимаются), и свежего состояния нет: прежний «Detected person» нельзя показывать как текущий.
     */
    @Test
    fun activitySeenBeforeTheScreenWentOffIsNotShownAsCurrent() {
        freshLaunch()
        controlPost(
            "/__control/activity",
            """{"topic":"camera_activity","payload":{"yard":{"motion":false,"objects":[{"id":"p1","label":"person","stationary":true}],"config":{"enabled":true}}}}""",
        )
        signIn()
        assertVisible(desc("Detected person"), 20_000)

        screenOff()
        control("/__control/fail/ws/0/1000")
        SystemClock.sleep(2_000)

        screenOn()
        assertVisible(text("Live"))
        assertGone(desc("Detected person"), 10_000)
    }
}
