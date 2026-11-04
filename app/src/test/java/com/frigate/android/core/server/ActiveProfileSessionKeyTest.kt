package com.frigate.android.core.server

import com.frigate.android.core.network.Endpoint
import com.frigate.android.core.network.activeFor
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The session belongs to the server, so switching to the home address must not log the user out. */
class ActiveProfileSessionKeyTest {
    private val primary = ServerProfile("Home", "https://vps.example.com", homeUrl = "http://192.168.1.10:5000")
    private val atHome = primary.activeFor(Endpoint.Home)
    private val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")

    @Test
    fun cookieSavedOnPrimaryIsFoundAtHome() {
        val store = EncryptedSessionCookieStore(InMemoryPreferences(), key)
        store.save(primary, "frigate_token=abc")

        assertEquals("frigate_token=abc", store.load(atHome))
    }

    @Test
    fun cookieRenewedAtHomeIsFoundOnPrimary() {
        val store = EncryptedSessionCookieStore(InMemoryPreferences(), key)
        store.save(atHome, "frigate_token=new")

        assertEquals("frigate_token=new", store.load(primary))
    }

    @Test
    fun credentialsSavedOnPrimaryAreFoundAtHome() {
        val store = EncryptedCredentialsStore(InMemoryPreferences(), SessionCookieCipher(key))
        store.save(primary, FrigateCredentials("admin", "secret"))

        assertEquals(FrigateCredentials("admin", "secret"), store.load(atHome))
    }

    @Test
    fun plainSessionSavedOnPrimaryIsFoundAtHome() {
        val store = DefaultServerStore(InMemoryPreferences())
        store.saveSession(primary, "frigate_token=abc")

        assertEquals("frigate_token=abc", store.loadSession(atHome))
    }

    @Test
    fun clearAtHomeRemovesTheSharedSession() {
        val store = EncryptedSessionCookieStore(InMemoryPreferences(), key)
        store.save(primary, "frigate_token=abc")

        store.clear(atHome)

        assertNull(store.load(primary))
    }

    @Test
    fun differentServersStillHaveDifferentSessions() {
        val store = EncryptedSessionCookieStore(InMemoryPreferences(), key)
        store.save(primary, "frigate_token=abc")

        assertNull(store.load(ServerProfile("Other", "https://other.example.com", homeUrl = "http://192.168.1.10:5000")))
    }

    private class InMemoryPreferences : ServerPreferences {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }
}
