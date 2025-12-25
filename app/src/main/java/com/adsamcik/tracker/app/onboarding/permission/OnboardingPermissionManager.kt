package com.adsamcik.tracker.app.onboarding.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.adsamcik.tracker.app.onboarding.data.Permission

/**
 * Modern permission manager using Activity Result API.
 * Replaces the legacy Dexter-based permission system.
 */
/**
 * Contract used by onboarding UI to check/request permissions.
 * Keep this minimal to allow stable fakes in instrumentation tests.
 */
interface IOnboardingPermissionManager {
    val activity: ComponentActivity
    fun isPermissionGranted(permission: Permission): Boolean
    fun shouldShowRequestPermissionRationale(permission: Permission): Boolean
    suspend fun requestPermission(permission: Permission): PermissionResult
    fun getGrantedPermissions(): Set<Permission>
    fun getPermissionDisplayName(permission: Permission): String
    fun getPermissionDescription(permission: Permission): String
}

class OnboardingPermissionManager(override val activity: ComponentActivity) : IOnboardingPermissionManager {

    private var currentCallback: ((PermissionResult) -> Unit)? = null
    
    private val singlePermissionLauncher: ActivityResultLauncher<String> = 
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            currentCallback?.invoke(
                if (granted) PermissionResult.Granted else PermissionResult.Denied
            )
            currentCallback = null
        }
    
    private val multiplePermissionsLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            val grantedPermissions = permissions.filterValues { it }.keys
            val deniedPermissions = permissions.filterValues { !it }.keys
            
            currentCallback?.invoke(
                if (allGranted) {
                    PermissionResult.Granted
                } else {
                    PermissionResult.PartiallyGranted(grantedPermissions.toList(), deniedPermissions.toList())
                }
            )
            currentCallback = null
        }

    /**
     * Check if a permission is currently granted
     */
    override fun isPermissionGranted(permission: Permission): Boolean {
        val manifestPermissions = getManifestPermissions(permission)
        return if (permission == com.adsamcik.tracker.app.onboarding.data.Permission.LOCATION_FOREGROUND ||
            (permission == com.adsamcik.tracker.app.onboarding.data.Permission.NEARBY_WIFI_DEVICES &&
             Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        ) {
            // Location and pre-33 WiFi: accept if either coarse or fine location is granted
            manifestPermissions.any { manifestPermission ->
                ContextCompat.checkSelfPermission(activity, manifestPermission) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            manifestPermissions.all { manifestPermission ->
                ContextCompat.checkSelfPermission(activity, manifestPermission) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * Check if permission rationale should be shown
     */
    override fun shouldShowRequestPermissionRationale(permission: Permission): Boolean {
        val manifestPermissions = getManifestPermissions(permission)
        return manifestPermissions.any { manifestPermission ->
            activity.shouldShowRequestPermissionRationale(manifestPermission)
        }
    }

    /**
     * Request a single permission
     */
    override suspend fun requestPermission(permission: Permission): PermissionResult {
        return suspendCancellableCoroutine { continuation ->
            val manifestPermissions = getManifestPermissions(permission)
            
            // Check if already granted
            if (manifestPermissions.all { 
                ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED 
            }) {
                continuation.resume(PermissionResult.Granted)
                return@suspendCancellableCoroutine
            }

            currentCallback = { result ->
                continuation.resume(result)
            }

            if (manifestPermissions.size == 1) {
                singlePermissionLauncher.launch(manifestPermissions.first())
            } else {
                multiplePermissionsLauncher.launch(manifestPermissions.toTypedArray())
            }
        }
    }

    /**
     * Get all currently granted onboarding permissions
     */
    override fun getGrantedPermissions(): Set<Permission> {
        return Permission.values().filter { permission ->
            isPermissionGranted(permission)
        }.toSet()
    }

    /**
     * Map onboarding permission to Android manifest permissions
     */
    private fun getManifestPermissions(permission: Permission): List<String> {
        return when (permission) {
            Permission.LOCATION_FOREGROUND -> {
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
            Permission.LOCATION_BACKGROUND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    emptyList() // Background location is included in foreground on older versions
                }
            }
            Permission.ACTIVITY_RECOGNITION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    listOf(Manifest.permission.ACTIVITY_RECOGNITION)
                } else {
                    emptyList() // Not needed on older versions
                }
            }
            Permission.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList() // Not needed on older versions
                }
            }
            Permission.NEARBY_WIFI_DEVICES -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    // Prior to API 33, Wi‑Fi scans and results are gated by location permissions
                    // Either ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION suffices; request both and accept partial
                    listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                }
            }
        }
    }

    /**
     * Get user-friendly permission names for UI display
     */
    override fun getPermissionDisplayName(permission: Permission): String {
        return when (permission) {
            Permission.LOCATION_FOREGROUND -> "Location Access"
            Permission.LOCATION_BACKGROUND -> "Background Location"
            Permission.ACTIVITY_RECOGNITION -> "Activity Recognition"
            Permission.NOTIFICATIONS -> "Notifications"
            Permission.NEARBY_WIFI_DEVICES -> "Nearby WiFi Devices"
        }
    }

    /**
     * Get permission description for rationale dialogs
     */
    override fun getPermissionDescription(permission: Permission): String {
        return when (permission) {
            Permission.LOCATION_FOREGROUND -> 
                "Location access is needed to track your routes and movement patterns."
            Permission.LOCATION_BACKGROUND -> 
                "Background location allows automatic tracking when the app is minimized."
            Permission.ACTIVITY_RECOGNITION -> 
                "Activity recognition helps detect when you're walking, running, or driving."
            Permission.NOTIFICATIONS -> 
                "Notifications provide helpful insights about your movement patterns."
            Permission.NEARBY_WIFI_DEVICES -> 
                "WiFi access improves indoor location accuracy and reduces battery usage."
        }
    }
}

/**
 * Result of permission request
 */
sealed class PermissionResult {
    object Granted : PermissionResult()
    object Denied : PermissionResult()
    data class PartiallyGranted(
        val grantedPermissions: List<String>,
        val deniedPermissions: List<String>
    ) : PermissionResult()
}

/**
 * Extension function to easily create permission manager
 */
fun ComponentActivity.createOnboardingPermissionManager(): OnboardingPermissionManager {
    return OnboardingPermissionManager(this)
}

/**
 * Simple provider to allow swapping permission manager factory in tests.
 */
object OnboardingPermissionManagerProvider {
    @Volatile
    var factory: (ComponentActivity) -> IOnboardingPermissionManager = { it.createOnboardingPermissionManager() }
}
