package com.frigate.android.core.server

import java.net.URLDecoder

/**
 * Media locations derived from Frigate review segments.
 *
 * `thumb_path` is server supplied and therefore untrusted: only the review clip folder is accepted
 * and the result is always resolved against the configured [ServerProfile].
 */
object ReviewMediaEndpoint {
    private const val REVIEW_CLIP_PREFIX = "/media/frigate/clips/review/"

    fun thumbnailUrl(profile: ServerProfile, thumbnailPath: String?): String? {
        val path = thumbnailPath?.trim().orEmpty()
        if (!path.startsWith(REVIEW_CLIP_PREFIX)) return null

        val relative = path.removePrefix(REVIEW_CLIP_PREFIX)
        if (relative.isBlank()) return null
        if (relative.any { it.isWhitespace() || it == '?' || it == '#' || it == '\\' }) return null
        if (relative.split('/').any { it == "." || it == ".." }) return null

        val decoded = runCatching { URLDecoder.decode(relative, "UTF-8") }.getOrNull() ?: return null
        if (decoded.contains("..")) return null

        return "${profile.baseUrl.trimEnd('/')}/clips/review/$relative"
    }

    /** Archive playlist served by the nginx vod module, outside the `/api/` prefix. */
    fun vodPlaylistUrl(profile: ServerProfile, camera: String, after: Double, before: Double): String {
        val encodedCamera = UrlComponents.encodeComponent(camera)
        val from = UrlComponents.formatTimestamp(after)
        val to = UrlComponents.formatTimestamp(before)
        return "${profile.baseUrl.trimEnd('/')}/vod/$encodedCamera/start/$from/end/$to/master.m3u8"
    }
}
