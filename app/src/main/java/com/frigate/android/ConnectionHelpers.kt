package com.frigate.android

import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateNetworkException
import com.frigate.android.core.server.FrigateRepository
import com.frigate.android.core.server.ServerProfile

internal suspend fun loadConnectedState(
    profile: ServerProfile,
    client: FrigateClient,
): ConnectionState.Connected {
    val repository = FrigateRepository(client)
    val user = repository.loadProfile()
    val configuration = repository.loadConfiguration()
    return ConnectionState.Connected(
        profile = profile,
        user = user,
        cameras = configuration.cameras,
        sessionCookie = client.cookieHeader(),
        uiTimezone = configuration.uiTimezone,
    )
}

internal fun isUnauthorized(error: Throwable): Boolean =
    error is FrigateHttpException && error.code == 401

/**
 * The state after a failed attempt to reach the server.
 *
 * Only the server itself can refuse the session: a 401 asks for the password. Anything else (a
 * network that the system blocks while the screen is off, a tunnel that is still coming up, a
 * proxy without its upstream) leaves the session and the credentials alone, and the app tries again.
 */
internal fun connectionFailure(profile: ServerProfile, error: Throwable): ConnectionState =
    if (isUnauthorized(error)) {
        ConnectionState.LoginRequired(profile, error.userMessage())
    } else {
        ConnectionState.Unreachable(profile, error.userMessage())
    }

/** The pause before the next attempt to reach the server: it doubles up to half a minute. */
internal fun reconnectPauseMillis(failures: Int): Long = minOf(1_000L shl failures.coerceIn(0, 5), 30_000L)

internal fun Throwable.userMessage(): String = when (this) {
    is FrigateHttpException -> if (code == 401) "Invalid username or password." else "Frigate returned HTTP $code."
    is FrigateNetworkException -> "Could not reach Frigate. Check the URL and network."
    else -> message ?: "Something went wrong."
}
