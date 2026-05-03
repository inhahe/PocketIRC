package com.pocketirc.app.irc

import com.pocketirc.app.data.ChatLineRepository
import com.pocketirc.app.model.ChatLine
import com.pocketirc.app.model.TreeNode
import com.pocketirc.app.model.TypingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory store of buffers (channels, queries, status windows) and their messages.
 * Lives inside the service so it survives Activity recreation.
 *
 * v1 keeps the last MAX_LINES per buffer in memory. Persistent history is a TODO
 * (Room database, indexed by serverId+target).
 */
class BufferStore(private val history: ChatLineRepository? = null) {

    /** Buffer ids whose history we've already attempted to hydrate from disk. */
    private val hydrated = mutableSetOf<String>()

    /** Hydrate the most recent N lines for [bufferId] from Room into the in-memory store. */
    suspend fun hydrate(serverId: String, bufferName: String) {
        val id = "$serverId::$bufferName"
        if (!hydrated.add(id)) return
        val repo = history ?: return
        val lines = repo.loadRecent(serverId, bufferName)
        if (lines.isEmpty()) return
        val current = _buffers.value
        val existing = current[id]
        val merged = if (existing == null) {
            Buffer(
                serverId = serverId,
                name = bufferName,
                kind = if (bufferName == "(status)") TreeNode.Buffer.Kind.STATUS
                       else if (bufferName.startsWith("#") || bufferName.startsWith("&"))
                           TreeNode.Buffer.Kind.CHANNEL
                       else TreeNode.Buffer.Kind.QUERY,
                lines = lines,
            )
        } else {
            // Splice persisted lines in front of any in-memory lines, dedup by id.
            val combined = (lines + existing.lines).distinctBy { it.id }.takeLast(MAX_LINES)
            existing.copy(lines = combined)
        }
        _buffers.value = current + (id to merged)
    }


    data class Buffer(
        val serverId: String,
        val name: String,            // channel name, query nick, or "(status)"
        val kind: TreeNode.Buffer.Kind,
        val lines: List<ChatLine> = emptyList(),
        val unread: Int = 0,
        val highlighted: Boolean = false,
        val typingNicks: Map<String, Long> = emptyMap(),  // nick -> expiresAtMs
        /** Last line id that was visible in the UI when this buffer was last read. */
        val lastReadLineId: Long = 0L,
        /** Set on activation: marks the boundary BEFORE which lines were already read. */
        val unreadMarkerLineId: Long? = null,
        /** Most recent channel topic (channel buffers only). */
        val topic: String? = null,
        /** Most-recent-first list of nicks who spoke in this buffer. Capped, LRU. */
        val recentSpeakers: List<String> = emptyList(),
    ) {
        val id: String get() = "$serverId::$name"
    }

    private val _buffers = MutableStateFlow<Map<String, Buffer>>(emptyMap())
    val buffers: StateFlow<Map<String, Buffer>> = _buffers

    private val ids = AtomicLong(0)
    @Volatile var activeBufferId: String? = null
    /** True while the UI is in the foreground. The active-buffer notification
     *  suppression only applies when the app is actually visible. */
    @Volatile var foreground: Boolean = false

