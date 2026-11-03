package com.frigate.android

import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateHttpException
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
