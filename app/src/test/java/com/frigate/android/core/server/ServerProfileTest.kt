package com.frigate.android.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ServerProfileTest {
    @Test
    fun normalizesTrailingSlashAndBlankLabel() {
        val profile = ServerProfile.fromInput("", " https://frigate.example.com/// ").getOrThrow()

        assertEquals("Frigate server", profile.label)
        assertEquals("https://frigate.example.com", profile.baseUrl)
    }

    @Test
    fun rejectsCredentialsInUrl() {
        val error = ServerProfile.fromInput("Home", "https://user:password@frigate.example.com")
            .exceptionOrNull()

        assertEquals("Credentials must not be included in the URL.", error?.message)
    }

    @Test
    fun rejectsQueryAndFragment() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.fromInput("Home", "https://frigate.example.com/path?token=secret").getOrThrow()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.fromInput("Home", "https://frigate.example.com/path#section").getOrThrow()
        }
    }

    @Test
    fun rejectsEmptyUnsupportedSchemeAndMissingHost() {
        listOf(
            "",
            "ftp://frigate.example.com",
            "http:///frigate",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerProfile.fromInput("Home", input).getOrThrow()
            }
        }
    }
}
