package com.frigate.android.feature.events

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MotionRegionUiTest {
    @get:Rule val compose = createComposeRule()
    @Test
    fun missingHeatmapDisablesSpatialControlsWithoutApplyingInventedRegions() {
        val selection = MotionRegionSelection("yard")
        compose.setContent { MotionRegionSheet(selection, emptyMap(), {}, {}) }
        compose.onNodeWithContentDescription("Region cell 1, 1").assertIsNotEnabled()
        compose.onNodeWithText("Crop to region").assertExists()
        compose.onNodeWithContentDescription("Dim background").assertIsNotEnabled()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(selection.pending.isEmpty()) }
    }

    @Test
    fun cellsRemainDraftUntilApplyAndCancelRestoresAppliedRegion() {
        val selection = MotionRegionSelection("yard")
        var applied = 0
        compose.setContent { MotionRegionSheet(selection, mapOf(0 to 8.0), {}, { applied++ }) }
        compose.onNodeWithContentDescription("Region cell 1, 1").performClick()
        compose.runOnIdle { assertTrue(selection.applied.isEmpty()); assertEquals(setOf(0), selection.pending) }
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(1, applied); assertEquals(setOf(0), selection.applied) }
        compose.onNodeWithContentDescription("Region cell 1, 2").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(setOf(0), selection.applied); assertEquals(setOf(0), selection.pending) }
    }
}