    /** Returns true if the line should fire a mention notification. */
    fun ingest(event: IrcEvent, ourNick: String): Boolean {
        return when (event) {
            is IrcEvent.Connected -> {
                ensureStatus(event.serverId)
                appendSystem(event.serverId, "(status)", "Connected.")
                false
            }
            is IrcEvent.Disconnected -> {
                ensureStatus(event.serverId)
                appendSystem(event.serverId, "(status)", "Disconnected: ${event.reason ?: "unknown"}")
                false
            }
            is IrcEvent.Status -> {
                ensureStatus(event.serverId)
                appendSystem(event.serverId, "(status)", event.text)
                false
            }
            is IrcEvent.Joined -> {
                ensureBuffer(event.serverId, event.channel, TreeNode.Buffer.Kind.CHANNEL)
                appendSystem(event.serverId, event.channel,
                    "${event.nick} joined", subjectNicks = listOf(event.nick))
                false
            }
            is IrcEvent.Parted -> {
                appendSystem(event.serverId, event.channel,
                    "${event.nick} left", subjectNicks = listOf(event.nick))
                false
            }
            is IrcEvent.Quit -> {
                val reason = if (event.reason.isNullOrBlank()) "" else " (${event.reason})"
                // Append a "quit" line to every buffer where this nick is currently a member.
                _buffers.value.values
                    .filter { it.serverId == event.serverId && it.kind == TreeNode.Buffer.Kind.CHANNEL }
                    .forEach { buf ->
                        appendSystem(event.serverId, buf.name,
                            "${event.nick} quit$reason", subjectNicks = listOf(event.nick))
                    }
                false
            }
            is IrcEvent.NickChanged -> {
                _buffers.value.values
                    .filter { it.serverId == event.serverId && it.kind == TreeNode.Buffer.Kind.CHANNEL }
                    .forEach { buf ->
                        appendSystem(event.serverId, buf.name,
                            "${event.oldNick} is now known as ${event.newNick}",
                            subjectNicks = listOf(event.oldNick, event.newNick))
                    }
                false
            }
            is IrcEvent.Kicked -> {
                val reason = if (event.reason.isNullOrBlank()) "" else " (${event.reason})"
                appendSystem(event.serverId, event.channel,
                    "${event.target} was kicked by ${event.by}$reason",
                    subjectNicks = listOf(event.target, event.by))
                false
            }
            is IrcEvent.TopicChanged -> {
                ensureBuffer(event.serverId, event.channel, TreeNode.Buffer.Kind.CHANNEL)
                update(event.serverId, event.channel) { it.copy(topic = event.topic) }
                appendSystem(
                    event.serverId, event.channel,
                    if (event.setter != null) "Topic set by ${event.setter}: ${event.topic}"
                    else "Topic: ${event.topic}",
                    subjectNicks = listOfNotNull(event.setter),
                )
                false
            }
            is IrcEvent.Message -> {
                // Suppress server-echoed copies of our own messages (echo-message
                // CAP, ZNC playback, multi-attach bouncers). Local echo handles
                // displaying our own outgoing messages.
                if (event.sender.equals(ourNick, ignoreCase = true)) return false
                val isPm = !event.target.startsWith("#") && !event.target.startsWith("&")
                val bufferName = if (isPm) event.sender else event.target
                val kind = if (isPm) TreeNode.Buffer.Kind.QUERY else TreeNode.Buffer.Kind.CHANNEL
                ensureBuffer(event.serverId, bufferName, kind)
                val highlight = isPm || event.text.contains(ourNick, ignoreCase = true)
                appendLine(
                    event.serverId, bufferName,
                    ChatLine(
                        id = ids.incrementAndGet(),
                        timestamp = event.timestampMs,
                        sender = event.sender,
                        text = event.text,
                        kind = if (event.isNotice) ChatLine.Kind.NOTICE
                               else if (event.isAction) ChatLine.Kind.ACTION
                               else ChatLine.Kind.MESSAGE,
                    ),
                    highlight = highlight,
                )
                // Clear typing for this nick on this buffer
                update(event.serverId, bufferName) { b ->
                    b.copy(typingNicks = b.typingNicks - event.sender)
                }
                // Return true if this would fire a mention notification.
                // ConnectionManager filters further: replay messages
                // (event.fromHistory) get routed through a debounced batch
                // window that suppresses or releases them depending on the
                // configured threshold.
                highlight && !(foreground && activeBufferId == "${event.serverId}::$bufferName")
            }
            is IrcEvent.Typing -> {
                val isPm = !event.target.startsWith("#") && !event.target.startsWith("&")
                val bufferName = if (isPm) event.sender else event.target
                ensureBuffer(event.serverId, bufferName,
                    if (isPm) TreeNode.Buffer.Kind.QUERY else TreeNode.Buffer.Kind.CHANNEL)
                update(event.serverId, bufferName) { b ->
                    val next = when (event.state) {
                        TypingState.ACTIVE -> b.typingNicks + (event.sender to event.receivedAtMs + 6_000)
                        TypingState.PAUSED, TypingState.DONE -> b.typingNicks - event.sender
                    }
                    b.copy(typingNicks = next)
                }
                false
            }
            is IrcEvent.Raw -> false
            is IrcEvent.Numeric -> false  // routed in ConnectionManager, never reaches here
            is IrcEvent.CtcpQuery -> false // handled in ConnectionManager.handleCtcpQuery, no buffer side effect
            is IrcEvent.NotifyState -> {
                ensureStatus(event.serverId)
                val verb = if (event.online) "is now online" else "is now offline"
                appendSystem(event.serverId, "(status)", "${event.nick} $verb",
                    subjectNicks = listOf(event.nick))
                false
            }
        }
    }

    /** Direct topic setter so the numeric handler in ConnectionManager can update without an event. */
    fun setTopic(serverId: String, channel: String, topic: String) {
        ensureBuffer(serverId, channel, TreeNode.Buffer.Kind.CHANNEL)
        update(serverId, channel) { it.copy(topic = topic) }
    }

    /** Public buffer creator — used by /query, double-click-nick, etc. Idempotent. */
    fun openBuffer(serverId: String, name: String, kind: TreeNode.Buffer.Kind) {
        ensureBuffer(serverId, name, kind)
    }


