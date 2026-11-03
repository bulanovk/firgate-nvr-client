package com.frigate.android.core.server

import java.net.URI

/** The single endpoint used until Wi-Fi routing is introduced in the final phase. */
data class ServerProfile(
    val label: String,
    val baseUrl: String,
) {
    companion object {
        fun fromInput(label: String, input: String): Result<ServerProfile> {
            val normalized = input.trim().trimEnd('/')
            if (normalized.isBlank()) {
                return Result.failure(IllegalArgumentException("Enter a Frigate server URL."))
            }

            return runCatching {
                val uri = URI(normalized)
                require(uri.scheme == "https" || uri.scheme == "http") {
                    "Use an http:// or https:// URL."
                }
                require(uri.userInfo.isNullOrBlank()) {
                    "Credentials must not be included in the URL."
                }
                require(!uri.host.isNullOrBlank()) {
                    "The URL must include a server host."
                }
                require(uri.query == null && uri.fragment == null) {
                    "Do not include a query or fragment in the server URL."
                }
                ServerProfile(
                    label = label.trim().ifBlank { "Frigate server" },
                    baseUrl = normalized,
                )
            }
        }
    }
}
