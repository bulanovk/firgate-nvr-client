package com.frigate.android.core.network

import com.frigate.android.core.server.HomeNetwork
import com.frigate.android.core.server.ServerProfile

/** Which of the two addresses of a server is in use. */
enum class Endpoint { Primary, Home }

/** What the selector needs to know about the current Wi-Fi; Android types stay outside. */
data class NetworkSnapshot(
    val ssid: String?,
    val bssid: String?,
    val locationGranted: Boolean,
) {
    /** The SSID without the quotes Android adds; null when Android hides it or there is no Wi-Fi. */
    val knownSsid: String?
        get() = ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    /** The BSSID, unless Android replaced it with the placeholder it shows to apps without permission. */
    val knownBssid: String?
        get() = bssid?.takeIf { !it.equals(HIDDEN_BSSID, ignoreCase = true) }

    /** The network as a list entry; null when it cannot be told, or when the access point cannot be pinned. */
    fun asHomeNetwork(pinAccessPoint: Boolean): HomeNetwork? {
        val name = knownSsid ?: return null
        if (!pinAccessPoint) return HomeNetwork(name)
        return knownBssid?.let { HomeNetwork(name, it) }
    }

    companion object {
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val HIDDEN_BSSID = "02:00:00:00:00:00"

        /** Before the first report of the monitor nothing is known, so the main address is used. */
        val Unknown = NetworkSnapshot(null, null, false)
    }
}

object EndpointSelector {
    /** Home only when the phone is on a home network and the home address is usable; otherwise Primary. */
    fun select(profile: ServerProfile, snapshot: NetworkSnapshot, homeUnreachable: Boolean): Endpoint {
        if (homeUnreachable || profile.homeUrl == null || !snapshot.locationGranted) return Endpoint.Primary
        val ssid = snapshot.knownSsid ?: return Endpoint.Primary
        val bssid = snapshot.knownBssid
        return if (profile.homeNetworks.any { it.matches(ssid, bssid) }) Endpoint.Home else Endpoint.Primary
    }

    private fun HomeNetwork.matches(ssid: String, bssid: String?): Boolean =
        this.ssid == ssid && (this.bssid == null || this.bssid.equals(bssid, ignoreCase = true))
}

/** The profile every consumer works with: [baseUrl] is the address of [endpoint]. */
fun ServerProfile.activeFor(endpoint: Endpoint): ServerProfile =
    if (endpoint == Endpoint.Home && homeUrl != null) copy(baseUrl = homeUrl) else this
