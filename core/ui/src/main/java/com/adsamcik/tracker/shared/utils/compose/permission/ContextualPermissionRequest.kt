package com.adsamcik.tracker.shared.utils.compose.permission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.shared.utils.R


/**
 * Permission types with associated rationale strings.
 */
enum class PermissionType(
    val icon: ImageVector,
    val rationaleTitle: Int,
    val rationaleMessage: Int
) {
    LOCATION_FOREGROUND(
        Icons.Filled.LocationOn,
        R.string.permission_location_rationale_title,
        R.string.permission_location_rationale_message
    ),
    ACTIVITY_RECOGNITION(
        Icons.Filled.LocationOn,
        R.string.permission_activity_rationale_title,
        R.string.permission_activity_rationale_message
    ),
    PHONE_STATE(
        Icons.Filled.Phone,
        R.string.permission_phone_state_rationale_title,
        R.string.permission_phone_state_rationale_message
    ),
    LOCATION_BACKGROUND(
        Icons.Filled.LocationOn,
        R.string.permission_background_location_rationale_title,
        R.string.permission_background_location_rationale_message
    )
}

/**
 * Contextual permission request following Apple-style pattern:
 * 1. Show rationale BEFORE system prompt
 * 2. User chooses to proceed or skip
 * 3. If proceed, show system permission dialog
 * 4. Handle denial gracefully with settings action
 */
@Composable
fun ContextualPermissionRequest(
    permissionType: PermissionType,
    permission: String,
    onPermissionResult: (granted: Boolean) -> Unit,
    onDismiss: () -> Unit,
    rationaleMessageOverride: Int? = null
) {
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = onPermissionResult
    )
    
    // A sequential repair (for example Cell: precise Location, then phone state) reuses this
    // composition slot. Reset the rationale when the requested permission changes.
    var showRationale by remember(permissionType, permission) { mutableStateOf(true) }
    
    if (showRationale) {
        PermissionRationaleDialog(
            permissionType = permissionType,
            rationaleMessageOverride = rationaleMessageOverride,
            onAllow = {
                showRationale = false
                permissionLauncher.launch(permission)
            },
            onDeny = {
                showRationale = false
                onDismiss()
                onPermissionResult(false)
            }
        )
    }
}

/**
 * Rationale dialog shown before system permission prompt.
 */
@Composable
private fun PermissionRationaleDialog(
    permissionType: PermissionType,
    rationaleMessageOverride: Int? = null,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        icon = {
            Icon(
                imageVector = permissionType.icon,
                contentDescription = null
            )
        },
        title = {
            Text(stringResource(permissionType.rationaleTitle))
        },
        text = {
            Text(stringResource(rationaleMessageOverride ?: permissionType.rationaleMessage))
        },
        confirmButton = {
            Button(onClick = onAllow) {
                Text(stringResource(R.string.permission_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(stringResource(R.string.permission_deny))
            }
        }
    )
}

/**
 * Snackbar shown when permission is denied with action to open settings.
 */
@Composable
fun PermissionDeniedSnackbar(
    snackbarHostState: SnackbarHostState,
    message: String
) {
    val context = LocalContext.current
    val actionLabel = stringResource(R.string.permission_denied_settings_action)
    
    LaunchedEffect(Unit) {
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Long
        )
        
        if (result == SnackbarResult.ActionPerformed) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}
