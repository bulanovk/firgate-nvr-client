package com.frigate.android.core.server

import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SessionCookieCipherTest {
    @Test
    fun encryptsAndDecryptsCookieWithoutStoringPlaintext() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val cipher = SessionCookieCipher(key)

        val encrypted = cipher.encrypt("frigate_token=abc")

        assertNotEquals("frigate_token=abc", encrypted)
        assertEquals("frigate_token=abc", cipher.decrypt(encrypted))
    }

    @Test
    fun encryptionUsesAUniqueIvForEachCookie() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val cipher = SessionCookieCipher(key)

        val first = cipher.encrypt("frigate_token=abc")
        val second = cipher.encrypt("frigate_token=abc")

        assertNotEquals(first, second)
        assertNotEquals(Base64.getDecoder().decode(first).take(12), Base64.getDecoder().decode(second).take(12))
    }
}
