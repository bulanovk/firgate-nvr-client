package com.frigate.android.feature.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.frigate.android.feature.player.ScreenOnWhilePlaying

@UnstableApi
@Composable
internal fun NativeMseVideo(
    player: NativeMsePlayer,
    modifier: Modifier = Modifier,
    onReady: (Boolean) -> Unit,
    onSurface: (MseTextureView?) -> Unit = {},
    fullResolution: Boolean = false,
) {
    val currentOnReady = rememberUpdatedState(onReady)
    val currentOnSurface = rememberUpdatedState(onSurface)
    key(player) {
        AndroidView(
            factory = { context ->
                MseTextureView(
                    context = context,
                    nativePlayer = player,
                    onReady = { currentOnReady.value(it) },
                    fullResolution = fullResolution,
                ).also { currentOnSurface.value(it) }
            },
            modifier = modifier,
            onReset = null,
            onRelease = { view ->
                view.detachOutput()
                currentOnSurface.value(null)
            },
            update = {},
        )
    }
}

@UnstableApi
internal class MseTextureView(
    context: Context,
    private val nativePlayer: NativeMsePlayer,
    private val onReady: (Boolean) -> Unit,
    private val fullResolution: Boolean = false,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var output: Surface? = null
    private var texture: SurfaceTexture? = null
    private var rendered = false
    private var textureUpdated = false
    private var bufferWidth = 0
    private var bufferHeight = 0

    init {
        surfaceTextureListener = this
        isOpaque = true
        ScreenOnWhilePlaying(this, nativePlayer.player)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        detachOutput()
        this.texture = texture
        val surface = Surface(texture)
        output = surface
        nativePlayer.attachOutput(surface) { ready ->
            if (output !== surface) return@attachOutput
            rendered = ready
            if (!ready) textureUpdated = false
            onReady(rendered && textureUpdated)
        }
        if (nativePlayer.player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
            nativePlayer.prepareAndPlay()
        }
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        textureUpdated = true
        updateBufferSize()
        if (rendered) onReady(true)
    }

    /**
     * The buffer of the surface keeps the frame in the resolution of the source.
     *
     * Without it the decoded 4K frame is shrunk to the size of the view before the user sees it,
     * and the zoom has nothing to enlarge: it would stretch an already shrunk picture. The grid
     * keeps the buffer at the size of the view, six tiles of 4K buffers would take too much memory.
     */
    private fun updateBufferSize() {
        if (!fullResolution) return
        val texture = texture ?: return
        val size = nativePlayer.player.videoSize
        if (size.width <= 0 || size.height <= 0) return
        if (size.width == bufferWidth && size.height == bufferHeight) return
        bufferWidth = size.width
        bufferHeight = size.height
        texture.setDefaultBufferSize(size.width, size.height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        detachOutput()
        return true
    }

    fun detachOutput() {
        output?.let { surface ->
            nativePlayer.detachOutput(surface)
            surface.release()
        }
        output = null
        texture = null
        rendered = false
        textureUpdated = false
        onReady(false)
    }

    fun playbackStats(): LivePlaybackStats? = if (output != null) nativePlayer.playbackStats() else null

    fun currentBitmap(): Bitmap? = if (isAvailable) getBitmap() else null
}
