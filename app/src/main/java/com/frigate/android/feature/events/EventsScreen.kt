package com.frigate.android.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateReview
import kotlinx.coroutines.launch

@Composable
internal fun EventsScreen(
    state: EventsUiState,
    onOpenReview: (ReviewOpenRequest) -> Unit,
    onAction: (EventsAction) -> Unit,
    modifier: Modifier = Modifier,
    catalog: EventsCatalog = EventsCatalog(),
    gridState: LazyGridState = rememberLazyGridState(),
    picture: @Composable BoxScope.(FrigateReview) -> Unit = {},
    timeline: @Composable () -> Unit = {},
    motion: @Composable () -> Unit = {},
    motionOnly: Boolean = false,
    onMotionOnly: () -> Unit = {},
) {
    val items = remember(state.items, state.filter) { filteredReviews(state.items, state.filter) }
    var drawer by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    MaterialTheme(colorScheme = eventsColorScheme) {
        CompositionLocalProvider(LocalContentColor provides eventsColorScheme.onBackground) {
        BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
            val expanded = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                EventsHeader(state, expanded, catalog, onAction, { drawer = it }, motionOnly, onMotionOnly)
                if (state.message != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onAction(EventsAction.RetryFailedAction) }) { Text("Retry") }
                }
                if (state.newItemsAvailable) TextButton(onClick = {
                    onAction(EventsAction.ReloadNewItems)
                    scope.launch { gridState.scrollToItem(0) }
                }, modifier = Modifier.fillMaxWidth()) { Text("New items available") }
                Row(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.filter.type == EventsType.MOTION) motion()
                        else if (!state.loaded && state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else if (items.isEmpty()) EventsEmptyState(state.filter.type, filtered = state.items.isNotEmpty())
                        else LazyVerticalGrid(columns = GridCells.Fixed(if (expanded) 3 else 1), state = gridState,
                            contentPadding = PaddingValues(horizontal = if (expanded) 8.dp else 4.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(if (expanded) 16.dp else 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(items, key = { it.id }) { review ->
                                ReviewGridCard(review, review.id in state.selectedIds, review.id in state.pendingIds,
                                    onOpen = {
                                        if (state.selectedIds.isNotEmpty()) onAction(EventsAction.ToggleSelection(review.id))
                                        else onOpenReview(ReviewOpenRequest(review, state.query.range))
                                    }, onAction = onAction, cameraName = catalog.cameras[review.camera] ?: review.camera,
                                    timezone = state.query.timezone, picture = { picture(review) })
                            }
                            if (items.any { it.endTime != null && !it.hasBeenReviewed }) item(span = { GridItemSpan(maxLineSpan) }) {
                                TextButton(onClick = { onAction(EventsAction.MarkViewed(items.filter { it.endTime != null && !it.hasBeenReviewed }.map { it.id }, true)) }) {
                                    Text("Mark these items as reviewed")
                                }
                            }
                        }
                    }
                    if (expanded) timeline()
                }
            }
            EventsDrawers(drawer, state, catalog, { drawer = it }, onAction, expanded)
        }
        }
    }
}
