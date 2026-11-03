package com.frigate.android.feature.player

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlayerZoomTest {
    @Test
    fun maxScaleComesFromTheSourceResolution() {
        assertEquals(10f, playerZoomMaxScale(3840, 1032f), 0.01f)
        assertEquals(7.44f, playerZoomMaxScale(2560, 1032f), 0.01f)
        assertEquals(5.58f, playerZoomMaxScale(1920, 1032f), 0.01f)
        assertEquals(3.72f, playerZoomMaxScale(1280, 1032f), 0.01f)
        assertEquals(1.86f, playerZoomMaxScale(640, 1032f), 0.01f)
    }

    @Test
    fun unknownFrameWidthFallsBackToFour() {
        assertEquals(PLAYER_ZOOM_FALLBACK_SCALE, playerZoomMaxScale(0, 1032f))
        assertEquals(PLAYER_ZOOM_FALLBACK_SCALE, playerZoomMaxScale(3840, 0f))
    }

    @Test
    fun aSourceSmallerThanTheScreenCannotBeZoomed() {
        assertEquals(1f, playerZoomMaxScale(100, 1032f), 0.001f)
    }

    @Test
    fun zoomKeepsTheFocusPointOnScreen() {
        val zoom = zoomAround(PlayerZoom(), 2f, 150f, 50f, 10f, 200f, 100f)
        assertEquals(2f, zoom.scale, 0.001f)
        assertEquals(-50f, zoom.offsetX, 0.001f)
        assertEquals(0f, zoom.offsetY, 0.001f)
    }

    @Test
    fun theContentPointUnderTheFocusStaysUnderTheFocus() {
        val width = 200f
        val height = 100f
        val focusX = 160f
        val focusY = 40f
        val zoom = zoomAround(PlayerZoom(), 2.5f, focusX, focusY, 10f, width, height)
        val centerX = width / 2f
        val centerY = height / 2f
        val contentX = centerX + (focusX - centerX - zoom.offsetX) / zoom.scale
        val contentY = centerY + (focusY - centerY - zoom.offsetY) / zoom.scale
        assertEquals(focusX, centerX + (contentX - centerX) * zoom.scale + zoom.offsetX, 0.01f)
        assertEquals(focusY, centerY + (contentY - centerY) * zoom.scale + zoom.offsetY, 0.01f)
    }

    @Test
    fun theScaleNeverExceedsTheLimit() {
        val zoom = zoomAround(PlayerZoom(), 40f, 100f, 50f, 10f, 200f, 100f)
        assertEquals(10f, zoom.scale, 0.001f)
    }

    @Test
    fun theScaleNeverDropsBelowOne() {
        val zoom = zoomAround(PlayerZoom(scale = 2f), 0.1f, 100f, 50f, 10f, 200f, 100f)
        assertEquals(1f, zoom.scale, 0.001f)
        assertEquals(0f, zoom.offsetX, 0.001f)
        assertEquals(0f, zoom.offsetY, 0.001f)
    }

    @Test
    fun aZoomAtTheLimitDoesNotMove() {
        val start = PlayerZoom(scale = 10f, offsetX = 20f, offsetY = 5f)
        assertEquals(start, zoomAround(start, 2f, 100f, 50f, 10f, 200f, 100f))
    }

    @Test
    fun panStopsAtTheContentEdge() {
        val zoom = panBy(PlayerZoom(scale = 2f), 500f, 500f, 200f, 100f)
        assertEquals(100f, zoom.offsetX, 0.001f)
        assertEquals(50f, zoom.offsetY, 0.001f)
    }

    @Test
    fun panAtUnitScaleDoesNothing() {
        val zoom = panBy(PlayerZoom(), 40f, 20f, 200f, 100f)
        assertEquals(1f, zoom.scale, 0.001f)
        assertEquals(0f, zoom.offsetX, 0.001f)
        assertEquals(0f, zoom.offsetY, 0.001f)
    }

    @Test
    fun clampKeepsTheContentInsideTheFrame() {
        val zoom = clampZoom(PlayerZoom(scale = 2f, offsetX = 900f, offsetY = -900f), 200f, 100f)
        assertEquals(100f, zoom.offsetX, 0.001f)
        assertEquals(-50f, zoom.offsetY, 0.001f)
    }
}
