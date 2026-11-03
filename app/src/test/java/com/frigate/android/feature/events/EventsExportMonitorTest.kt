package com.frigate.android.feature.events

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsExportMonitorTest {
    @Test
    fun queuedIsNotCompletedAndPollingStopsWhenHiddenThenReconcilesOnResume() {
        var reads = 0
        var ready = false
        val repo = object : EventsRepository by FakeEventsRepository() {
            override suspend fun exports(): List<ExportJob> {
                reads++
                return listOf(ExportJob("job", if (ready) "completed" else "running", if (ready) "exports/file.mp4" else null))
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val monitor = EventsExportMonitor(repo, scope, {}, sleep = { awaitCancellation() })
        monitor.watch(listOf(ExportJob("job", "queued")))
        assertEquals(0, reads)
        assertNull(monitor.jobs.value.single().downloadPath)
        monitor.setActive(true)
        assertEquals("running",monitor.jobs.value.single().status)
        assertEquals(1,reads)
        monitor.setActive(false)
        ready = true
        assertEquals(1,reads)
        monitor.setActive(true)
        assertEquals("exports/file.mp4",monitor.jobs.value.single().downloadPath)
        assertEquals(2,reads)
        scope.cancel()
    }
}
