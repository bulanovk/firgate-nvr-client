package com.frigate.android.feature.live

internal class LiveSessionGate {
    private var generation = 0L
    private var active = false
    fun begin(): Long { active = true; return ++generation }
    fun invalidate() { active = false }
    fun isCurrent(generation: Long): Boolean = active && this.generation == generation
}
