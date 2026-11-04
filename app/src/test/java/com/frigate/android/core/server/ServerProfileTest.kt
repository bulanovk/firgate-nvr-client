package com.frigate.android.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ServerProfileTest {
    @Test
    fun normalizesTrailingSlashAndBlankLabel() {
        val profile = ServerProfile.fromInput("", " https://frigate.example.com/// ").getOrThrow()

        assertEquals("Frigate server", profile.label)
        assertEquals("https://frigate.example.com", profile.baseUrl)
    }

    @Test
    fun rejectsCredentialsInUrl() {
        val error = ServerProfile.fromInput("Home", "https://user:password@frigate.example.com")
            .exceptionOrNull()

        assertEquals("Credentials must not be included in the URL.", error?.message)
    }

    @Test
    fun rejectsQueryAndFragment() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.fromInput("Home", "https://frigate.example.com/path?token=secret").getOrThrow()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.fromInput("Home", "https://frigate.example.com/path#section").getOrThrow()
        }
    }

    @Test
    fun primaryAddressMustUseHttpsExceptOnLoopback() {
        listOf("http://frigate.example.com", "http://192.168.1.10:5000").forEach { input ->
            val error = ServerProfile.fromInput("Home", input).exceptionOrNull()
            assertEquals("Use an https:// URL for the main address.", error?.message)
        }
        listOf("http://localhost:5000", "http://127.0.0.1:8080").forEach { input ->
            assertEquals(input, ServerProfile.fromInput("Home", input).getOrThrow().baseUrl)
        }
    }

    @Test
    fun homeAddressMayUseHttp() {
        val profile = ServerProfile("Home", "https://frigate.example.com")
            .withHome("http://192.168.1.10:5000", emptyList())
            .getOrThrow()

        assertEquals("http://192.168.1.10:5000", profile.homeUrl)
    }

    @Test
    fun homeUrlIsNormalizedLikeThePrimaryUrl() {
        val profile = ServerProfile("Home", "https://frigate.example.com")
            .withHome(" http://192.168.1.10:5000/ ", listOf(HomeNetwork("Home WiFi")))
            .getOrThrow()

        assertEquals("http://192.168.1.10:5000", profile.homeUrl)
        assertEquals(listOf(HomeNetwork("Home WiFi")), profile.homeNetworks)
        assertEquals("https://frigate.example.com", profile.baseUrl)
    }

    @Test
    fun blankHomeUrlRemovesHomeAddress() {
        val profile = ServerProfile("Home", "https://frigate.example.com", homeUrl = "http://192.168.1.10:5000")
            .withHome("  ", emptyList())
            .getOrThrow()

        assertEquals(null, profile.homeUrl)
    }

    @Test
    fun invalidHomeUrlIsRejectedWithTheSameRules() {
        listOf("ftp://nas", "http://user:pw@nas", "http://nas?x=1").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerProfile("Home", "https://frigate.example.com").withHome(input, emptyList()).getOrThrow()
            }
        }
    }

    @Test
    fun blankNetworkNamesAreDropped() {
        val profile = ServerProfile("Home", "https://frigate.example.com")
            .withHome("http://nas:5000", listOf(HomeNetwork(" "), HomeNetwork(" Home ", " aa:bb:cc:dd:ee:ff ")))
            .getOrThrow()

        assertEquals(listOf(HomeNetwork("Home", "aa:bb:cc:dd:ee:ff")), profile.homeNetworks)
    }

    @Test
    fun rejectsEmptyUnsupportedSchemeAndMissingHost() {
        listOf(
            "",
            "ftp://frigate.example.com",
            "http:///frigate",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerProfile.fromInput("Home", input).getOrThrow()
            }
        }
    }

    @Test
    fun changingTheMainAddressKeepsTheLabelAndTheHomeSettings() {
        val home = ServerProfile.fromInput("Dacha", "https://a.example.com").getOrThrow()
            .withHome("http://192.168.1.10:5000", listOf(HomeNetwork("Home"))).getOrThrow()

        val changed = home.withMain(" https://b.example.com/ ").getOrThrow()

        assertEquals("Dacha", changed.label)
        assertEquals("https://b.example.com", changed.baseUrl)
        assertEquals("https://b.example.com", changed.primaryUrl)
        assertEquals("http://192.168.1.10:5000", changed.homeUrl)
        assertEquals(listOf(HomeNetwork("Home")), changed.homeNetworks)
    }

    @Test
    fun changedMainAddressFollowsTheSameRulesAsTheFirstOne() {
        val profile = ServerProfile.fromInput("Dacha", "https://a.example.com").getOrThrow()

        assertEquals("Use an https:// URL for the main address.", profile.withMain("http://b.example.com").exceptionOrNull()?.message)
        assertEquals("Enter a Frigate server URL.", profile.withMain("  ").exceptionOrNull()?.message)
    }
}
