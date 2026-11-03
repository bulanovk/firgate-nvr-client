package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordingIndicatorTest {
    @Test
    fun reflectsConfiguredAndCurrentlyActiveRecordingState() {
        assertEquals(
            RecordingIndicatorState.ACTIVE,
            recordingIndicatorState(recordEnabled = true, activeRecording = true),
        )
        assertEquals(
            RecordingIndicatorState.ENABLED,
            recordingIndicatorState(recordEnabled = true, activeRecording = false),
        )
        assertEquals(
            RecordingIndicatorState.DISABLED,
            recordingIndicatorState(recordEnabled = false, activeRecording = true),
        )
        assertEquals(
            RecordingIndicatorState.DISABLED,
            recordingIndicatorState(recordEnabled = false, activeRecording = false),
        )
    }
}
