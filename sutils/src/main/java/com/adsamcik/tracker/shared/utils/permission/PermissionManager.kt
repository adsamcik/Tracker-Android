package com.adsamcik.tracker.shared.utils.permission

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Modern permission manager using Activity Result API.
 * Replaces legacy Dexter-based permission system.
 * 
 * Contract: Provides permission request functionality with rationale dialogs.
 * Inputs: PermissionRequest with permissions to request and callbacks.
 * Outputs: PermissionRequestResult via callback with granted/denied permissions.
 * Errors: Throws IllegalStateException if used outside ComponentActivity context.
 */
object PermissionManager {

    /**
     * Check and request permissions using Activity Result API.
     * Must be called from ComponentActivity context.
     */
    fun checkPermissions(permissionRequest: PermissionRequest) {
        val activity = getActivityFromContext(permissionRequest.context)
        
        // Check which permissions are already granted
        val (granted, needsRequest) = partitionPermissions(activity, permissionRequest.permissionList)
        
        if (needsRequest.isEmpty()) {
            // All permissions already granted
            val grantedResults = granted.map { 
                PermissionResult(data = it, isSuccess = true, isForeverDenied = false)
            }
            permissionRequest.resultCallback(PermissionRequestResult(grantedResults, emptyList()))
            return
        }
        
        // Store the request for result handling
        val launcher = PermissionLauncherRegistry.getOrCreateLauncher(activity)
        launcher.launch(permissionRequest, granted)
    }

    /**
     * Check permissions with rationale dialog for denied permissions.
     */
    fun checkPermissionsWithRationaleDialog(permissionRequest: PermissionRequest) {
        val activity = getActivityFromContext(permissionRequest.context)
        
        // Check which permissions are already granted
        val (granted, needsRequest) = partitionPermissions(activity, permissionRequest.permissionList)
        
        if (needsRequest.isEmpty()) {
            // All permissions already granted
            val grantedResults = granted.map { 
                PermissionResult(data = it, isSuccess = true, isForeverDenied = false)
            }
            permissionRequest.resultCallback(PermissionRequestResult(grantedResults, emptyList()))
            return
        }
        
        // Check if we should show rationale for any permission
        val needsRationale = needsRequest.filter { permissionData ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permissionData.name)
        }
        
