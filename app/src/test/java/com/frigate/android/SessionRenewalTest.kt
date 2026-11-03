package com.frigate.android

import com.frigate.android.core.server.CredentialsStore
import com.frigate.android.core.server.FrigateCredentials
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.ServerProfile
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The renewal of a session that the server no longer accepts.
 *
 * The credentials of the user are the way back into the server, so they stay in the store until
 * the server refuses the login itself: an expired session is not a reason to ask for the password
 * again, and an unreachable server is not a refusal.
 */
class SessionRenewalTest {
    private val profile = ServerProfile("Home", "http://frigate.local")

    @Test
    fun renewsTheSessionWithTheStoredCredentials() = runBlocking {
        val store = InMemoryCredentialsStore()
        store.save(profile, FrigateCredentials("operator", "secret"))
        val renewal = SessionRenewal(store)

        val cookie = renewal.renew(profile) { credentials ->
            assertEquals("operator", credentials.username)
            assertEquals("secret", credentials.password)
            "frigate_token=new"
        }

        assertEquals("frigate_token=new", cookie)
        assertEquals(FrigateCredentials("operator", "secret"), store.load(profile))
    }

    @Test
    fun hasNothingToRenewWithWithoutStoredCredentials() = runBlocking {
        val renewal = SessionRenewal(InMemoryCredentialsStore())

        val cookie = renewal.renew(profile) { "frigate_token=new" }

        assertNull(cookie)
    }

    @Test
    fun keepsTheCredentialsWhenTheServerIsUnreachable() = runBlocking {
        val store = InMemoryCredentialsStore()
        store.save(profile, FrigateCredentials("operator", "secret"))
        val renewal = SessionRenewal(store)

        val error = runCatching {
            renewal.renew(profile) { throw IOException("no route to host") }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(FrigateCredentials("operator", "secret"), store.load(profile))
    }

    @Test
    fun dropsTheCredentialsWhenTheServerRefusesTheLogin() = runBlocking {
        val store = InMemoryCredentialsStore()
        store.save(profile, FrigateCredentials("operator", "secret"))
        val renewal = SessionRenewal(store)

        val error = runCatching {
            renewal.renew(profile) { throw FrigateHttpException(401, "Login failed") }
        }.exceptionOrNull()

        assertTrue(error is FrigateHttpException)
        assertNull(store.load(profile))
    }

    @Test
    fun keepsTheCredentialsOfAnotherServerWhenOneLoginIsRefused() = runBlocking {
        val store = InMemoryCredentialsStore()
        val remote = ServerProfile("Remote", "https://frigate.example.com")
        store.save(profile, FrigateCredentials("operator", "secret"))
        store.save(remote, FrigateCredentials("remote-user", "remote-secret"))
        val renewal = SessionRenewal(store)

        runCatching {
            renewal.renew(profile) { throw FrigateHttpException(401, "Login failed") }
        }

        assertEquals(FrigateCredentials("remote-user", "remote-secret"), store.load(remote))
    }

    @Test
    fun signsInAgainAndKeepsTheCookieOfTheNewSession() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setHeader("Set-Cookie", "frigate_token=new; Path=/; HttpOnly")
                    .setBody("""{"success":true}"""),
            )
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"username":"operator"}"""))

            val cookie = signIn(
                ServerProfile("Test", server.url("/").toString()),
                FrigateCredentials("operator", "secret"),
            )

            assertTrue(cookie.contains("frigate_token=new"))
            val login = server.takeRequest()
            assertEquals("/api/login", login.path)
            assertEquals("""{"user":"operator","password":"secret"}""", login.body.readUtf8())
        }
    }

    private class InMemoryCredentialsStore : CredentialsStore {
        private val values = mutableMapOf<String, FrigateCredentials>()
        override fun save(profile: ServerProfile, credentials: FrigateCredentials) {
            values[profile.baseUrl] = credentials
        }
        override fun load(profile: ServerProfile): FrigateCredentials? = values[profile.baseUrl]
        override fun clear(profile: ServerProfile) {
            values.remove(profile.baseUrl)
        }
    }
}
