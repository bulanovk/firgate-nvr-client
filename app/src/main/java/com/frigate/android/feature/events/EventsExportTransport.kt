package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.ServerProfile
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** Stream to an app-owned destination, never launch a cookie-bearing server URL externally. */
internal class EventsExportTransport(private val profile: ServerProfile, private val cookie: String, private val maxBytes: Long = Long.MAX_VALUE) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun download(path: String, output: OutputStream): Long {
        if (!path.startsWith("exports/")) throw IOException("Invalid export path")
        val url = eventsMediaUrl(profile, path) ?: throw IOException("Invalid export path")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("Cookie", cookie).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val total = response.use {
                            if (!it.isSuccessful) throw FrigateHttpException(it.code, "Could not download export")
                            val body = it.body ?: throw IOException("Empty export")
                            if (body.contentLength() > maxBytes) throw IOException("Export exceeds size limit")
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var count = 0L
                                while (continuation.isActive) {
                                    val size = input.read(buffer)
                                    if (size == -1) break
                                    if (size > maxBytes - count) throw IOException("Export exceeds size limit")
                                    output.write(buffer, 0, size)
                                    count += size
                                }
                                if (count == 0L && continuation.isActive) throw IOException("Empty export")
                                count
                            }
                        }
                        if (continuation.isActive) continuation.resume(total)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
}
