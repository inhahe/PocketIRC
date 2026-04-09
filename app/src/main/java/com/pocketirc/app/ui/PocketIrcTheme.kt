package com.pocketirc.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.pocketirc.app.data.ThemeMode

/** Composition local for the user-chosen chat font size. */
val LocalChatFontSize = staticCompositionLocalOf { 17 }

@Composable
fun PocketIrcTheme(
    themeMode: ThemeMode,
    fontSizeSp: Int,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalChatFontSize provides fontSizeSp) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
