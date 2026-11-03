package com.frigate.android.feature.review

import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.ReviewMediaEndpoint
import com.frigate.android.core.server.ServerProfile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * Loads review thumbnails from the review clip folder of the configured server.
 *
 * The thumbnail path is server supplied, so it is only honoured when it points at the review clip
 * folder of the current profile. Redirects are never followed, the session cookie is only sent to
 * the configured origin and the payload is bounded.
 */
object ReviewThumbnailLoader {
    const val MAX_BYTES: Int = 2 * 1024 * 1024

    private val defaultClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun load(
        profile: ServerProfile,
        cookie: String,
        review: FrigateReview,
        client: OkHttpClient = defaultClient,
    ): ByteArray? {
        val url = ReviewMediaEndpoint.thumbnailUrl(profile, review.thumbnailPath) ?: return null
        return request(url, cookie, client)
    }

    private suspend fun request(url: String, cookie: String, client: OkHttpClient): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .apply { cookie.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
                .get()
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = runCatching { response.use { readImage(it) } }.getOrNull()
                    if (continuation.isCancelled) return
                    continuation.resume(bytes)
                }
            })
        }

    private fun readImage(response: Response): ByteArray {
        if (!response.isSuccessful) throw IOException("Thumbnail request failed")
        val contentType = response.header("Content-Type")
        if (contentType != null && !contentType.trim().startsWith("image/", ignoreCase = true)) {
            throw IOException("Thumbnail response is not an image")
        }
        val body = response.body ?: throw IOException("Thumbnail response was empty")
        if (body.contentLength() > MAX_BYTES) throw IOException("Thumbnail response is too large")
        val bytes = body.readBounded(MAX_BYTES) ?: throw IOException("Thumbnail response is too large")
        if (!isSupportedImagePayload(bytes)) throw IOException("Thumbnail payload is not a supported image")
        return bytes
    }

    private fun ResponseBody.readBounded(limit: Int): ByteArray? {
        val stream = byteStream()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}

/** True when the payload starts with the magic bytes of an image format the app can decode. */
internal fun isSupportedImagePayload(bytes: ByteArray): Boolean = when {
    bytes.startsWithMagic(0x52, 0x49, 0x46, 0x46) && bytes.startsWithMagicAt(8, 0x57, 0x45, 0x42, 0x50) -> true
    bytes.startsWithMagic(0xFF, 0xD8, 0xFF) -> true
    bytes.startsWithMagic(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> true
    bytes.startsWithMagic(0x47, 0x49, 0x46, 0x38) &&
        bytes.size >= 6 && (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
        bytes[5] == 0x61.toByte() -> true
    else -> false
}

private fun ByteArray.startsWithMagic(vararg magic: Int): Boolean {
    if (size < magic.size) return false
    return magic.withIndex().all { (index, value) -> this[index] == value.toByte() }
}

private fun ByteArray.startsWithMagicAt(offset: Int, vararg magic: Int): Boolean {
    if (size < offset + magic.size) return false
    return magic.withIndex().all { (index, value) -> this[offset + index] == value.toByte() }
}
