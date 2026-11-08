package com.frigate.android.core.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** The latest build of a channel, or null when the channel offers none. Throws when the source does not answer. */
fun interface UpdateFeed {
    suspend fun latest(channel: UpdateChannel): AvailableUpdate?
}

/**
 * Releases come from the GitHub releases of the repository (tag `vX.Y.Z`, asset `*.apk`), test
 * builds from `version.json` next to the test APK on GitHub Pages (`publish-test-apk.yml`).
 */
class GitHubUpdateFeed(
    private val releasesUrl: String = "https://api.github.com/repos/bulanovk/firgate-nvr-client/releases/latest",
    private val devUrl: String = "https://bulanovk.github.io/firgate-nvr-client/version.json",
) : UpdateFeed {
    override suspend fun latest(channel: UpdateChannel): AvailableUpdate? = when (channel) {
        UpdateChannel.Release -> parseRelease(get(releasesUrl, "application/vnd.github+json"))
        UpdateChannel.Dev -> parseDev(get(devUrl, "application/json"), devUrl)
        UpdateChannel.None -> null
    }

    private suspend fun get(url: String, accept: String): JsonObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "frigate-android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: JsonObject): AvailableUpdate? {
        val tag = json.string("tag_name") ?: return null
        val apk = json.getAsJsonArray("assets")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("name")?.endsWith(".apk") == true }
            ?.string("browser_download_url") ?: return null
        return AvailableUpdate(tag.removePrefix("v"), apk, json.string("body")?.takeIf { it.isNotBlank() }, null)
    }

    private fun parseDev(json: JsonObject, base: String): AvailableUpdate? {
        val build = json.get("build")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        val apk = json.string("apk") ?: return null
        val name = json.string("versionName") ?: return null
        return AvailableUpdate(name, URI(base).resolve(apk).toString(), null, build)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
