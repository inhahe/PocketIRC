package com.pocketirc.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

/**
 * Renders IRC formatting control characters as visible `^X` markers in a TextField
 * without modifying the underlying value. Each 1-char control becomes a 2-char display
 * pair, so we maintain an [OffsetMapping] that shifts cursor positions accordingly.
 */
object IrcControlVisualTransformation : VisualTransformation {

    private val labels: Map<Char, String> = mapOf(
        '\u0002' to "^B", // bold
        '\u0003' to "^K", // color
        '\u000F' to "^O", // reset
        '\u0011' to "^Q", // monospace
        '\u0016' to "^V", // reverse
        '\u001D' to "^I", // italic
        '\u001E' to "^S", // strikethrough
        '\u001F' to "^U", // underline
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val markerStyle = SpanStyle(
            color = Color(0xFF8AB4F8),
            fontWeight = FontWeight.Bold,
        )
        val builder = buildAnnotatedString {
            for (c in raw) {
                val label = labels[c]
                if (label != null) withStyle(markerStyle) { append(label) }
                else append(c)
            }
        }

        // Offsets: each control char (1 char) → 2 chars in display.
        val rawToDisplay = IntArray(raw.length + 1)
        var disp = 0
        for (i in raw.indices) {
            rawToDisplay[i] = disp
            disp += if (raw[i] in labels) 2 else 1
        }
        rawToDisplay[raw.length] = disp

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                rawToDisplay[offset.coerceIn(0, raw.length)]

            override fun transformedToOriginal(offset: Int): Int {
                // Walk display offset back to a raw index.
                var d = 0
                for (i in raw.indices) {
                    val step = if (raw[i] in labels) 2 else 1
                    if (d + step > offset) return i
                    d += step
                }
                return raw.length
            }
        }

        return TransformedText(builder, mapping)
    }
}
