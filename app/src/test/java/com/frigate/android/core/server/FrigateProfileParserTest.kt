package com.frigate.android.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrigateProfileParserTest {
    @Test
    fun parsesAuthenticatedProfileAndAllowedCameras() {
        val profile = FrigateProfileParser.parse(
            """{"username":"operator","role":"viewer","allowed_cameras":["front_door"]}""",
        )

        assertEquals("operator", profile.username)
        assertEquals("viewer", profile.role)
        assertEquals(setOf("front_door"), profile.allowedCameras)
    }

    @Test
    fun treatsMissingAllowedCamerasAsAllCameras() {
        val profile = FrigateProfileParser.parse("""{"username":"operator","role":"admin"}""")

        assertTrue(profile.allowedCameras == null)
    }

    @Test
    fun preservesEmptyAllowedCameraListAndTreatsNullAsUnrestricted() {
        val restrictedToNone = FrigateProfileParser.parse(
            """{"username":"operator","role":"viewer","allowed_cameras":[],"extra":true}""",
        )
        val unrestricted = FrigateProfileParser.parse(
            """{"username":"operator","role":"viewer","allowed_cameras":null}""",
        )

        assertEquals(emptySet<String>(), restrictedToNone.allowedCameras)
        assertNull(unrestricted.allowedCameras)
    }
}
