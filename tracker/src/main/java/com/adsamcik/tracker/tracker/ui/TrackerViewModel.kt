package com.adsamcik.tracker.tracker.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver
import com.adsamcik.tracker.tracker.TRACKER_LOG_SOURCE
import com.adsamcik.tracker.tracker.ui.receiver.SessionUpdateReceiver

/**
 * View model for tracker fragment (legacy, deprecated).
 * 
 * @deprecated Used in legacy Fragment architecture. Migrate to Compose route with constructor-injected dependencies.
 * See TrackerRoute for Compose-based alternative with proper DI via AppGraph.
 */
@Deprecated(
	message = "Legacy AndroidViewModel. Migrate to Compose route with constructor-injected repository.",
	level = DeprecationLevel.WARNING
)
internal class TrackerViewModel(application: Application) : AndroidViewModel(application) {

	init {
		val context = getApplication<Application>().applicationContext
		getBaseIntent(context).also { intent ->
			intent.setAction(TrackerUpdateReceiver.ACTION_REGISTER_COMPONENT)
			context.sendBroadcast(intent)
		}
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

		getBaseIntent(context).also { intent ->
			intent.setAction(TrackerUpdateReceiver.ACTION_UNREGISTER_COMPONENT)
			context.sendBroadcast(intent)
		}
		Logger.log(
				LogData(
						message = "Attempted tracker listener unregistration",
						source = TRACKER_LOG_SOURCE
				)
		)
	}

	private fun getBaseIntent(context: Context) = Intent().also { intent ->
		intent.putExtra( TrackerUpdateReceiver.RECEIVER_LISTENER_REGISTRATION_CLASSNAME, SessionUpdateReceiver::class.java.name)
		intent.setPackage(context.packageName)
	}
}
