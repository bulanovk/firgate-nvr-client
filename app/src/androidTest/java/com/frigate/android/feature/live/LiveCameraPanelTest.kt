package com.frigate.android.feature.live

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.media3.common.util.UnstableApi
import com.frigate.android.core.server.FrigateCamera
import com.frigate.android.core.server.ServerProfile
import com.frigate.android.feature.player.PlayerZoomState
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The panel of a camera.
 *
 * The panel is shown without a player and without a server: the test covers the header, the
 * fullscreen and the zoom of the picture, not the stream.
 */
@UnstableApi
class LiveCameraPanelTest {
    @get:Rule val compose = createComposeRule()

    private fun panel(
        fullscreen: Boolean = false,
        expanded: Boolean = false,
        zoom: PlayerZoomState = PlayerZoomState(),
        camera: FrigateCamera = FrigateCamera("yard", "Yard", true),
        role: String = "viewer",
        onSetControl: (CameraControl, Boolean) -> Boolean = { _, _ -> false },
        onStreamChange: (String) -> Unit = {},
        audioAvailable: Boolean = false,
        onMutedChange: (Boolean) -> Unit = {},
        onPictureInPicture: ((Float, android.graphics.Rect) -> Unit)? = null,
    ): PlayerZoomState {
        val still = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
        compose.setContent {
            LiveCameraPanel(
                camera = camera,
                role = role,
                onSetControl = onSetControl,
                onStreamChange = onStreamChange,
                audioAvailable = audioAvailable,
                onMutedChange = onMutedChange,
                onPictureInPicture = onPictureInPicture,
                profile = ServerProfile("Test", "http://127.0.0.1:1/"),
                cookie = "",
                activity = CameraActivity(),
                visible = false,
                expanded = expanded,
                keepPlayer = false,
                playerKey = "yard",
                codecOffer = "",
                httpClient = OkHttpClient(),
                pool = LiveCameraPlayerPool<NativeMsePlayer> { it.release() },
                statuses = mutableStateMapOf(),
                aspects = mutableStateMapOf(),
                videoWidths = mutableStateMapOf(),
                snapshots = mutableStateMapOf("yard" to SnapshotEntry(still, false, 0L, 0L)),
                fullscreen = fullscreen,
                onFullscreenChange = {},
                onRetry = {},
                onBack = {},
                onOpenHistory = {},
                zoom = zoom,
            )
        }
        return zoom
    }

    @Test
    fun adminSettingsPublishTheRequestedValueInsteadOfOnlyPaintingASwitch() {
        var command: Pair<CameraControl, Boolean>? = null
        panel(role = "admin", onSetControl = { control, value -> command = control to value; true })
        compose.onNodeWithContentDescription("Camera settings").performClick()
        compose.onNodeWithContentDescription("Object detection").performClick()
        compose.runOnIdle { assertEquals(CameraControl.DETECTION to false, command) }
    }

    @Test
    fun viewerCanSelectARealConfiguredStreamButHasNoAdminToggles() {
        var selected = ""
        panel(camera = FrigateCamera("yard", "Yard", true,
            streams = linkedMapOf("Main" to "yard", "Low" to "yard_sub")),
            onStreamChange = { selected = it })
        compose.onNodeWithContentDescription("Camera settings").performClick()
        compose.onNodeWithContentDescription("Object detection").assertDoesNotExist()
        compose.onNodeWithContentDescription("Camera stream").performClick()
        compose.onNodeWithText("Low").performClick()
        compose.runOnIdle { assertEquals("yard_sub", selected) }
    }

    @Test
    fun theWideHeaderCarriesTheManualRecordingOfTheMockup() {
        panel(expanded = true, role = "admin")
        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed()
    }

    @Test
    fun aViewerHasNoManualRecordingInTheHeader() {
        panel(expanded = true, role = "viewer")
        compose.onNodeWithContentDescription("Start recording").assertDoesNotExist()
    }

    @Test
    fun theNarrowHeaderHasNoManualRecording() {
        panel(expanded = false, role = "admin")
        compose.onNodeWithContentDescription("Start recording").assertDoesNotExist()
    }

    @Test
    fun theModeListOffersTheEnginesOfThePwaAndTheUnavailableOneStaysDimmed() {
        panel()
        compose.onNodeWithContentDescription("Camera settings").performClick()
        compose.onNodeWithContentDescription("Stream mode").performScrollTo().performClick()
        compose.onAllNodesWithText("MSE").fetchSemanticsNodes().let { assertTrue("the engine that plays is listed", it.isNotEmpty()) }
        compose.onAllNodesWithText("WebRTC")[0].assertIsDisplayed()
        // The PWA keeps an engine it cannot use in the list, dimmed, with the reason.
        compose.onNodeWithText("The app has no WebRTC playback yet").assertIsDisplayed()
        compose.onAllNodesWithText("WebRTC")[0].assertIsNotEnabled()
    }

