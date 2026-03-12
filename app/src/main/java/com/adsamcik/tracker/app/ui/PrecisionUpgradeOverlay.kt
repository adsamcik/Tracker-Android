package com.adsamcik.tracker.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.app.tracker.ui.UpgradeReason
import com.adsamcik.tracker.app.tracker.ui.UpgradeToPrecisePrompt
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionType
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.R as PrefR

/**
 * Non-blocking overlay that prompts the user to upgrade from approximate
 * to precise location when conditions are met.
 * Manages its own state for the upgrade prompt and permission request.
 */
@Composable
internal fun PrecisionUpgradeOverlay() {
    val context = LocalContext.current
    val prefs = remember { Preferences.getPref(context) }

    var shouldShowUpgradePrompt by remember {
        mutableStateOf(
            prefs.getBooleanRes(
                PrefR.string.settings_should_show_precision_upgrade_key,
                false,
            ),
        )
    }

    var hasPreciseLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    var showPreciseLocationPermissionRequest by remember { mutableStateOf(false) }

    // Permission request dialog
    if (showPreciseLocationPermissionRequest) {
        ContextualPermissionRequest(
            permissionType = PermissionType.LOCATION_FOREGROUND,
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            onPermissionResult = { granted ->
                hasPreciseLocationPermission = granted
                if (granted) {
                    prefs.edit {
                        setString(PrefR.string.settings_location_precision_key, "PRECISE")
                        setInt(PrefR.string.settings_approximate_session_count_key, 0)
                        setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, false)
                    }
                    shouldShowUpgradePrompt = false
                }
                showPreciseLocationPermissionRequest = false
            },
            onDismiss = {
                showPreciseLocationPermissionRequest = false
            },
        )
    }

    // Upgrade prompt
    if (shouldShowUpgradePrompt && !hasPreciseLocationPermission) {
        UpgradeToPrecisePrompt(
            onDismiss = {
                prefs.edit {
                    setBoolean(PrefR.string.settings_precision_upgrade_dismissed_key, true)
                    setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, false)
                    setInt(PrefR.string.settings_approximate_session_count_key, 0)
                }
                shouldShowUpgradePrompt = false
            },
            onUpgrade = {
                shouldShowUpgradePrompt = false
                showPreciseLocationPermissionRequest = true
            },
            reason = UpgradeReason.GENERAL,
        )
    }
}
