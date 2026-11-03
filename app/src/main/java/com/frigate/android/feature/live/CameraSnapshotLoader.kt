package com.frigate.android.feature.live

import com.frigate.android.core.server.ServerProfile
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class CameraSnapshot(val bytes: ByteArray, val offline: Boolean)

object CameraSnapshotLoader {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun loadBytes(profile: ServerProfile, cameraId: String, cookie: String): ByteArray =
        request(profile, cameraId, cookie, "").bytes

    suspend fun load(
        profile: ServerProfile,
        cameraId: String,
        cookie: String,
        height: Int,
        cacheKey: Long,
    ): CameraSnapshot = request(profile, cameraId, cookie, "?height=${height.coerceIn(1, 1080)}&cache=$cacheKey")

    private suspend fun request(
        profile: ServerProfile,
        cameraId: String,
        cookie: String,
        query: String,
    ): CameraSnapshot = suspendCancellableCoroutine { continuation ->
        val encodedCameraId = URLEncoder.encode(cameraId, Charsets.UTF_8.name()).replace("+", "%20")
        val request = Request.Builder()
            .url("${profile.baseUrl.trimEnd('/')}/api/$encodedCameraId/latest.webp$query")
            .header("Cookie", cookie)
            .get()
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Snapshot request failed")
                        CameraSnapshot(
                            it.body?.bytes() ?: throw IOException("Snapshot response was empty"),
                            it.header("X-Frigate-Offline").equals("true", ignoreCase = true),
                        )
                    }
                }
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
}
