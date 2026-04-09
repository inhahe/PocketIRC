package com.pocketirc.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChatLineDao {

    @Insert
    suspend fun insert(line: ChatLineEntity): Long

    /**
     * Returns the last [limit] lines for a buffer in chronological order
     * (oldest first), suitable for hydrating an in-memory ring buffer.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM chat_lines
            WHERE serverId = :serverId AND bufferName = :bufferName
            ORDER BY timestamp DESC, id DESC
            LIMIT :limit
        ) ORDER BY timestamp ASC, id ASC
    """)
    suspend fun recent(serverId: String, bufferName: String, limit: Int): List<ChatLineEntity>

    @Query("""
        SELECT DISTINCT serverId || '::' || bufferName AS bufferId
        FROM chat_lines
    """)
    suspend fun knownBufferIds(): List<String>

    @Query("DELETE FROM chat_lines WHERE serverId = :serverId AND bufferName = :bufferName")
    suspend fun deleteBuffer(serverId: String, bufferName: String)

    @Query("DELETE FROM chat_lines WHERE serverId = :serverId")
    suspend fun deleteServer(serverId: String)

    /** House-keeping: trim a buffer to the most recent [keep] rows. */
    @Query("""
        DELETE FROM chat_lines
        WHERE serverId = :serverId AND bufferName = :bufferName
        AND id NOT IN (
            SELECT id FROM chat_lines
            WHERE serverId = :serverId AND bufferName = :bufferName
            ORDER BY timestamp DESC, id DESC
            LIMIT :keep
        )
    """)
    suspend fun trim(serverId: String, bufferName: String, keep: Int)
}
