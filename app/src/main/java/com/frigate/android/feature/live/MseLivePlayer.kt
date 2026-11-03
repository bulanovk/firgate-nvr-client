package com.frigate.android.feature.live

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.frigate.android.core.server.LiveStreamEndpoint
import com.frigate.android.core.server.ServerProfile
import org.json.JSONObject

class MseLivePlayer(
    private val context: Context,
    private val server: ServerProfile,
    private val cameraId: String,
    private val streamName: String,
    private val cookie: String,
    private val onStatus: (String) -> Unit,
) {
    private var webView: WebView? = null
    private var streaming = false
    private var pageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    fun createView(): WebView = WebView(context).also { view ->
        webView = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                applyDesiredState()
            }
        }
        view.addJavascriptInterface(StatusBridge(streamName, onStatus), "FrigateNative")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setCookie(server.baseUrl, cookie)
        CookieManager.getInstance().flush()
        view.loadDataWithBaseURL(
            "${server.baseUrl.trimEnd('/')}/",
            playerHtml(
                LiveStreamEndpoint.mseWebSocketUrl(server, streamName),
                "${server.baseUrl.trimEnd('/')}/api/${android.net.Uri.encode(cameraId)}/latest.jpg",
            ),
            "text/html",
            "UTF-8",
            null,
        )
    }

    fun start() {
        if (streaming || webView == null) return
        streaming = true
        applyDesiredState()
    }

    fun pause() {
        if (!streaming) return
        streaming = false
        webView?.evaluateJavascript("window.stopFrigateStream && window.stopFrigateStream()", null)
        onStatus("Paused")
    }

    fun showSnapshot() {
        streaming = false
        webView?.evaluateJavascript("window.showFrigateSnapshot && window.showFrigateSnapshot()", null)
        onStatus("Snapshot")
    }

    fun stop() {
        streaming = false
        pageLoaded = false
        webView?.apply {
            removeJavascriptInterface("FrigateNative")
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
    }

    private fun applyDesiredState() {
        if (!pageLoaded) return
        val command = if (streaming) {
            "window.startFrigateStream && window.startFrigateStream()"
        } else {
            "window.showFrigateSnapshot && window.showFrigateSnapshot()"
        }
        webView?.evaluateJavascript(command, null)
    }

    private class StatusBridge(
        private val streamName: String,
        private val onStatus: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun status(value: String) {
            Log.i(TAG, "status=$value stream=$streamName")
            onStatus(value)
        }
    }

    private fun playerHtml(webSocketUrl: String, stillUrl: String): String {
        val encodedWebSocketUrl = JSONObject.quote(webSocketUrl)
        val encodedStillUrl = JSONObject.quote(stillUrl)
        return """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;background:#101310;overflow:hidden">
            <img id="still" style="width:100%;height:100%;object-fit:contain;display:block" />
            <video id="video" autoplay playsinline muted style="width:100%;height:100%;object-fit:contain;visibility:hidden;position:absolute;left:0;top:0"></video>
            <script>
            (function() {
              var still = document.getElementById('still');
              var video = document.getElementById('video');
              var socket = null;
              var mediaSource = null;
              var sourceBuffer = null;
              var queue = [];
              var retryTimer = null;
              var startupRevealTimer = null;
              var stopped = true;
              var snapshotVisible = true;
              var wsUrl = $encodedWebSocketUrl;
              var stillUrl = $encodedStillUrl;
              still.src = stillUrl;

              function status(value) {
                if (window.FrigateNative) window.FrigateNative.status(value);
              }

              function supportedCodecs() {
                if (!window.MediaSource) return '';
                ${mseSupportedCodecsScript()}
                return codecs.filter(function(codec) {
                  return MediaSource.isTypeSupported('video/mp4; codecs="' + codec + '"');
                }).join(',');
              }

              function pump() {
                if (!sourceBuffer || sourceBuffer.updating || queue.length === 0) return;
                try {
                  sourceBuffer.appendBuffer(queue.shift());
                } catch (error) {
                  status('MSE append error');
                  queue = [];
                }
              }

              function keepSnapshotVisible(refresh) {
                if (refresh) {
                  still.src = stillUrl + (stillUrl.indexOf('?') >= 0 ? '&' : '?') + 't=' + Date.now();
                }
                still.style.display = 'block';
                video.style.visibility = 'hidden';
                snapshotVisible = true;
              }

              function openMse(mime) {
                if (!window.MediaSource || !MediaSource.isTypeSupported(mime)) {
                  status('MSE codec unavailable');
                  return;
                }
                keepSnapshotVisible(false);
                mediaSource = new MediaSource();
                video.src = URL.createObjectURL(mediaSource);
                mediaSource.addEventListener('sourceopen', function() {
                  try {
                    sourceBuffer = mediaSource.addSourceBuffer(mime);
                    ${mseBufferMaintenanceScript()}
                    pump();
                    video.play().catch(function() {});
                    status('Buffering');
                  } catch (error) {
                    status('MSE source error');
                  }
                }, {once:true});
              }

              function connect() {
                if (stopped) return;
                var codecs = supportedCodecs();
                if (!codecs) { status('MSE unsupported'); return; }
                status('Connecting');
                socket = new WebSocket(wsUrl);
                socket.binaryType = 'arraybuffer';
                socket.onopen = function() {
                  status('Signaling connected');
                  socket.send(JSON.stringify({type:'mse', value:codecs}));
                };
                socket.onmessage = function(event) {
                  if (typeof event.data === 'string') {
                    try {
                      var message = JSON.parse(event.data);
                      if (message.type === 'mse') openMse(message.value);
                      if (message.type === 'error') status('Server: ' + message.value);
                    } catch (error) { status('MSE response error'); }
                    return;
                  }
                  queue.push(event.data);
                  pump();
                };
                socket.onerror = function() { status('MSE connection failed'); };
                socket.onclose = function() {
                  if (stopped) return;
                  status('Reconnecting');
                  retryTimer = setTimeout(connect, 1000);
                };
              }

              window.startFrigateStream = function() {
                stopped = false;
                if (socket === null) connect();
                if (video) video.play().catch(function() {});
              };
              window.stopFrigateStream = function() {
                stopped = true;
                if (retryTimer) clearTimeout(retryTimer);
                if (startupRevealTimer) clearTimeout(startupRevealTimer);
                retryTimer = null;
                startupRevealTimer = null;
                if (socket) {
                  socket.onclose = null;
                  socket.close();
                  socket = null;
                }
                queue = [];
                sourceBuffer = null;
                if (video) video.pause();
              };
              window.showFrigateSnapshot = function() {
                window.stopFrigateStream();
                keepSnapshotVisible(!snapshotVisible);
              };
              ${mseFirstFrameRevealScript()}
            })();
            </script>
            </body></html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "FrigateMSE"
    }
}
