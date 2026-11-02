package com.caremate.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

val Sos = Color(0xFFC62828)

private val Light = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF6D4C41), secondaryContainer = Color(0xFFFFE0B2),
    background = Color(0xFFFFFBF5), surface = Color(0xFFFFFBF5),
    surfaceVariant = Color(0xFFF1EBE3),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF80CBC4), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFFFCC80), secondaryContainer = Color(0xFF5D4037),
)

/** Material theme with an optional extra-large text mode for low vision. */
@Composable
fun CareMateTheme(large: Boolean, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * if (large) 1.25f else 1f)) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
    }
}
