package com.adsamcik.tracker.shared.utils.fragment

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.utils.permission.PermissionManager
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.shared.utils.permission.PermissionRequestResult

/**
 * Fragment extending [CoreUIFragment] with permission utility.
 * Supports callbacks for permission requests using modern Activity Result API.
 */
abstract class CorePermissionFragment : CoreUIFragment() {
	private var currentPermissionRequest: PermissionRequest? = null

	private val permissionLauncher: ActivityResultLauncher<Array<String>> = 
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
			val request = currentPermissionRequest ?: return@registerForActivityResult
			currentPermissionRequest = null
			
			val permissionArray = permissions.keys.toTypedArray()
			val grantResults = permissions.values.map { if (it) 0 else -1 }.toIntArray()
			
			val result = PermissionRequestResult.newFromResult(permissionArray, grantResults, request)
			request.resultCallback.invoke(result)
		}

	/**
	 * Simplifies permission requests to allow for better callbacks using modern Activity Result API.
	 */
	@Synchronized
	fun requestPermissions(request: PermissionRequest) {
		require(request.permissionList.isNotEmpty())
		require(currentPermissionRequest == null) { "Another permission request is already in progress" }

		currentPermissionRequest = request
		
		// Use PermissionManager for rationale dialog, but handle result with modern API
		val requestWithModernCallback = PermissionRequest.from(request)
			.onResult { _ ->
				// Launch the permission request using Activity Result API
				val permissionNames = request.permissionList.map { it.name }.toTypedArray()
				permissionLauncher.launch(permissionNames)
			}
			.build()
		
		PermissionManager.checkPermissionsWithRationaleDialog(requestWithModernCallback)
	}
}
