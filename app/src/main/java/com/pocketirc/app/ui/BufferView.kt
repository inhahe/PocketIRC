package com.pocketirc.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketirc.app.irc.BufferStore
import com.pocketirc.app.model.ChatLine
import com.pocketirc.app.model.TypingState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val urlRegex = Regex("""\bhttps?://[^\s<>"']+""")

private sealed interface ChatEntry {
    val key: Any
    data class Line(val line: ChatLine) : ChatEntry { override val key get() = line.id }
    data class Marker(val beforeLineId: Long) : ChatEntry {
        override val key get() = "unread-$beforeLineId"
    }
}

@Composable
fun BufferView(
    buffer: BufferStore.Buffer?,
    ourNick: String?,
    colorNicks: Boolean,
    timestampFormat: String = "HH:mm",
    useMonospace: Boolean = false,
    sendOnEnter: Boolean = true,
    searchQuery: String = "",
    channelNicks: List<String> = emptyList(),
    pendingInsert: String? = null,
    onInsertConsumed: () -> Unit = {},
    onSenderClick: (String) -> Unit = {},
    onSenderDoubleClick: (String) -> Unit = {},
    onSenderLongClick: (String) -> Unit = {},
    onSend: (String) -> Unit,
    onTyping: (TypingState) -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalUseMonospace provides useMonospace) {
        BufferViewInner(
            buffer, ourNick, colorNicks, timestampFormat, sendOnEnter, searchQuery, channelNicks,
            pendingInsert, onInsertConsumed, onSenderClick, onSenderDoubleClick,
            onSenderLongClick, onSend, onTyping,
        )
    }
}

