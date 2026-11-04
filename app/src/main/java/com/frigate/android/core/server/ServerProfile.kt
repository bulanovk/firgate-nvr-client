package com.frigate.android.core.server

import java.net.URI

/** A Wi-Fi network that counts as "home"; with a [bssid] only that access point matches. */
data class HomeNetwork(
    val ssid: String,
    val bssid: String? = null,
)

/**
 * A Frigate server. [baseUrl] is the primary address that works from anywhere; [homeUrl] is an
 * optional direct address used while the phone is on one of the [homeNetworks].
 */
data class ServerProfile(
    val label: String,
    val baseUrl: String,
    val homeUrl: String? = null,
    val homeNetworks: List<HomeNetwork> = emptyList(),
    /** The primary address that identifies the server; stays put when [baseUrl] switches to [homeUrl]. */
    val primaryUrl: String = baseUrl,
) {
    /** Replaces the home address and networks; a blank [homeInput] removes the home address. */
    fun withHome(homeInput: String, networks: List<HomeNetwork>): Result<ServerProfile> {
        val cleanNetworks = networks.mapNotNull { network ->
            val ssid = network.ssid.trim()
            if (ssid.isEmpty()) null else HomeNetwork(ssid, network.bssid?.trim()?.ifEmpty { null })
        }
        if (homeInput.isBlank()) {
            return Result.success(copy(homeUrl = null, homeNetworks = cleanNetworks))
        }
        return normalizeUrl(homeInput, httpAllowed = true).map { copy(homeUrl = it, homeNetworks = cleanNetworks) }
    }

    /** Replaces the main address; the label and the home settings stay. The session of the old address is not carried over. */
    fun withMain(input: String): Result<ServerProfile> =
        normalizeUrl(input, httpAllowed = false).map { copy(baseUrl = it, primaryUrl = it) }

    companion object {
        private val loopbackHosts = setOf("localhost", "127.0.0.1")

        /** The main address is reachable from anywhere, so it must be https; loopback is for local test servers. */
        fun fromInput(label: String, input: String): Result<ServerProfile> =
            normalizeUrl(input, httpAllowed = false).map { url ->
                ServerProfile(label = label.trim().ifBlank { "Frigate server" }, baseUrl = url)
            }

        private fun normalizeUrl(input: String, httpAllowed: Boolean): Result<String> {
            val normalized = input.trim().trimEnd('/')
            if (normalized.isBlank()) {
                return Result.failure(IllegalArgumentException("Enter a Frigate server URL."))
            }

            return runCatching {
                val uri = URI(normalized)
                require(uri.scheme == "https" || uri.scheme == "http") {
                    "Use an http:// or https:// URL."
                }
                require(httpAllowed || uri.scheme == "https" || uri.host in loopbackHosts) {
                    "Use an https:// URL for the main address."
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
                normalized
            }
        }
    }
}
