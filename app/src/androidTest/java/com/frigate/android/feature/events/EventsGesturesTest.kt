package com.frigate.android.feature.events

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EventsGesturesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun horizontalSwipesMarkOrPreviewButNeverOpenHistory() {
        val actions = mutableListOf<EventsAction>()
        var opens = 0
        compose.setContent { ReviewGridCard(reviewFixture(), false, false, { opens++ }, actions::add, Modifier.width(280.dp)) }
        val card = compose.onNodeWithContentDescription("Open recording: yard, 00:02:00")
        card.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(listOf(EventsAction.MarkViewed(listOf("r"), true)), actions); actions.clear() }
        card.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(listOf(EventsAction.Preview("r")), actions); assertEquals(0, opens) }
    }

    @Test
    fun reviewedEventIsNotMarkedAgainByALeftSwipe() {
        val actions = mutableListOf<EventsAction>()
        compose.setContent { ReviewGridCard(reviewFixture().copy(hasBeenReviewed = true), false, false, {}, actions::add, Modifier.width(280.dp)) }
        compose.onNodeWithContentDescription("Open recording: yard, 00:02:00").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun accessibilityActionsDoTheSameAsTheSwipes() {
        val actions = mutableListOf<EventsAction>()
        compose.setContent { ReviewGridCard(reviewFixture(), false, false, {}, actions::add, Modifier.width(280.dp)) }
        val card = compose.onNodeWithContentDescription("Open recording: yard, 00:02:00")
        card.performCustomAccessibilityActionWithLabel("Mark as reviewed")
        card.performCustomAccessibilityActionWithLabel("Preview")
        card.performCustomAccessibilityActionWithLabel("Select event")
        compose.runOnIdle {
            assertEquals(listOf(EventsAction.MarkViewed(listOf("r"), true), EventsAction.Preview("r"),
                EventsAction.ToggleSelection("r")), actions)
        }
    }

    @Test
    fun ongoingLeftAndVerticalDragDoNothingButLongPressSelects() {
        val actions = mutableListOf<EventsAction>()
        var opens = 0
        compose.setContent { ReviewGridCard(reviewFixture().copy(endTime = null), false, false, { opens++ }, actions::add, Modifier.width(280.dp)) }
        val card = compose.onNodeWithContentDescription("Open recording: yard, 00:02:00")
        card.performTouchInput { swipeLeft() }
        card.performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(actions.isEmpty()); assertEquals(0, opens) }
        card.performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf(EventsAction.ToggleSelection("r")), actions); assertEquals(0, opens) }
    }
}
