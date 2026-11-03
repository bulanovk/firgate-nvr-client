package com.frigate.android.blackbox

import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Встроенное превью карточки события: транспорт (401, отсутствие записи, удержанный ответ), смена миниатюры на
 * первый декодированный кадр и ход ползунка. Кадр определяется по пикселям снаружи процесса приложения.
 */
class EventsPreviewTest : EventsTestBase() {
    /**
     * Обзор получасовой давности с фиксированным началом: окно превью не плывёт от опроса к опросу. Превью, дошедшее
     * до середины окна, отмечает обзор просмотренным, и карточка уходит из списка; отметка здесь отклоняется, чтобы
     * карточка с превью оставалась на экране (сама отметка проверена в EventsListTest).
     */
    private fun fixedReview() {
        control("/__control/review/${System.currentTimeMillis() / 1000 - 1_800}")
        control("/__control/timezone/UTC")
        control("/__control/fail/viewed/500/1000")
    }

    private fun assertSessionRenewedOnceWithoutSignInScreen() {
        // Первый вход + ровно одно продление: повторные запросы не должны плодить входы.
        awaitRequests("POST /api/login", 2)
        SystemClock.sleep(3_000)
        assertEquals("продление сессии должно быть одним", 2, requestCount("POST /api/login"))
        assertGone(text("Sign in"), 2_000)
    }

    /** У события нет записи превью: вместо вечного индикатора карточка говорит, что превью недоступно. */
    @Test
    fun aMissingPreviewShowsUnavailableInsteadOfSpinningForever() {
        openEvents { fixedReview(); control("/__control/preview/none") }
        startInlinePreview()
        assertVisible(text("Preview unavailable"), 20_000)
    }

    /**
     * 401 на миниатюре — это истёкшая сессия: приложение продлевает её один раз, а не показывает «нет картинки».
     * Миниатюру карточки запрашивают двое: карусель Live (её загрузчик ошибку 401 не обрабатывает) и карточка Events,
     * поэтому отказ задан на два запроса: какой бы ни пришёл первым, карточка получит свой отказ.
     */
    @Test
    fun anUnauthorizedThumbnailRenewsTheSessionOnceAndThenShowsIt() {
        openEvents { fixedReview(); control("/__control/fail/thumb/401/2") }
        assertSessionRenewedOnceWithoutSignInScreen()
        val region = cardCentre()
        val deadline = SystemClock.uptimeMillis() + 20_000
        var share = 0.0
        while (SystemClock.uptimeMillis() < deadline && share < 0.3) {
            val shot = screenshot()
            share = blueShare(shot, region)
            shot.recycle()
            if (share < 0.3) SystemClock.sleep(500)
        }
        assertTrue("после продления миниатюра (синяя картинка) должна появиться, доля=$share", share >= 0.3)
    }

    /**
     * Пока видео не отдало первый кадр, поверх текстуры остаётся миниатюра; кадр появляется, когда ответ получен.
     * Ответ медиа удерживается на mock, поэтому состояние «видео запрошено, кадра ещё нет» держится сколько нужно.
     */
    @Test
    fun theThumbnailStaysUntilTheFirstDecodedFrameAndThenTheVideoTakesOver() {
        requireMedia()
        openEvents { fixedReview() }
        val region = cardCentre()
        // Миниатюра успела загрузиться до превью.
        run {
            val deadline = SystemClock.uptimeMillis() + 15_000
            var share = 0.0
            while (SystemClock.uptimeMillis() < deadline && share < 0.3) {
                val shot = screenshot(); share = blueShare(shot, region); shot.recycle()
                if (share < 0.3) SystemClock.sleep(400)
            }
            assertTrue("миниатюра должна быть видна до превью, доля=$share", share >= 0.3)
        }
        control("/__control/hold/preview-media/on")
        startInlinePreview()
        awaitRequests("GET /clips/preview.mp4", 1)
        repeat(6) {
            keepScreenOn()
            val shot = screenshot()
            val share = blueShare(shot, region)
            val square = findSquare(shot, region = region, minChannel = 110)
            shot.recycle()
            assertNull("кадра видео ещё быть не должно, пока медиа удержано", square)
            assertTrue("миниатюра должна оставаться до первого кадра, доля=$share", share >= 0.3)
            SystemClock.sleep(500)
        }
        control("/__control/hold/preview-media/off")
        val square = awaitSquare(timeoutMs = 30_000, region = region, minChannel = 110)
        assertTrue("кадр видео найден: $square", square.pixels > 0)
    }

    /** 401 на медиа превью: сессия продлевается один раз, после чего превью открывается заново и играет. */
    @Test
    fun anUnauthorizedPreviewRenewsTheSessionOnceAndPlaysAfterwards() {
        requireMedia()
        openEvents { fixedReview(); control("/__control/fail/preview-media/401/1") }
        startInlinePreview()
        assertSessionRenewedOnceWithoutSignInScreen()
        // Продление пересоздаёт сессию Events: превью закрыто, его открывают снова — уже с новой cookie.
        card()
        if (device.findObject(stopPreview) == null) startInlinePreview()
        awaitSquare(timeoutMs = 30_000, region = cardCentre(), minChannel = 110)
        assertEquals("новых продлений быть не должно", 2, requestCount("POST /api/login"))
    }

    /** После «seek» ползунок карточки продолжает идти вперёд: позиция не застывает на выбранном значении. */
    @Test
    fun theCardSliderKeepsAdvancingAfterTheUserSeeks() {
        requireMedia()
        openEvents { fixedReview() }
        startInlinePreview()
        awaitSquare(timeoutMs = 30_000, region = cardCentre(), minChannel = 110)
        awaitSlider()
        val max = sliderMax() ?: error("у ползунка нет диапазона")
        assertTrue("диапазон ползунка слишком мал: $max", max > 6f)
        val target = 1f
        setSliderValue(target)
        var reached = false
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline && !reached) {
            keepScreenOn()
            SystemClock.sleep(200)
            reached = (sliderValue() ?: 0f) > target + 3f
        }
        assertTrue("ползунок остался на значении после перемотки (max=$max)", reached)
    }

    /** Превью держит аппаратный декодер: закрытие превью освобождает его, иначе карточки исчерпали бы кодеки. */
    @Test
    fun stoppingThePreviewReleasesTheDecoder() {
        requireMedia()
        openEvents { fixedReview() }
        assertEquals("до превью декодеров быть не должно", 0, videoDecoders())
        startInlinePreview()
        awaitSquare(timeoutMs = 30_000, region = cardCentre(), minChannel = 110)
        assertEquals("при воспроизведении превью занимает один декодер", 1, videoDecoders())
        tap(stopPreview)
        assertGone(stopPreview, 5_000)
        awaitNoDecoders()
    }
}
