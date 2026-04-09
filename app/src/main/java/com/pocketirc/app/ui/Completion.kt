package com.pocketirc.app.ui

import androidx.compose.ui.text.input.TextFieldValue

data class CompletionState(
    /** Range in the field text occupied by the partial word being completed. */
    val wordRange: IntRange,
    /** Candidate completions, ordered by priority (recency > alphabetical). */
    val candidates: List<String>,
)

private val EMPTY = CompletionState(IntRange.EMPTY, emptyList())

/**
 * Compute nick completions for the word at the cursor position.
 *
 * Priority:
 *  1. Nicks that have spoken recently in the buffer (most recent first)
 *  2. Then alphabetical from the channel nick list
 *
 * Channel mode prefixes (`@`, `+`, etc.) are stripped from candidates so the
 * inserted text is the bare nick.
 */
fun computeCompletions(
    field: TextFieldValue,
    channelNicks: List<String>,
    recentSpeakers: List<String>,
): CompletionState {
    val text = field.text
    val cursor = field.selection.end.coerceIn(0, text.length)
    if (cursor == 0) return EMPTY

    // Find the start of the word at the cursor.
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    if (start == cursor) return EMPTY  // cursor is on whitespace

    val partial = text.substring(start, cursor)
    if (partial.isBlank()) return EMPTY
    val partialLower = partial.lowercase()

    val stripped = channelNicks
        .map { it.dropWhile { c -> c in "@+%&~" } }
        .filter { it.isNotEmpty() }
        .distinct()

    val matches = stripped.filter { it.lowercase().startsWith(partialLower) }
    if (matches.isEmpty()) return EMPTY

    val recencyIndex = recentSpeakers
        .mapIndexed { idx, nick -> nick.lowercase() to idx }
        .toMap()

    val sorted = matches.sortedWith(
        compareBy(
            { recencyIndex[it.lowercase()] ?: Int.MAX_VALUE },
            { it.lowercase() },
        )
    )

    return CompletionState(start until cursor, sorted)
}
