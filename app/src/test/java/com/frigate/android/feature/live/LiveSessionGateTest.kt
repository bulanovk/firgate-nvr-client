package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LiveSessionGateTest {
    @Test
    fun stopAndReconnectRejectLateCallbacksFromOldConnection() {
        val gate = LiveSessionGate()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
