package com.adsamcik.tracker.shared.utils.permission

import android.content.Context

/**
 * Permission data (temporary stub for Stage 0)
 */
data class PermissionData(
    val name: String,
    val rationaleProvider: (Context) -> String = { "" }
) {
    
    constructor(permission: String) : this(permission, { "" })
    
    fun getRationale(context: Context): String = rationaleProvider(context)
}
