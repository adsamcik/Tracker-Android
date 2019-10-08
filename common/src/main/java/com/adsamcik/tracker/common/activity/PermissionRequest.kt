package com.adsamcik.tracker.common.activity

typealias PermissionCallback = (result: PermissionResult) -> Unit

data class PermissionRequest(
		val permissionList: Array<String>,
		val callback: PermissionCallback
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as PermissionRequest

		if (!permissionList.contentEquals(other.permissionList)) return false
		if (callback != other.callback) return false

		return true
	}

	override fun hashCode(): Int {
		var result = permissionList.contentHashCode()
		result = 31 * result + callback.hashCode()
		return result
	}
}
