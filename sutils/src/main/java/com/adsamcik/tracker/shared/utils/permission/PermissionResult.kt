package com.adsamcik.tracker.shared.utils.permission

import android.content.pm.PackageManager
import com.adsamcik.tracker.shared.utils.debug.Reporter
import com.karumi.dexter.MultiplePermissionsReport

data class PermissionResult(
		private val grantedPermissions: List<String>,
		private val deniedPermissions: List<String>
) {
	val isCancelled get() = grantedPermissions.isNotEmpty().and(deniedPermissions.isNotEmpty())
	val isSuccess get() = grantedPermissions.isNotEmpty().and(deniedPermissions.isEmpty())

	companion object {
		internal fun newFromResult(
				permissions: Array<out String>,
				grantResults: IntArray
		): PermissionResult {
			require(permissions.size == grantResults.size)
			val size = permissions.size

			val granted = mutableListOf<String>()
			val denied = mutableListOf<String>()

			for (i in 0 until size) {
				val permission = permissions[i]
				when (grantResults[i]) {
					PackageManager.PERMISSION_GRANTED -> granted.add(permission)
					PackageManager.PERMISSION_DENIED -> denied.add(permission)
					else -> Reporter.report("Unknown result ${grantResults[i]}")
				}
			}
			return PermissionResult(
					granted,
					denied
			)
		}
	}
}
