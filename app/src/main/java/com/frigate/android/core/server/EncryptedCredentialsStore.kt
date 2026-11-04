package com.frigate.android.core.server

import java.nio.charset.StandardCharsets
import java.util.Base64

data class FrigateCredentials(
    val username: String,
    val password: String,
)

internal class EncryptedCredentialsStore(
    private val preferences: ServerPreferences,
    private val cipher: SessionCookieCipher,
) : CredentialsStore {
    override fun save(profile: ServerProfile, credentials: FrigateCredentials) {
        val payload = listOf(credentials.username, credentials.password)
            .joinToString(".") { value ->
                Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
            }
        preferences.put(keyFor(profile), cipher.encrypt(payload))
    }

    override fun load(profile: ServerProfile): FrigateCredentials? = preferences.get(keyFor(profile))?.let { encrypted ->
        runCatching {
            val values = cipher.decrypt(encrypted).split('.', limit = 2)
            require(values.size == 2)
            FrigateCredentials(
                username = String(Base64.getUrlDecoder().decode(values[0]), StandardCharsets.UTF_8),
                password = String(Base64.getUrlDecoder().decode(values[1]), StandardCharsets.UTF_8),
            )
        }.getOrNull()
    }

    override fun clear(profile: ServerProfile) {
        preferences.remove(keyFor(profile))
    }

    private fun keyFor(profile: ServerProfile): String = CREDENTIALS_PREFIX + profile.primaryUrl.sha256()

    private companion object {
        const val CREDENTIALS_PREFIX = "encrypted_credentials_"
    }
}