    /** Append a self-authored chat line locally (for echo, before any server roundtrip). */
    fun appendOwnMessage(serverId: String, target: String, ourNick: String, text: String, action: Boolean) {
        val isPm = !target.startsWith("#") && !target.startsWith("&")
        val kind = if (isPm) TreeNode.Buffer.Kind.QUERY else TreeNode.Buffer.Kind.CHANNEL
        ensureBuffer(serverId, target, kind)
        appendLine(
            serverId, target,
            ChatLine(
                id = ids.incrementAndGet(),
                timestamp = System.currentTimeMillis(),
                sender = ourNick,
                text = text,
                kind = if (action) ChatLine.Kind.ACTION else ChatLine.Kind.MESSAGE,
            ),
            highlight = false,
        )
    }

    /** Append a system line to a specific buffer id, or to the server's (status) if missing. */
    fun appendSystemTo(serverId: String, bufferId: String?, text: String) {
        val target = bufferId?.let { id ->
            _buffers.value[id]?.let { id }
        } ?: "$serverId::(status)".also { ensureStatus(serverId) }
        val (sid, name) = target.split("::", limit = 2).let { it[0] to it[1] }
        appendSystem(sid, name, text)
    }

    fun removeBuffer(bufferId: String) {
        _buffers.value = _buffers.value - bufferId
    }

    /** Drop all in-memory lines from [bufferId]. (Persisted history is left alone.) */
    fun clearLines(bufferId: String) {
        val cur = _buffers.value
        val b = cur[bufferId] ?: return
        _buffers.value = cur + (bufferId to b.copy(
            lines = emptyList(),
            unreadMarkerLineId = null,
            unread = 0,
            highlighted = false,
        ))
    }

    fun allBufferIdsForServer(serverId: String): List<String> =
        _buffers.value.values.filter { it.serverId == serverId }.map { it.id }

    fun markRead(bufferId: String) {
        _buffers.value = _buffers.value.mapValues { (id, b) ->
            if (id != bufferId) b
            else {
                val latest = b.lines.lastOrNull()?.id ?: 0L
                // The marker captures where reading WAS — we'll show a divider
                // before the first line whose id > marker. If nothing is unread
                // (latest == lastReadLineId), null out the marker.
                val newMarker = if (latest > b.lastReadLineId) b.lastReadLineId else null
                b.copy(
                    unread = 0,
                    highlighted = false,
                    unreadMarkerLineId = newMarker,
                    lastReadLineId = latest,
                )
            }
        }
    }

    /** Called when the user navigates away from a buffer; clears its in-buffer divider. */
    fun clearUnreadMarker(bufferId: String) {
        _buffers.value = _buffers.value.mapValues { (id, b) ->
            if (id == bufferId) b.copy(unreadMarkerLineId = null) else b
        }
    }

    private fun ensureStatus(serverId: String) =
        ensureBuffer(serverId, "(status)", TreeNode.Buffer.Kind.STATUS)

    private fun ensureBuffer(serverId: String, name: String, kind: TreeNode.Buffer.Kind) {
        val id = "$serverId::$name"
        if (_buffers.value.containsKey(id)) return
        _buffers.value = _buffers.value + (id to Buffer(serverId, name, kind))
    }

    private fun appendSystem(
        serverId: String,
        bufferName: String,
        text: String,
        subjectNicks: List<String> = emptyList(),
    ) {
        val id = "$serverId::$bufferName"
        if (!_buffers.value.containsKey(id)) {
            ensureBuffer(serverId, bufferName, TreeNode.Buffer.Kind.CHANNEL)
        }
        appendLine(
            serverId, bufferName,
            ChatLine(
                id = ids.incrementAndGet(),
                timestamp = System.currentTimeMillis(),
                sender = null,
                text = text,
                kind = ChatLine.Kind.SYSTEM,
                subjectNicks = subjectNicks,
            ),
            highlight = false,
        )
    }

    private fun appendLine(
        serverId: String, name: String, line: ChatLine, highlight: Boolean,
    ) {
        update(serverId, name) { b ->
            val active = foreground && activeBufferId == b.id
            // LRU bump for recent speakers — only count real chatter, not joins/system noise.
            val newSpeakers = if (line.sender != null &&
                line.kind in setOf(ChatLine.Kind.MESSAGE, ChatLine.Kind.ACTION)) {
                (listOf(line.sender) + b.recentSpeakers.filter { it != line.sender }).take(20)
            } else b.recentSpeakers
            b.copy(
                lines = (b.lines + line).takeLast(MAX_LINES),
                unread = if (active) 0 else b.unread + 1,
                highlighted = b.highlighted || (highlight && !active),
                lastReadLineId = if (active) line.id else b.lastReadLineId,
                recentSpeakers = newSpeakers,
            )
        }
        history?.persist(serverId, name, line)
    }

    private inline fun update(serverId: String, name: String, transform: (Buffer) -> Buffer) {
        val id = "$serverId::$name"
        val current = _buffers.value
        val existing = current[id] ?: return
        _buffers.value = current + (id to transform(existing))
    }

    companion object { const val MAX_LINES = 1000 }
}
