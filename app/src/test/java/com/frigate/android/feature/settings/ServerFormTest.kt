package com.frigate.android.feature.settings

import com.frigate.android.core.server.HomeNetwork
import com.frigate.android.core.server.ServerProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerFormTest {
    private val server = ServerProfile.fromInput("Dacha", "https://a.example.com").getOrThrow()
        .withHome("http://192.168.1.10:5000", listOf(HomeNetwork("Home"))).getOrThrow()

    @Test
    fun unchangedMainAddressSavesTheHomeSettingsOnly() {
        val form = server.submit("https://a.example.com/", "http://10.0.0.5:5000", listOf(HomeNetwork("Dacha 5G")))

        assertFalse(form.mainChanged)
        assertEquals("http://10.0.0.5:5000", form.profile?.homeUrl)
        assertEquals(listOf(HomeNetwork("Dacha 5G")), form.profile?.homeNetworks)
        assertEquals("https://a.example.com", form.profile?.primaryUrl)
    }

    @Test
    fun changedMainAddressIsReportedAndKeepsTheHomeSettings() {
        val form = server.submit("https://b.example.com", "http://192.168.1.10:5000", listOf(HomeNetwork("Home")))

        assertTrue(form.mainChanged)
        assertEquals("https://b.example.com", form.profile?.primaryUrl)
        assertEquals("https://b.example.com", form.profile?.baseUrl)
        assertEquals("http://192.168.1.10:5000", form.profile?.homeUrl)
        assertEquals("Dacha", form.profile?.label)
    }

    @Test
    fun blankHomeAddressRemovesIt() {
        val form = server.submit("https://a.example.com", "  ", emptyList())

        assertNull(form.profile?.homeUrl)
        assertNull(form.homeError)
    }

    @Test
    fun invalidMainAddressBlocksTheSaveAndIsShownAtItsField() {
        val form = server.submit("http://b.example.com", "http://192.168.1.10:5000", emptyList())

        assertNull(form.profile)
        assertEquals("Use an https:// URL for the main address.", form.mainError)
        assertNull(form.homeError)
    }

    @Test
    fun invalidHomeAddressBlocksTheSaveAndIsShownAtItsField() {
        val form = server.submit("https://a.example.com", "ftp://nas", emptyList())

        assertNull(form.profile)
        assertNull(form.mainError)
        assertNotNull(form.homeError)
    }

    @Test
    fun bothFieldsReportTheirOwnErrors() {
        val form = server.submit("", "ftp://nas", emptyList())

        assertNull(form.profile)
        assertEquals("Enter a Frigate server URL.", form.mainError)
        assertNotNull(form.homeError)
    }
}
