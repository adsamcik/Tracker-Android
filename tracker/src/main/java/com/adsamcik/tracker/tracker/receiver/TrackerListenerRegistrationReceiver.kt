package com.adsamcik.tracker.tracker.receiver


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.TrackerSession.Companion.RECEIVER_LISTENER_REGISTRATION_CLASSNAME
import com.adsamcik.tracker.shared.utils.module.PostTrackerPublicComponent

/**
 * Receives notification actions
 */
internal class TrackerListenerRegistrationReceiver(
		private val registrationListener: (PostTrackerPublicComponent) -> Unit
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		val className = intent.getStringExtra(RECEIVER_LISTENER_REGISTRATION_CLASSNAME)
		if (className == null) {
			Reporter.report("Received listener registration intent without class name")
		} else if (!className.startsWith(BASE_CLASS_NAME)) {
			Reporter.report("Received listener registration with invalid class name. \"$className\"")
		} else {
			try {
				@Suppress("unchecked_cast")
				val tClass = Class.forName(className) as Class<PostTrackerPublicComponent>
				val instance = tClass.newInstance()
				registrationListener(instance)
			} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
				Reporter.report(e)
			}
		}
	}

	companion object {
		private const val BASE_CLASS_NAME = "com.adsamcik.tracker"
	}
}

