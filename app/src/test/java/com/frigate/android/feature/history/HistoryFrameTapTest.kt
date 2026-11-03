package com.frigate.android.feature.history

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryFrameTapTest {
    private val slop = 24f

    @Test
    fun aSingleTapIsCounted() {
        val tap = HistoryFrameTap(slop)
        assertFalse(tap.onAction(FrameTouchAction.Down, 10f, 10f, 0))
        assertTrue(tap.onAction(FrameTouchAction.Up, 12f, 11f, 60))
    }

    @Test
    fun aSecondFingerMakesItNotATap() {
        val tap = HistoryFrameTap(slop)
        tap.onAction(FrameTouchAction.Down, 10f, 10f, 0)
        tap.onAction(FrameTouchAction.PointerDown, 10f, 10f, 20)
        assertFalse(tap.onAction(FrameTouchAction.Up, 10f, 10f, 60))
    }

    @Test
    fun aMoveBeyondTheSlopMakesItNotATap() {
        val tap = HistoryFrameTap(slop)
        tap.onAction(FrameTouchAction.Down, 10f, 10f, 0)
        tap.onAction(FrameTouchAction.Move, 40f, 10f, 20)
        assertFalse(tap.onAction(FrameTouchAction.Up, 40f, 10f, 60))
    }

    @Test
    fun aMoveWithinTheSlopKeepsItATap() {
        val tap = HistoryFrameTap(slop)
        tap.onAction(FrameTouchAction.Down, 10f, 10f, 0)
        tap.onAction(FrameTouchAction.Move, 20f, 18f, 20)
        assertTrue(tap.onAction(FrameTouchAction.Up, 20f, 18f, 60))
    }

    @Test
    fun aCancelledGestureMakesItNotATap() {
        val tap = HistoryFrameTap(slop)
        tap.onAction(FrameTouchAction.Down, 10f, 10f, 0)
        tap.onAction(FrameTouchAction.Cancel, 10f, 10f, 20)
        assertFalse(tap.onAction(FrameTouchAction.Up, 10f, 10f, 60))
    }

    @Test
    fun theSecondTapOfADoubleTapIsNotCounted() {
        val tap = HistoryFrameTap(slop)
        tap.onAction(FrameTouchAction.Down, 10f, 10f, 0)
        assertTrue(tap.onAction(FrameTouchAction.Up, 10f, 10f, 60))
        tap.onAction(FrameTouchAction.Down, 10f, 10f, 200)
        assertFalse(tap.onAction(FrameTouchAction.Up, 10f, 10f, 250))
    }

    @Test
    fun aSlowSecondTapIsCountedAgain() {
        val tap = HistoryFrameTap(slop)
        tap.onAction(FrameTouchAction.Down, 10f, 10f, 0)
        assertTrue(tap.onAction(FrameTouchAction.Up, 10f, 10f, 60))
        tap.onAction(FrameTouchAction.Down, 10f, 10f, 600)
        assertTrue(tap.onAction(FrameTouchAction.Up, 10f, 10f, 650))
    }
}
