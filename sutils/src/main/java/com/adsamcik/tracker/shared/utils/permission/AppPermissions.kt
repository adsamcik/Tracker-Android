package com.adsamcik.tracker.shared.utils.permission

import androidx.fragment.app.FragmentActivity

class AppPermissions {

	class Builder(val activity: FragmentActivity) {

		private val permissions = mutableListOf<PermissionData>()

		fun permissions(permissions: Collection<PermissionData>) {
			this.permissions.addAll(permissions)
		}

		fun permissions(vararg permissions: Pair<String, Int>) {
			permissions(permissions.map { PermissionData(it.first, it.second) })
		}

		fun setOnPermissionsRationale() {

		}

		fun onDenied() {

		}

		fun onForeverDenied() {

		}
	}

	companion object {
		fun with(activity: FragmentActivity): AppPermissions.Builder = Builder(activity)
	}
}

