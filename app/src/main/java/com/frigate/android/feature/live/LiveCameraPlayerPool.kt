package com.frigate.android.feature.live

internal class LiveCameraPlayerPool<T>(private val release: (T) -> Unit) {
    private val players = mutableMapOf<String, T>()

    fun getOrCreate(cameraId: String, create: () -> T): T =
        players.getOrPut(cameraId, create)

    fun retain(cameraIds: Set<String>) {
        val removedCameraIds = players.keys - cameraIds
        removedCameraIds.forEach { cameraId ->
            players.remove(cameraId)?.let(release)
        }
    }

    fun releaseAll() {
        players.values.forEach(release)
        players.clear()
    }
}
