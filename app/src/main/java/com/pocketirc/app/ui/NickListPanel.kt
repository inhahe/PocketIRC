package com.pocketirc.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp

@Composable
fun NickListPanel(
    nicks: List<String>,
    colorNicks: Boolean,
    onNickClick: (String) -> Unit = {},
    onNickDoubleClick: (String) -> Unit = {},
    onNickLongClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val sorted = remember(nicks) {
        nicks.sortedWith(
            compareByDescending<String> { rankOf(prefixChar(it)) }
                .thenBy { stripPrefix(it).lowercase() }
        )
    }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "Users (${sorted.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(sorted, key = { it }) { rawNick ->
                NickRow(
                    rawNick = rawNick,
                    colorNicks = colorNicks,
                    defaultColor = defaultColor,
                    onClick = { onNickClick(stripPrefix(rawNick)) },
                    onDoubleClick = { onNickDoubleClick(stripPrefix(rawNick)) },
                    onLongClick = { onNickLongClick(stripPrefix(rawNick)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NickRow(
    rawNick: String,
    colorNicks: Boolean,
    defaultColor: Color,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bare = stripPrefix(rawNick)
    val prefix = prefixChar(rawNick)
    val nickColor = if (colorNicks) colorForNick(bare) else defaultColor
    val baseStyle = TextStyle(
        fontSize = TextUnit(LocalChatFontSize.current.toFloat(), TextUnitType.Sp),
        color = nickColor,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = prefix?.toString() ?: " ",
            style = baseStyle.copy(
                color = colorForPrefix(prefix) ?: nickColor,
                fontWeight = if (prefix != null) FontWeight.Bold else FontWeight.Normal,
            ),
            modifier = Modifier.width(14.dp),
        )
        Text(text = bare, style = baseStyle)
    }
}

/** Standard PREFIX rank — owner > admin > op > halfop > voice > none. */
private fun rankOf(prefix: Char?): Int = when (prefix) {
    '~' -> 5  // owner / founder
    '&' -> 4  // admin / protected
    '@' -> 3  // op
    '%' -> 2  // halfop
    '+' -> 1  // voice
    else -> 0
}

private fun colorForPrefix(prefix: Char?): Color? = when (prefix) {
    '~' -> Color(0xFFE57373)
    '&' -> Color(0xFFFFB74D)
    '@' -> Color(0xFF81C784)
    '%' -> Color(0xFF64B5F6)
    '+' -> Color(0xFFFFD54F)
    else -> null
}

private fun prefixChar(nick: String): Char? =
    nick.firstOrNull()?.takeIf { it in "@+%&~" }

private fun stripPrefix(nick: String): String =
    nick.dropWhile { it in "@+%&~" }