    @Test
    fun statisticsCanBeEnabledFromTheCameraSettings() {
        panel()
        compose.onNodeWithContentDescription("Camera settings").performClick()
        compose.onNodeWithContentDescription("Statistics").performScrollTo().performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Playback statistics").assertIsDisplayed()
        compose.onNodeWithText("Waiting for playback").assertIsDisplayed()
    }

    @Test
    fun debugViewCanBeEnabledWithoutZoomingTheOverlay() {
        panel()
        compose.onNodeWithContentDescription("Camera settings").performClick()
        compose.onNodeWithContentDescription("Debug view").performScrollTo().performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Playback debug view").assertIsDisplayed()
        compose.onNodeWithText("MSE · waiting for first frame").assertIsDisplayed()
    }

    @Test
    fun pictureInPictureUsesThePictureAspectAndItsBounds() {
        var aspect = 0f
        var bounds = android.graphics.Rect()
        panel(onPictureInPicture = { ratio, rect -> aspect = ratio; bounds = rect })
        compose.onNodeWithContentDescription("Picture in picture").assertWidthIsEqualTo(36.dp).performClick()
        compose.runOnIdle {
            assertEquals(16f / 9, aspect)
            assertTrue(bounds.width() > 0 && bounds.height() > 0)
        }
    }

    @Test
    fun supportedAudioCanBeUnmutedFromTheApprovedHeader() {
        var muted = true
        panel(audioAvailable = true, onMutedChange = { muted = it })
        compose.onNodeWithContentDescription("Unmute audio")
            .assertIsDisplayed().assertWidthIsEqualTo(36.dp).performClick()
        compose.runOnIdle { assertEquals(false, muted) }
    }

    @Test
    fun supportedAudioStaysAvailableInFullscreen() {
        panel(fullscreen = true, audioAvailable = true)
        compose.onNodeWithContentDescription("Unmute audio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Camera settings").assertDoesNotExist()
    }

    @Test
    fun theHeaderOffersTheActionsOfTheCamera() {
        panel()
        compose.onNodeWithContentDescription("Back to cameras").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open history").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fullscreen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Camera settings").assertIsDisplayed()
    }

    @Test
    fun theHeaderMatchesTheApprovedButtonGeometryAndFill() {
        panel()
        val button = compose.onNodeWithContentDescription("Fullscreen")
        button.assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        val pixels = button.captureToImage().toPixelMap()
        assertEquals(androidx.compose.ui.graphics.Color(0xFF26282D), pixels[5, pixels.height / 2])
        assertEquals(androidx.compose.ui.graphics.Color.Black, pixels[0, 0])
        compose.onNodeWithContentDescription("Back to cameras")
            .assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        val back = compose.onNodeWithContentDescription("Back to cameras").captureToImage().toPixelMap()
        assertEquals(androidx.compose.ui.graphics.Color.Black, back[5, back.height / 2])
    }

    @Test
    fun theWideHeaderOffersTheSnapshotBesideTheSettings() {
        panel(expanded = true)
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithText("History").assertIsDisplayed()
        val snapshot = compose.onNodeWithContentDescription("Take a snapshot")
        snapshot.assertIsDisplayed().assertWidthIsEqualTo(36.dp)
        val snapshotBounds = snapshot.fetchSemanticsNode().boundsInRoot
        val settingsBounds = compose.onNodeWithContentDescription("Camera settings")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(snapshotBounds.right < settingsBounds.left)
        assertEquals(snapshotBounds.top, settingsBounds.top)
    }

    @Test
    fun theHeaderHasNoPlaceholdersForUnsupportedActions() {
        panel()
        listOf("Picture in picture", "Microphone", "Audio", "Start recording", "Take a snapshot")
            .forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
    }

    @Test
    fun theFullscreenDoesNotKeepTheSettingsOnWideScreensEither() {
        panel(fullscreen = true, expanded = true)
        compose.onNodeWithContentDescription("Camera settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Leave fullscreen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Take a snapshot").assertIsDisplayed()
    }

    @Test
    fun theSettingsOfTheCameraOfferTheSnapshot() {
        panel()
        compose.onNodeWithContentDescription("Camera settings").performClick()
        compose.onNodeWithText("Take a snapshot").assertIsDisplayed()
    }

    @Test
    fun theFullscreenKeepsOnlyTheFloatingHeader() {
        panel(fullscreen = true)
        compose.onNodeWithContentDescription("Leave fullscreen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open history").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to cameras").assertDoesNotExist()
    }

    @Test
    fun aPinchEnlargesThePictureOfThePanel() {
        val zoom = panel()
        compose.onRoot().performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f),
                end0 = center - Offset(140f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(140f, 0f),
            )
        }
        compose.runOnIdle {
            assertTrue("A pinch must enlarge the picture, the scale was ${zoom.zoom.scale}", zoom.zoom.scale > 1f)
        }
    }
}
