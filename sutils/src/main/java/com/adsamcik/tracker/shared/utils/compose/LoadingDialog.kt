package com.adsamcik.tracker.shared.utils.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Compose replacement for MaterialDialog-based loading dialogs.
 * Shows an indeterminate progress indicator with optional message.
 * 
 * @param visible Whether the dialog is shown
 * @param title Dialog title text
 * @param message Optional loading message
 * @param onDismiss Called when dialog should be dismissed (usually disabled during loading)
 */
@Composable
fun LoadingDialog(
    visible: Boolean,
    title: String? = null,
    message: String? = null,
    onDismiss: () -> Unit = { /* disabled during loading */ }
) {
    if (visible) {
        AlertDialog(
            modifier = Modifier.testTag("loadingDialog"),
            onDismissRequest = onDismiss,
            title = title?.let { { Text(text = it) } },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp)
                    )
                    if (message != null) {
                        Spacer(Modifier.width(16.dp))
                        Text(text = message)
                    }
                }
            },
            confirmButton = { /* No buttons during loading */ },
            dismissButton = { /* No buttons during loading */ }
        )
    }
}
