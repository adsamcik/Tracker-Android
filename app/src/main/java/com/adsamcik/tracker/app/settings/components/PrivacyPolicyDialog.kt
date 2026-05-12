package com.adsamcik.tracker.app.settings.components

import android.content.res.Resources
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
    val privacyText = remember(context) {
        readBundledPrivacyPolicy(context.resources)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_privacy_policy_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (privacyText != null) {
                    MarkdownText(markdown = privacyText)
                } else {
                    Text(stringResource(R.string.settings_privacy_policy_unavailable))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

internal fun readBundledPrivacyPolicy(resources: Resources): String? = try {
    resources.openRawResource(R.raw.privacy_policy)
        .bufferedReader()
        .use { it.readText() }
        .let(::stripLeadingMarkdownHeading)
} catch (_: Exception) {
    null
}

private fun stripLeadingMarkdownHeading(raw: String): String {
    val trimmed = raw.trimStart()
    val firstNewline = trimmed.indexOf('\n')
    return if (firstNewline > 0 && trimmed.startsWith("# ")) {
        trimmed.substring(firstNewline + 1).trimStart()
    } else {
        trimmed
    }
}
