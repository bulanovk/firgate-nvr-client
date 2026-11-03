package com.frigate.android.core.server

import java.math.BigDecimal

/** URL pieces shared by the REST client and the media endpoints. */
object UrlComponents {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val HEX = "0123456789ABCDEF".toCharArray()

    /** Percent-encodes everything outside the RFC 3986 unreserved set, so it is safe in a path segment and in a query value. */
    fun encodeComponent(value: String): String {
        val builder = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val character = byte.toInt().toChar()
            if (character in UNRESERVED) {
                builder.append(character)
            } else {
                val unsigned = byte.toInt() and 0xFF
                builder.append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F])
            }
        }
        return builder.toString()
    }

    /** Formats a Unix timestamp without locale-dependent separators or exponents. */
    fun formatTimestamp(value: Double): String {
        val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
        return if (decimal.scale() <= 0) decimal.toBigInteger().toString() else decimal.toPlainString()
    }
}
