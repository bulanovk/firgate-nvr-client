package com.frigate.android.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultServerStoreTest {
    @Test
    fun savedServerCanBeRestoredFromPreferences() {
        val preferences = InMemoryPreferences()
        val store = DefaultServerStore(preferences)
        val profile = ServerProfile("Home", "http://frigate.local")

        store.save(profile)

        assertEquals(profile, store.load())
    }

    @Test
    fun homeUrlAndNetworksSurviveRestore() {
        val store = DefaultServerStore(InMemoryPreferences())
        val profile = ServerProfile(
            label = "Home",
            baseUrl = "https://frigate.example.com",
            homeUrl = "http://192.168.1.10:5000",
            homeNetworks = listOf(HomeNetwork("Home WiFi"), HomeNetwork("Cabin", "aa:bb:cc:dd:ee:ff")),
        )

        store.save(profile)

        assertEquals(profile, store.load())
    }

    @Test
    fun profileSavedWithoutHomeAddressRestoresWithoutIt() {
        val preferences = InMemoryPreferences()
        val store = DefaultServerStore(preferences)
        store.save(ServerProfile("Home", "https://a", "http://b", listOf(HomeNetwork("X"))))

        store.save(ServerProfile("Home", "https://a"))

        assertEquals(ServerProfile("Home", "https://a"), store.load())
    }

    @Test
    fun profileStoredBeforeHomeRoutingStillLoads() {
        val preferences = InMemoryPreferences()
        preferences.put("default_server_url", "https://old.example.com")

        assertEquals(ServerProfile("Frigate server", "https://old.example.com"), DefaultServerStore(preferences).load())
    }

    private class InMemoryPreferences : ServerPreferences {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }
}
