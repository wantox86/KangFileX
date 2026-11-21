package com.wantox86.kangfilex.presentation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KangDarkColorScheme = darkColorScheme(
    primary = KangColors.AccentOrange,
    onPrimary = Color(0xFF121417),
    secondary = KangColors.StorageCyan,
    onSecondary = Color(0xFF121417),
    background = KangColors.Background,
    onBackground = KangColors.TextPrimary,
    surface = KangColors.Surface,
    onSurface = KangColors.TextPrimary,
    surfaceVariant = KangColors.SurfaceElevated,
    onSurfaceVariant = KangColors.TextSecondary,
    outline = KangColors.Divider,
    error = KangColors.ErrorRed
)

@Composable
fun KangFileXTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KangDarkColorScheme,
        typography = KangTypography,
        content = content
    )
}
