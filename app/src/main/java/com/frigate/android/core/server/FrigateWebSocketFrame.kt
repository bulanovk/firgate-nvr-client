package com.frigate.android.core.server

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Envelope shared by every Frigate websocket frame: `{"topic": "...", "payload": "..."}`. */
object FrigateWebSocketFrame {
    fun topic(raw: String): String? = runCatching {
        JsonParser.parseString(raw).asJsonObject.get("topic")?.takeIf { !it.isJsonNull }?.asString
    }.getOrNull()

    fun payload(raw: String): JsonElement? = runCatching {
        JsonParser.parseString(raw).asJsonObject.get("payload")?.takeUnless { it.isJsonNull }
    }.getOrNull()
}
