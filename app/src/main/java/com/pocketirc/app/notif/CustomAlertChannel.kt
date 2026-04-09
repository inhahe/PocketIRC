package com.pocketirc.app.notif

import kotlinx.serialization.Serializable

/**
 * A user-defined notification channel for /alert and /on -c. Created via the
 * Settings UI; persisted as a JSON list in DataStore. Once the underlying
 * Android NotificationChannel is created, its sound, importance, vibration,
 * lights, and badge are frozen — only the user can change them after that
 * point, via Android system settings.
 */
@Serializable
data class CustomAlertChannel(
    /** Short user-facing name. Used as the value of /alert -c <name>. */
    val name: String,
    /** Stable Android NotificationChannel id. Generated at creation time. */
    val channelId: String,
    /** "silent" | "quiet" | "normal" | "loud" — determines the importance. */
    val importance: String,
    /** Notification sound URI ("" = system default for the importance level). */
    val sound: String,
    val vibration: Boolean,
    val lights: Boolean,
    val badge: Boolean,
)
