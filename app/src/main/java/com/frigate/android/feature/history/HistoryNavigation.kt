package com.frigate.android.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.frigate.android.core.server.FrigateReview

/**
 * The viewing session of the archive.
 *
 * The camera, the panel, the window and the filter belong to the session: the drawers of the
 * archive report their choice here and the screen of the archive reads it. The playhead stays in
 * wall-clock time, so switching the camera keeps the moment of the day.
 *
 * The player of the session outlives the screen: leaving the archive releases the player of
 * Media3, but the position and the play/pause state are kept here and the player is rebuilt with
 * them when the archive is opened again.
 */
internal class HistoryNavigation(
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    /** The event the archive was opened for, if it was opened from the carousel. */
    var review by mutableStateOf<FrigateReview?>(null)
        private set

    /** The camera of the archive. */
    var cameraId by mutableStateOf<String?>(null)
        private set

    /** The window of the archive. */
    var range by mutableStateOf(HistoryRange.initial(nowSeconds()))
        private set

    /** The window the session was opened with; the calendar restores it. */
    var initialRange by mutableStateOf(range)
        private set

    /** The panel of the archive: the timeline, the events or the detail. */
    var panelMode by mutableStateOf(HistoryPanelMode.TIMELINE)
        private set

    /** The filter of the events. */
    var filter by mutableStateOf(HistoryFilter.reset())
        private set

    /** The wall-clock playhead and the zoom of the timeline. */
    var screenModel by mutableStateOf(newScreenModel())
        private set

    /** The playback state of the archive player. */
    val player = HistoryPlayerController()

    /** The camera of the last viewing, kept over the closing of the archive. */
    private var lastCameraId: String? = null

    /** The event of the last viewing, kept over the closing of the archive. */
    private var lastReviewId: String? = null

    /** True while the archive is open. */
    val isOpen: Boolean get() = cameraId != null

    /** Opens the archive at the moment of the event. */
    fun openReview(review: FrigateReview) {
        val isNewEvent = review.id != lastReviewId
        this.review = review
        lastReviewId = review.id
        cameraId = review.camera
        lastCameraId = review.camera
        range = HistoryRange.initial(nowSeconds())
        initialRange = range
        if (isNewEvent) restartPlayback() else resumePlayback()
    }

    /** An explicit Events entry starts again even when the same review was opened before. */
    fun openReview(review: FrigateReview, range: HistoryRange, restart: Boolean) {
        this.review = review
        lastReviewId = review.id
        cameraId = review.camera
        lastCameraId = review.camera
        this.range = range
        initialRange = range
        if (restart) restartPlayback() else resumePlayback()
    }

    fun openRecording(cameraId: String, timestamp: Double, range: HistoryRange) {
        require(timestamp.isFinite() && range.contains(timestamp))
        review = null
        lastReviewId = null
        this.cameraId = cameraId
        lastCameraId = cameraId
        this.range = range
        initialRange = range
        screenModel = HistoryScreenModel(anchorSeconds = timestamp, bounds = range.after..range.before)
        player.bounds = range.after..range.before
        player.seekTo(timestamp)
        player.play()
    }

    /** Opens the archive of a camera without an event. */
    fun openCamera(cameraId: String) {
        val isNewCamera = cameraId != lastCameraId
        review = null
        lastReviewId = null
        this.cameraId = cameraId
        lastCameraId = cameraId
        if (isNewCamera) restartPlayback() else resumePlayback()
    }

    /** Switches the camera of the archive and keeps the moment of the day. */
    fun changeCamera(cameraId: String) {
        this.cameraId = cameraId
    }

    /** Changes the window of the archive, as the calendar does. */
    fun changeRange(range: HistoryRange) {
        this.range = range
    }

    /** Returns the window the session was opened with. */
    fun restoreInitialRange() {
        range = initialRange
    }

    /** Changes the panel of the archive. */
    fun changePanelMode(mode: HistoryPanelMode) {
        panelMode = mode
    }

    /** Changes the filter of the events. */
    fun changeFilter(filter: HistoryFilter) {
        this.filter = filter
    }

    /** Leaves the archive and returns to the live screen. */
    fun close() {
        review = null
        cameraId = null
    }

    /** Leaves the archive and asks for the live screen of the camera of the archive. */
    fun openLive(): String? {
        val camera = cameraId
        close()
        return camera
    }

    /** The user moved the playhead of the timeline. */
    fun seekTo(seconds: Double) {
        screenModel.seekTo(seconds)
        player.seekTo(seconds)
    }

    /** Moves the playhead to the event and starts the playback of the session. */
    private fun restartPlayback() {
        val anchor = historyAnchorSeconds(review, range)
        screenModel = HistoryScreenModel(anchorSeconds = anchor, bounds = range.after..range.before)
        player.bounds = range.after..range.before
        player.seekTo(anchor)
        player.play()
    }

    /**
     * Rebuilds the source of the session at the moment the playback has reached.
     *
     * The anchor of the source is the origin of the playlist, so it follows the position of the
     * player: the archive continues where it stopped, and not where it was started.
     */
    private fun resumePlayback() {
        val position = player.state.positionSeconds
        player.bounds = range.after..range.before
        screenModel.seekTo(position)
        player.seekTo(position)
    }

    private fun newScreenModel(): HistoryScreenModel {
        val window = range
        return HistoryScreenModel(
            anchorSeconds = historyAnchorSeconds(review, window),
            bounds = window.after..window.before,
        )
    }
}

/** Where the archive opens: four seconds before the event or the last minute of the window. */
internal fun historyAnchorSeconds(review: FrigateReview?, range: HistoryRange): Double =
    review?.let { range.startPosition(it.startTime - HistoryRange.REVIEW_PADDING) }
        ?: (range.before - 60.0).coerceAtLeast(range.after)
