package com.frigate.android.feature.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateRepository
import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/** Session scoped review state of the Live carousel together with the actions the host can trigger. */
internal class ReviewSessionHandle(private val controller: ReviewController) {
    val state: StateFlow<ReviewUiState> get() = controller.state

    /** A `reviews` frame of the shared activity socket invalidated the list. */
    fun onReviewsMessage(raw: String) = controller.onReviewsMessage(raw)

    /** The shared socket reconnected, so the list is restored over REST. */
    fun onReconnect() = controller.invalidate(0L)

    fun markViewed(id: String) = controller.markViewed(id)

    fun refresh() = controller.invalidate(0L)
}

@Composable
internal fun rememberReviewSession(
    profile: ServerProfile,
    cookie: String,
    active: Boolean,
    cameraIds: Set<String>,
    onUnauthorized: () -> Unit,
): ReviewSessionHandle {
    val currentUnauthorized by rememberUpdatedState(onUnauthorized)
    val controller = remember(profile, cookie) {
        ReviewController(
            repository = FrigateRepository(FrigateClient(profile, cookie)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            onUnauthorized = { currentUnauthorized() },
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    LaunchedEffect(controller, active, cameraIds) {
        controller.setActive(active, cameraIds)
    }

    return remember(controller) { ReviewSessionHandle(controller) }
}
