package com.frigate.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.frigate.android.feature.events.eventsColorScheme

/** The whole app is dark, like the Frigate PWA: black screens, blue-grey buttons. */
@Composable
fun FrigateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = eventsColorScheme, content = content)
}
