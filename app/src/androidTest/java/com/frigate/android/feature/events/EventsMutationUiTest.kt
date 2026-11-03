package com.frigate.android.feature.events

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EventsMutationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test
    fun deleteWarnsAboutRecordingsAndCancelNeverConfirms() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent { EventsDeleteDialog(2, { dismissed++ }, { confirmed++ }) }
        compose.onNodeWithText("This also deletes overlapping recordings. This cannot be undone.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, dismissed); assertEquals(0, confirmed) }
    }
    @Test
    fun onlyCompletedExportsOfferSaveAndDoNotExposeServerUrl() {
        var saved: String? = null
        compose.setContent { EventsExportJobs(listOf(ExportJob("queued", "queued"), ExportJob("done", "completed", "exports/file.mp4")),
            false, false, {}, {}, { saved = it.id }) }
        compose.onAllNodesWithText("Save video").assertCountEquals(1)
        compose.onNodeWithText("Save video").performClick()
        compose.runOnIdle { assertEquals("done", saved) }
    }

    @Test
    fun exportRequiresExplicitSubmission() {
        var submitted = 0
        compose.setContent { EventsExportDialog(2, emptyList(), {}, { submitted++ }) }
        compose.runOnIdle { assertEquals(0, submitted) }
        compose.onNodeWithText("Export").performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }
}
