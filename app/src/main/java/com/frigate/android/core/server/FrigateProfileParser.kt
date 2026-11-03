package com.frigate.android.core.server

import com.google.gson.JsonParser

object FrigateProfileParser {
    fun parse(body: String): FrigateProfile {
        val profile = JsonParser.parseString(body).asJsonObject
        val allowed = profile.get("allowed_cameras")
            ?.takeIf { !it.isJsonNull }
            ?.asJsonArray
            ?.map { it.asString }
            ?.toSet()
        return FrigateProfile(
            username = profile.get("username")?.asString.orEmpty(),
            role = profile.get("role")?.asString.orEmpty(),
            allowedCameras = allowed,
        )
    }
}
