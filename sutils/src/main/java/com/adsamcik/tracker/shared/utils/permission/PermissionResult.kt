package com.adsamcik.tracker.shared.utils.permission

import android.content.pm.PackageManager
import com.adsamcik.tracker.logger.Reporter

/**
 * Results from permission request
 */
data class PermissionRequestResult(
		private val granted: List<PermissionResult>,
		private val denied: List<PermissionResult>
) {
	val isSuccess: Boolean = denied.isEmpty()

	companion object {

		private fun findInRequest(
				name: String,
				index: Int,
				request: PermissionRequest
		): PermissionData {
			val current = request.permissionList[index]
			if (current.name == name) return current

			return requireNotNull(request.permissionList.find { it.name == name })
		}

		internal fun newFromResult(
				permissions: Array<out String>,
				grantResults: IntArray,
				request: PermissionRequest
		): PermissionRequestResult {
			require(permissions.size == grantResults.size)
			val size = permissions.size

			val granted = mutableListOf<PermissionResult>()
			val denied = mutableListOf<PermissionResult>()

			for (i in 0 until size) {
				val permission = permissions[i]
				val data = findInRequest(permission, i, request)

				when (grantResults[i]) {
					PackageManager.PERMISSION_GRANTED -> granted.add(
							PermissionResult(
									data,
									isSuccess = true,
									isForeverDenied = false
							)
					)
					PackageManager.PERMISSION_DENIED -> denied.add(
							PermissionResult(
									data,
									isSuccess = false,
									isForeverDenied = true
							)
					)
					else -> Reporter.report("Unknown result ${grantResults[i]}")
				}
			}
			return PermissionRequestResult(granted, denied)
		}
	}
}

/**
 * Result from permission request
 */
data class PermissionResult(
		val data: PermissionData,
		val isSuccess: Boolean,
		val isForeverDenied: Boolean
)
