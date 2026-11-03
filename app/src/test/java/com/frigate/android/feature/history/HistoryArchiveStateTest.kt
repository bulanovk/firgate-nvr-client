package com.frigate.android.feature.history

import com.frigate.android.core.server.FrigateHttpException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HistoryArchiveStateTest {
    @Test
    fun aCancelledLoadIsNotAFailureOfTheArchive() {
        // The load of the previous camera is cancelled when the user changes the camera. Its error
        // must not be written into the panel of the new one.
        val cancellation = CancellationException("Job was cancelled")

        assertThrows(CancellationException::class.java) {
            historyArchiveFailure(cancellation)
        }
    }

    @Test
    fun anHttpFailureBecomesTheMessageOfThePanel() {
        val failure = historyArchiveFailure(FrigateHttpException(500, "boom"))

        assertEquals("Frigate returned HTTP 500.", failure.message)
    }

    @Test
    fun aNetworkFailureBecomesTheMessageOfThePanel() {
        val failure = historyArchiveFailure(IllegalStateException("the connection is gone"))

        assertEquals("the connection is gone", failure.message)
    }
}
