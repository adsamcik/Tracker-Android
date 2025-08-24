package com.adsamcik.tracker.shared.utils.permission

import android.content.Context

/**
 * Temporary minimal shim for PermissionManager to maintain compilation.
 * This should be replaced with proper Activity Result API implementations.
 */
object PermissionManager {

    /**
     * Temporary stub - replace with Activity Result API
     */
    fun checkPermissions(permissionRequest: PermissionRequest) {
        // TODO: Replace with Activity Result API implementation
        // For now, just call the callback with denied status to prevent crashes
        val deniedResults = permissionRequest.permissionList.map { permissionData ->
            PermissionResult(
                data = permissionData,
                isSuccess = false,
                isForeverDenied = false
            )
        }
        
        val result = PermissionRequestResult(
            granted = emptyList(),
            denied = deniedResults
        )
        
        permissionRequest.resultCallback(result)
    }

    /**
     * Temporary stub - replace with Activity Result API
     */
    fun checkPermissionsWithRationaleDialog(permissionRequest: PermissionRequest) {
        // TODO: Replace with Activity Result API implementation
        checkPermissions(permissionRequest)
    }

    /**
     * Temporary stub - replace with Activity Result API
     */
    fun checkActivityPermissions(context: Context, callback: PermissionResultCallback) {
        // TODO: Replace with Activity Result API implementation
        callback(PermissionRequestResult(emptyList(), emptyList()))
    }
}
