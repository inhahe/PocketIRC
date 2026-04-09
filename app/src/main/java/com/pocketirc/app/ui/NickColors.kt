package com.pocketirc.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * Two hand-picked palettes — one tuned for legibility on a dark background,
 * one for a light background. [colorForNick] picks the right one for the
 * current theme so nicks don't disappear when the theme flips.
 *
 * Selection is a deterministic hash of the lowercased nick, so the same nick
 * always gets the same color (within a given theme).
 */
private val darkPalette = listOf(
    Color(0xFFEF5350), Color(0xFFEC407A), Color(0xFFAB47BC), Color(0xFF7E57C2),
    Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF29B6F6), Color(0xFF26C6DA),
    Color(0xFF26A69A), Color(0xFF66BB6A), Color(0xFF9CCC65), Color(0xFFD4E157),
    Color(0xFFFFCA28), Color(0xFFFFA726), Color(0xFFFF7043), Color(0xFFBCAAA4),
)

private val lightPalette = listOf(
    Color(0xFFC62828), Color(0xFFAD1457), Color(0xFF6A1B9A), Color(0xFF4527A0),
    Color(0xFF283593), Color(0xFF1565C0), Color(0xFF0277BD), Color(0xFF00838F),
    Color(0xFF00695C), Color(0xFF2E7D32), Color(0xFF558B2F), Color(0xFF9E9D24),
    Color(0xFFF9A825), Color(0xFFEF6C00), Color(0xFFD84315), Color(0xFF4E342E),
)

@Composable
@ReadOnlyComposable
fun colorForNick(nick: String): Color {
    val palette = if (isSystemInDarkTheme()) darkPalette else lightPalette
    if (nick.isEmpty()) return palette[0]
    var h = 0
    for (c in nick.lowercase()) h = h * 31 + c.code
    return palette[((h % palette.size) + palette.size) % palette.size]
}
