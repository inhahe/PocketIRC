package com.pocketirc.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Parses mIRC formatting control codes (bold/italic/underline/strikethrough/color/reset)
 * into a list of (text, style) segments. Combine with URL/mention overlays at render time.
 *
 * Supported codes:
 *   \u0002  bold toggle
 *   \u001D  italic toggle
 *   \u001F  underline toggle
 *   \u001E  strikethrough toggle
 *   \u0016  reverse (swap fg/bg)
 *   \u000F  reset all
 *   \u0003  color: 0–2 fg digits, optional ',' + 0–2 bg digits. Bare \u0003 clears colors.
 *
 * Reference: https://modern.ircdocs.horse/formatting.html
 */
data class MircSegment(val text: String, val style: SpanStyle)

object MircFormat {

    /**
     * Full 99-color mIRC palette (indices 0-98). Index 99 is "default" — represented
     * as null in [colorAt] so the renderer falls back to the theme color.
     * Source: https://modern.ircdocs.horse/formatting.html
     */
    private val mircColors: List<Color> = listOf(
        Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF0000FF), Color(0xFF008000),
        Color(0xFFFF0000), Color(0xFFA52A2A), Color(0xFFFF00FF), Color(0xFFFFA500),
        Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF), Color(0xFF00FFFF),
        Color(0xFFADD8E6), Color(0xFFFFC0CB), Color(0xFF808080), Color(0xFFD3D3D3),
        Color(0xFF470000), Color(0xFF472100), Color(0xFF474700), Color(0xFF324700),
        Color(0xFF004700), Color(0xFF00472C), Color(0xFF004747), Color(0xFF002747),
        Color(0xFF000047), Color(0xFF2E0047), Color(0xFF470047), Color(0xFF47002A),
        Color(0xFF740000), Color(0xFF743A00), Color(0xFF747400), Color(0xFF517400),
        Color(0xFF007400), Color(0xFF007449), Color(0xFF007474), Color(0xFF004074),
        Color(0xFF000074), Color(0xFF4B0074), Color(0xFF740074), Color(0xFF740045),
        Color(0xFFB50000), Color(0xFFB56300), Color(0xFFB5B500), Color(0xFF7DB500),
        Color(0xFF00B500), Color(0xFF00B571), Color(0xFF00B5B5), Color(0xFF0063B5),
        Color(0xFF0000B5), Color(0xFF7500B5), Color(0xFFB500B5), Color(0xFFB5006B),
        Color(0xFFFF0000), Color(0xFFFF8C00), Color(0xFFFFFF00), Color(0xFFB2FF00),
        Color(0xFF00FF00), Color(0xFF00FFA0), Color(0xFF00FFFF), Color(0xFF008CFF),
        Color(0xFF0000FF), Color(0xFFA500FF), Color(0xFFFF00FF), Color(0xFFFF0098),
        Color(0xFFFF5959), Color(0xFFFFB459), Color(0xFFFFFF71), Color(0xFFCFFF60),
        Color(0xFF6FFF6F), Color(0xFF65FFC9), Color(0xFF6DFFFF), Color(0xFF59B4FF),
        Color(0xFF5959FF), Color(0xFFC459FF), Color(0xFFFF66FF), Color(0xFFFF59BC),
        Color(0xFFFF9C9C), Color(0xFFFFD39C), Color(0xFFFFFF9C), Color(0xFFE2FF9C),
        Color(0xFF9CFF9C), Color(0xFF9CFFDB), Color(0xFF9CFFFF), Color(0xFF9CD3FF),
        Color(0xFF9C9CFF), Color(0xFFDC9CFF), Color(0xFFFF9CFF), Color(0xFFFF94D3),
        Color(0xFF000000), Color(0xFF131313), Color(0xFF282828), Color(0xFF363636),
        Color(0xFF4D4D4D), Color(0xFF656565), Color(0xFF818181), Color(0xFF9F9F9F),
        Color(0xFFBCBCBC), Color(0xFFE2E2E2), Color(0xFFFFFFFF),
    )

    /** Returns the color at [idx], or null for index 99 (default) / out-of-range. */
    private fun colorAt(idx: Int): Color? = mircColors.getOrNull(idx)

    fun parse(text: String): List<MircSegment> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<MircSegment>()
        val sb = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var fg: Color? = null
        var bg: Color? = null

        fun flush() {
            if (sb.isEmpty()) return
            val style = SpanStyle(
                color = fg ?: Color.Unspecified,
                background = bg ?: Color.Unspecified,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = when {
                    underline && strike ->
                        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    underline -> TextDecoration.Underline
                    strike -> TextDecoration.LineThrough
                    else -> null
                },
            )
            out.add(MircSegment(sb.toString(), style))
            sb.clear()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '\u0002' -> { flush(); bold = !bold; i++ }
                '\u001D' -> { flush(); italic = !italic; i++ }
                '\u001F' -> { flush(); underline = !underline; i++ }
                '\u001E' -> { flush(); strike = !strike; i++ }
                '\u000F' -> {
                    flush()
                    bold = false; italic = false; underline = false; strike = false
                    fg = null; bg = null
                    i++
                }
                '\u0016' -> { flush(); val t = fg; fg = bg; bg = t; i++ }
                '\u0003' -> {
                    flush()
                    i++
                    val fgStr = readDigits(text, i, max = 2); i += fgStr.length
                    if (fgStr.isEmpty()) {
                        fg = null; bg = null
                    } else {
                        fg = colorAt(fgStr.toInt())
                        if (i < text.length && text[i] == ',') {
                            // Lookahead: only consume the comma if at least one bg digit follows.
                            val after = readDigits(text, i + 1, max = 2)
                            if (after.isNotEmpty()) {
                                i += 1 + after.length
                                bg = colorAt(after.toInt())
                            }
                        }
                    }
                }
                '\u0004', '\u0011' -> { i++ } // hex color / monospace — silently strip
                else -> { sb.append(c); i++ }
            }
        }
        flush()
        return out
    }

    private fun readDigits(s: String, start: Int, max: Int): String {
        var i = start
        val end = (start + max).coerceAtMost(s.length)
        while (i < end && s[i].isDigit()) i++
        return s.substring(start, i)
    }
}
