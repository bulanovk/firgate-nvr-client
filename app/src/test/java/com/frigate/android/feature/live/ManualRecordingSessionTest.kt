package com.frigate.android.feature.live

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManualRecordingSessionTest {
    @Test
    fun closeWaitsForInFlightStartAndEndsExactlyTheCreatedEvent() = runBlocking {
        val starting = CompletableDeferred<Unit>()
        val allowStart = CompletableDeferred<Unit>()
        val ended = mutableListOf<String>()
        val session = ManualRecordingSession(this,
            start = { starting.complete(Unit); allowStart.await(); "created" },
            end = { ended.add(it) }, onError = { fail(it) })
        session.toggle()
        starting.await()
        session.close()
        allowStart.complete(Unit)
        session.join()
        assertEquals(listOf("created"), ended)
        assertEquals(null, session.eventId.value)
    }

    @Test
    fun failedEndKeepsTheEventForRetryInsteadOfPretendingRecordingStopped() = runBlocking {
        var attempts = 0
        val errors = mutableListOf<String>()
        val session = ManualRecordingSession(this, start = { "created" }, end = {
            if (++attempts == 1) throw java.io.IOException("offline")
        }, onError = errors::add)
        session.toggle().join()
        session.toggle().join()
        assertEquals("created", session.eventId.value)
        assertEquals(listOf("offline"), errors)
        session.toggle().join()
        assertEquals(null, session.eventId.value)
        session.close()
        session.join()
    }

    @Test
    fun aSecondToggleWhileTheStartIsRunningDoesNotTouchTheRecording() = runBlocking {
        val starting = CompletableDeferred<Unit>()
        val allowStart = CompletableDeferred<Unit>()
        val ended = mutableListOf<String>()
        val session = ManualRecordingSession(this,
            start = { starting.complete(Unit); allowStart.await(); "created" },
            end = { ended.add(it) }, onError = { fail(it) })
        val first = session.toggle()
        starting.await()
        val second = session.toggle()
        allowStart.complete(Unit)
        first.join()
        second.join()
        assertEquals("created", session.eventId.value)
        assertEquals(emptyList<String>(), ended)
        session.close()
        session.join()
        assertEquals(listOf("created"), ended)
    }

    @Test
    fun closingTwiceEndsTheRecordingOnlyOnce() = runBlocking {
        val ended = mutableListOf<String>()
        val session = ManualRecordingSession(this,
            start = { "created" }, end = { ended.add(it) }, onError = { fail(it) })
        session.toggle().join()
        session.close()
        session.close()
        session.join()
        assertEquals(listOf("created"), ended)
    }

    @Test
    fun aFailedCleanupKeepsTheRecordingAndSaysThatItMayStillBeRunning() = runBlocking {
        val errors = mutableListOf<String>()
        val session = ManualRecordingSession(this, start = { "created" },
            end = { throw java.io.IOException("offline") }, onError = errors::add)
        session.toggle().join()
        session.close()
        session.join()
        assertEquals("created", session.eventId.value)
        assertEquals(listOf("Could not stop the recording, it may still be running"), errors)
    }
}
