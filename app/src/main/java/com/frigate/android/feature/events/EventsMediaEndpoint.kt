package com.frigate.android.feature.events

import com.frigate.android.core.server.ServerProfile
import java.net.URI

/** Server paths are profile-relative; absolute URLs must already belong to this exact base. */
internal fun eventsMediaUrl(profile: ServerProfile, path: String): String? {
    if (path.isBlank() || path.any { it.isISOControl() || it == '\\' } ||
        path.contains('%') || path.contains('?') || path.contains('#') || path.startsWith("//")) return null
    return runCatching {
        val base = URI(profile.baseUrl.trimEnd('/') + "/")
        val input = URI(path)
        if (input.rawUserInfo != null || input.path.orEmpty().split('/').any { it == ".." || it == "." }) return null
        val url = if (input.isAbsolute) input else base.resolve(path.trimStart('/'))
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return null
        if (!url.path.startsWith(base.path) || url.path.split('/').any { it == ".." || it == "." }) return null
        url.toASCIIString()
    }.getOrNull()
}
