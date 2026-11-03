package com.frigate.android.core.server

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
        return ServerProfile(label, url)
    }

    fun save(profile: ServerProfile) {
        preferences.put(URL_KEY, profile.baseUrl)
        preferences.put(LABEL_KEY, profile.label)
    }

    fun saveSession(profile: ServerProfile, cookie: String) {
        preferences.put(sessionKey(profile), cookie)
    }

    fun loadSession(profile: ServerProfile): String? = preferences.get(sessionKey(profile))

    fun clearSession(profile: ServerProfile) = preferences.remove(sessionKey(profile))

    fun clear() = preferences.clear()

    private fun sessionKey(profile: ServerProfile): String =
        SESSION_PREFIX + profile.baseUrl.sha256()

    private companion object {
        const val URL_KEY = "default_server_url"
        const val LABEL_KEY = "default_server_label"
        const val SESSION_PREFIX = "session_cookie_"
    }
}

internal fun String.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
