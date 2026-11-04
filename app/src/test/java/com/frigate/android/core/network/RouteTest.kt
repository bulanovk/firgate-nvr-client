package com.frigate.android.core.network

import com.frigate.android.core.server.HomeNetwork
import com.frigate.android.core.server.ServerProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RouteTest {
    private val profile = ServerProfile(
        label = "Home",
        baseUrl = "https://vps.example.com",
        homeUrl = "http://192.168.1.10:5000",
        homeNetworks = listOf(HomeNetwork("Home WiFi")),
    )
    private val atHome = NetworkSnapshot("Home WiFi", null, true)
    private val away = NetworkSnapshot(null, null, true)

    @Test
    fun startsOnPrimaryUntilTheNetworkIsKnown() {
        assertEquals("https://vps.example.com", Route(profile, NetworkSnapshot.Unknown).active.baseUrl)
    }

    @Test
    fun movingToTheHomeNetworkActivatesTheHomeAddress() {
        val route = Route(profile, away).withNetwork(atHome)

        assertEquals(Endpoint.Home, route.endpoint)
        assertEquals("http://192.168.1.10:5000", route.active.baseUrl)
    }

    @Test
    fun leavingTheHomeNetworkReturnsToPrimary() {
        assertEquals(Endpoint.Primary, Route(profile, atHome).withNetwork(away).endpoint)
    }

    @Test
    fun failedHomeFallsBackToPrimary() {
        assertEquals(Endpoint.Primary, Route(profile, atHome).homeFailed().endpoint)
    }

    @Test
    fun fallbackStaysWhileTheNetworkIsUnchanged() {
        val route = Route(profile, atHome).homeFailed().withNetwork(atHome)

        assertEquals(Endpoint.Primary, route.endpoint)
    }

    @Test
    fun aChangeOfNetworkRetriesHome() {
        val route = Route(profile, atHome).homeFailed().withNetwork(away).withNetwork(atHome)

        assertEquals(Endpoint.Home, route.endpoint)
    }

    @Test
    fun editingTheProfileRetriesHome() {
        val route = Route(profile, atHome).homeFailed().withProfile(profile.copy(label = "Renamed"))

        assertEquals(Endpoint.Home, route.endpoint)
    }

    @Test
    fun theSameProfileKeepsTheFallback() {
        val route = Route(profile, atHome).homeFailed().withProfile(profile.copy())

        assertEquals(Endpoint.Primary, route.endpoint)
    }

    @Test
    fun failureOnPrimaryDoesNotLatchAnything() {
        val route = Route(profile, away).homeFailed()

        assertEquals(Endpoint.Primary, route.endpoint)
        assertEquals(Endpoint.Home, route.withNetwork(atHome).endpoint)
    }

    @Test
    fun revokingTheLocationPermissionReturnsToPrimary() {
        val route = Route(profile, atHome).withNetwork(NetworkSnapshot("Home WiFi", null, false))

        assertEquals(Endpoint.Primary, route.endpoint)
    }

    @Test
    fun sameSnapshotKeepsTheActiveProfileEqual() {
        val route = Route(profile, atHome)

        assertEquals(route.active, route.withNetwork(atHome.copy()).active)
    }
}
