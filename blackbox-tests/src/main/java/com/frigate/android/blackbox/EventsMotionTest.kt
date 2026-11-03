package com.frigate.android.blackbox

import android.os.SystemClock
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вкладка Motion: общая шкала времени камер, обновление диапазона без потери камеры и настройки воспроизведения,
 * которые переживают выход из Motion. Видео и декодеры наблюдаются снаружи процесса приложения.
 */
class EventsMotionTest : EventsTestBase() {
    private val motionTab get() = desc("Motion")
    private val speed get() = desc("Motion speed")
    private val motionStamp get() = By.pkg(appId).descStartsWith("Motion timestamp:")

    private fun openMotion(cameras: Int = 1) {
        openEvents { if (cameras > 1) control("/__control/cameras/$cameras") }
        tap(motionTab)
        assertVisible(desc("Motion camera: ${if (cameras > 1) "cam1" else "yard"}"), 20_000)
    }

    /** Подпись метки времени первой плитки: она показывает общее время всех камер. */
    private fun stamp(): String = waitFor(motionStamp).contentDescription

    /**
     * Скользящее обновление диапазона (push по reviews) не должно ронять камеру: пока заменяющий запрос точек
     * движения не закончился, плитка остаётся на экране, а не мигает пустой сеткой.
     */
    @Test
    fun theRollingRefreshKeepsTheCameraWhileTheReplacementLoads() {
        openMotion()
        awaitRequests("GET /api/review/activity/motion", 1)
        control("/__control/hold/motion/on")
        try {
            controlPost(
                "/__control/ws/send",
                """{"topic":"reviews","payload":{"type":"new","before":{"data":{"objects":[]}},"after":{"severity":"alert","data":{"objects":["person"]}}}}""",
            )
            awaitRequests("GET /api/review/activity/motion", 2, timeoutMs = 25_000)
            SystemClock.sleep(1_500)
            assertVisible(desc("Motion camera: yard"), 3_000)
        } finally {
            control("/__control/hold/motion/off")
        }
    }

    /**
     * Выход из Motion закрывает панель камеры, но скорость, «Motion only» и момент времени остаются у пользователя.
     */
    @Test
    fun leavingMotionClosesTheCameraPaneButKeepsThePlaybackSettings() {
        openMotion()
        tap(speed)
        tap(text("4x"))
        tap(desc("Motion only"))
        setSliderValue((sliderMax("Motion time") ?: error("нет шкалы Motion")) / 2, "Motion time")
        SystemClock.sleep(800)
        val before = stamp()

        tap(desc("Motion clips: yard"))
        assertVisible(desc("Back to motion cameras"), 10_000)

        tap(alertsTab)
        assertGone(desc("Back to motion cameras"), 5_000)
        tap(motionTab)
        assertVisible(desc("Motion camera: yard"), 10_000)

        assertEquals("момент времени сохранён", before, stamp())
        // Подпись скорости — отдельный узел рядом с кнопкой «Motion speed»; меню закрыто, значит «4x» — это кнопка.
        assertVisible(text("4x"), 5_000)
        assertGone(text("8x"), 1_000)
        val shot = screenshot()
        val active = pixelsNear(shot, waitFor(desc("Motion only")).visibleBounds, 0xFF404B83.toInt(), tolerance = 6)
        shot.recycle()
        assertTrue("«Motion only» остаётся включённой, пикселей фона=$active", active > 1_000)
    }

    /**
     * Две камеры идут по одним часам: обе плитки декодируют кадр, после перемотки (в том числе после
     * воспроизведения) квадрат стоит в одной и той же точке кадра, при воспроизведении обе идут вперёд, а выход из
     * Motion освобождает оба декодера.
     */
    @Test
    fun twoCamerasDecodeSeekToTheSharedTimeAndReleaseTogether() {
        requireMedia()
        openMotion(cameras = 2)
        val first = waitFor(desc("Motion camera: cam1"))
        val second = waitFor(desc("Motion camera: cam2"))
        val regions = listOf(first.visibleBounds, second.visibleBounds)
        for (region in regions) awaitSquare(timeoutMs = 40_000, region = region, minChannel = 150)
        assertEquals("каждая плитка держит свой декодер", 2, videoDecoders())

        fun positions(): List<Pair<Double, Double>> {
            val shot = screenshot()
            try {
                return regions.map { region ->
                    val square = findSquare(shot, region = region, minChannel = 150) ?: error("в плитке нет кадра")
                    (square.centerX - region.left).toDouble() / region.width() to
                        (square.centerY - region.top).toDouble() / region.height()
                }
            } finally {
                shot.recycle()
            }
        }

        /** Обе камеры стоят на одном кадре (допуск — один-два кадра десятью кадрами в секунду). */
        fun awaitInSync(stage: String): List<Pair<Double, Double>> {
            val deadline = SystemClock.uptimeMillis() + 15_000
            var last = ""
            while (SystemClock.uptimeMillis() < deadline) {
                keepScreenOn()
                val found = runCatching {
                    val (a, b) = positions()
                    last = "$a / $b"
                    if (kotlin.math.abs(a.first - b.first) < 0.06 && kotlin.math.abs(a.second - b.second) < 0.06) listOf(a, b) else null
                }.getOrNull()
                if (found != null) return found
                SystemClock.sleep(500)
            }
            error("после «$stage» камеры показывают разные моменты: $last")
        }

        fun moved(a: Pair<Double, Double>, b: Pair<Double, Double>) =
            kotlin.math.abs(a.first - b.first) > 0.03 || kotlin.math.abs(a.second - b.second) > 0.03

        val max = sliderMax("Motion time") ?: error("нет шкалы Motion")
        setSliderValue(max * 0.4f, "Motion time")
        val seeked = awaitInSync("перемотки")

        // Во время воспроизведения каждый плеер идёт сам и расходится с общим временем не больше чем на окно
        // синхронизации приложения (0,75 с), поэтому тут проверяется ход обеих камер, а точная синхронность — после
        // следующей перемотки.
        val stampBefore = stamp()
        tap(desc("Play motion"))
        SystemClock.sleep(4_000)
        tap(desc("Pause motion"))
        SystemClock.sleep(1_500)
        assertNotEquals("за 4 с воспроизведения время должно уйти вперёд", stampBefore, stamp())
        val played = positions()
        assertTrue("первая камера не двигалась при воспроизведении: ${seeked[0]} -> ${played[0]}", moved(seeked[0], played[0]))
        assertTrue("вторая камера не двигалась при воспроизведении: ${seeked[1]} -> ${played[1]}", moved(seeked[1], played[1]))

        setSliderValue(max * 0.8f, "Motion time")
        awaitInSync("перемотки после воспроизведения")

        tap(alertsTab)
        awaitNoDecoders()
    }
}
