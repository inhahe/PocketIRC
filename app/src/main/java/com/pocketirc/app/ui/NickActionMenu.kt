package com.pocketirc.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NickActionSheet(
    nick: String,
    channelContext: String?,
    onDismiss: () -> Unit,
    onOpenQuery: () -> Unit,
    onWhois: () -> Unit,
    onWhowas: () -> Unit,
    onInviteRequest: () -> Unit,
    onCtcp: (String) -> Unit,
    onMode: (modes: String) -> Unit,
    onKick: () -> Unit,
    onKickWithReasonRequest: () -> Unit,
    onBanRequest: () -> Unit,
    onUnbanRequest: () -> Unit,
    onKickBanRequest: () -> Unit,
    onQuietRequest: () -> Unit,
    onUnquietRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Text(
                nick,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            ActionItem("Open query", Icons.Default.Chat) { onOpenQuery(); onDismiss() }
            ActionItem("WHOIS", Icons.Default.Info) { onWhois(); onDismiss() }
            ActionItem("WHOWAS", Icons.Default.History) { onWhowas(); onDismiss() }
            ActionItem("Invite to channel…", Icons.Default.MailOutline) { onInviteRequest(); onDismiss() }

            HorizontalDivider()
            ActionItem("CTCP PING", Icons.Default.Timer) { onCtcp("PING"); onDismiss() }
            ActionItem("CTCP VERSION", Icons.Default.VerifiedUser) { onCtcp("VERSION"); onDismiss() }
            ActionItem("CTCP TIME", Icons.Default.Timer) { onCtcp("TIME"); onDismiss() }

            if (channelContext != null) {
                HorizontalDivider()
                Text(
                    "In $channelContext",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                ActionItem("Op (+o)", Icons.Default.Star) { onMode("+o"); onDismiss() }
                ActionItem("Deop (-o)", Icons.Default.StarBorder) { onMode("-o"); onDismiss() }
                ActionItem("Half-op (+h)", Icons.Default.Star) { onMode("+h"); onDismiss() }
                ActionItem("Half-deop (-h)", Icons.Default.StarBorder) { onMode("-h"); onDismiss() }
                ActionItem("Voice (+v)", Icons.Default.VolumeUp) { onMode("+v"); onDismiss() }
                ActionItem("Devoice (-v)", Icons.Default.VolumeOff) { onMode("-v"); onDismiss() }
                ActionItem("Quiet hostmask… (+q)", Icons.Default.MicOff) { onQuietRequest(); onDismiss() }
                ActionItem("Unquiet hostmask… (-q)", Icons.Default.RecordVoiceOver) { onUnquietRequest(); onDismiss() }
                ActionItem("Kick", Icons.Default.PersonOff,
                    color = MaterialTheme.colorScheme.error) { onKick(); onDismiss() }
                ActionItem("Kick with message…", Icons.Default.PersonOff,
                    color = MaterialTheme.colorScheme.error) { onKickWithReasonRequest(); onDismiss() }
                ActionItem("Ban hostmask…", Icons.Default.Block,
                    color = MaterialTheme.colorScheme.error) { onBanRequest(); onDismiss() }
                ActionItem("Kick + ban hostmask…", Icons.Default.PersonOff,
                    color = MaterialTheme.colorScheme.error) { onKickBanRequest(); onDismiss() }
                ActionItem("Unban hostmask…", Icons.Default.Warning) { onUnbanRequest(); onDismiss() }
            }
        }
    }
}

@Composable
private fun ActionItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (color == androidx.compose.ui.graphics.Color.Unspecified)
                    androidx.compose.material3.LocalContentColor.current else color,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
