package com.frigate.android.core.network

import com.frigate.android.core.server.ServerProfile

/**
 * The address the app uses right now: the server, the network it sees and whether the home
 * address already failed on this network. Every change returns a new route, so the app can hold
 * it as plain state and rebuild its connections when [active] changes.
 */
data class Route(
    val profile: ServerProfile,
    val snapshot: NetworkSnapshot = NetworkSnapshot.Unknown,
    private val homeUnreachable: Boolean = false,
) {
    val endpoint: Endpoint get() = EndpointSelector.select(profile, snapshot, homeUnreachable)

    val active: ServerProfile get() = profile.activeFor(endpoint)

    /** A new network gives the home address another chance; the same network does not. */
    fun withNetwork(next: NetworkSnapshot): Route =
        if (next == snapshot) this else copy(snapshot = next, homeUnreachable = false)

    /** Edited settings may have fixed the home address, so it is tried again. */
    fun withProfile(next: ServerProfile): Route =
        if (next == profile) this else copy(profile = next, homeUnreachable = false)

    /** The home address did not answer: use the main one until the network changes. */
    fun homeFailed(): Route = if (endpoint == Endpoint.Home) copy(homeUnreachable = true) else this
}
