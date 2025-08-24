package com.adsamcik.tracker.app.onboarding.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.adsamcik.tracker.app.activity.MainActivity
import com.adsamcik.tracker.app.onboarding.data.*
import com.adsamcik.tracker.app.onboarding.permission.OnboardingPermissionManager
import com.adsamcik.tracker.app.onboarding.permission.createOnboardingPermissionManager
import com.adsamcik.tracker.app.onboarding.permission.PermissionResult
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission

/**
 * Coordinator activity for the new onboarding flow.
 * Replaces the old first-run dialog system with a modern Compose-based experience.
 */
class OnboardingActivity : ComponentActivity() {
    
    private val viewModel: OnboardingViewModel by viewModels()
    private lateinit var permissionManager: OnboardingPermissionManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize permission manager
        permissionManager = createOnboardingPermissionManager()
        
        setContent {
            // Use app theme - you might want to create a specific onboarding theme
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Rationale dialog state
                    val rationalePermission = remember { mutableStateOf<Permission?>(null) }

                    // Rationale dialog UI
                    val rp = rationalePermission.value
                    if (rp != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { rationalePermission.value = null },
                            title = { androidx.compose.material3.Text("Permission required") },
                            text = {
                                androidx.compose.material3.Text(
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
                                    androidx.compose.material3.Text("Continue")
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    rationalePermission.value = null
                                    viewModel.onEvent(OnboardingEvent.PermissionDenied(rp, "Rationale dismissed"))
                                }) {
                                    androidx.compose.material3.Text("Cancel")
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
                                                viewModel.onEvent(OnboardingEvent.PermissionDenied(perm, "User denied"))
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
    
    private fun observeNavigation() {
        // Using lifecycle-aware collection in Compose instead
    }
    
    private fun navigateToMainApp() {
        // Persist onboarding selections and apply them now
        applyOnboardingPreferences(viewModel.state.value.userPreferences)

        // Mark onboarding as completed in preferences
        markOnboardingCompleted()
        
        // Navigate to main app
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun applyOnboardingPreferences(prefs: UserPreferences) {
    val preferences = Preferences.getPref(this)
        preferences.edit {
            // Core enable toggles
            setBoolean(R.string.settings_location_enabled_key, prefs.enableLocationTracking)
            setBoolean(R.string.settings_activity_enabled_key, prefs.enableActivityTracking)
            setBoolean(R.string.settings_wifi_enabled_key, prefs.enableWifiTracking)
            setBoolean(R.string.settings_steps_enabled_key, prefs.enableStepsTracking)

            // Notification styling as a proxy user-visible toggle (no global enable switch exists)
            setBoolean(R.string.settings_notification_styled_key, prefs.enableNotifications)

            // Auto/background tracking maps to integer option: 0 = disabled, default (>0) = enabled
            val autoTrackingValue = if (prefs.enableAutomaticTracking && this@OnboardingActivity.hasActivityPermission) {
                resources.getString(R.string.settings_tracking_activity_default).toInt()
            } else 0
            setInt(R.string.settings_tracking_activity_key, autoTrackingValue)

            // Optional: map tracking profile to a timer choice when available
            val timerKey = when (prefs.trackingProfile) {
                TrackingProfile.PRECISE -> TrackerTimerManager.availableTimerData
                    .firstOrNull { it.second == R.string.settings_tracker_timer_fused }?.first
                TrackingProfile.BALANCED -> TrackerTimerManager.availableTimerData
                    .firstOrNull { it.second == R.string.settings_tracker_timer_location }?.first
                TrackingProfile.ECO -> TrackerTimerManager.availableTimerData
                    .firstOrNull { it.second == R.string.settings_tracker_timer_clock }?.first
            }
            if (timerKey != null) {
                setString(R.string.settings_tracker_timer_key, timerKey)
            }
        }

        // Apply side-effects for auto tracking changes
    val desiredAuto = if (prefs.enableAutomaticTracking && this.hasActivityPermission) {
            resources.getString(R.string.settings_tracking_activity_default).toInt()
        } else 0
        ActivityWatcherService.onAutoTrackingPreferenceChange(this, desiredAuto)
        if (desiredAuto > 0) {
            // Ensure watcher evaluates immediately
            ActivityWatcherService.poke(this)
        }
    }

    private fun markOnboardingCompleted() {
        val prefs = getSharedPreferences("onboarding", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("completed", true)
            .putLong("completed_time", System.currentTimeMillis())
            .apply()
    }
    
    companion object {
        const val EXTRA_ONBOARDING_STEP = "onboarding_step"

        fun createIntent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)

        fun createIntentForStep(context: Context, step: OnboardingStep): Intent =
            Intent(context, OnboardingActivity::class.java).putExtra(EXTRA_ONBOARDING_STEP, step.name)
        
        fun isOnboardingCompleted(context: Context): Boolean {
            val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
            return prefs.getBoolean("completed", false)
        }
    }
}

@Composable
fun OnboardingFlow(
    viewModel: OnboardingViewModel,
    permissionManager: OnboardingPermissionManager,
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
    
    // Main onboarding content
    OnboardingScreen(
        state = state,
        onEvent = { event ->
            // Handle permission requests through the permission manager
            when (event) {
                is OnboardingEvent.RequestPermission -> onRequestPermission(event.permission)
                else -> {
                    // Forward other events directly to ViewModel
                    viewModel.onEvent(event)
                }
            }
        }
    )
}
