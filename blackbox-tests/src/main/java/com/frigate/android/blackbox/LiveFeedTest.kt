package com.frigate.android.blackbox

import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Лента Live: активность камер по WebSocket и снимки всех карточек. Видео не нужно: проверяются
 * индикаторы объектов и запросы снимков, которые mock видит снаружи процесса приложения.
 */
class LiveFeedTest : BlackboxTest() {
    private fun wsConnections(): Int =
        Regex("\"count\"\\s*:\\s*(\\d+)").find(control("/__control/ws/connections"))?.groupValues?.get(1)?.toInt()
            ?: error("mock не вернул число подключений")

    /**
     * Activity socket принадлежит подключённой сессии, а не экрану: уход на Events не пересоздаёт его, push события,
     * пришедший пока Live скрыт, виден после возврата, а первое состояние (стоящий человек) не теряется.
     */
    @Test
    fun activitySocketSurvivesTabSwitchesAndDeliversUpdatesWhileHidden() {
        freshLaunch()
        controlPost(
            "/__control/activity",
            """{"topic":"camera_activity","payload":{"yard":{"motion":false,"objects":[{"id":"p1","label":"person","stationary":true}],"config":{"enabled":true}}}}""",
        )
        signIn()
        assertVisible(desc("Detected person"), 20_000)

        tap(text("Events"))
        assertVisible(By.pkg(appId).descContains("Alerts,"), 20_000)
        controlPost(
            "/__control/ws/send",
            """{"topic":"events","payload":{"type":"new","after":{"camera":"yard","id":"c1","label":"car","stationary":true}}}""",
        )
        tap(text("Live"))

        assertVisible(desc("Detected car"), 15_000)
        assertVisible(desc("Detected person"))
        assertEquals("переключение вкладок не должно пересоздавать соединение activity", 1, wsConnections())
    }

    /**
     * Все карточки камер строятся сразу, как в PWA: снимок последней из пяти запрашивается без единой прокрутки
     * (прежняя ленивая сетка компоновала её только после прокрутки).
     */
    @Test
    fun everyCameraStillIsRequestedBeforeAnyScroll() {
        freshLaunch()
        control("/__control/cameras/5")
        signIn()
        for (camera in listOf("cam1", "cam2", "cam3", "cam4", "cam5")) {
            awaitRequests("GET /api/$camera/latest.webp", 1, timeoutMs = 20_000)
        }
    }
}
