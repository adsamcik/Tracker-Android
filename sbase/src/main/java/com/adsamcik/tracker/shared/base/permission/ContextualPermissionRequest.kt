package com.adsamcik.tracker.shared.base.permission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.shared.base.R


/**
 * Permission types with associated rationale strings.
 */
enum class PermissionType(
    val icon: ImageVector,
    val rationaleTitle: Int,
    val rationaleMessage: Int
) {
    LOCATION_FOREGROUND(
        Icons.Default.LocationOn,
        R.string.permission_location_rationale_title,
        R.string.permission_location_rationale_message
    ),
    ACTIVITY_RECOGNITION(
        Icons.Default.LocationOn,
        R.string.permission_activity_rationale_title,
        R.string.permission_activity_rationale_message
    ),
    LOCATION_BACKGROUND(
        Icons.Default.LocationOn,
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
    onDismiss: () -> Unit
) {
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = onPermissionResult
    )
    
    var showRationale by remember { mutableStateOf(true) }
    
    if (showRationale) {
        PermissionRationaleDialog(
            permissionType = permissionType,
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
            Text(stringResource(permissionType.rationaleMessage))
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
