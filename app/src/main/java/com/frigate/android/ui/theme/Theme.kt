package com.frigate.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val FrigateTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, lineHeight = 42.sp),
        headlineLarge = headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val FrigateLightColors = lightColorScheme(
    primary = Color(0xFFB64B2C),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF66705B),
    background = Color(0xFFF4F1EA),
    surface = Color(0xFFFFFCF6),
    onBackground = Color(0xFF20241F),
    onSurface = Color(0xFF20241F),
)

@Composable
fun FrigateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FrigateLightColors,
        typography = FrigateTypography,
        content = content,
    )
}
