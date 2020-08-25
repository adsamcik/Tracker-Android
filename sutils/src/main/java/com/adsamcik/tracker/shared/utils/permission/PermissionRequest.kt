package com.adsamcik.tracker.shared.utils.permission

import android.content.Context
import androidx.fragment.app.FragmentActivity

typealias PermissionCallback = (result: PermissionRequestResult) -> Unit
typealias RationaleCallback = (token: PermissionRequest.Token, permissionList: List<PermissionData>) -> Unit

/**
 * Permission request
 */
data class PermissionRequest(
		val context: Context,
		val permissionList: List<PermissionData>,
		val callback: PermissionCallback
) {

	class Builder(val context: Context) {

		private val permissions = mutableListOf<PermissionData>()

		private var onPermissionRationale: RationaleCallback? = null

		fun permissions(permissions: Collection<PermissionData>): Builder {
			this.permissions.addAll(permissions)
			return this
		}

		fun permissions(vararg permissions: Pair<String, (context: Context) -> String>): Builder {
			permissions(permissions.map { PermissionData(it.first, it.second) })
			return this
		}

		fun permissionRationale(callback: RationaleCallback) {
			onPermissionRationale = callback
		}

		fun onResult() {

		}
	}

	class Token {
		fun resume() {

		}

		fun cancel() {

		}
	}

	companion object {
		fun with(activity: FragmentActivity): Builder = Builder(activity)
	}
}

/**
 * Permission data
 *
 * @param name Permission name (from manifest)
 * @param rationaleBuilder Rationale text builder
 */
data class PermissionData(val name: String, val rationaleBuilder: (context: Context) -> String)
