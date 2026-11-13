package com.frigate.android.core.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

interface ServerPreferences {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

class DefaultServerStore(private val preferences: ServerPreferences) {
    fun load(): ServerProfile? {
        val url = preferences.get(URL_KEY) ?: return null
        val label = preferences.get(LABEL_KEY) ?: "Frigate server"
        val networks = preferences.get(HOME_NETWORKS_KEY)?.let(::parseHomeNetworks).orEmpty()
        return ServerProfile(label, url, preferences.get(HOME_URL_KEY), networks)
    }

    fun save(profile: ServerProfile) {
        preferences.put(URL_KEY, profile.baseUrl)
        preferences.put(LABEL_KEY, profile.label)
        if (profile.homeUrl == null) preferences.remove(HOME_URL_KEY) else preferences.put(HOME_URL_KEY, profile.homeUrl)
        if (profile.homeNetworks.isEmpty()) {
            preferences.remove(HOME_NETWORKS_KEY)
        } else {
            preferences.put(HOME_NETWORKS_KEY, homeNetworksJson(profile.homeNetworks))
        }
    }

    fun saveSession(profile: ServerProfile, cookie: String) {
        preferences.put(sessionKey(profile), cookie)
    }

    fun loadSession(profile: ServerProfile): String? = preferences.get(sessionKey(profile))

    fun clearSession(profile: ServerProfile) = preferences.remove(sessionKey(profile))

    fun clear() = preferences.clear()

    private fun sessionKey(profile: ServerProfile): String =
        SESSION_PREFIX + profile.primaryUrl.sha256()

    private companion object {
        const val HOME_URL_KEY = "home_server_url"
        const val HOME_NETWORKS_KEY = "home_networks"
        const val URL_KEY = "default_server_url"
        const val LABEL_KEY = "default_server_label"
        const val SESSION_PREFIX = "session_cookie_"
    }
}

// The home networks are written and read by explicit keys, not by the field names of HomeNetwork: R8 renames
// those in a release build. Releases up to v0.0.2 stored what Gson saw there, `a` for the SSID and `b` for
// the BSSID, so those keys are still read.
private fun homeNetworksJson(networks: List<HomeNetwork>): String {
    val array = JsonArray()
    networks.forEach { network ->
        array.add(JsonObject().apply {
            addProperty("ssid", network.ssid)
            network.bssid?.let { addProperty("bssid", it) }
        })
    }
    return array.toString()
}

private fun parseHomeNetworks(json: String): List<HomeNetwork> {
    val array = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val ssid = entry.string("ssid") ?: entry.string("a") ?: return@mapNotNull null
        HomeNetwork(ssid, entry.string("bssid") ?: entry.string("b"))
    }
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

internal fun String.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
