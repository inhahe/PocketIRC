package com.pocketirc.app.data

import com.pocketirc.app.data.db.ChatLineDao
import com.pocketirc.app.data.db.ChatLineEntity
import com.pocketirc.app.model.ChatLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridge between the in-memory [com.pocketirc.app.irc.BufferStore] and Room.
 * All writes are fire-and-forget on a background scope; reads are suspending and
 * called once per buffer when it's first activated.
 */
class ChatLineRepository(private val dao: ChatLineDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun persist(serverId: String, bufferName: String, line: ChatLine) {
        // Skip ephemeral system noise to keep the DB lean. Connection-status spam
        // and join/part chatter aren't valuable to scroll back through.
        if (line.kind == ChatLine.Kind.SYSTEM && line.sender == null) return
        scope.launch {
            dao.insert(
                ChatLineEntity(
                    serverId = serverId,
                    bufferName = bufferName,
                    timestamp = line.timestamp,
                    sender = line.sender,
                    text = line.text,
                    kind = line.kind.name,
                )
            )
        }
    }

    suspend fun loadRecent(serverId: String, bufferName: String, limit: Int = 200): List<ChatLine> =
        dao.recent(serverId, bufferName, limit).map { e ->
            ChatLine(
                id = e.id,
                timestamp = e.timestamp,
                sender = e.sender,
                text = e.text,
                kind = runCatching { ChatLine.Kind.valueOf(e.kind) }.getOrDefault(ChatLine.Kind.MESSAGE),
            )
        }

    fun trimAsync(serverId: String, bufferName: String, keep: Int = 1000) {
        scope.launch { dao.trim(serverId, bufferName, keep) }
    }

    /** Trim every known buffer down to [keep] most recent lines. */
    suspend fun trimAll(keep: Int = 1000) {
        dao.knownBufferIds().forEach { id ->
            val parts = id.split("::", limit = 2)
            if (parts.size == 2) dao.trim(parts[0], parts[1], keep)
        }
    }

    fun deleteServer(serverId: String) {
        scope.launch { dao.deleteServer(serverId) }
    }

    fun deleteBuffer(serverId: String, bufferName: String) {
        scope.launch { dao.deleteBuffer(serverId, bufferName) }
    }
}
