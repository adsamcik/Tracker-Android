package com.adsamcik.tracker.app.settings.privacypolicy

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.style.compose.MarkdownText

@Composable
fun PrivacyPolicyDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val privacyText = remember(context) { loadPrivacyPolicyText(context) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_privacy_policy_title)) },
        text = {
            if (privacyText != null) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    MarkdownText(markdown = privacyText)
                }
            } else {
                Text(stringResource(R.string.privacy_policy_load_error))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

private fun loadPrivacyPolicyText(context: Context): String? = runCatching {
    context.resources.openRawResource(R.raw.privacy_policy)
        .bufferedReader()
        .use { it.readText() }
        .trimStart()
        .let { trimmed ->
            val firstNewline = trimmed.indexOf('\n')
            if (firstNewline > 0 && trimmed.startsWith("# ")) {
                trimmed.substring(firstNewline + 1).trimStart()
            } else {
                trimmed
            }
        }
}.getOrNull()
