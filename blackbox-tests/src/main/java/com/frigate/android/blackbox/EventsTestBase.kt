package com.frigate.android.blackbox

import android.graphics.Rect
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2

/**
 * Общее для тестов вкладки Events: вход, открытие вкладки и работа с карточкой обзора.
 * Все они идут через интерфейс и mock Frigate; состояние приложения тесты не читают.
 */
abstract class EventsTestBase : BlackboxTest() {
    protected val alertsTab: BySelector get() = By.pkg(appId).descContains("Alerts,")
    protected val anyCard: BySelector get() = By.pkg(appId).descStartsWith("Open recording:")

    /** Новая сессия, сценарий mock ([scenario] выполняется после сброса, до входа), вкладка Events. */
    protected fun openEvents(scenario: () -> Unit = {}) {
        freshLaunch()
        scenario()
        signIn()
        tap(text("Events"))
        assertVisible(alertsTab, 20_000)
    }

    protected fun card(timeoutMs: Long = 20_000): UiObject2 = waitFor(anyCard, timeoutMs)

    protected fun cardBounds(): Rect = card().visibleBounds

    /**
     * Серединная полоса карточки: сверху и снизу лежат метки и подложка-градиент, а посередине кадр виден как есть.
     * В ней ищем цвет миниатюры и кадр видео.
     */
    protected fun cardCentre(): Rect {
        val b = cardBounds()
        return Rect(b.left + b.width() / 5, b.top + b.height() * 3 / 10, b.right - b.width() / 5, b.bottom - b.height() * 3 / 10)
    }

    /** Свайп вправо по карточке открывает встроенное превью (жест PWA); свайп влево отмечает просмотренным. */
    protected fun startInlinePreview() {
        repeat(3) {
            try {
                card().swipe(Direction.RIGHT, 0.7f, 600)
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                android.os.SystemClock.sleep(300)
            }
        }
        card().swipe(Direction.RIGHT, 0.7f, 600)
    }

    protected val stopPreview: BySelector get() = desc("Stop preview")
}
