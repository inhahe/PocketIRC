package com.pocketirc.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Renders the human-friendly title of a notification-sound URI by asking
 * Android. Falls back to "(default)" if [uri] is blank or unresolvable.
 */
@Composable
fun rememberSoundTitle(uri: String): String {
    val ctx = LocalContext.current
    return remember(uri) {
        if (uri.isBlank()) "(system default)"
        else runCatching {
            val r = RingtoneManager.getRingtone(ctx, Uri.parse(uri))
            r?.getTitle(ctx) ?: uri
        }.getOrDefault(uri)
    }
}

/**
 * Pick / Clear buttons + a label showing the currently selected sound.
 * Uses Android's built-in [RingtoneManager.ACTION_RINGTONE_PICKER] dialog.
 *
 * @param current the currently saved URI string (empty = system default)
 * @param onPick called with the new URI string ("" if the user cleared)
 */
@Composable
fun SoundPickerRow(
    label: String = "Notification sound",
    current: String,
    onPick: (String) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data
                ?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            onPick(uri?.toString() ?: "")
        }
    }
    val title = rememberSoundTitle(current)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_TYPE,
                        RingtoneManager.TYPE_NOTIFICATION,
                    )
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pick a notification sound")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    if (current.isNotBlank()) {
                        putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            Uri.parse(current),
                        )
                    }
                }
                launcher.launch(intent)
            }) { Text("Pick sound…") }
            if (current.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onPick("") }) { Text("Clear") }
            }
        }
    }
}
