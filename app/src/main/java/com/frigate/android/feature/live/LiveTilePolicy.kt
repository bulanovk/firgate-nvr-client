package com.frigate.android.feature.live

internal fun liveTileDemand(visible: Boolean, enabled: Boolean, activity: CameraActivity, stream: String): Boolean =
    visible && enabled && activity.offline != true && stream.isNotBlank() &&
        (activity.activeMotion || activity.activeTracking)

internal class LiveOutputReadiness {
    private var output: Any? = null
    var ready = false
        private set

    fun attach(output: Any) {
        this.output = output
        ready = false
    }

    fun rendered(output: Any) {
        if (this.output === output) ready = true
    }

    fun detach(output: Any) {
        if (this.output !== output) return
        this.output = null
        ready = false
    }
}

internal data class LiveTilePlayback(
    val keepPlayer: Boolean = false,
    val stopAtMs: Long? = null,
) {
    fun update(demand: Boolean, allowed: Boolean, nowMs: Long): LiveTilePlayback = when {
        !allowed -> LiveTilePlayback()
        demand -> LiveTilePlayback(keepPlayer = true)
        !keepPlayer -> this
        stopAtMs == null -> copy(stopAtMs = nowMs + 500)
        nowMs >= stopAtMs -> LiveTilePlayback()
        else -> this
    }
}

internal fun liveSnapshotInterval(
    visible: Boolean,
    enabled: Boolean,
    offline: Boolean,
    active: Boolean,
    ready: Boolean,
    attempted: Boolean = true,
): Long = when {
    !enabled || offline -> -1
    // A camera outside the viewport gets one still, so it is ready before the user scrolls to it.
    !visible -> if (attempted) -1 else 0
    ready && !active -> 300
    ready -> 60_000
    active -> 200
    else -> 30_000
}

internal fun snapshotDelayAfterLoad(intervalMs: Long, loadTimeMs: Long, failed: Boolean): Long = when {
    failed -> maxOf(intervalMs, 1000)
    loadTimeMs > intervalMs -> 1
    else -> intervalMs
}

internal fun liveTileAspect(width: Int, height: Int): Float {
    val aspect = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9
    return when {
        aspect > 2 -> 32f / 9
        aspect < 1 -> 8f / 9
        else -> 16f / 9
    }
}
