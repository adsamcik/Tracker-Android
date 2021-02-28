package com.adsamcik.tracker.tracker.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver
import com.adsamcik.tracker.tracker.TRACKER_LOG_SOURCE
import com.adsamcik.tracker.tracker.ui.receiver.SessionUpdateReceiver

/**
 * View model for tracker fragment.
 */
internal class TrackerViewModel(application: Application) : AndroidViewModel(application) {

	init {
		val context = getApplication<Application>().applicationContext
		context.sendBroadcast(
				Intent(TrackerUpdateReceiver.ACTION_REGISTER_COMPONENT).putExtra(
						TrackerUpdateReceiver.RECEIVER_LISTENER_REGISTRATION_CLASSNAME,
						SessionUpdateReceiver::class.java.name
				),
				TrackerSession.BROADCAST_PERMISSION
		)
		Logger.log(
				LogData(
						message = "Attempted tracker listener registration",
						source = TRACKER_LOG_SOURCE
				)
		)
	}

	override fun onCleared() {
		super.onCleared()
		val context = getApplication<Application>().applicationContext
		context.sendBroadcast(
				Intent(TrackerUpdateReceiver.ACTION_UNREGISTER_COMPONENT).putExtra(
						TrackerUpdateReceiver.RECEIVER_LISTENER_REGISTRATION_CLASSNAME,
						SessionUpdateReceiver::class.java.name
				),
				TrackerSession.BROADCAST_PERMISSION
		)
		Logger.log(
				LogData(
						message = "Attempted tracker listener unregistration",
						source = TRACKER_LOG_SOURCE
				)
		)
	}
}
