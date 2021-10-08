package com.adsamcik.tracker.tracker.receiver


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver.Companion.RECEIVER_LISTENER_REGISTRATION_CLASSNAME
import com.adsamcik.tracker.tracker.TRACKER_LOG_SOURCE
import com.adsamcik.tracker.tracker.module.TrackerListenerManager

/**
 * Receives tracker listener registrations.
 */
internal class TrackerListenerRegistrationReceiver : BroadcastReceiver() {

	private fun resolveClass(intent: Intent): Class<TrackerUpdateReceiver>? {
		val className = intent.getStringExtra(RECEIVER_LISTENER_REGISTRATION_CLASSNAME)
		if (className == null) {
			Reporter.report("Received listener intent without class name")
			return null
		} else if (!className.startsWith(BASE_CLASS_NAME)) {
			Reporter.report("Received listener with invalid class name. \"$className\"")
			return null
		} else {
			try {
				@Suppress("unchecked_cast")
				return Class.forName(className) as Class<TrackerUpdateReceiver>
			} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
				Reporter.report(e)
				return null
			}
		}
	}

	private fun registerIntent(context: Context, intent: Intent) {
		val jClass = resolveClass(intent)
		if (jClass != null) {
			try {
				val instance = jClass.newInstance()
				TrackerListenerManager.register(context, instance)
				Logger.log(
						LogData(
								message = "Registered tracker update listener ${jClass.name}",
								source = TRACKER_LOG_SOURCE
						)
				)
			} catch (e: IllegalAccessException) {
				Reporter.report(e)
			} catch (e: InstantiationException) {
				Reporter.report(e)
			}
		}
	}

	private fun unregisterIntent(intent: Intent) {
		val jClass = resolveClass(intent)
		if (jClass != null) {
			TrackerListenerManager.unregister(jClass)
		}
	}

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			TrackerUpdateReceiver.ACTION_REGISTER_COMPONENT -> registerIntent(context, intent)
			TrackerUpdateReceiver.ACTION_UNREGISTER_COMPONENT -> unregisterIntent(intent)
			else -> Reporter.report("Unknown intent action ${intent.action} in package ${intent.`package`}")
		}
	}

	companion object {
		private const val BASE_CLASS_NAME = "com.adsamcik.tracker"
	}
}

