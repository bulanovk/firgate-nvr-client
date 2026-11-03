package com.frigate.android.feature.live

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveCameraTileTest {
    @get:Rule val compose = createComposeRule()

    /**
     * A tile that fits the window of the test.
     *
     * The tile is 16:9 and fills the width of the screen: in the landscape of the device it grows
     * taller than the window, and the name chip and the error bar fall out of it. Here the tile is
     * sized to fit both ways, the way a tile of the grid lies in a scrollable column.
     */
    @Composable
    private fun FittingTile(tile: @Composable (Modifier) -> Unit) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            tile(Modifier.width(minOf(maxWidth, maxHeight * 16f / 9f)))
        }
    }

    @Test
    fun stillRemainsUntilCurrentOutputFrameAndReturnsOnFailure() {
        val state = mutableStateOf(LiveTileVisualState(name = "Yard"))
        compose.setContent {
            FittingTile { modifier ->
                LiveCameraTile(state.value, ImageBitmap(16, 9), modifier = modifier, onRetry = {})
            }
        }
        compose.onNodeWithContentDescription("Yard snapshot").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(videoReady = true) }
        compose.onNodeWithContentDescription("Yard snapshot").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(videoReady = false, mediaFailed = true) }
        compose.onNodeWithContentDescription("Yard snapshot").assertIsDisplayed()
        compose.onNodeWithText("Live video unavailable").assertIsDisplayed()
    }

    @Test
    fun motionAndObjectsAreNotPresentedAsRecording() {
        compose.setContent {
            FittingTile { modifier ->
                LiveCameraTile(
                    LiveTileVisualState(name = "Yard", motion = true, tracking = true, objects = setOf("person", "car")),
                    ImageBitmap(16, 9), modifier = modifier, onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Yard").assertIsDisplayed()
        compose.onNodeWithContentDescription("Motion detected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Detected person").assertIsDisplayed()
        compose.onNodeWithContentDescription("Detected car").assertIsDisplayed()
        compose.onNodeWithText("REC").assertDoesNotExist()
        val capture = compose.onRoot().captureToImage().asAndroidBitmap()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, "live-tile-ui-test.png").outputStream().use {
            capture.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun textureOutputStaysUnderStillAndOverlays() {
        val state = mutableStateOf(LiveTileVisualState(name = "Yard", motion = true))
        val updated = AtomicBoolean(false)
        val still = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.GREEN) }
        compose.setContent {
            LiveCameraTile(state.value, still.asImageBitmap(), onRetry = {}) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    TextureView(context).apply {
                        isOpaque = true
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                Surface(texture).let { surface ->
                                    val canvas = surface.lockCanvas(null)
                                    canvas.drawColor(AndroidColor.RED)
                                    surface.unlockCanvasAndPost(canvas)
                                    surface.release()
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { updated.set(true) }
                        }
                    }
                })
            }
        }
        compose.waitUntil(5000) { updated.get() }
        val starting = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(AndroidColor.GREEN, starting.getPixel(starting.width / 2, starting.height / 2))
        compose.runOnIdle { state.value = state.value.copy(videoReady = true) }
        val playing = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(AndroidColor.RED, playing.getPixel(playing.width / 2, playing.height / 2))
        assertTrue("Name chip must paint above red texture", AndroidColor.green(playing.getPixel(playing.width - 110, 45)) > 20)
        assertTrue("Texture must not paint square corners", playing.getPixel(0, 0) != AndroidColor.RED)
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, "live-texture-ui-test.png").outputStream().use {
            playing.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun disabledAndOfflineHaveDistinctMessagesAndRetryCallsAction() {
        val state = mutableStateOf(LiveTileVisualState(name = "Yard", enabled = false))
        var retries = 0
        compose.setContent { LiveCameraTile(state.value, null, onRetry = { retries++ }) }
        compose.onNodeWithText("Camera disabled").assertIsDisplayed()
        compose.onNodeWithContentDescription("Motion detected").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(enabled = true, offline = true) }
        compose.onNodeWithText("Camera offline").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(offline = false, mediaFailed = true) }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
