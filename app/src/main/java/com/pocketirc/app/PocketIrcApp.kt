package com.pocketirc.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PocketIrcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install the fatal-crash handler before anything else can throw.
        com.pocketirc.app.error.CrashFile.install(
            this,
            appVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown"),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    getString(R.string.notif_channel_service),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    // The persistent foreground notification isn't an alert
                    // and shouldn't put a dot on the launcher icon.
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MENTIONS,
                    getString(R.string.notif_channel_mentions),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    // Explicit so the intent is documented. HIGH already
                    // implies vibration on most devices, but stating it makes
                    // it survive future Android-default changes.
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_NOTIFY,
                    getString(R.string.notif_channel_notify),
                    // Notify-list events (alice came online / bob went offline)
                    // are background-awareness, not interruptions. A user
                    // watching 5 nicks easily sees 20+/day; the LOW level
                    // means they appear in the shade silently and the user
                    // catches up at their leisure.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                }
            )
            // Four user-alert channels covering the useful range of "how
            // intrusive should this be." Each is named with a short, plain
            // word the user can pick from /alert and from the settings
            // dropdown. Users can override sound, vibration, lights, and
            // badge per channel from Android Settings → Apps → Pocket IRC →
            // Notifications at any time.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_USER_SILENT,
                    "Pocket IRC alerts — silent",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_USER_QUIET,
                    "Pocket IRC alerts — quiet",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_USER_NORMAL,
                    "Pocket IRC alerts — normal",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setShowBadge(true)
                    enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_USER_LOUD,
                    "Pocket IRC alerts — loud",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setShowBadge(true)
                    enableVibration(true)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "irc_service"
        const val CHANNEL_MENTIONS = "irc_mentions"
        const val CHANNEL_NOTIFY = "irc_notify"

        /** "silent" — IMPORTANCE_MIN, no sound, no badge, no vibration. */
        const val CHANNEL_USER_SILENT = "irc_user_silent"
        /** "quiet" — IMPORTANCE_LOW, in shade, no sound, no badge. */
        const val CHANNEL_USER_QUIET = "irc_user_quiet"
        /** "normal" — IMPORTANCE_DEFAULT, sound, badge, no vibration. The default. */
        const val CHANNEL_USER_NORMAL = "irc_user_normal"
        /** "loud" — IMPORTANCE_HIGH, sound, vibration, badge, heads-up, screen-wake. */
        const val CHANNEL_USER_LOUD = "irc_user_loud"

        /** Short user-facing names mapped to channel ids. */
        val USER_CHANNEL_NAMES: Map<String, String> = linkedMapOf(
            "silent" to CHANNEL_USER_SILENT,
            "quiet" to CHANNEL_USER_QUIET,
            "normal" to CHANNEL_USER_NORMAL,
            "loud" to CHANNEL_USER_LOUD,
        )
    }
}
