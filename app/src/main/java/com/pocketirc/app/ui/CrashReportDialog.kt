package com.pocketirc.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The constants below need to match the actual project URL and contact
 * address. Both are placeholders until the GitHub repo and the SimpleLogin /
 * Proton alias have been created — search-and-replace once they are.
 */
private const val GITHUB_REPO = "inhahe/pocketirc"
private const val CONTACT_EMAIL = "inhahe.backstage595@slmails.com"

/**
 * Dialog shown when the user has agreed to look at a crash or error report.
 * Offers GitHub, email, copy-to-clipboard, and dismiss. Nothing leaves the
 * device unless the user explicitly chooses one of the send actions.
 */
@Composable
fun CrashReportDialog(
    title: String,
    fullText: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pocket IRC will not send anything automatically. " +
                        "Pick one of the buttons below to share this report with the developer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                        Text(
                            fullText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            openGitHub(ctx, title, fullText)
                            onDismiss()
                        },
                    ) { Text("GitHub") }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            openEmail(ctx, title, fullText)
                            onDismiss()
                        },
                    ) { Text("Email") }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            copyToClipboard(ctx, fullText)
                            onDismiss()
                        },
                    ) { Text("Copy") }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss,
                    ) { Text("Dismiss") }
                }
            }
        }
    }
}

private fun openGitHub(ctx: Context, title: String, body: String) {
    // GitHub silently truncates very long issue URLs. Cap at ~6 KB of body so
    // the URL stays under the practical limit (~8 KB) with overhead.
    val truncated = if (body.length > 6_000)
        body.take(6_000) + "\n\n[trace truncated; full trace was copied to clipboard]"
    else body
    if (body.length > 6_000) copyToClipboard(ctx, body)
    val urlTitle = Uri.encode(title)
    val urlBody = Uri.encode("```\n$truncated\n```")
    val url = "https://github.com/$GITHUB_REPO/issues/new?title=$urlTitle&body=$urlBody"
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: ActivityNotFoundException) {
        copyToClipboard(ctx, "$title\n\n$body")
    }
}

private fun openEmail(ctx: Context, title: String, body: String) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Pocket IRC: $title")
        putExtra(Intent.EXTRA_TEXT, body)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        copyToClipboard(ctx, "$title\n\n$body")
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cb?.setPrimaryClip(ClipData.newPlainText("Pocket IRC error", text))
}
