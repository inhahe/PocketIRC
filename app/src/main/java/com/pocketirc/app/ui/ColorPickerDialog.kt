package com.pocketirc.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pocketirc.app.irc.MircColors

/**
 * mIRC color picker. Tap a swatch to pick foreground (commits & closes).
 * Long-press a swatch to set background (picker stays open).
 *
 * Matches qtpyrc UX: bg-only becomes "1,bg" (black on bg).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorPickerDialog(
    onDismiss: () -> Unit,
    onPick: (code: String) -> Unit,
) {
    var pendingBg by remember { mutableStateOf<Int?>(null) }

    fun commit(fg: Int?) {
        onPick(MircColors.colorCode(fg, pendingBg))
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Tap = foreground · Long-press = background",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (pendingBg != null) "Background: ${pendingBg}  (tap fg to insert)"
                    else "No background selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))

                // Base 16: 2 rows × 8 cols
                ColorGrid(
                    range = 0..15,
                    cols = 8,
                    onTap = { commit(it) },
                    onLongPress = { pendingBg = it },
                )
                Spacer(Modifier.height(6.dp))
                // Extended 16..98: 7 rows × 12 cols (84 cells, last is empty)
                ColorGrid(
                    range = 16..98,
                    cols = 12,
                    onTap = { commit(it) },
                    onLongPress = { pendingBg = it },
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (pendingBg != null) {
                        TextButton(onClick = { pendingBg = null }) { Text("Clear bg") }
                    }
                    TextButton(onClick = { commit(null) }) { Text("Insert (no fg)") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorGrid(
    range: IntRange,
    cols: Int,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    val list = range.toList()
    val rows = (list.size + cols - 1) / cols
    Column {
        for (r in 0 until rows) {
            Row {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    if (i < list.size) {
                        val idx = list[i]
                        val (rr, gg, bb) = MircColors.rgb[idx]
                        val bg = Color(rr, gg, bb)
                        val fg = if (MircColors.isDark(idx)) Color.White else Color.Black
                        Box(
                            modifier = Modifier
                                .padding(1.dp)
                                .size(width = 26.dp, height = 22.dp)
                                .background(bg)
                                .combinedClickable(
                                    onClick = { onTap(idx) },
                                    onLongClick = { onLongPress(idx) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                idx.toString(),
                                color = fg,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        Spacer(Modifier.padding(1.dp).size(width = 26.dp, height = 22.dp))
                    }
                }
            }
        }
    }
}
