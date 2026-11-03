package com.frigate.android.blackbox

import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Список Events: часовой пояс интерфейса, открытие записи, отметка просмотренным и её повтор. */
class EventsListTest : EventsTestBase() {
    /** Обзор двухчасовой давности: он попадает в скользящие 24 часа вкладки, как у настоящего сервера. */
    private val reviewStart = System.currentTimeMillis() / 1000 - 7_200

    private fun stamp(zone: String): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.of(zone))
            .format(java.time.Instant.ofEpochSecond(reviewStart))

    private fun fixedReview(zone: String = "UTC") {
        control("/__control/review/$reviewStart")
        control("/__control/timezone/$zone")
    }

    /**
     * Запись открывается из карточки: отметка просмотренным уходит на сервер один раз, History открыт,
     * а Back возвращает на ту же вкладку Events (а не на первую).
     */
    @Test
    fun openingARecordingMarksItViewedOnceAndBackReturnsToTheEventsTab() {
        openEvents(::fixedReview)
        tap(desc("Open recording: Yard, ${stamp("UTC")}"))
        awaitRequests("POST /api/reviews/viewed", 1)
        assertVisible(desc("Back to Events"), 20_000)
        SystemClock.sleep(2_000)
        assertEquals("отметка просмотренным должна уйти один раз", 1, requestCount("POST /api/reviews/viewed"))

        tap(desc("Back to Events"))
        assertVisible(alertsTab, 20_000)
        assertGone(desc("Back to Events"))
    }

    /**
     * Сбой отметки не закрывает History: поверх открытого экрана появляется сообщение с повтором, повтор
     * отправляет вторую отметку, и пользователь остаётся в архиве.
     */
    @Test
    fun aFailedViewedMarkCanBeRetriedWhileHistoryStaysOpen() {
        openEvents {
            fixedReview()
            control("/__control/fail/viewed/500/1")
        }
        tap(desc("Open recording: Yard, ${stamp("UTC")}"))
        waitFor(text("Could not update events. Please retry."), 20_000)
        // У History есть и собственный Retry; сообщение экрана Events рисуется последним.
        val retry = device.findObjects(text("Retry")).lastOrNull() ?: error("нет кнопки Retry")
        retry.click()
        awaitRequests("POST /api/reviews/viewed", 2)
        assertVisible(desc("Back to Events"))
    }

    /** Часовой пояс из настроек сервера важнее часов телефона: подпись карточки считается в Asia/Kolkata (UTC+5:30), а не по поясу телефона. */
    @Test
    fun theConfiguredInterfaceTimezoneOverridesTheDeviceClock() {
        openEvents { fixedReview("Asia/Kolkata") }
        assertVisible(desc("Open recording: Yard, ${stamp("Asia/Kolkata")}"), 20_000)
    }

    /** Просмотренный обзор скрыт, пока не включён «Show reviewed»; после включения карточка видна и помечена. */
    @Test
    fun showReviewedRevealsAnAlreadyViewedEvent() {
        openEvents {
            fixedReview()
            control("/__control/reviewed/on")
        }
        assertGone(anyCard, 8_000)
        tap(desc("Show reviewed"))
        waitFor(desc("Open recording: Yard, ${stamp("UTC")}"), 15_000)
        // Метка объектов у просмотренного обзора зелёная (0xFF166534), у непросмотренного серая (0xFF6B7280).
        val shot = screenshot()
        val bounds = cardBounds()
        assertTrue("метка просмотренного обзора должна быть зелёной", pixelsNear(shot, bounds, 0xFF166534.toInt()) > 40)
        assertTrue("серой метки непросмотренного быть не должно", pixelsNear(shot, bounds, 0xFF6B7280.toInt(), tolerance = 4) < 20)
        shot.recycle()
    }
}
