package com.pocketirc.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted chat line. Indexed by (serverId, bufferName, timestamp DESC) so the
 * "last N lines for buffer X" query is cheap.
 */
@Entity(
    tableName = "chat_lines",
    indices = [Index(value = ["serverId", "bufferName", "timestamp"])],
)
data class ChatLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val bufferName: String,
    val timestamp: Long,
    val sender: String?,
    val text: String,
    val kind: String,        // ChatLine.Kind name
)
