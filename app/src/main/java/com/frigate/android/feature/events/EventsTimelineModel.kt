package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.MotionPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

internal class EventsTimelineModel(val range: EventsRange, val items: List<FrigateReview>, val motion: List<MotionPoint>) {
    fun timeAt(fraction: Double): Double = range.after + fraction.coerceIn(0.0, 1.0) * (range.before - range.after)
    fun fractionAt(time: Double): Double = ((time - range.after) / (range.before - range.after)).coerceIn(0.0, 1.0)
    fun reviewAt(time: Double): String? = items.filter { it.startTime <= time && (it.endTime ?: range.before) >= time }.maxByOrNull { it.startTime }?.id
}

internal data class EventsTimelineTick(val timestamp: Double, val label: String)

internal fun timelineTicks(range: EventsRange, timezone: String, maxTicks: Int = 512): List<EventsTimelineTick> {
    require(maxTicks >= 2)
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(timezone))
    val ticks = mutableListOf<EventsTimelineTick>()
    var step = 900.0
    while ((range.before - range.after) / step + 1 > maxTicks) step *= 2
    var time = ceil(range.after / step) * step
    while (time <= range.before && ticks.size < maxTicks) {
        ticks += EventsTimelineTick(time, formatter.format(Instant.ofEpochSecond(time.toLong())))
        time += step
    }
    return ticks
}
