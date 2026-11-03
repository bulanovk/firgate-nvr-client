package com.frigate.android.feature.events

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.center
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateReview
import kotlin.math.abs
import com.frigate.android.core.server.FrigateReviewCounts
import com.frigate.android.core.server.FrigateReviewSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal fun reviewFixture(id: String = "r") = FrigateReview(id, "yard", 120.0, 140.0, "alert", false, null, listOf("person"))
internal fun eventsFixture() = EventsUiState(
    EventsQuery(listOf("yard"), EventsRange(100.0, 200.0, null), "UTC"),
    items = listOf(reviewFixture()), loaded = true,
    summary = FrigateReviewSummary(FrigateReviewCounts(12, 3, 5, 2), emptyMap()),
)

class EventsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun wideHeaderKeepsAllFiltersReachableAtSixHundredDp() {
        compose.setContent {
            Box(Modifier.requiredWidth(600.dp)) {
                EventsHeader(eventsFixture(), true, EventsCatalog(), {}, {})
            }
        }
        val actions = listOf("Cameras", "Show reviewed", "Date", "Filters").map {
            compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot()
        }
        val tabs = listOf("Alerts, 9", "Detections, 3", "Motion").map {
            compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot()
        }
        (actions + tabs).forEach { bounds ->
            assertTrue("every header control must keep a touch target, got $bounds", bounds.right - bounds.left >= 40.dp)
        }
        val left = (actions + tabs).minOf { it.left }
        val right = (actions + tabs).maxOf { it.right }
        assertTrue("header content must fit into 600dp, got ${right - left}", right - left <= 600.dp)
    }

    @Test
    fun wideHeaderShowsLabelledCountsAndFilterLabels() {
        compose.setContent {
            Box(Modifier.requiredWidth(780.dp)) {
                EventsHeader(eventsFixture(), true, EventsCatalog(), {}, {})
            }
        }
        compose.onNodeWithText("Alerts ∙ 9").assertExists()
        compose.onNodeWithText("Detections ∙ 3").assertExists()
        compose.onNodeWithText("Motion").assertExists()
        compose.onNodeWithText("Cameras").assertExists()
        compose.onNodeWithText("Filters").assertExists()
    }

    @Test
    fun headerTabsAndActionsNeverOverlap() {
        var width by mutableStateOf(600.dp)
        compose.setContent {
            Box(Modifier.requiredWidth(width)) {
                EventsHeader(eventsFixture(), true, EventsCatalog(), {}, {})
            }
        }
        listOf(600.dp, 700.dp, 768.dp, 780.dp, 900.dp, 1120.dp).forEach { next ->
            width = next
            compose.waitForIdle()
            val tabs = listOf("Alerts, 9", "Detections, 3", "Motion").map {
                compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot()
            }
            val actions = listOf("Cameras", "Show reviewed", "Date", "Filters").map {
                compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot()
            }
            val tabRight = tabs.maxOf { it.right }
            val actionLeft = actions.minOf { it.left }
            assertTrue("type tabs must not run into the filters at $next", tabRight <= actionLeft)
            (tabs + actions).forEach { bounds ->
                assertTrue("every header control must keep a touch target at $next, got $bounds",
                    bounds.right - bounds.left >= 36.dp)
            }
        }
    }

    @Test
    fun wideLayoutShowsTheTimelineRailAndTheNarrowOneDoesNot() {
        compose.setContent {
            Box(Modifier.requiredWidth(780.dp)) {
                EventsScreen(eventsFixture(), {}, {}, timeline = {
                    EventsTimeline(EventsTimelineModel(eventsFixture().query.range, eventsFixture().items, emptyList()), "UTC", {}, {})
                })
            }
        }
        compose.onNodeWithContentDescription("Timeline overview").assertExists()
    }

    @Test
    fun filteredEmptyIsNotTheSameAsAnEmptyTimeRange() {
        var state by mutableStateOf(eventsFixture().copy(items = emptyList()))
        compose.setContent { EventsScreen(state, {}, {}) }
        compose.onNodeWithText("No alerts to review").assertExists()
        compose.onNodeWithTag("events-empty-icon").assertExists()
        compose.runOnIdle {
            state = eventsFixture().copy(items = listOf(reviewFixture().copy(hasBeenReviewed = true)))
        }
        compose.waitForIdle()
        compose.onNodeWithText("There are no alerts to review").assertExists()
    }

    @Test
    fun wideFilterSheetIsACenteredDialog() {
        compose.setContent {
            Box(Modifier.requiredWidth(780.dp).height(600.dp).semantics { contentDescription = "Events root" }) {
                EventsDrawers("cameras", eventsFixture(), EventsCatalog(cameras = mapOf("yard" to "Yard")), {}, {},
                    expanded = true)
            }
        }
        val root = compose.onNodeWithContentDescription("Events root").getUnclippedBoundsInRoot()
        val dialog = compose.onNodeWithContentDescription("Filter dialog").getUnclippedBoundsInRoot()
        assertTrue("the wide filter dialog must stay centered, got $dialog in $root",
            abs((dialog.left.value + dialog.right.value) / 2f - (root.left.value + root.right.value) / 2f) < 1f)
        assertTrue("the wide filter dialog must not hug the bottom, got $dialog in $root",
            dialog.bottom < root.bottom - 100.dp)
        assertTrue("the wide filter dialog must not sit at the very top, got $dialog in $root",
            dialog.top > root.top + 100.dp)
        compose.onNodeWithText("Cameras").assertIsDisplayed()
    }

    @Test
    fun narrowFilterSheetStaysABottomSheet() {
        compose.setContent {
            Box(Modifier.requiredWidth(360.dp).height(600.dp)) {
                EventsDrawers("cameras", eventsFixture(), EventsCatalog(cameras = mapOf("yard" to "Yard")), {}, {})
            }
        }
        compose.onNodeWithContentDescription("Filter dialog").assertDoesNotExist()
        compose.onNodeWithText("Cameras").assertIsDisplayed()
    }

    @Test
    fun theDatePickerSpeaksTheLanguageOfTheApp() {
        compose.setContent { EventsDrawers("date", eventsFixture(), EventsCatalog(), {}, {}) }
        compose.onNodeWithText("Select date").assertExists()
        compose.onNodeWithText("Selected date").assertExists()
    }

    @Test
    fun settingsSheetListsAllLabelsAndAllZonesWithNamedOptions() {
        val state = eventsFixture().copy(items = listOf(reviewFixture().copy(zones = listOf("porch"))))
        compose.setContent { EventsScreen(state, {}, {}) }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Filter").performClick()
        compose.onNodeWithText("All Labels").assertIsDisplayed()
        compose.onNodeWithText("All Zones").assertIsDisplayed()
        compose.onNodeWithContentDescription("Label person").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zone porch").assertIsDisplayed()
    }

    @Test
    fun settingsSheetDoesNotRepeatTheReviewedFilter() {
        compose.setContent { EventsScreen(eventsFixture(), {}, {}) }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Show reviewed").assertDoesNotExist()
    }

    @Test
    fun oneApplyChangesLabelsZonesAndSeverityTogether() {
        val actions = mutableListOf<EventsAction>()
        val state = eventsFixture().copy(items = listOf(reviewFixture().copy(zones = listOf("porch"))))
        compose.setContent { EventsScreen(state, {}, actions::add) }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Filter").performClick()
        compose.onNodeWithContentDescription("Include other severity").performClick()
        compose.onNodeWithContentDescription("Label person").performClick()
        compose.onNodeWithContentDescription("Zone porch").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertEquals(listOf(EventsAction.SetFilters(listOf("person"), listOf("porch"), true)), actions)
        }
    }

    @Test
    fun wideFilterPanelCarriesTheGeneralFiltersWithoutTheDate() {
        compose.setContent {
            Box(Modifier.requiredWidth(780.dp).height(600.dp)) {
                EventsDrawers("filters", eventsFixture(), EventsCatalog(), {}, {}, expanded = true)
            }
        }
        compose.onNodeWithText("All Labels").assertIsDisplayed()
        compose.onNodeWithText("Review settings").assertDoesNotExist()
    }

    @Test
    fun motionTypeReplacesTheReviewedFilterWithMotionOnly() {
        compose.setContent {
            Box(Modifier.requiredWidth(780.dp)) {
                EventsHeader(eventsFixture().copy(filter = EventsFilter(type = EventsType.MOTION)), true,
                    EventsCatalog(), {}, {})
            }
        }
        compose.onNodeWithContentDescription("Show reviewed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Motion only").assertIsDisplayed()
    }

    @Test
    fun darkEventsProvidesReadableContentColorToMotionAndTimeline() {
        var motionColor = androidx.compose.ui.graphics.Color.Unspecified
        var timelineColor = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            EventsScreen(eventsFixture().copy(filter = EventsFilter(type = EventsType.MOTION)), {}, {},
                motion = { motionColor = androidx.compose.material3.LocalContentColor.current },
                timeline = { timelineColor = androidx.compose.material3.LocalContentColor.current })
        }
        compose.runOnIdle {
            assertEquals(eventsColorScheme.onBackground, motionColor)
            if (timelineColor != androidx.compose.ui.graphics.Color.Unspecified)
                assertEquals(eventsColorScheme.onBackground, timelineColor)
        }
    }

    @Test
    fun narrowOffersThreeTypesAndSettingsDoesNotToggleReviewed() {
        val actions = mutableListOf<EventsAction>()
        compose.setContent { Box(Modifier.width(390.dp)) { EventsScreen(eventsFixture(), {}, actions::add, timeline = {
            EventsTimeline(EventsTimelineModel(eventsFixture().query.range, eventsFixture().items, emptyList()), "UTC", {}, {})
        }) } }
        compose.onNodeWithContentDescription("Alerts, 9").assertIsDisplayed()
        compose.onNodeWithContentDescription("Detections, 3").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Motion").assertIsDisplayed()
        compose.onNodeWithContentDescription("Timeline overview").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Calendar").assertIsDisplayed()
        compose.onNodeWithText("Filter").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(EventsAction.SelectType(EventsType.DETECTION)), actions)
        }
    }

    @Test
    fun tapOpensActualReviewAndSelectionTapDoesNotOpenHistory() {
        var opened: ReviewOpenRequest? = null
        val actions = mutableListOf<EventsAction>()
        compose.setContent { EventsScreen(eventsFixture().copy(selectedIds = setOf("r")), { opened = it }, actions::add) }
        compose.onNodeWithContentDescription("Open recording: yard, 00:02:00").performClick()
        compose.runOnIdle { assertNull(opened); assertEquals(EventsAction.ToggleSelection("r"), actions.single()) }
    }

    @Test
    fun reviewCardShowsObjectIconsAndRelativeAgeInsteadOfRawObjectText() {
        val now = System.currentTimeMillis() / 1000.0
        val review = reviewFixture().copy(startTime = now - 120, endTime = now - 100, objectLabels = listOf("car", "person"))
        compose.setContent { Box(Modifier.width(280.dp)) { ReviewGridCard(review, false, false, {}, {}) } }
        compose.onNodeWithContentDescription("Detected car", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Detected person", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2m ago", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("car · person", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun ordinaryTapCarriesCameraAndRange() {
        var opened: ReviewOpenRequest? = null
        compose.setContent { EventsScreen(eventsFixture(), { opened = it }, {}) }
        compose.onNodeWithContentDescription("Open recording: yard, 00:02:00").performClick()
        compose.runOnIdle { assertEquals("yard", opened?.review?.camera); assertEquals(100.0, opened?.range?.after) }
    }
}
