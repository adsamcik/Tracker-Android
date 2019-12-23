package com.adsamcik.tracker.shared.base


import android.app.ActivityManager
import android.content.Context
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped


object Process {
	fun getName(context: Context): String? {
		val pid = android.os.Process.myPid()
		val manager = context.getSystemServiceTyped<ActivityManager>(Context.ACTIVITY_SERVICE)
		return manager.runningAppProcesses?.find { it.pid == pid }?.processName
	}

	/**
	 * Returns true if process is main. Assumes process name is the same as package.
	 *
	 * @return True if process is main or detection fails, because there is not much else to be done.
	 */
	//todo test this
	fun isMainProcess(context: Context): Boolean {
		return context.applicationInfo.processName == getName(context)
	}
}
