package com.frigate.android.core.server

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionCookieCipher(
    private val key: SecretKey,
    private val random: SecureRandom = SecureRandom(),
) {
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = try {
            val candidate = ByteArray(IV_SIZE_BYTES).also { random.nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, candidate))
            candidate
        } catch (error: java.security.InvalidAlgorithmParameterException) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv
        }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(value: String): String {
        val payload = Base64.getDecoder().decode(value)
        require(payload.size > IV_SIZE_BYTES) { "Invalid encrypted session cookie." }
        val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
        val encrypted = payload.copyOfRange(IV_SIZE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
        return cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
    }
}