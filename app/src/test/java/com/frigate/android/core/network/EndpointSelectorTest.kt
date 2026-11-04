package com.frigate.android.core.network

import com.frigate.android.core.server.HomeNetwork
import com.frigate.android.core.server.ServerProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EndpointSelectorTest {
    private val profile = ServerProfile(
        label = "Home",
        baseUrl = "https://vps.example.com",
        homeUrl = "http://192.168.1.10:5000",
        homeNetworks = listOf(HomeNetwork("Home WiFi")),
    )

    private fun select(
        snapshot: NetworkSnapshot,
        profile: ServerProfile = this.profile,
        homeUnreachable: Boolean = false,
    ) = EndpointSelector.select(profile, snapshot, homeUnreachable)

    @Test
    fun matchingSsidSelectsHome() {
        assertEquals(Endpoint.Home, select(NetworkSnapshot("Home WiFi", null, true)))
    }

    @Test
    fun androidQuotedSsidMatches() {
        assertEquals(Endpoint.Home, select(NetworkSnapshot("\"Home WiFi\"", null, true)))
    }

    @Test
    fun ssidIsCaseSensitive() {
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("home wifi", null, true)))
    }

    @Test
    fun otherNetworkSelectsPrimary() {
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("Cafe", "aa:bb:cc:dd:ee:ff", true)))
    }

    @Test
    fun noWifiSelectsPrimary() {
        assertEquals(Endpoint.Primary, select(NetworkSnapshot(null, null, true)))
    }

    @Test
    fun unknownSsidSelectsPrimary() {
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("<unknown ssid>", null, true)))
    }

    @Test
    fun withoutLocationPermissionPrimaryIsUsed() {
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("Home WiFi", null, false)))
    }

    @Test
    fun withoutHomeUrlPrimaryIsUsed() {
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("Home WiFi", null, true), profile.copy(homeUrl = null)))
    }

    @Test
    fun emptyNetworkListSelectsPrimary() {
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("Home WiFi", null, true), profile.copy(homeNetworks = emptyList())))
    }

    @Test
    fun unreachableHomeFallsBackToPrimary() {
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("Home WiFi", null, true), homeUnreachable = true))
    }

    @Test
    fun networkWithBssidRequiresBothToMatch() {
        val pinned = profile.copy(homeNetworks = listOf(HomeNetwork("Home WiFi", "AA:BB:CC:DD:EE:FF")))

        assertEquals(Endpoint.Home, select(NetworkSnapshot("Home WiFi", "aa:bb:cc:dd:ee:ff", true), pinned))
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("Home WiFi", "11:22:33:44:55:66", true), pinned))
        assertEquals(Endpoint.Primary, select(NetworkSnapshot("Home WiFi", null, true), pinned))
    }

    @Test
    fun placeholderBssidIsTreatedAsUnknown() {
        val pinned = profile.copy(homeNetworks = listOf(HomeNetwork("Home WiFi", "02:00:00:00:00:00")))

        assertEquals(Endpoint.Primary, select(NetworkSnapshot("Home WiFi", "02:00:00:00:00:00", true), pinned))
    }

    @Test
    fun anyOfSeveralNetworksSelectsHome() {
        val several = profile.copy(homeNetworks = listOf(HomeNetwork("Home 2.4"), HomeNetwork("Home 5")))

        assertEquals(Endpoint.Home, select(NetworkSnapshot("Home 5", null, true), several))
    }

    @Test
    fun activeProfileUsesTheAddressOfTheEndpoint() {
        assertEquals("http://192.168.1.10:5000", profile.activeFor(Endpoint.Home).baseUrl)
        assertEquals("https://vps.example.com", profile.activeFor(Endpoint.Primary).baseUrl)
    }
}
