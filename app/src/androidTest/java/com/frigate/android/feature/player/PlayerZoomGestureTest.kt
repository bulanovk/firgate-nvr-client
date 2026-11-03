package com.frigate.android.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The gestures of the picture.
 *
 * The zoom is a property of the player, so the same gestures have to work in the live panel and in
 * the archive. The JVM tests cover the arithmetic of the zoom; these tests cover the part that
 * only a device can show: the fingers really move the picture.
 */
class PlayerZoomGestureTest {
    @get:Rule val compose = createComposeRule()

    private fun picture(): PlayerZoomState {
        val state = PlayerZoomState()
        compose.setContent {
            Box(
                Modifier.fillMaxSize().background(Color.Gray)
                    .playerZoomGestures(state)
                    .playerZoomTransform(state),
            )
        }
        return state
    }

    private fun spreadFingers() {
        compose.onRoot().performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f),
                end0 = center - Offset(140f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(140f, 0f),
            )
        }
    }

    @Test
    fun aPinchEnlargesThePicture() {
        val state = picture()
        spreadFingers()
        compose.runOnIdle {
            assertTrue("A pinch must enlarge the picture, the scale was ${state.zoom.scale}", state.zoom.scale > 1f)
        }
    }

    @Test
    fun aDoubleTapReturnsThePictureToUnitScale() {
        val state = picture()
        spreadFingers()
        compose.runOnIdle { assertTrue(state.zoom.scale > 1f) }
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.runOnIdle {
            assertEquals("A double tap must return the picture to unit scale", 1f, state.zoom.scale, 0.001f)
        }
    }

    @Test
    fun aDragMovesTheEnlargedPicture() {
        val state = picture()
        spreadFingers()
        compose.onRoot().performTouchInput { swipe(center, center + Offset(200f, 0f), 300L) }
        compose.runOnIdle {
            assertTrue("The picture must follow the finger, the shift was ${state.zoom.offsetX}", state.zoom.offsetX > 0f)
        }
    }

    @Test
    fun aDragAtUnitScaleMovesNothing() {
        val state = picture()
        compose.onRoot().performTouchInput { swipe(center, center + Offset(200f, 0f), 300L) }
        compose.runOnIdle {
            assertEquals("A picture at unit scale must stand still", 0f, state.zoom.offsetX, 0.001f)
        }
    }
}
