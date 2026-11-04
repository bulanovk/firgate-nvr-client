package com.frigate.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val PillColor = Color(0xFF3B4350)
internal val PillAccent = Color(0xFF8FB4FF)
internal val PillMuted = Color(0xFF9A9A9A)

/** Button of the dark screens: a blue-grey pill, or the light primary one for the main action. */
@Composable
internal fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE8E8E8), contentColor = Color(0xFF111111),
                disabledContainerColor = Color(0xFF2A2A2A), disabledContentColor = Color(0xFF777777),
            )
        } else {
            ButtonDefaults.buttonColors(containerColor = PillColor, contentColor = Color.White)
        },
    ) { Text(label, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal) }
}
