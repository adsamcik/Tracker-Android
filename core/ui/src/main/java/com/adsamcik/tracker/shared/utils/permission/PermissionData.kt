package com.adsamcik.tracker.shared.utils.permission

import android.content.Context

/**
 * Permission data containing permission name and optional rationale provider.
 * 
 * Contract: Encapsulates Android permission with user-facing rationale explanation.
 * Inputs: Permission name (manifest string), optional rationale provider function.
 * Outputs: Rationale text via getRationale() for display in permission dialogs.
 */
data class PermissionData(
    val name: String,
    val rationaleProvider: (Context) -> String = { "" }
) {
    
    /**
     * Convenience constructor for permission without custom rationale.
     */
    constructor(permission: String) : this(permission, { "" })
    
    /**
     * Get user-facing rationale explanation for this permission.
     * @param context Context for accessing string resources
     * @return Rationale text, or empty string if none provided
     */
    fun getRationale(context: Context): String = rationaleProvider(context)
}
