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

    private class InMemoryPreferences : ServerPreferences {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }
}
