package com.frigate.android.feature.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a manual event until it is ended, including when the panel closes during its creation. */
internal class ManualRecordingSession(
    parentScope: CoroutineScope,
    private val start: suspend () -> String,
    private val end: suspend (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutex = Mutex()
    private val currentEvent = MutableStateFlow<String?>(null)
    private val working = MutableStateFlow(false)
    val eventId = currentEvent.asStateFlow()
    val busy = working.asStateFlow()
    private var closed = false
    /**
     * The request is taken at the moment of the tap, not when the lock is free: a second tap
     * during a running request is refused, it must not wait for the first one and then stop the
     * recording it has just started.
     */
    private val requested = AtomicBoolean(false)

    fun toggle(): Job {
        if (closed || !requested.compareAndSet(false, true)) return Job().apply { complete() }
        return scope.launch {
            try {
                mutex.withLock {
                    working.value = true
                    try {
                        val id = currentEvent.value
                        if (id == null) currentEvent.value = start() else {
                            end(id)
                            currentEvent.value = null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onError(error.message ?: "Recording request failed")
                    } finally {
                        working.value = false
                    }
                }
            } finally {
                requested.set(false)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.launch {
            mutex.withLock {
                try {
                    currentEvent.value?.let { end(it); currentEvent.value = null }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The event stays owned by the session: the recording may still be running and
                    // the user has to know about it.
                    onError("Could not stop the recording, it may still be running")
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    suspend fun join() = job.join()
}
