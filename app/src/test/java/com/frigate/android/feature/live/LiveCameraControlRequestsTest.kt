package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveCameraControlRequestsTest {
    @Test
    fun anOldTimeoutDoesNotCancelANewerRequestOfTheSameControl() {
        val requests = LiveCameraControlRequests()
        val first = requests.begin(CameraControl.DETECTION, true)
        assertTrue(requests.finish(CameraControl.DETECTION, first))

        val second = requests.begin(CameraControl.DETECTION, false)
        assertFalse(requests.finish(CameraControl.DETECTION, first),
            "The timeout of the old request must not cancel the new one")
        assertTrue(requests.isPending(CameraControl.DETECTION))
        assertTrue(requests.finish(CameraControl.DETECTION, second))
        assertFalse(requests.isPending(CameraControl.DETECTION))
    }

    @Test
    fun aControlWithoutARequestIsNeverPending() {
        val requests = LiveCameraControlRequests()
        assertFalse(requests.isPending(CameraControl.RECORDING))
        assertFalse(requests.finish(CameraControl.RECORDING, 1L))
    }

    @Test
    fun differentControlsDoNotShareTheirRequests() {
        val requests = LiveCameraControlRequests()
        val detection = requests.begin(CameraControl.DETECTION, true)
        requests.begin(CameraControl.RECORDING, true)
        assertFalse(requests.finish(CameraControl.RECORDING, detection),
            "The token of one control must not finish the request of another")
        assertTrue(requests.isPending(CameraControl.RECORDING))
        assertTrue(requests.isPending(CameraControl.DETECTION))
    }

    @Test
    fun theConfirmationOfTheCameraRemovesOnlyItsOwnRequest() {
        val requests = LiveCameraControlRequests()
        requests.begin(CameraControl.DETECTION, true)
        requests.begin(CameraControl.RECORDING, true)
        assertTrue(requests.confirm(CameraControl.DETECTION, true))
        assertFalse(requests.isPending(CameraControl.DETECTION))
        assertTrue(requests.isPending(CameraControl.RECORDING))
    }

    @Test
    fun aConfirmationOfAnotherValueDoesNotRemoveTheRequest() {
        val requests = LiveCameraControlRequests()
        requests.begin(CameraControl.DETECTION, true)
        assertFalse(requests.confirm(CameraControl.DETECTION, false))
        assertTrue(requests.isPending(CameraControl.DETECTION))
    }
}
