package com.pocketirc.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pocketirc.app.model.TreeNode
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NetworkTree(
    nodes: List<TreeNode.Server>,
    selectedBufferId: String?,
    onBufferClick: (String) -> Unit,
    onJoinClick: (serverId: String) -> Unit = {},
    onServerLongPress: (serverId: String) -> Unit = {},
    onBufferLongPress: (TreeNode.Buffer) -> Unit = {},
    onLeaveBuffer: (bufferId: String) -> Unit = {},
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        nodes.forEach { server ->
            val isOpen = expanded[server.id] ?: true
            item(key = "srv-${server.id}") {
                ServerRow(
                    server = server,
                    expanded = isOpen,
                    onToggle = { expanded[server.id] = !isOpen },
                    onLongPress = { onServerLongPress(server.id) },
                    onJoinClick = { onJoinClick(server.id) },
                )
            }
            if (isOpen) {
                items(server.children, key = { "buf-${it.id}" }) { buf ->
                    if (buf.kind == TreeNode.Buffer.Kind.STATUS) {
                        BufferRow(
                            buffer = buf,
                            selected = buf.id == selectedBufferId,
                            onClick = { onBufferClick(buf.id) },
                            onLongPress = { onBufferLongPress(buf) },
                        )
                    } else {
                        SwipeToLeave(
                            onConfirm = { onLeaveBuffer(buf.id) },
                        ) {
                            BufferRow(
                                buffer = buf,
                                selected = buf.id == selectedBufferId,
                                onClick = { onBufferClick(buf.id) },
                                onLongPress = { onBufferLongPress(buf) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Right-to-left swipe to leave the channel/query, with two safeguards:
 *
 *  1. [detectHorizontalDragGestures] uses Compose's horizontal touch slop, which
 *     only claims the gesture once horizontal motion exceeds the slop *and* is
 *     dominant over vertical motion. If the user starts a vertical drag, the
 *     parent LazyColumn keeps the gesture and this composable never sees it.
 *
 *  2. While the swipe is in progress we track cumulative vertical drift via the
 *     raw pointer events. If vertical drift exceeds [verticalCancelDp], we treat
 *     the gesture as a scroll attempt, snap the row back to 0, and ignore the
 *     final release.
 *
 * Confirmation requires the row to travel at least [thresholdFraction] of its
 * own width before the user lifts their finger. Otherwise it springs back.
 */
@Composable
private fun SwipeToLeave(
    onConfirm: () -> Unit,
    thresholdFraction: Float = 0.5f,
    verticalCancelDp: Int = 24,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val verticalCancelPx = with(density) { verticalCancelDp.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var verticalDrift by remember { mutableFloatStateOf(0f) }
    var cancelled by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                // Raw event loop runs *alongside* detectHorizontalDragGestures
                // below to track vertical drift for cancellation. We don't
                // consume here so the horizontal-drag detector still works.
            },
    ) {
        // Red "leave" background reveals as the row slides left.
        Box(
            Modifier
                .matchParentSize()
                .background(Color(0xFFB71C1C))
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Leave", tint = Color.White)
        }

        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            verticalDrift = 0f
                            cancelled = false
                        },
                        onDragEnd = {
                            scope.launch {
                                if (!cancelled &&
                                    widthPx > 0 &&
                                    offsetX.value.absoluteValue >= widthPx * thresholdFraction
                                ) {
                                    offsetX.animateTo(-widthPx.toFloat())
                                    onConfirm()
                                } else {
                                    offsetX.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            // Track vertical drift via the raw pointer position.
                            verticalDrift += change.positionChange().y
                            if (verticalDrift.absoluteValue > verticalCancelPx) {
                                cancelled = true
                                scope.launch { offsetX.animateTo(0f) }
                                return@detectHorizontalDragGestures
                            }
                            if (cancelled) return@detectHorizontalDragGestures
                            // Only allow leftward travel.
                            val next = (offsetX.value + dragAmount).coerceAtMost(0f)
                            scope.launch { offsetX.snapTo(next) }
                            change.consume()
                        },
                    )
                },
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: TreeNode.Server,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onJoinClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
        )
        Icon(
            imageVector = if (server.connected) Icons.Default.Cloud else Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(
            text = server.label,
            fontWeight = FontWeight.Bold,
            fontStyle = if (server.saved) androidx.compose.ui.text.font.FontStyle.Normal
                        else androidx.compose.ui.text.font.FontStyle.Italic,
        )
        if (!server.saved) {
            Spacer(Modifier.width(4.dp))
            Text(
                "(temporary)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            )
        }
        if (server.unread > 0) {
            Spacer(Modifier.width(6.dp))
            UnreadBadge(server.unread, server.highlighted)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onJoinClick,
            enabled = server.connected,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Join channel")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BufferRow(
    buffer: TreeNode.Buffer,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
             else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(start = 32.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (buffer.kind) {
            TreeNode.Buffer.Kind.CHANNEL -> Icons.Default.Forum
            TreeNode.Buffer.Kind.QUERY -> Icons.Default.Person
            TreeNode.Buffer.Kind.STATUS -> Icons.Default.Info
        }
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Text(buffer.label)
        if (buffer.unread > 0) {
            Spacer(Modifier.width(6.dp))
            UnreadBadge(buffer.unread, buffer.highlighted)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun UnreadBadge(count: Int, highlighted: Boolean) {
    val color = if (highlighted) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
    Badge(containerColor = color) { Text(count.toString()) }
}
