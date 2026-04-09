package com.pocketirc.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Receives the inline reply from a mention notification's RemoteInput action and
 * forwards it to the running [IrcService] as a started-service intent. The service
 * picks the message up in [IrcService.onStartCommand] and sends it.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: return
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        val forward = Intent(context, IrcService::class.java).apply {
            action = IrcService.ACTION_REPLY
            putExtra(IrcService.EXTRA_SERVER_ID, serverId)
            putExtra(IrcService.EXTRA_TARGET, target)
            putExtra(IrcService.EXTRA_TEXT, text)
            putExtra(IrcService.EXTRA_NOTIF_ID, notifId)
        }
        context.startService(forward)
    }

    companion object {
        const val KEY_TEXT = "pocketirc.reply.text"
        const val EXTRA_SERVER_ID = "pocketirc.reply.server"
        const val EXTRA_TARGET = "pocketirc.reply.target"
        const val EXTRA_NOTIF_ID = "pocketirc.reply.notif"
    }
}
