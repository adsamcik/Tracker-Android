package com.adsamcik.tracker.shared.utils.fragment

import com.adsamcik.tracker.shared.utils.permission.PermissionRequest

/**
 * Temporary minimal shim for CorePermissionFragment to maintain compilation.
 * This should be replaced with proper Activity Result API implementations.
 */
abstract class CorePermissionFragment : CoreUIFragment() {

    /**
     * Temporary stub - replace with Activity Result API
     */
    @Synchronized
    fun requestPermissions(request: PermissionRequest) {
        // TODO: Replace with Activity Result API implementation
        // For now, just use the PermissionManager stub
        com.adsamcik.tracker.shared.utils.permission.PermissionManager.checkPermissions(request)
    }
}
