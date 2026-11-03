package com.frigate.android.core.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidEncryptedCredentialsStore(context: Context) : CredentialsStore {
    private val preferences = AndroidServerPreferences(context, "frigate_secure_credentials")
    private val store = EncryptedCredentialsStore(preferences, SessionCookieCipher(loadOrCreateKey()))

    override fun save(profile: ServerProfile, credentials: FrigateCredentials) = store.save(profile, credentials)

    override fun load(profile: ServerProfile): FrigateCredentials? = store.load(profile)

    override fun clear(profile: ServerProfile) = store.clear(profile)

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "frigate_credentials_key"
    }
}
