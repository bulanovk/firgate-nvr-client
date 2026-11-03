package com.frigate.android.core.server

import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EncryptedSessionCookieStoreTest {
    @Test
    fun restoresEncryptedCookieAfterStoreRecreation() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val profile = ServerProfile("Home", "http://frigate.local")

        EncryptedSessionCookieStore(preferences, key).save(profile, "frigate_token=abc")

        assertEquals(
            "frigate_token=abc",
            EncryptedSessionCookieStore(preferences, key).load(profile),
        )
    }

    @Test
    fun doesNotShareCookieBetweenServerOrigins() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val home = ServerProfile("Home", "http://frigate.local")
        val remote = ServerProfile("Remote", "https://frigate.example.com")
        val store = EncryptedSessionCookieStore(preferences, key)
        store.save(home, "frigate_token=home")

        assertNull(store.load(remote))
    }

    @Test
    fun clearRemovesOnlyTheSelectedServerSession() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val home = ServerProfile("Home", "http://frigate.local")
        val remote = ServerProfile("Remote", "https://frigate.example.com")
        val store = EncryptedSessionCookieStore(preferences, key)
        store.save(home, "frigate_token=home")
        store.save(remote, "frigate_token=remote")

        store.clear(home)

        assertNull(store.load(home))
        assertEquals("frigate_token=remote", store.load(remote))
    }

    @Test
    fun isolatesCookiesAcrossSchemeHostAndPort() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val profile = ServerProfile("Home", "http://frigate.example.com:5000/base")
        val store = EncryptedSessionCookieStore(preferences, key)
        store.save(profile, "frigate_token=synthetic")
        val variants = listOf(
            ServerProfile("Scheme", "https://frigate.example.com:5000/base"),
            ServerProfile("Host", "http://other.example.com:5000/base"),
            ServerProfile("Port", "http://frigate.example.com:5001/base"),
        )

        variants.forEach { assertNull(store.load(it)) }
    }

    @Test
    fun returnsNullForWrongKeyInvalidBase64ShortPayloadAndTamperedTag() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val profile = ServerProfile("Home", "http://frigate.local")
        val store = EncryptedSessionCookieStore(preferences, key)
        store.save(profile, "frigate_token=synthetic")

        val wrongKey = SecretKeySpec(ByteArray(32) { 5 }, "AES")
        assertNull(EncryptedSessionCookieStore(preferences, wrongKey).load(profile))

        listOf("not-base64", "AA==").forEach { corrupted ->
            preferences.replaceOnlyStoredValue(corrupted)
            assertNull(store.load(profile))
        }

        val tampered = Base64.getDecoder().decode(preferences.storedValues().single()).also { payload ->
            payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        }
        preferences.replaceOnlyStoredValue(Base64.getEncoder().encodeToString(tampered))
        assertNull(store.load(profile))
    }

    @Test
    fun backingPreferencesContainOnlyEncryptedCookie() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val profile = ServerProfile("Home", "http://frigate.local")
        val cookie = "frigate_token=synthetic"

        EncryptedSessionCookieStore(preferences, key).save(profile, cookie)

        assertFalse(preferences.storedValues().any { it.contains(cookie) })
    }

    private class InMemoryPreferences : ServerPreferences {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
        fun storedValues(): List<String> = values.values.toList()
        fun replaceOnlyStoredValue(value: String) {
            val key = values.keys.single()
            values[key] = value
        }
    }
}
