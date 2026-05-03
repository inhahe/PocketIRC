package com.pocketirc.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.app.Person
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pocketirc.app.PocketIrcApp
import com.pocketirc.app.R
import com.pocketirc.app.data.AppSettings
import com.pocketirc.app.data.ChatLineRepository
import com.pocketirc.app.data.ServerRepository
import com.pocketirc.app.data.SettingsRepository
import com.pocketirc.app.data.db.PocketIrcDatabase
import com.pocketirc.app.irc.ConnectionManager
import com.pocketirc.app.irc.IrcEvent
import com.pocketirc.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

/**
 * Foreground service that owns [ConnectionManager]. Restores all saved servers on
 * startup, posts mention notifications, and keeps a persistent ongoing notification
 * so Android does not kill our IRC sockets.
 */
class IrcService : LifecycleService() {

    lateinit var manager: ConnectionManager
        private set
    private lateinit var repo: ServerRepository
    private lateinit var history: ChatLineRepository
    private lateinit var settingsRepo: SettingsRepository

    @Volatile private var currentSettings: AppSettings = AppSettings()

    /** Serializes ConnectionManager-driven writes to the server-list DataStore. */
    private val configPersistMutex = kotlinx.coroutines.sync.Mutex()

    /** Optional listener invoked when our own nick joins a channel. */
    var selfJoinListener: ((serverId: String, channel: String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        val service: IrcService get() = this@IrcService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = ServerRepository(applicationContext)
        history = ChatLineRepository(PocketIrcDatabase.get(applicationContext).chatLines())
        settingsRepo = SettingsRepository(applicationContext)
        manager = ConnectionManager(history)
        try {
            startForeground(NOTIF_ID, buildOngoingNotification())
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when
            // the system restarts us from background (START_STICKY). Nothing we
            // can do — the service will run without a foreground notification
            // until the user opens the app, at which point MainActivity will
            // re-issue startForegroundService which has foreground privilege.
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                e.javaClass.simpleName == "ForegroundServiceStartNotAllowedException") {
                android.util.Log.w("PocketIRC",
                    "Could not start foreground — running in background", e)
            } else throw e
        }

        // Mirror current settings into a volatile field so notification posting
        // can read them synchronously without suspending. Also push the
        // bouncer-replay notification threshold into the manager whenever it
        // changes.
        lifecycleScope.launch {
            settingsRepo.settings.collect { s ->
                currentSettings = s
                manager.replayNotificationThreshold = s.replayNotificationThreshold
                manager.ctcpVersion = s.ctcpVersion
                manager.ctcpTime = s.ctcpTime
                manager.ctcpFinger = s.ctcpFinger
                manager.ctcpUserinfo = s.ctcpUserinfo
                manager.ctcpSource = s.ctcpSource
                manager.defaultNicks = s.defaultNicksCsv
                    .split(Regex("[\\s,]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                manager.defaultRealName = s.defaultRealName
                manager.defaultUserName = s.defaultUserName
                manager.autoAddJoinedChannels = s.autoAddJoinedChannels
            }
        }

        // Wire the autojoin persister: when ConnectionManager mutates a
        // connection's autoJoin list in place (because of /join /part with
        // auto-add on, or via the per-channel toggle), write the updated
        // ServerConfig back to DataStore — but only if the network is
        // already in the persisted list. Ephemeral /server connections
        // shouldn't get silently promoted.
        //
        // The persister receives just a serverId (not a captured snapshot)
        // and reads the live conn.config inside the coroutine, so back-to-
        // back mutations don't risk persisting stale snapshots out of
        // order. A Mutex serializes writes to prevent the read-modify-
        // write race on the DataStore preference.
        manager.configPersister = { sid ->
            lifecycleScope.launch {
                configPersistMutex.withLock {
                    val live = manager.configFor(sid) ?: return@withLock
                    val saved = repo.servers.firstOrNull() ?: return@withLock
                    if (saved.any { it.id == live.id }) {
                        repo.upsert(live, saved)
                    }
                }
            }
        }

        // Wire the /on hook executor so engine-fired actions can run as
        // slash commands against the originating server's (status) buffer.
        manager.onHookExecutor = { serverId, command, ctxTarget ->
            runHookCommand(serverId, command, ctxTarget)
        }
        manager.onHookNotifier = { title, body, channelName ->
            postUserNotification(title, body, channelName)
        }

        // Startup sequence — runs sequentially in a single coroutine so that
        // /on hooks are registered, custom channels exist, and any /server
        // lines in the startup script have completed BEFORE the autoconnect
        // loop starts. This eliminates the race where an autoconnected
        // server's CONNECT event fires before the script's /on hook is in
        // the registry, and prevents script /server lines from clobbering
        // saved configs that the autoconnect loop is about to read.
        lifecycleScope.launch {
            val s = settingsRepo.settings.first()
            manager.onHooks.load(s.onHooksJson)
            // Recreate the user's persisted custom alert channels (idempotent
            // — if they already exist on the OS side, this is a no-op).
            if (s.customAlertChannelsJson.isNotBlank()) {
                runCatching {
                    val list = kotlinx.serialization.json.Json
                        .decodeFromString<List<com.pocketirc.app.notif.CustomAlertChannel>>(
                            s.customAlertChannelsJson
                        )
                    for (ch in list) com.pocketirc.app.notif.CustomChannelsManager.create(this@IrcService, ch)
                }
            }
            // Run startup script lines BEFORE autoconnect.
            for (raw in s.startupScript.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                runHookCommand(serverId = "", command = line)
            }
            // Now auto-connect every saved server. Re-read repo.servers in
            // case the script just persisted a new entry via /server -p.
            repo.servers.first().forEach { cfg ->
                manager.store.hydrate(cfg.id, "(status)")
                manager.add(cfg)
            }
        }
        // Surface mentions/PMs as high-importance notifications.
        lifecycleScope.launch {
            manager.mentions.collect { postMentionNotification(it) }
        }
        // Notify-list state changes — separate (default-importance) notification channel.
        lifecycleScope.launch {
            manager.notifyEvents.collect { postNotifyStateNotification(it) }
        }
        // Forward self-join events so the VM can auto-select the new buffer.
        lifecycleScope.launch {
            manager.selfJoins.collect { (sid, ch) -> selfJoinListener?.invoke(sid, ch) }
        }
        // Periodic Room trim — keep each buffer's last 1000 lines.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60L * 60L * 1000L) // every hour
                runCatching { history.trimAll(keep = 1000) }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_REPLY) handleReply(intent)
        return START_STICKY
    }

    /**
     * Minimal command runner used by /on hook actions and the startup script.
     * Supports a useful subset of slash commands. Unknown ones are sent verbatim
     * via /raw if a server is selected, or logged as errors otherwise.
     */
    private fun runHookCommand(serverId: String, command: String, contextTarget: String = "") {
        val line = command.trim()
        if (line.isEmpty()) return
        val statusBuf = if (serverId.isNotEmpty()) "$serverId::(status)" else null
        fun status(text: String) {
            if (serverId.isNotEmpty()) manager.store.appendSystemTo(serverId, statusBuf, text)
        }
        // Plain text → no-op (we have no implicit target).
        if (!line.startsWith("/")) {
            status("on/script: ignored plain text '$line'")
            return
        }
        when (val p = com.pocketirc.app.irc.SlashCommand.parse(line)) {
            is com.pocketirc.app.irc.ParsedInput.Msg ->
                manager.sendMessage(serverId, p.target, p.text)
            is com.pocketirc.app.irc.ParsedInput.NoticeCmd ->
                manager.sendNotice(serverId, p.target, p.text)
            is com.pocketirc.app.irc.ParsedInput.Action ->
                if (serverId.isNotEmpty()) manager.sendAction(serverId, "(status)", p.text)
            is com.pocketirc.app.irc.ParsedInput.Raw ->
                manager.sendRaw(serverId, p.line)
            is com.pocketirc.app.irc.ParsedInput.Echo -> status(p.text)
            is com.pocketirc.app.irc.ParsedInput.Say -> {
                // From a hook, /say sends to the channel/nick the originating
                // event was bound to. From the startup script (no context), it
                // falls back to a status echo so it isn't silently lost.
                if (serverId.isNotEmpty() && contextTarget.isNotEmpty()) {
                    manager.sendMessage(serverId, contextTarget, p.text)
                } else {
                    status("/say: ${p.text}")
                }
            }
            is com.pocketirc.app.irc.ParsedInput.Join ->
                manager.joinChannel(serverId, p.channel)
            is com.pocketirc.app.irc.ParsedInput.Part ->
                p.channel?.let { manager.partChannel(serverId, it, p.reason) }
            is com.pocketirc.app.irc.ParsedInput.Mode ->
                manager.mode(serverId, p.target, p.args)
            is com.pocketirc.app.irc.ParsedInput.Nick ->
                manager.changeNick(serverId, p.nick)
            is com.pocketirc.app.irc.ParsedInput.Quit ->
                manager.remove(serverId, p.reason ?: "Pocket IRC")
            is com.pocketirc.app.irc.ParsedInput.Error -> status("on/script error: ${p.message}")
            else -> {
                // Fall back to raw for the long tail (motd, who, etc.)
                if (serverId.isNotEmpty()) manager.sendRaw(serverId, line.removePrefix("/"))
                else status("on/script: '$line' needs an active server")
            }
        }
    }

    /** Auto-incrementing id for ad-hoc /alert and /on notifications. */
    private val nextNotifId = java.util.concurrent.atomic.AtomicInteger(50_000)

    /**
     * Resolves a notification channel id from a user-typed [channelName].
     * Built-in names (silent/quiet/normal/loud) map to the static channels.
     * Custom names are looked up against the latest persisted list. Falls
     * back to CHANNEL_USER_NORMAL when nothing matches.
     */
    private fun resolveChannel(channelName: String?): String {
        val effective = channelName ?: currentSettings.defaultAlertChannel
        val customs = if (currentSettings.customAlertChannelsJson.isBlank()) emptyList()
        else runCatching {
            kotlinx.serialization.json.Json
                .decodeFromString<List<com.pocketirc.app.notif.CustomAlertChannel>>(
                    currentSettings.customAlertChannelsJson
                )
        }.getOrDefault(emptyList())
        return com.pocketirc.app.notif.CustomChannelsManager.resolve(effective, customs)
            ?: PocketIrcApp.CHANNEL_USER_NORMAL
    }

    /** Post a system notification triggered by /alert or an /on -d hook. */
    private fun postUserNotification(
        title: String,
        body: String,
        channelName: String?,
    ) {
        val channelId = resolveChannel(channelName)
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        try { nm.notify(nextNotifId.incrementAndGet(), notif) } catch (_: SecurityException) {}
    }

    private fun handleReply(intent: Intent) {
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: return
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        manager.sendMessage(serverId, target, text)
        // Cancel the original notification so the user doesn't see a stale "tap to reply".
        if (notifId != -1) {
            androidx.core.app.NotificationManagerCompat.from(this).cancel(notifId)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, PocketIrcApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun postMentionNotification(msg: IrcEvent.Message) {
        // Categorize and apply the right setting toggle.
        val isPm = !msg.target.startsWith("#") && !msg.target.startsWith("&")
        // Heuristic for server-originated notices: senders that contain a dot are
        // almost certainly a server name like "irc.libera.chat", not a user nick.
        val senderLooksLikeServer = msg.sender.contains('.')
        val allowed = when {
            msg.isNotice && senderLooksLikeServer -> false
            msg.isNotice -> currentSettings.noticeNotifications
            isPm -> currentSettings.privmsgNotifications
            else -> currentSettings.mentionNotifications  // channel mention
        }
        if (!allowed) return

        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val notifId = msg.id().hashCode()
        val tap = PendingIntent.getActivity(
            this, notifId,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Decide reply target: a PM goes back to the sender; a channel mention
        // replies into the channel.
        val replyTarget = if (isPm) msg.sender else msg.target

        val remoteInput = RemoteInput.Builder(ReplyReceiver.KEY_TEXT)
            .setLabel("Reply to $replyTarget")
            .build()

        val replyIntent = Intent(this, ReplyReceiver::class.java).apply {
            putExtra(ReplyReceiver.EXTRA_SERVER_ID, msg.serverId)
            putExtra(ReplyReceiver.EXTRA_TARGET, replyTarget)
            putExtra(ReplyReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val replyPi = PendingIntent.getBroadcast(
            this, notifId, replyIntent,
            // FLAG_MUTABLE is required so RemoteInput can inject the user's text.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPi,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val builder = NotificationCompat.Builder(this, PocketIrcApp.CHANNEL_MENTIONS)
            .setContentTitle(if (isPm) msg.sender else "${msg.sender} in ${msg.target}")
            .setContentText(msg.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(tap)
            .setAutoCancel(true)
        if (currentSettings.notificationReplyAction) {
            builder.addAction(replyAction)
        }
        val notif = builder.build()
        try {
            nm.notify(notifId, notif)
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
    }

    private fun IrcEvent.Message.id() = "$serverId|$target|$timestampMs|$sender"

    private fun postNotifyStateNotification(ev: IrcEvent.NotifyState) {
        if (!currentSettings.notifyListNotifications) return
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val title = if (ev.online) "${ev.nick} is online" else "${ev.nick} is offline"
        val tap = PendingIntent.getActivity(
            this, ev.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, PocketIrcApp.CHANNEL_NOTIFY)
            .setContentTitle(title)
            .setContentText(ev.serverId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(("notify|" + ev.serverId + "|" + ev.nick).hashCode(), notif)
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
    }

    override fun onDestroy() {
        manager.shutdownAll()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: IrcService? = null
        const val NOTIF_ID = 1001
        const val ACTION_REPLY = "com.pocketirc.app.action.REPLY"
        const val EXTRA_SERVER_ID = "com.pocketirc.app.extra.SERVER_ID"
        const val EXTRA_TARGET = "com.pocketirc.app.extra.TARGET"
        const val EXTRA_TEXT = "com.pocketirc.app.extra.TEXT"
        const val EXTRA_NOTIF_ID = "com.pocketirc.app.extra.NOTIF_ID"
    }
}
