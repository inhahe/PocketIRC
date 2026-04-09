package com.pocketirc.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "pocketirc_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val colorNicks: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontSizeSp: Int = 17,
    val timestampFormat: String = "HH:mm",
    val useMonospace: Boolean = true,
    val defaultQuitMessage: String = "Pocket IRC",
    val mentionNotifications: Boolean = true,
    val privmsgNotifications: Boolean = true,
    val noticeNotifications: Boolean = true,
    val notificationReplyAction: Boolean = true,
    val notifyListNotifications: Boolean = true,
    val sendOnEnter: Boolean = true,
    /** JSON-serialized list of OnHook entries. Empty string = no hooks. */
    val onHooksJson: String = "",
    /** Multi-line slash-command script run at service startup. */
    val startupScript: String = "",
    /** Default channel for /alert when -c is not specified. Built-in or custom name. */
    val defaultAlertChannel: String = "normal",
    /** JSON-serialized list of CustomAlertChannel entries. Empty string = none. */
    val customAlertChannelsJson: String = "",
    /**
     * On reconnecting to a bouncer (or otherwise receiving a batch of replayed
     * messages), only fire mention notifications if the burst contains at most
     * this many. 0 = never fire for replays. Higher = always fire even for big
     * playbacks. Default 3.
     */
    val replayNotificationThreshold: Int = 3,
    /**
     * CTCP response overrides. Empty = built-in default, "off" = decline
     * the request entirely, otherwise the literal string is sent back.
     * For [ctcpTime] only, "utc" is also recognized as "send the current
     * time formatted in UTC instead of the device's local timezone" — the
     * privacy escape hatch for users who don't want to leak their tz.
     */
    val ctcpVersion: String = "",
    val ctcpTime: String = "",
    val ctcpFinger: String = "",
    val ctcpUserinfo: String = "",
    val ctcpSource: String = "",
    /**
     * Global identity defaults. A network's [com.pocketirc.app.model.ServerConfig]
     * may override any of these (its own field is non-null = override, null =
     * inherit). Empty global defaults are valid — but in that case the user
     * must supply per-network values when adding a server.
     *
     * [defaultNicksCsv] is stored as a comma-separated string for symmetry
     * with the per-network field's wire format and the /set ergonomics.
     */
    /**
     * Comma- (or whitespace-) separated list of nicknames. The first entry
     * is the primary; the rest are tried in order if the primary is taken.
     * After the list is exhausted the client appends `_` characters.
     */
    val defaultNicksCsv: String = "",
    val defaultRealName: String = "",
    val defaultUserName: String = "",
    /**
     * When true, every successful /join is automatically added to the
     * network's autojoin list (and every /part removes it). Only applies
     * to persisted networks — ephemeral `/server` connections without -p
     * are unaffected. Default off.
     */
    val autoAddJoinedChannels: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private val keyColorNicks = booleanPreferencesKey("color_nicks")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyFontSize = intPreferencesKey("font_size_sp")
    private val keyTimestampFormat = stringPreferencesKey("timestamp_format")
    private val keyMonospace = booleanPreferencesKey("use_monospace")
    private val keyQuitMessage = stringPreferencesKey("default_quit_message")
    private val keyMentionNotifs = booleanPreferencesKey("mention_notifications")
    private val keyPrivmsgNotifs = booleanPreferencesKey("privmsg_notifications")
    private val keyNoticeNotifs = booleanPreferencesKey("notice_notifications")
    private val keyNotifReply = booleanPreferencesKey("notification_reply")
    private val keyNotifyNotifs = booleanPreferencesKey("notify_list_notifications")
    private val keySendOnEnter = booleanPreferencesKey("send_on_enter")
    private val keyOnHooks = stringPreferencesKey("on_hooks_json")
    private val keyStartupScript = stringPreferencesKey("startup_script")
    private val keyDefaultAlertChannel = stringPreferencesKey("default_alert_channel")
    private val keyCustomAlertChannels = stringPreferencesKey("custom_alert_channels_json")
    private val keyReplayThreshold = intPreferencesKey("replay_notification_threshold")
    private val keyCtcpVersion = stringPreferencesKey("ctcp_version")
    private val keyCtcpTime = stringPreferencesKey("ctcp_time")
    private val keyCtcpFinger = stringPreferencesKey("ctcp_finger")
    private val keyCtcpUserinfo = stringPreferencesKey("ctcp_userinfo")
    private val keyCtcpSource = stringPreferencesKey("ctcp_source")
    private val keyDefaultNicks = stringPreferencesKey("default_nicks_csv")
    private val keyDefaultRealName = stringPreferencesKey("default_realname")
    private val keyDefaultUserName = stringPreferencesKey("default_username")
    private val keyAutoAddJoined = booleanPreferencesKey("auto_add_joined_channels")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            colorNicks = prefs[keyColorNicks] ?: false,
            themeMode = prefs[keyThemeMode]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            fontSizeSp = prefs[keyFontSize] ?: 17,
            timestampFormat = prefs[keyTimestampFormat] ?: "HH:mm",
            useMonospace = prefs[keyMonospace] ?: true,
            defaultQuitMessage = prefs[keyQuitMessage] ?: "Pocket IRC",
            mentionNotifications = prefs[keyMentionNotifs] ?: true,
            privmsgNotifications = prefs[keyPrivmsgNotifs] ?: true,
            noticeNotifications = prefs[keyNoticeNotifs] ?: true,
            notificationReplyAction = prefs[keyNotifReply] ?: true,
            notifyListNotifications = prefs[keyNotifyNotifs] ?: true,
            sendOnEnter = prefs[keySendOnEnter] ?: true,
            onHooksJson = prefs[keyOnHooks] ?: "",
            startupScript = prefs[keyStartupScript] ?: "",
            defaultAlertChannel = prefs[keyDefaultAlertChannel] ?: "normal",
            customAlertChannelsJson = prefs[keyCustomAlertChannels] ?: "",
            replayNotificationThreshold = prefs[keyReplayThreshold] ?: 3,
            ctcpVersion = prefs[keyCtcpVersion] ?: "",
            ctcpTime = prefs[keyCtcpTime] ?: "",
            ctcpFinger = prefs[keyCtcpFinger] ?: "",
            ctcpUserinfo = prefs[keyCtcpUserinfo] ?: "",
            ctcpSource = prefs[keyCtcpSource] ?: "",
            defaultNicksCsv = prefs[keyDefaultNicks] ?: "",
            defaultRealName = prefs[keyDefaultRealName] ?: "",
            defaultUserName = prefs[keyDefaultUserName] ?: "",
            autoAddJoinedChannels = prefs[keyAutoAddJoined] ?: false,
        )
    }

    suspend fun setReplayNotificationThreshold(n: Int) {
        context.settingsDataStore.edit { it[keyReplayThreshold] = n.coerceIn(0, 100) }
    }

    suspend fun setDefaultAlertChannel(name: String) {
        context.settingsDataStore.edit { it[keyDefaultAlertChannel] = name }
    }

    suspend fun setCustomAlertChannelsJson(json: String) {
        context.settingsDataStore.edit { it[keyCustomAlertChannels] = json }
    }

    suspend fun setOnHooksJson(json: String) {
        context.settingsDataStore.edit { it[keyOnHooks] = json }
    }

    suspend fun setStartupScript(script: String) {
        context.settingsDataStore.edit { it[keyStartupScript] = script }
    }

    suspend fun setColorNicks(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyColorNicks] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[keyThemeMode] = mode.name }
    }

    suspend fun setFontSize(sp: Int) {
        context.settingsDataStore.edit { it[keyFontSize] = sp.coerceIn(10, 28) }
    }

    suspend fun setTimestampFormat(format: String) {
        context.settingsDataStore.edit { it[keyTimestampFormat] = format.ifBlank { "HH:mm" } }
    }

    suspend fun setUseMonospace(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyMonospace] = enabled }
    }

    suspend fun setDefaultQuitMessage(msg: String) {
        context.settingsDataStore.edit { it[keyQuitMessage] = msg }
    }

    suspend fun setMentionNotifications(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyMentionNotifs] = enabled }
    }

    suspend fun setNotificationReplyAction(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyNotifReply] = enabled }
    }

    suspend fun setNotifyListNotifications(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyNotifyNotifs] = enabled }
    }

    suspend fun setPrivmsgNotifications(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyPrivmsgNotifs] = enabled }
    }

    suspend fun setNoticeNotifications(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyNoticeNotifs] = enabled }
    }

    suspend fun setSendOnEnter(enabled: Boolean) {
        context.settingsDataStore.edit { it[keySendOnEnter] = enabled }
    }

    suspend fun setCtcpVersion(s: String) {
        context.settingsDataStore.edit { it[keyCtcpVersion] = s }
    }

    suspend fun setCtcpTime(s: String) {
        context.settingsDataStore.edit { it[keyCtcpTime] = s }
    }

    suspend fun setCtcpFinger(s: String) {
        context.settingsDataStore.edit { it[keyCtcpFinger] = s }
    }

    suspend fun setCtcpUserinfo(s: String) {
        context.settingsDataStore.edit { it[keyCtcpUserinfo] = s }
    }

    suspend fun setCtcpSource(s: String) {
        context.settingsDataStore.edit { it[keyCtcpSource] = s }
    }

    suspend fun setDefaultNicksCsv(s: String) {
        context.settingsDataStore.edit { it[keyDefaultNicks] = s }
    }

    suspend fun setDefaultRealName(s: String) {
        context.settingsDataStore.edit { it[keyDefaultRealName] = s }
    }

    suspend fun setDefaultUserName(s: String) {
        context.settingsDataStore.edit { it[keyDefaultUserName] = s }
    }

    suspend fun setAutoAddJoinedChannels(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyAutoAddJoined] = enabled }
    }
}
