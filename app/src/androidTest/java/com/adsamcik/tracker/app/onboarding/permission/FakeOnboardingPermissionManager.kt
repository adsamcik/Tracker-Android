package com.adsamcik.tracker.app.onboarding.permission

import androidx.activity.ComponentActivity
import com.adsamcik.tracker.app.onboarding.data.Permission

/**
 * Test double that grants all permissions deterministically.
 */
class FakeOnboardingPermissionManager(override val activity: ComponentActivity) : IOnboardingPermissionManager {
    private val granted = mutableSetOf<Permission>().apply { addAll(Permission.values()) }

    override fun isPermissionGranted(permission: Permission): Boolean = granted.contains(permission)
    override fun shouldShowRequestPermissionRationale(permission: Permission): Boolean = false
    override suspend fun requestPermission(permission: Permission): PermissionResult = PermissionResult.Granted
    override fun getGrantedPermissions(): Set<Permission> = granted
    override fun getPermissionDisplayName(permission: Permission): String = permission.name
    override fun getPermissionDescription(permission: Permission): String = permission.name
}
