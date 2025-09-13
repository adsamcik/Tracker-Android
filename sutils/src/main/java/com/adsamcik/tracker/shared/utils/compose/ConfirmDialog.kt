package com.adsamcik.tracker.shared.utils.compose

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

// Contract:
// Input: visible flag drives presence; title/message strings supplied by caller; confirm/dismiss labels.
// Output: Calls onConfirm then onDismiss (caller decides order) or just onDismiss when cancelled.
// Errors: None (pure UI). Caller maintains state hoisting.
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String?,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    AlertDialog(
        modifier = modifier.testTag("confirm_dialog"),
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, modifier = Modifier.testTag("confirm_dialog_confirm")) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("confirm_dialog_dismiss")) {
                Text(dismissLabel)
            }
        }
    )
}