        if (needsRationale.isNotEmpty() && permissionRequest.rationaleCallback != null) {
            // Show rationale dialog
            val token = PermissionRequest.Token(
                onContinue = {
                    val launcher = PermissionLauncherRegistry.getOrCreateLauncher(activity)
                    launcher.launch(permissionRequest, granted)
                },
                onCancel = {
                    // User cancelled, return denied results
                    val grantedResults = granted.map { 
                        PermissionResult(data = it, isSuccess = true, isForeverDenied = false)
                    }
                    val deniedResults = needsRequest.map {
                        PermissionResult(data = it, isSuccess = false, isForeverDenied = false)
                    }
                    permissionRequest.resultCallback(PermissionRequestResult(grantedResults, deniedResults))
                }
            )
            permissionRequest.rationaleCallback.invoke(token, needsRationale)
        } else if (needsRationale.isNotEmpty()) {
            // Show default rationale dialog
            showDefaultRationaleDialog(activity, needsRationale) { proceed ->
                if (proceed) {
                    val launcher = PermissionLauncherRegistry.getOrCreateLauncher(activity)
                    launcher.launch(permissionRequest, granted)
                } else {
                    // User cancelled, return denied results
                    val grantedResults = granted.map { 
                        PermissionResult(data = it, isSuccess = true, isForeverDenied = false)
                    }
                    val deniedResults = needsRequest.map {
                        PermissionResult(data = it, isSuccess = false, isForeverDenied = false)
                    }
                    permissionRequest.resultCallback(PermissionRequestResult(grantedResults, deniedResults))
                }
            }
        } else {
            // No rationale needed, request directly
            val launcher = PermissionLauncherRegistry.getOrCreateLauncher(activity)
            launcher.launch(permissionRequest, granted)
        }
    }

    /**
     * Specialized check for activity recognition permissions.
     */
    fun checkActivityPermissions(context: Context, callback: PermissionResultCallback) {
        val activity = getActivityFromContext(context)
        
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            // Activity recognition doesn't require permission on pre-Q
            callback(PermissionRequestResult(emptyList(), emptyList()))
            return
        }
        
        val permissionData = PermissionData(
            android.Manifest.permission.ACTIVITY_RECOGNITION
        ) { ctx ->
            ctx.getString(com.adsamcik.tracker.shared.utils.R.string.permission_rationale_activity)
        }
        
        checkPermissions(
            PermissionRequest.newInstance(context, permissionData, callback)
        )
    }

    private fun getActivityFromContext(context: Context): ComponentActivity {
        return when (context) {
            is ComponentActivity -> context
            else -> throw IllegalStateException(
                "PermissionManager requires ComponentActivity context. " +
                "Got ${context.javaClass.simpleName}. Please use Activity Result API from activity."
            )
        }
    }

    private fun partitionPermissions(
        activity: ComponentActivity,
        permissions: List<PermissionData>
    ): Pair<List<PermissionData>, List<PermissionData>> {
        val granted = mutableListOf<PermissionData>()
        val needsRequest = mutableListOf<PermissionData>()
        
        permissions.forEach { permissionData ->
            if (ContextCompat.checkSelfPermission(activity, permissionData.name) == PackageManager.PERMISSION_GRANTED) {
                granted.add(permissionData)
            } else {
                needsRequest.add(permissionData)
            }
        }
        
        return granted to needsRequest
    }

    private fun showDefaultRationaleDialog(
        activity: ComponentActivity,
        permissions: List<PermissionData>,
        onResult: (Boolean) -> Unit
    ) {
        val res = activity.resources
        val message = buildString {
            appendLine(res.getString(com.adsamcik.tracker.shared.utils.R.string.permission_rationale_dialog_intro))
            appendLine()
            permissions.forEach { permissionData ->
                val rationale = permissionData.getRationale(activity)
                if (rationale.isNotBlank()) {
                    appendLine("• $rationale")
                } else {
                    appendLine("• ${permissionData.name}")
                }
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(com.adsamcik.tracker.shared.utils.R.string.permission_rationale_title)
            .setMessage(message)
            .setPositiveButton(com.adsamcik.tracker.shared.base.R.string.generic_continue) { _, _ -> onResult(true) }
            .setNegativeButton(com.adsamcik.tracker.shared.base.R.string.generic_cancel) { _, _ -> onResult(false) }
            .setCancelable(false)
            .show()
    }
}

/**
 * Registry to manage permission launchers per activity lifecycle.
 * Ensures launchers are properly registered before use.
 */
private object PermissionLauncherRegistry {
    private val launchers = mutableMapOf<ComponentActivity, PermissionLauncherWrapper>()
    
    fun getOrCreateLauncher(activity: ComponentActivity): PermissionLauncherWrapper {
        return launchers.getOrPut(activity) {
            PermissionLauncherWrapper(activity).also { wrapper ->
                // Clean up when activity is destroyed
                activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                        launchers.remove(activity)
                    }
                })
            }
        }
    }
}

/**
 * Wrapper for Activity Result launcher to handle permission requests.
 */
private class PermissionLauncherWrapper(private val activity: ComponentActivity) {
    private var currentRequest: PermissionRequest? = null
    private var alreadyGranted: List<PermissionData> = emptyList()
    
    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val request = currentRequest ?: return@registerForActivityResult
            currentRequest = null
            
            val grantedResults = alreadyGranted.map { 
                PermissionResult(data = it, isSuccess = true, isForeverDenied = false)
            }.toMutableList()
            
            val deniedResults = mutableListOf<PermissionResult>()
            
            permissions.forEach { (permissionName, granted) ->
                val permissionData = request.permissionList.find { it.name == permissionName }
                if (permissionData != null) {
                    if (granted) {
                        grantedResults.add(
                            PermissionResult(data = permissionData, isSuccess = true, isForeverDenied = false)
                        )
                    } else {
                        val isForeverDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                            activity,
                            permissionName
                        )
                        deniedResults.add(
                            PermissionResult(
                                data = permissionData,
                                isSuccess = false,
                                isForeverDenied = isForeverDenied
                            )
                        )
                    }
                }
            }
            
            request.resultCallback(PermissionRequestResult(grantedResults, deniedResults))
            alreadyGranted = emptyList()
        }
    
    fun launch(request: PermissionRequest, alreadyGrantedPermissions: List<PermissionData>) {
        currentRequest = request
        alreadyGranted = alreadyGrantedPermissions
        
        val permissionNames = request.permissionList
            .filterNot { alreadyGrantedPermissions.contains(it) }
            .map { it.name }
            .toTypedArray()
        
        if (permissionNames.isEmpty()) {
            // All already granted (shouldn't happen, but handle gracefully)
            val results = alreadyGrantedPermissions.map { 
                PermissionResult(data = it, isSuccess = true, isForeverDenied = false)
            }
            request.resultCallback(PermissionRequestResult(results, emptyList()))
            currentRequest = null
            alreadyGranted = emptyList()
        } else {
            launcher.launch(permissionNames)
        }
    }
}
