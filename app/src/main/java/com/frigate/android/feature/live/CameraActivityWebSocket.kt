package com.frigate.android.feature.live

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.LiveStreamEndpoint
import com.frigate.android.core.server.ServerProfile
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class CameraActivityWebSocket(
    private val server: ServerProfile,
    private val cookie: String,
    private val onActivityChanged: (Map<String, CameraActivity>) -> Unit,
    private val onReviewsMessage: (String) -> Unit = {},
    private val onReconnect: () -> Unit = {},
) {
    private val client = createLiveWebSocketClient(pingIntervalMillis = 30_000)
    private val handler = Handler(Looper.getMainLooper())
    private val gate = LiveSessionGate()
    private var scope: CoroutineScope? = null
    private var activityState = CameraActivityState()
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var started = false
    private var opened = false

    fun setControl(camera: String, control: CameraControl, enabled: Boolean): Boolean =
        started && opened && webSocket?.send(control.command(camera, enabled)) == true

    fun start() {
        if (started) return
        started = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        connect()
    }

    fun stop() {
        started = false
        opened = false
        gate.invalidate()
        scope?.cancel()
        scope = null
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Stopped")
        webSocket = null
        client.dispatcher.cancelAll()
        // What the cameras saw before the stop is not their state now: the screen shows nothing
        // until the next snapshot of the server.
        activityState = CameraActivityState()
        onActivityChanged(emptyMap())
    }

    private fun connect() {
        if (!started) return
        val generation = gate.begin()
        var receivedStats = false
        val request = Request.Builder()
            .url(LiveStreamEndpoint.frigateWebSocketUrl(server))
            .apply { cookie.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (!gate.isCurrent(generation)) {
                        webSocket.close(1000, "Obsolete")
                        return@post
                    }
                    reconnectAttempt = 0
                    opened = true
                    val sent = webSocket.send("{\"topic\":\"onConnect\",\"payload\":\"\",\"retain\":false}")
                    Log.d("CameraActivity", "open generation=$generation snapshotRequested=$sent")
                    onReconnect()
                    scope?.launch {
                        val stats = withContext(Dispatchers.IO) {
                            runCatching { FrigateClient(server, cookie).stats() }.getOrNull()
                        }
                        if (stats != null && gate.isCurrent(generation) && !receivedStats) {
                            handleMessage(JsonObject().apply {
                                addProperty("topic", "stats")
                                addProperty("payload", stats)
                            }.toString())
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post {
                    if (!gate.isCurrent(generation)) return@post
                    val topic = runCatching {
                        com.google.gson.JsonParser.parseString(text).asJsonObject.get("topic")?.asString
                    }.getOrNull()
                    if (topic == "camera_activity") Log.d("CameraActivity", "snapshot received generation=$generation")
                    if (topic == "stats") receivedStats = true
                    handleMessage(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { if (gate.isCurrent(generation)) scheduleReconnect(webSocket) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { if (gate.isCurrent(generation)) scheduleReconnect(webSocket) }
            }
        })
    }

    private fun handleMessage(raw: String) {
        val next = CameraActivityDispatch.dispatch(
            raw = raw,
            state = activityState,
            onReviewsMessage = onReviewsMessage,
            onActivityChanged = { cameras -> onActivityChanged(cameras) },
        )
        if (next == activityState) return
        val cameras = next.cameras
        Log.d("CameraActivity", "state cameras=${cameras.size} motion=${cameras.values.count { it.activeMotion }} tracking=${cameras.values.count { it.activeTracking }} objects=${cameras.values.sumOf { it.displayedObjects.size }}")
        activityState = next
    }

    private fun scheduleReconnect(socket: WebSocket) {
        if (!started || webSocket !== socket) return
        gate.invalidate()
        opened = false
        webSocket = null
        val delay = minOf(1_000L shl reconnectAttempt.coerceAtMost(5), 30_000L)
        reconnectAttempt++
        handler.postDelayed({ connect() }, delay)
    }
}
