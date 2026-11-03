package com.frigate.android.core.server

import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EncryptedCredentialsStoreTest {
    @Test
    fun restoresUsernameAndPasswordAfterStoreRecreation() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val profile = ServerProfile("Home", "http://frigate.local")
        val credentials = FrigateCredentials("operator", "secret")

        EncryptedCredentialsStore(preferences, SessionCookieCipher(key)).save(profile, credentials)

        assertTrue(EncryptedCredentialsStore(preferences, SessionCookieCipher(key)).load(profile) == credentials)
    }

    @Test
    fun doesNotShareCredentialsBetweenServerOrigins() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val home = ServerProfile("Home", "http://frigate.local")
        val remote = ServerProfile("Remote", "https://frigate.example.com")
        val store = EncryptedCredentialsStore(preferences, SessionCookieCipher(key))
        store.save(home, FrigateCredentials("operator", "secret"))

        assertNull(store.load(remote))
    }

    @Test
    fun isolatesCredentialsAcrossSchemeHostAndPort() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val profile = ServerProfile("Home", "http://frigate.example.com:5000/base")
        val store = EncryptedCredentialsStore(preferences, SessionCookieCipher(key))
        store.save(profile, FrigateCredentials("operator", "secret"))
        val variants = listOf(
            ServerProfile("Scheme", "https://frigate.example.com:5000/base"),
            ServerProfile("Host", "http://other.example.com:5000/base"),
            ServerProfile("Port", "http://frigate.example.com:5001/base"),
        )

        variants.forEach { assertNull(store.load(it)) }
    }

    @Test
    fun clearRemovesOnlySelectedProfileCredentials() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val home = ServerProfile("Home", "http://frigate.local")
        val remote = ServerProfile("Remote", "https://frigate.example.com")
        val homeCredentials = FrigateCredentials("home-user", "home-secret")
        val remoteCredentials = FrigateCredentials("remote-user", "remote-secret")
        val store = EncryptedCredentialsStore(preferences, SessionCookieCipher(key))
        store.save(home, homeCredentials)
        store.save(remote, remoteCredentials)

        store.clear(home)

        assertNull(store.load(home))
        assertTrue(store.load(remote) == remoteCredentials)
    }

    @Test
    fun storesUnicodeAndDelimitedCredentialsOnlyAsCiphertext() {
        val preferences = InMemoryPreferences()
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val profile = ServerProfile("Home", "http://frigate.local")
        val credentials = FrigateCredentials("operator:東京", "p|ass.word/秘密")
        val store = EncryptedCredentialsStore(preferences, SessionCookieCipher(key))

        store.save(profile, credentials)

        assertTrue(store.load(profile) == credentials)
        assertFalse(preferences.storedValues().any { it.contains("operator") || it.contains("秘密") })
    }

    @Test
    fun returnsNullForWrongKeyInvalidBase64ShortPayloadAndTamperedTag() {
        val preferences = InMemoryPreferences()
        val profile = ServerProfile("Home", "http://frigate.local")
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val store = EncryptedCredentialsStore(preferences, SessionCookieCipher(key))
        store.save(profile, FrigateCredentials("operator", "secret"))

        val wrongKey = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        assertNull(EncryptedCredentialsStore(preferences, SessionCookieCipher(wrongKey)).load(profile))

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
