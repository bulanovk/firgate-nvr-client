package com.frigate.android.feature.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MotionCropGeometryTest {
    @Test
    fun cropPreservesSourceAspectAndCentersSelectedPixelsWithoutStretching() {
        val geometry = motionCropGeometry(300.0, 400.0, 16.0 / 9, NormalizedRect(0.0,0.0,.0625,1.0))
        assertEquals(16.0 / 9, geometry.frameWidth / geometry.frameHeight, .00001)
        assertEquals(400.0, geometry.frameHeight * geometry.scale, .00001)
        assertTrue(geometry.frameWidth * .0625 * geometry.scale <= 300.0)
        assertEquals(.46875 * geometry.frameWidth * geometry.scale, geometry.translationX, .00001)
    }
    @Test
    fun absentCropFitsSourceWithNoPanOrMagnification() {
        val geometry = motionCropGeometry(300.0,400.0,2.0,null)
        assertEquals(300.0,geometry.frameWidth)
        assertEquals(150.0,geometry.frameHeight)
        assertEquals(1.0,geometry.scale)
        assertEquals(0.0,geometry.translationX)
    }
}
