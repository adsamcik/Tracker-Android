package com.adsamcik.tracker.app.onboarding.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
// import removed: legacy MainActivity no longer used
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.app.onboarding.data.*
import com.adsamcik.tracker.app.onboarding.permission.IOnboardingPermissionManager
import com.adsamcik.tracker.app.onboarding.permission.OnboardingPermissionManagerProvider
import com.adsamcik.tracker.app.onboarding.permission.PermissionResult
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.onboarding.DefaultOnboardingRepository
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import com.adsamcik.tracker.shared.preferences.R as PrefR
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.R as BaseR
import com.adsamcik.tracker.activity.R as ActivityR
import com.adsamcik.tracker.tracker.R as TrackerR
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.maintenance.DataRetentionWorker
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode

/**
 * Coordinator activity for the new onboarding flow.
 * Replaces the old first-run dialog system with a modern Compose-based experience.
 */
@OptIn(ExperimentalStdlibApi::class)
class OnboardingActivity : ComponentActivity() {
    
    private val viewModel: OnboardingViewModel by viewModels()
    private lateinit var permissionManager: IOnboardingPermissionManager
    private val onboardingRepository: OnboardingRepository by lazy {
        DefaultOnboardingRepository(this, Dispatchers.IO)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
    // Initialize permission manager (swappable in tests via provider)
    permissionManager = OnboardingPermissionManagerProvider.factory(this)
        
        setContent {
            val dark = isSystemInDarkTheme()
            AppTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Rationale dialog state
                    val rationalePermission = remember { mutableStateOf<Permission?>(null) }
                    // Settings redirect dialog state (used for background location policy)
                    val settingsPermission = remember { mutableStateOf<Permission?>(null) }

                    // Rationale dialog UI
                    val rp = rationalePermission.value
                    if (rp != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { rationalePermission.value = null },
                            title = { Text(stringResource(id = R.string.onboarding_perm_rationale_title)) },
                            text = {
                                Text(
                                    permissionManager.getPermissionDescription(rp)
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    rationalePermission.value = null
                                    // Launch the actual permission request now
                                    permissionManager.activity.lifecycleScope.launch {
                                        try {
                                            val result = permissionManager.requestPermission(rp)
                                            when (result) {
                                                PermissionResult.Granted ->
                                                    viewModel.onEvent(OnboardingEvent.PermissionGranted(rp))
                                                PermissionResult.Denied ->
                                                    viewModel.onEvent(OnboardingEvent.PermissionDenied(rp, "User denied"))
                                                is PermissionResult.PartiallyGranted -> {
                                                    // Graceful partial handling for foreground location
                                                    if (rp == Permission.LOCATION_FOREGROUND && result.grantedPermissions.isNotEmpty()) {
                                                        viewModel.onEvent(OnboardingEvent.PermissionGranted(rp))
                                                    } else {
                                                        viewModel.onEvent(OnboardingEvent.PermissionDenied(rp, "Partially granted"))
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            viewModel.onEvent(OnboardingEvent.PermissionDenied(rp, e.message))
                                        }
                                    }
                                }) {
                                    Text(stringResource(id = R.string.onboarding_button_continue))
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    rationalePermission.value = null
                                    viewModel.onEvent(OnboardingEvent.PermissionDenied(rp, "Rationale dismissed"))
                                }) {
                                    Text(stringResource(id = R.string.onboarding_button_cancel))
                                }
                            }
                        )
                    }

                    // Settings redirect dialog UI (e.g., for Background Location "Allow all the time")
                    val sp = settingsPermission.value
                    if (sp != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { settingsPermission.value = null },
                            title = { Text(stringResource(id = R.string.onboarding_open_settings_title)) },
                            text = {
                                val message = when (sp) {
                                    Permission.LOCATION_BACKGROUND -> stringResource(id = R.string.onboarding_background_location_settings_message)
                                    else -> stringResource(id = R.string.onboarding_open_settings_generic_message)
                                }
                                Text(message)
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    settingsPermission.value = null
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + packageName)
                                    )
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(intent)
                                }) {
                                    Text(stringResource(id = R.string.onboarding_open_settings_confirm))
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    settingsPermission.value = null
                                }) {
                                    Text(stringResource(id = R.string.onboarding_button_cancel))
                                }
                            }
                        )
                    }

                    OnboardingFlow(
                        viewModel = viewModel,
                        permissionManager = permissionManager,
                        onNavigateToMainApp = { navigateToMainApp() },
                        onRequestPermission = { perm ->
                            if (permissionManager.shouldShowRequestPermissionRationale(perm)) {
                                // Show rationale first; actual request will be launched from the dialog confirm
                                rationalePermission.value = perm
                            } else {
                                // Direct request flow
                                permissionManager.activity.lifecycleScope.launch {
                                    try {
                                        val result = permissionManager.requestPermission(perm)
                                        when (result) {
                                            PermissionResult.Granted ->
                                                viewModel.onEvent(OnboardingEvent.PermissionGranted(perm))
                                            PermissionResult.Denied ->
                                                run {
                                                    if (perm == Permission.LOCATION_BACKGROUND) {
                                                        // Offer a settings redirect to comply with background location guidance
                                                        settingsPermission.value = Permission.LOCATION_BACKGROUND
                                                    }
                                                    viewModel.onEvent(OnboardingEvent.PermissionDenied(perm, "User denied"))
                                                }
                                            is PermissionResult.PartiallyGranted -> {
                                                val acceptPartial = when (perm) {
                                                    Permission.LOCATION_FOREGROUND -> result.grantedPermissions.isNotEmpty()
                                                    Permission.NEARBY_WIFI_DEVICES -> result.grantedPermissions.isNotEmpty()
                                                    else -> false
                                                }
                                                if (acceptPartial) {
                                                    viewModel.onEvent(OnboardingEvent.PermissionGranted(perm))
                                                } else {
                                                    viewModel.onEvent(OnboardingEvent.PermissionDenied(perm, "Partially granted"))
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        viewModel.onEvent(OnboardingEvent.PermissionDenied(perm, e.message))
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
        
        // Observe navigation events
        observeNavigation()

    // Handle optional deep link to a specific onboarding step
        intent.getStringExtra(EXTRA_ONBOARDING_STEP)?.let { stepName ->
            OnboardingStep.fromName(stepName)?.let { step ->
                viewModel.onEvent(OnboardingEvent.GoToStep(step))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // After returning from settings, re-check permissions and update state
        val granted = permissionManager.getGrantedPermissions()
        granted.forEach { perm ->
            viewModel.onEvent(OnboardingEvent.PermissionGranted(perm))
        }
    }
    
    private fun observeNavigation() {
        // Using lifecycle-aware collection in Compose instead
    }
    
    private fun navigateToMainApp() {
        // Persist onboarding selections and apply them now
        applyOnboardingPreferences(viewModel.state.value.userPreferences)

        // Mark onboarding as completed in preferences
        markOnboardingCompleted()
        
        // Navigate to main app
    val intent = Intent(this, MainActivityCompose::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun applyOnboardingPreferences(prefs: UserPreferences) {
        val preferences = Preferences.getPref(this)
        val hasPrimaryTrackingSource =
            prefs.enableLocationTracking || prefs.enableActivityTracking || prefs.enableStepsTracking
        val locationEnabled = prefs.enableLocationTracking || !hasPrimaryTrackingSource
        preferences.edit {
            // Store location precision choice
            prefs.locationPrecisionMode?.let { mode ->
                setString(
                    PrefR.string.settings_location_precision_key,
                    mode.name
                )
            }
            
            // Core enable toggles
            setBoolean(PrefR.string.settings_location_enabled_key, locationEnabled)
            setBoolean(PrefR.string.settings_activity_enabled_key, prefs.enableActivityTracking)
            setBoolean(PrefR.string.settings_wifi_enabled_key, prefs.enableWifiTracking)
            setBoolean(PrefR.string.settings_cell_enabled_key, prefs.enableCellTracking)
            setBoolean(PrefR.string.settings_steps_enabled_key, prefs.enableStepsTracking)

            // Notification styling as a proxy user-visible toggle (no global enable switch exists)
            setBoolean(PrefR.string.settings_notification_styled_key, prefs.enableNotifications)

            // Map additional auto-tracking toggles to Settings screen keys so onboarding matches Settings
            setBoolean(PrefR.string.settings_auto_tracking_transition_key, prefs.autoTransitionsEnabled)
            setBoolean(ActivityR.string.settings_activity_watcher_key, prefs.activityWatcherEnabled)
            setBoolean(TrackerR.string.settings_disabled_recharge_key, prefs.pauseWhileCharging)

            // Apply min distance/time if set
            prefs.trackingMinDistanceMeters?.let { setInt(PrefR.string.settings_tracking_min_distance_key, it) }
            prefs.trackingMinTimeSeconds?.let { setInt(PrefR.string.settings_tracking_min_time_key, it) }

            // Tracking profiles removed; rely on explicit distance/time and internal defaults
        }

        // Persist auto-cleanup setting to Proto DataStore
        lifecycleScope.launch(Dispatchers.IO) {
            RetentionConfigStore(this@OnboardingActivity, Dispatchers.IO).update {
                copy(autoCleanupEnabled = prefs.autoCleanupOldData)
            }
        }

        // Auto/background tracking mode: use selected index when available; fall back to default/disabled
        val selectedMode = prefs.autoTrackingModeIndex
        val autoTrackingValue = when {
            selectedMode > 0 && hasActivityPermission -> selectedMode
            prefs.enableAutomaticTracking && hasActivityPermission ->
                resources.getString(PrefR.string.settings_tracking_activity_default).toInt()
            else -> 0
        }
        preferences.edit { setInt(PrefR.string.settings_tracking_activity_key, autoTrackingValue) }

        // Apply side-effects for auto tracking changes (use computed value, not re-read from prefs)
        ActivityWatcherService.onAutoTrackingPreferenceChange(this, autoTrackingValue)
        if (autoTrackingValue > 0) {
            // Ensure watcher evaluates immediately
            ActivityWatcherService.poke(this)
        }

    // Sync weekly data retention schedule with preference
    DataRetentionWorker.initialize(this)
    }

    private fun markOnboardingCompleted() {
        lifecycleScope.launch {
            onboardingRepository.markCompleted()
        }
    }
    
    companion object {
        const val EXTRA_ONBOARDING_STEP = "onboarding_step"

        fun createIntent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)

        fun createIntentForStep(context: Context, step: OnboardingStep): Intent =
            Intent(context, OnboardingActivity::class.java).putExtra(EXTRA_ONBOARDING_STEP, step.name)
        
        /**
         * Check if onboarding is completed using async Flow.
         * Prefer using OnboardingRepository.isCompleted directly with SplashScreen API.
         */
        suspend fun isOnboardingCompletedAsync(context: Context): Boolean {
            return DefaultOnboardingRepository(context, Dispatchers.IO).isCompleted.first()
        }
    }
}

@Composable
fun OnboardingFlow(
    viewModel: OnboardingViewModel,
    permissionManager: IOnboardingPermissionManager,
    onNavigateToMainApp: () -> Unit,
    onRequestPermission: (Permission) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigationEvent by viewModel.navigationEvent.collectAsStateWithLifecycle()
    
    // Initialize granted permissions on first composition
    LaunchedEffect(Unit) {
        val grantedPermissions = permissionManager.getGrantedPermissions()
        grantedPermissions.forEach { permission ->
            viewModel.onEvent(OnboardingEvent.PermissionGranted(permission))
        }
    }
    
    // Handle navigation events
    LaunchedEffect(navigationEvent) {
        when (navigationEvent) {
            NavigationEvent.NavigateToMainApp -> {
                onNavigateToMainApp()
                viewModel.clearNavigationEvent()
            }
            is NavigationEvent.NavigateToStep -> {
                // Handle step navigation if needed
                viewModel.clearNavigationEvent()
            }
            null -> { /* No navigation event */ }
        }
    }
    
    // Streamlined single-screen onboarding (Apple-style)
    // Location precision choice before permissions
    StreamlinedOnboardingScreen(
        onComplete = { precisionMode ->
            // Store precision mode choice
            viewModel.onEvent(OnboardingEvent.UpdateLocationPrecisionMode(precisionMode))
            // Apply smart defaults immediately
            viewModel.applySmartDefaults()
            // Complete onboarding
            viewModel.onEvent(OnboardingEvent.CompleteOnboarding)
        }
    )
}
