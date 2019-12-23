package com.adsamcik.tracker.shared.utils.fragment

import android.os.Build
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.utils.permission.PermissionCallback
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.shared.utils.permission.PermissionResult

abstract class CorePermissionFragment : CoreUIFragment() {
	private val permissionRequestList = mutableListOf<Pair<Int, PermissionRequest>>()
	private var lastPermissionRequestId = 1000

	@CallSuper
	override fun onRequestPermissionsResult(
			requestCode: Int,
			permissions: Array<out String>,
			grantResults: IntArray
	) {
		val index = permissionRequestList.indexOfFirst { it.first == requestCode }
		require(index >= 0) { "There was no permission request with this id" }

		val request = permissionRequestList.removeAt(index).second
		val result = PermissionResult.newFromResult(permissions, grantResults)
		request.callback.invoke(result)
	}

	/**
	 * Simplifies permission requests to allow for better callbacks.
	 */
	@Synchronized
	fun requestPermissions(request: PermissionRequest) {
		require(request.permissionList.isNotEmpty())

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			val id = ++lastPermissionRequestId

			permissionRequestList.add(id to request)
			requestPermissions(request.permissionList, id)
		}
	}

	/**
	 * Simplifies permission requests to allow for better callbacks.
	 * This is convenience method for shorter syntax.
	 * Equal to calling requestPermission(PermissionRequest).
	 */
	fun requestPermissions(permissions: Array<String>, callback: PermissionCallback) {
		requestPermissions(
				PermissionRequest(
						permissions,
						callback
				)
		)
	}
}
