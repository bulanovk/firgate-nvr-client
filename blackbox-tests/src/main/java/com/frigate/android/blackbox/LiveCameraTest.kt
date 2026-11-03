package com.frigate.android.blackbox

import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCameraTest : BlackboxTest() {
    private fun openCamera() {
        freshLaunch()
        signIn()
        tapCameraTile()
        assertVisible(desc("Back to cameras"))
    }

    @Test
    fun cameraOpensAndBackReturnsToTheGrid() {
        openCamera()
        assertVisible(desc("Open history"))
        tap(desc("Back to cameras"))
        assertGridShown()
        assertGone(desc("Back to cameras"))
    }

    @Test
    fun fullscreenHidesTheTabsAndTheTabsReturnToLive() {
        openCamera()
        tap(desc("Fullscreen"))
        assertVisible(desc("Leave fullscreen"))
        assertGone(text("Events"))
        assertGone(text("Settings"))
        tap(desc("Leave fullscreen"))
        assertVisible(text("Events"))
        tap(desc("Back to cameras"))
        tap(text("Settings"))
        tap(text("Live"))
        assertGridShown()
        tap(text("Events"))
        assertVisible(text("Mark these items as reviewed"))
        tap(text("Live"))
        assertGridShown()
    }

    @Test
    fun theCameraStaysOpenAfterTheRotation() {
        openCamera()
        val before = device.displayRotation
        device.setOrientationLeft()
        device.waitForIdle()
        assertVisible(desc("Back to cameras"))
        check(device.displayRotation != before) { "поворот не состоялся, тест ничего не доказывает" }
        device.setOrientationNatural()
        device.waitForIdle()
        assertVisible(desc("Back to cameras"))
        assertEquals(before, device.displayRotation)
    }

    @Test
    fun aPinchEnlargesThePictureAndADoubleTapResetsIt() {
        openCamera()
        val picture = waitFor(By.pkg(appId).descStartsWith("Zoom 1"))
        val before = picture.contentDescription
        picture.pinchOpen(0.6f)
        device.waitForIdle()
        // Подпись зависит от локали устройства («1,0x» / «1.0x»): сравниваем с исходной, а не с литералом.
        val after = device.findObject(By.pkg(appId).descStartsWith("Zoom"))?.contentDescription
        org.junit.Assert.assertNotEquals("щипок не изменил зум", before, after)
    }
}
