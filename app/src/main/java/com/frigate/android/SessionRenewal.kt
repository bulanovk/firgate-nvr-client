package com.frigate.android

import com.frigate.android.core.server.CredentialsStore
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateCredentials
import com.frigate.android.core.server.FrigateRepository
import com.frigate.android.core.server.ServerProfile

/**
 * The renewal of a session that the server no longer accepts.
 *
 * The credentials of the user are the way back into the server, so they stay in the encrypted
 * store until the server refuses the login itself: an expired session is not a reason to ask for
 * the password again, and an unreachable server is not a refusal.
 */
internal class SessionRenewal(private val credentialsStore: CredentialsStore) {
    /**
     * Signs in again with the stored credentials of the profile.
     *
     * The login returns the cookie of the new session. Without stored credentials there is
     * nothing to sign in with: the result is null and the user signs in by hand.
     */
    suspend fun renew(
        profile: ServerProfile,
        login: suspend (FrigateCredentials) -> String,
    ): String? {
        val credentials = credentialsStore.load(profile) ?: return null
        try {
            return login(credentials)
        } catch (error: Throwable) {
            // The error comes from the login itself, so a refusal means that the stored password
            // is stale and is dropped. Every other failure is temporary and keeps the
            // credentials of the user.
            if (isUnauthorized(error)) credentialsStore.clear(profile)
            throw error
        }
    }
}

/** Signs in with the credentials and returns the cookie of the new session. */
internal suspend fun signIn(profile: ServerProfile, credentials: FrigateCredentials): String {
    val client = FrigateClient(profile)
    FrigateRepository(client).login(credentials.username, credentials.password)
    return client.cookieHeader()
}
