package com.frigate.android.feature.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

internal class EventsSessionHandle(
    val controller: EventsController,
    val repository: EventsRepository,
    val scope: CoroutineScope,
) {
    val state: StateFlow<EventsUiState> get() = controller.state
    fun onReviewsMessage(raw: String) = controller.onReviewsMessage(raw)
    fun onReconnect() = controller.onReconnect()
}

/** Keep this above the History branch: hiding the grid pauses refresh, not its state or actions. */
@Composable
internal fun rememberEventsSession(
    profile: ServerProfile,
    cookie: String,
    active: Boolean,
    initialQuery: EventsQuery,
    onUnauthorized: () -> Unit,
): EventsSessionHandle {
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val visible by rememberUpdatedState(active)
    val owner = LocalContext.current as LifecycleOwner
    val session = remember(profile, cookie) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ClientEventsRepository(FrigateClient(profile, cookie))
        EventsSessionHandle(EventsController(repository, scope, initialQuery,
            onUnauthorized = { unauthorized() }), repository, scope)
    }
    DisposableEffect(session) {
        onDispose { session.controller.close(); session.scope.cancel() }
    }
    DisposableEffect(session, owner, active) {
        fun update() = session.controller.setActive(visible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            session.controller.setActive(false)
        }
    }
    return session
}
