package com.frigate.android.core.server

/**
 * The stored credentials of a user, one pair per server.
 *
 * The store is the way back into a server whose session has expired, so the credentials stay in
 * it until the server refuses the login itself.
 */
interface CredentialsStore {
    fun save(profile: ServerProfile, credentials: FrigateCredentials)

    fun load(profile: ServerProfile): FrigateCredentials?

    fun clear(profile: ServerProfile)
}
