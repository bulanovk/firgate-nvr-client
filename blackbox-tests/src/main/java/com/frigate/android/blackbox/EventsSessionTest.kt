package com.frigate.android.blackbox

import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Жизненный цикл сессии Events: уход приложения в фон, переключение вкладок и сохранение фильтра. Приложение
 * сворачивается и возвращается так же, как это делает пользователь (HOME и значок), а запросы считает mock.
 */
class EventsSessionTest : EventsTestBase() {
    /** Запрос, который шлёт только вкладка Events (карусель Live список обзоров берёт без сводки). */
    private val eventsLoad = "GET /api/review/summary"

    private fun reviewedScenario() {
        control("/__control/review/$reviewStart")
        control("/__control/timezone/UTC")
        control("/__control/reviewed/on")
    }

    private val reviewedCard get() = desc("Open recording: Yard, ${stamp()}")

    private fun stamp(): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.of("UTC"))
            .format(java.time.Instant.ofEpochSecond(reviewStart))

    private val reviewStart = System.currentTimeMillis() / 1000 - 1_800

    /** Свёрнутое приложение не возобновляет встроенное превью само: после возврата карточка снова обычная. */
    @Test
    fun backgroundingClosesTheInlinePreviewInsteadOfRestartingItOnResume() {
        openEvents {
            control("/__control/review/$reviewStart")
            control("/__control/timezone/UTC")
            control("/__control/fail/viewed/500/1000")
        }
        startInlinePreview()
        assertVisible(stopPreview, 15_000)

        device.pressHome()
        SystemClock.sleep(2_500)
        bringToFront()

        assertVisible(alertsTab, 20_000)
        assertGone(stopPreview, 8_000)
    }

    /**
     * Фильтр «Show reviewed» переживает и вкладку Live, и фон; пока Events скрыта или приложение остановлено,
     * вкладка ничего не запрашивает у сервера, а после возврата обновляется снова.
     */
    @Test
    fun theReviewedFilterSurvivesHidingAndNothingIsRequestedWhileHidden() {
        openEvents { reviewedScenario() }
        assertGone(anyCard, 5_000)
        tap(desc("Show reviewed"))
        waitFor(reviewedCard, 15_000)

        // Вкладка Live: Events скрыта и не опрашивает сервер.
        tap(text("Live"))
        val whileOnLive = requestCount(eventsLoad)
        SystemClock.sleep(8_000)
        assertEquals("скрытая вкладка Events не должна запрашивать данные", whileOnLive, requestCount(eventsLoad))
        tap(text("Events"))
        waitFor(reviewedCard, 15_000)
        awaitRequests(eventsLoad, whileOnLive + 1)

        // Фон: приложение остановлено, запросов нет; после возврата данные обновляются, а фильтр на месте.
        val beforeHome = requestCount(eventsLoad)
        device.pressHome()
        SystemClock.sleep(8_000)
        assertEquals("остановленное приложение не должно запрашивать данные", beforeHome, requestCount(eventsLoad))
        bringToFront()
        awaitRequests(eventsLoad, beforeHome + 1, timeoutMs = 25_000)
        assertVisible(reviewedCard, 15_000)
    }
}