private val LocalUseMonospace = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun BufferViewInner(
    buffer: BufferStore.Buffer?,
    ourNick: String?,
    colorNicks: Boolean,
    timestampFormat: String,
    sendOnEnter: Boolean,
    searchQuery: String,
    channelNicks: List<String>,
    pendingInsert: String?,
    onInsertConsumed: () -> Unit,
    onSenderClick: (String) -> Unit,
    onSenderDoubleClick: (String) -> Unit,
    onSenderLongClick: (String) -> Unit,
    onSend: (String) -> Unit,
    onTyping: (TypingState) -> Unit,
) {
    if (buffer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Open a channel from the drawer", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    var field by remember { mutableStateOf(TextFieldValue("")) }

    // Pending insert (from tap-nick-in-userlist or tap-nick-in-chat).
    LaunchedEffect(pendingInsert) {
        val ins = pendingInsert ?: return@LaunchedEffect
        val pos = field.selection.end.coerceIn(0, field.text.length)
        val newText = field.text.substring(0, pos) + ins + field.text.substring(pos)
        field = TextFieldValue(text = newText, selection = TextRange(pos + ins.length))
        onInsertConsumed()
    }

    // Recompute completions whenever input or speaker state changes.
    val completions = remember(field, channelNicks, buffer.recentSpeakers) {
        computeCompletions(field, channelNicks, buffer.recentSpeakers)
    }

    val applyCompletion: (String) -> Unit = remember(completions) {
        { nick ->
            val range = completions.wordRange
            if (!range.isEmpty()) {
                val before = field.text.substring(0, range.first)
                val after = field.text.substring(range.last + 1)
                val newText = before + nick + after
                val newCursor = before.length + nick.length
                field = TextFieldValue(text = newText, selection = TextRange(newCursor))
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // "Currently typing" strip pinned at the top of the buffer.
        TopTypingStrip(buffer)
        val listState = rememberLazyListState()
        // Jump (no animation) to the bottom on buffer switch so the user
        // always lands on the latest line when they click into a channel.
        LaunchedEffect(buffer.id) {
            if (buffer.lines.isNotEmpty()) listState.scrollToItem(buffer.lines.lastIndex)
        }
        // Smooth-scroll to follow new lines as they arrive within the
        // currently-open buffer.
        LaunchedEffect(buffer.id, buffer.lines.size) {
            if (buffer.lines.isNotEmpty()) listState.animateScrollToItem(buffer.lines.lastIndex)
        }

        // Apply search filter, then splice in the unread divider.
        val visibleLines = remember(buffer.lines, searchQuery) {
            if (searchQuery.isBlank()) buffer.lines
            else buffer.lines.filter {
                it.text.contains(searchQuery, ignoreCase = true) ||
                (it.sender?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
        val entries = remember(visibleLines, buffer.unreadMarkerLineId) {
            val out = mutableListOf<ChatEntry>()
            val markerId = buffer.unreadMarkerLineId
            var inserted = false
            for (line in visibleLines) {
                if (!inserted && markerId != null && line.id > markerId) {
                    out += ChatEntry.Marker(line.id)
                    inserted = true
                }
                out += ChatEntry.Line(line)
            }
            out
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                ) {
                    items(entries, key = { it.key }) { entry ->
                        when (entry) {
                            is ChatEntry.Marker -> UnreadDivider()
                            is ChatEntry.Line -> LineRow(
                                line = entry.line,
                                ourNick = ourNick,
                                colorNicks = colorNicks,
                                onSenderClick = onSenderClick,
                                onSenderDoubleClick = onSenderDoubleClick,
                                onSenderLongClick = onSenderLongClick,
                                timestampFormat = timestampFormat,
                            )
                        }
                    }
                }
            }
        }

        // Completion chips strip — only renders when there are matches, and sits
        // between the chat list and the input bar so the chat history shifts up
        // to make room rather than being overlaid.
        if (completions.candidates.size > 1) {
            CompletionChipStrip(
                candidates = completions.candidates,
                onPick = applyCompletion,
            )
        }

        val doSend: () -> Unit = {
            val toSend = field.text.trim()
            if (toSend.isNotEmpty()) {
                onSend(toSend)
                field = TextFieldValue("")
                onTyping(TypingState.DONE)
            }
        }
        val doTab: () -> Unit = {
            completions.candidates.firstOrNull()?.let(applyCompletion)
        }
        val insertAtCursor: (String) -> Unit = { s ->
            val start = field.selection.start.coerceIn(0, field.text.length)
            val end = field.selection.end.coerceIn(0, field.text.length)
            val newText = field.text.substring(0, start) + s + field.text.substring(end)
            field = TextFieldValue(text = newText, selection = TextRange(start + s.length))
        }
        InputBar(
            field = field,
            onFieldChange = { field = it },
            sendOnEnter = sendOnEnter,
            onTab = doTab,
            onSend = doSend,
            onTyping = onTyping,
            onInsert = insertAtCursor,
        )
    }
}

private val chatStyle: androidx.compose.ui.text.TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(
        fontSize = LocalChatFontSize.current.sp,
        fontFamily = if (LocalUseMonospace.current)
            androidx.compose.ui.text.font.FontFamily.Monospace
        else null,
    )

@Composable
private fun UnreadDivider() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
        )
        Text(
            "  unread  ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun CompletionChipStrip(
    candidates: List<String>,
    onPick: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (nick in candidates) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { onPick(nick) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    nick,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineRow(
    line: ChatLine,
    ourNick: String?,
    colorNicks: Boolean,
    onSenderClick: (String) -> Unit,
    onSenderDoubleClick: (String) -> Unit,
    onSenderLongClick: (String) -> Unit,
    timestampFormat: String,
) {
    val time = remember(line.timestamp, timestampFormat) {
        runCatching {
            SimpleDateFormat(timestampFormat, Locale.getDefault()).format(Date(line.timestamp))
        }.getOrElse {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(line.timestamp))
        }
    }
    val defaultText = MaterialTheme.colorScheme.onSurface
    val nickColor = if (colorNicks) colorForNick(line.sender ?: "") else defaultText
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(time, style = MaterialTheme.typography.labelMedium,
             modifier = Modifier.padding(end = 6.dp, top = 3.dp))
        when (line.kind) {
            ChatLine.Kind.SYSTEM, ChatLine.Kind.JOIN, ChatLine.Kind.PART,
            ChatLine.Kind.QUIT, ChatLine.Kind.NICK -> {
                SystemLineText(
                    text = "* ${line.text}",
                    subjectNicks = line.subjectNicks,
                    onSenderClick = onSenderClick,
                    onSenderDoubleClick = onSenderDoubleClick,
                    onSenderLongClick = onSenderLongClick,
                )
            }
            ChatLine.Kind.ACTION -> {
                val senderMod = line.sender?.let { s ->
                    Modifier.combinedClickable(
                        onClick = { onSenderClick(s) },
                        onDoubleClick = { onSenderDoubleClick(s) },
                        onLongClick = { onSenderLongClick(s) },
                    )
                } ?: Modifier
                Text(
                    "* ${line.sender} ${line.text}",
                    style = chatStyle,
                    color = nickColor,
                    modifier = senderMod,
                )
            }
            ChatLine.Kind.NOTICE -> {
                Text("-${line.sender}- ${line.text}",
                     style = chatStyle,
                     color = MaterialTheme.colorScheme.tertiary)
            }
            ChatLine.Kind.MESSAGE -> {
                val senderMod = line.sender?.let { s ->
                    Modifier.combinedClickable(
                        onClick = { onSenderClick(s) },
                        onDoubleClick = { onSenderDoubleClick(s) },
                        onLongClick = { onSenderLongClick(s) },
                    )
                } ?: Modifier
                Text(
                    text = "<${line.sender}>",
                    style = chatStyle,
                    color = nickColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 4.dp).then(senderMod),
                )
                Text(annotateMessageBody(line.text, ourNick), style = chatStyle)
            }
        }
    }
}

/**
 * Render a SYSTEM-kind line, splitting on any [subjectNicks] so the nick parts
 * are clickable (single tap = insert into input, double tap = open query,
 * long-press = action menu). Plain text segments use a normal Text composable.
 *
 * Wrapping is handled by FlowRow so a long line splits across multiple visual
 * rows like a normal paragraph.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SystemLineText(
    text: String,
    subjectNicks: List<String>,
    onSenderClick: (String) -> Unit,
    onSenderDoubleClick: (String) -> Unit,
    onSenderLongClick: (String) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    if (subjectNicks.isEmpty()) {
        Text(text, style = chatStyle, color = outline)
        return
    }
    // Build a regex matching any subject nick as a whole word.
    val pattern = subjectNicks
        .filter { it.isNotBlank() }
        .joinToString("|") { Regex.escape(it) }
    if (pattern.isEmpty()) {
        Text(text, style = chatStyle, color = outline)
        return
    }
    val rx = Regex("\\b($pattern)\\b")
    val matches = rx.findAll(text).toList()
    if (matches.isEmpty()) {
        Text(text, style = chatStyle, color = outline)
        return
    }

    // Tokenize: alternate plain spans and clickable nick spans.
    data class Span(val text: String, val nick: String?)
    val spans = mutableListOf<Span>()
    var cursor = 0
    for (m in matches) {
        if (m.range.first > cursor) {
            spans += Span(text.substring(cursor, m.range.first), null)
        }
        spans += Span(text.substring(m.range.first, m.range.last + 1), m.value)
        cursor = m.range.last + 1
    }
    if (cursor < text.length) spans += Span(text.substring(cursor), null)

    FlowRow(modifier = Modifier.fillMaxWidth()) {
        for (span in spans) {
            if (span.nick == null) {
                Text(span.text, style = chatStyle, color = outline)
            } else {
                Text(
                    span.text,
                    style = chatStyle,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.combinedClickable(
                        onClick = { onSenderClick(span.nick) },
                        onDoubleClick = { onSenderDoubleClick(span.nick) },
                        onLongClick = { onSenderLongClick(span.nick) },
                    ),
                )
            }
        }
    }
}

@Composable
private fun annotateMessageBody(text: String, ourNick: String?): AnnotatedString {
    val mentionRegex = ourNick?.takeIf { it.isNotBlank() }
        ?.let { Regex("(?i)\\b${Regex.escape(it)}\\b") }
    val highlight = SpanStyle(color = Color(0xFFE57373), fontWeight = FontWeight.Bold)
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = Color(0xFF64B5F6), fontWeight = FontWeight.Medium)
    )
    val segments = MircFormat.parse(text)

    return buildAnnotatedString {
        for (seg in segments) {
            withStyle(seg.style) {
                appendWithLinksAndMentions(seg.text, mentionRegex, highlight, linkStyle)
            }
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendWithLinksAndMentions(
    text: String,
    mentionRegex: Regex?,
    highlight: SpanStyle,
    linkStyle: TextLinkStyles,
) {
    fun appendWithMentions(slice: String) {
        if (mentionRegex == null) { append(slice); return }
        val mentions = mentionRegex.findAll(slice).toList()
        if (mentions.isEmpty()) { append(slice); return }
        var i = 0
        for (m in mentions) {
            if (m.range.first > i) append(slice.substring(i, m.range.first))
            withStyle(highlight) { append(slice.substring(m.range.first, m.range.last + 1)) }
            i = m.range.last + 1
        }
        if (i < slice.length) append(slice.substring(i))
    }

    val urlMatches = urlRegex.findAll(text).toList()
    var cursor = 0
    for (m in urlMatches) {
        if (m.range.first > cursor) appendWithMentions(text.substring(cursor, m.range.first))
        val url = text.substring(m.range.first, m.range.last + 1)
        withLink(LinkAnnotation.Url(url = url, styles = linkStyle)) { append(url) }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) appendWithMentions(text.substring(cursor))
}

/**
 * Pinned strip at the top of a buffer that lists nicks currently typing
 * (driven by the IRCv3 +typing client tag inside TAGMSG). Hidden when nobody
 * is typing. Works for both channel and query buffers.
 */
@Composable
private fun TopTypingStrip(buffer: BufferStore.Buffer) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(buffer.id) {
        while (true) { delay(1000); now = System.currentTimeMillis() }
    }
    val typing = buffer.typingNicks.filterValues { it > now }.keys.sorted()
    if (typing.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Typing:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                typing.joinToString(", "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun InputBar(
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    sendOnEnter: Boolean,
    onTab: () -> Unit,
    onSend: () -> Unit,
    onTyping: (TypingState) -> Unit,
    onInsert: (String) -> Unit,
) {
    var formatMenuOpen by remember { mutableStateOf(false) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    if (colorPickerOpen) {
        ColorPickerDialog(
            onDismiss = { colorPickerOpen = false },
            onPick = { code -> onInsert(code) },
        )
    }
    // Rate-limit outgoing +typing notifications: spec recommends sending ACTIVE
    // at most once every ~3 seconds, and PAUSED only after the user stops
    // editing for ~6s. Re-keying on every keystroke would otherwise blast one
    // TAGMSG per character, fill the rate limiter, and delay the real PRIVMSG.
    var lastActiveSent by remember { mutableLongStateOf(0L) }
    LaunchedEffect(field.text) {
        if (field.text.isBlank()) {
            onTyping(TypingState.DONE)
            lastActiveSent = 0L
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        if (now - lastActiveSent > 3_000) {
            onTyping(TypingState.ACTIVE)
            lastActiveSent = now
        }
        delay(6_000)
        onTyping(TypingState.PAUSED)
        lastActiveSent = 0L
    }

    // Long-press gesture (used by both the input field wrapper and the Send
    // button). For Send, we wait for the finger to lift before opening the menu
    // — opening it while the finger is still down causes the up-event to land
    // outside the popup and immediately dismiss it. For the input wrapper, we
    // use the Initial pointer pass and never consume events so the text field
    // still receives all taps for cursor placement and selection.
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = onFieldChange,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Tab -> { onTab(); true }
                            Key.Enter, Key.NumPadEnter -> {
                                if (sendOnEnter && !ev.isShiftPressed && !ev.isCtrlPressed) {
                                    onSend(); true
                                } else false
                            }
                            else -> false
                        }
                    },
                placeholder = { Text("Message") },
            singleLine = false,
            maxLines = 4,
            // For soft keyboards, onPreviewKeyEvent doesn't see Enter — IME
            // events come through KeyboardActions instead. When sendOnEnter is
            // on, advertise ImeAction.Send so the soft keyboard's "Enter" key
            // becomes a Send button and triggers onSend.
            keyboardOptions = if (sendOnEnter)
                androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                )
            else androidx.compose.foundation.text.KeyboardOptions.Default,
            keyboardActions = if (sendOnEnter)
                KeyboardActions(onSend = { onSend() })
            else KeyboardActions(),
            textStyle = chatStyle,
            visualTransformation = IrcControlVisualTransformation,
            leadingIcon = {
                IconButton(onClick = { formatMenuOpen = true }) {
                    Icon(
                        Icons.Default.FormatColorText,
                        contentDescription = "Format",
                    )
                }
            },
        )
        Box {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onSend() },
                            onLongPress = { formatMenuOpen = true },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
            DropdownMenu(
                expanded = formatMenuOpen,
                onDismissRequest = { formatMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Bold") },
                    onClick = {
                        onInsert(com.pocketirc.app.irc.MircColors.BOLD)
                        formatMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Italic") },
                    onClick = {
                        onInsert(com.pocketirc.app.irc.MircColors.ITALIC)
                        formatMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Underline") },
                    onClick = {
                        onInsert(com.pocketirc.app.irc.MircColors.UNDERLINE)
                        formatMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Color…") },
                    onClick = {
                        formatMenuOpen = false
                        colorPickerOpen = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Reset") },
                    onClick = {
                        onInsert(com.pocketirc.app.irc.MircColors.RESET)
                        formatMenuOpen = false
                    },
                )
            }
        }
    }
}
