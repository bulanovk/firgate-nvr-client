package com.frigate.android.feature.live

import com.frigate.android.core.server.FrigateWebSocketFrame

/**
 * Routes a websocket frame to the camera activity reducer and to the review updates.
 *
 * Review updates are dispatched before the reducer, because an unchanged camera state must not
 * swallow the signal that the review list has changed.
 */
internal object CameraActivityDispatch {
    const val REVIEWS_TOPIC = "reviews"

    fun dispatch(
        raw: String,
        state: CameraActivityState,
        onReviewsMessage: (String) -> Unit = {},
        onActivityChanged: (Map<String, CameraActivity>) -> Unit = {},
    ): CameraActivityState {
        if (FrigateWebSocketFrame.topic(raw) == REVIEWS_TOPIC) {
            onReviewsMessage(raw)
        }

        val next = CameraActivityReducer.reduce(state, raw)
        if (next == state) return state
        onActivityChanged(next.cameras)
        return next
    }
}
