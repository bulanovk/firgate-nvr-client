package com.frigate.android.core.server

import javax.crypto.SecretKey

class EncryptedSessionCookieStore(
    private val preferences: ServerPreferences,
    key: SecretKey,
) {
    private val cipher = SessionCookieCipher(key)

    fun save(profile: ServerProfile, cookie: String) {
        preferences.put(keyFor(profile), cipher.encrypt(cookie))
    }

    fun load(profile: ServerProfile): String? = preferences.get(keyFor(profile))?.let { encrypted ->
        runCatching { cipher.decrypt(encrypted) }.getOrNull()
    }

    fun clear(profile: ServerProfile) {
        preferences.remove(keyFor(profile))
    }

    private fun keyFor(profile: ServerProfile): String =
        SESSION_PREFIX + profile.baseUrl.sha256()

    private companion object {
        const val SESSION_PREFIX = "encrypted_session_cookie_"
    }
}
