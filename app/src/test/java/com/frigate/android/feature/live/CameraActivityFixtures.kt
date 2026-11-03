package com.frigate.android.feature.live

internal object CameraActivityFixtures {
    fun envelope(topic: String, payload: String): String =
        """{"topic":"$topic","payload":$payload}"""

    fun stringPayloadEnvelope(topic: String, payload: String): String =
        """{"topic":"$topic","payload":${payload.toJsonString()}}"""

    private fun String.toJsonString(): String =
        buildString {
            append('"')
            for (character in this@toJsonString) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
}
