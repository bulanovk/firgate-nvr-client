package com.frigate.android.feature.live

import android.media.MediaCodecList
import com.google.gson.JsonParser

sealed interface MseControlMessage {
    data class Negotiated(val mimeType: String) : MseControlMessage
    data class ServerError(val message: String) : MseControlMessage
    data object UnexpectedMessage : MseControlMessage
}

object Go2RtcMseProtocol {
    private const val AVC_CANDIDATE = "avc1.640029"
    private const val HEVC_CANDIDATE = "hvc1.1.6.L153.B0"
    private val CODECS_PATTERN = Regex("codecs\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    fun videoCodecOffer(supportedMimeTypes: Set<String>): String = buildList {
        if ("video/avc" in supportedMimeTypes) add(AVC_CANDIDATE)
        if ("video/hevc" in supportedMimeTypes) add(HEVC_CANDIDATE)
    }.joinToString(",")

    fun codecOffer(supportedMimeTypes: Set<String>, includeAudio: Boolean = false): String {
        val video = videoCodecOffer(supportedMimeTypes)
        if (video.isBlank() || !includeAudio) return video
        return buildList {
            add(video)
            if ("audio/mp4a-latm" in supportedMimeTypes) add("mp4a.40.2")
            if ("audio/flac" in supportedMimeTypes) add("flac")
            if ("audio/opus" in supportedMimeTypes) add("opus")
        }.joinToString(",")
    }

    fun deviceVideoCodecOffer(): String = deviceCodecOffer(includeAudio = false)

    fun deviceCodecOffer(includeAudio: Boolean): String {
        val supported = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.asList() }
            .map { it.lowercase() }
            .toSet()
        return codecOffer(supported, includeAudio)
    }

    fun isMimeTypeSupported(mimeType: String, codecOffer: String): Boolean {
        if (!mimeType.substringBefore(';').trim().equals("video/mp4", ignoreCase = true)) return false
        val codecs = CODECS_PATTERN.find(mimeType)?.groupValues?.getOrNull(1)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val offeredCodecs = codecOffer.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        return codecs.isNotEmpty() && codecs.all { it in offeredCodecs }
    }

    fun parseControlMessage(text: String): MseControlMessage {
        val message = runCatching {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: return MseControlMessage.UnexpectedMessage
        val type = message.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return MseControlMessage.UnexpectedMessage
        val value = message.get("value")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return MseControlMessage.UnexpectedMessage

        return when (type) {
            "mse" -> if (value.startsWith("video/")) {
                MseControlMessage.Negotiated(value)
            } else {
                MseControlMessage.UnexpectedMessage
            }
            "error" -> MseControlMessage.ServerError(value)
            else -> MseControlMessage.UnexpectedMessage
        }
    }
}
