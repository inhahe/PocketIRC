package com.pocketirc.app.ui

import android.content.Context
import android.content.Intent
import com.pocketirc.app.irc.BufferStore
import com.pocketirc.app.model.ChatLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BufferExport {

    /**
     * Format an in-memory buffer as plain text for export.
     * One line per [ChatLine] with full ISO timestamp + sender + message.
     */
    fun render(buffer: BufferStore.Buffer): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("# ").append(buffer.serverId).append(" ").append(buffer.name).append('\n')
        if (!buffer.topic.isNullOrBlank()) {
            sb.append("# Topic: ").append(buffer.topic).append('\n')
        }
        sb.append('\n')
        for (line in buffer.lines) {
            sb.append(fmt.format(Date(line.timestamp))).append(' ')
            when (line.kind) {
                ChatLine.Kind.MESSAGE -> sb.append('<').append(line.sender).append("> ").append(line.text)
                ChatLine.Kind.ACTION -> sb.append("* ").append(line.sender).append(' ').append(line.text)
                ChatLine.Kind.NOTICE -> sb.append('-').append(line.sender).append("- ").append(line.text)
                else -> sb.append("* ").append(line.text)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Launch a share-sheet with the rendered buffer text in [Intent.EXTRA_TEXT].
     * Recipients see plain text — works with messaging, notes, mail, text files,
     * and most other share targets without needing storage permissions.
     */
    fun share(context: Context, buffer: BufferStore.Buffer) {
        val text = render(buffer)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "${buffer.name} log")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export ${buffer.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
