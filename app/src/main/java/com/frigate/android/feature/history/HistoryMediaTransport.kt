package com.frigate.android.feature.history

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** The archive player is only allowed to talk to the origin of the configured server. */
internal class HistoryMediaException(message: String) : IOException(message)

/**
 * The single HTTP path of the archive player. Every URL is checked against the origin of the
 * server profile before the request leaves the device, so neither a manifest nor a redirect can
 * pull the session cookie to a foreign host.
 */
internal class HistoryMediaTransport(
    origin: URI,
    private val cookie: String,
) {
    private val originScheme = origin.scheme?.lowercase()
    private val originHost = origin.host
    private val originPort = effectivePort(origin)

    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isSameOrigin(candidate: URI): Boolean {
        val scheme = candidate.scheme?.lowercase() ?: return false
        if (scheme != originScheme) return false
        if (!candidate.host.equals(originHost, ignoreCase = true)) return false
        return effectivePort(candidate) == originPort
    }

    /** Rejects anything that is not a plain http(s) URL of the origin of the profile. */
    fun checkUrl(url: String): URI {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw HistoryMediaException("Malformed archive URL.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw HistoryMediaException("Unsupported archive URL scheme.")
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            throw HistoryMediaException("Credentials must not be included in the URL.")
        }
        if (uri.host.isNullOrEmpty()) {
            throw HistoryMediaException("The archive URL must include a server host.")
        }
        if (!isSameOrigin(uri)) {
            throw HistoryMediaException("The archive URL points outside the configured server.")
        }
        return uri
    }

    /** The session cookie of the profile always wins over a header supplied by the caller. */
    fun headers(supplied: Map<String, String>): Map<String, String> {
        val headers = supplied.toMutableMap()
        headers["Cookie"] = cookie
        return headers
    }

    fun request(url: String): Request =
        Request.Builder()
            .url(checkUrl(url).toString())
            .header("Cookie", cookie)
            .build()

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
}
