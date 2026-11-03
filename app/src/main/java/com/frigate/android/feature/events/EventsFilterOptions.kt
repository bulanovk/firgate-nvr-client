package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateReview

internal data class EventsFilterOptions(val labels: List<String>, val zones: List<String>)

/**
 * The reference offers only what the loaded records contain (`EventView.tsx` reviewFilterList):
 * object labels without the "-verified" suffix, audio labels and zones, in order of appearance.
 */
internal fun eventsFilterOptions(items: List<FrigateReview>): EventsFilterOptions = EventsFilterOptions(
    labels = items.flatMap { it.objectLabels.map { l -> l.replace("-verified", "") } + it.audioLabels }.distinct(),
    zones = items.flatMap { it.zones }.distinct(),
)

internal fun eventsLabelTitle(label: String): String = label.replaceFirstChar { it.uppercaseChar() }

/** The reference "All" row is a reset: switching it on clears the selection, switching it off does nothing. */
internal fun allOptionSelection(selected: Set<String>, on: Boolean): Set<String> = if (on) emptySet() else selected

/** The reference never lets the last checked label or zone go: unchecking it does nothing. */
internal fun toggleFilterValue(selected: Set<String>, value: String, on: Boolean): Set<String> =
    if (on) selected + value else if (selected.size > 1) selected - value else selected
