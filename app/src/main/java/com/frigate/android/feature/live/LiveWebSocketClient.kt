package com.frigate.android.feature.live

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal fun createLiveWebSocketClient(pingIntervalMillis: Long = 0): OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            if (pingIntervalMillis > 0) {
                pingInterval(pingIntervalMillis, TimeUnit.MILLISECONDS)
            }
        }
        .build()
