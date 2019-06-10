package com.adsamcik.signalcollector.game.receiver

import android.content.BroadcastReceiver
import android.content.Context
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.game.R
import com.adsamcik.signalcollector.game.challenge.worker.ChallengeWorker
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

internal object BroadcastManager {
	private val listeners = mutableListOf<BroadcastListener>()

	init {
		listeners.add(BroadcastListener(OnSessionFinishedReceiver::class, this::challengeListener))
	}

	private fun challengeListener(context: Context, listener: BroadcastListener) {
		//todo move this out of BroadcastManager
		val workManager = WorkManager.getInstance(context)

		if (Preferences.getPref(context).getBooleanRes(R.string.settings_game_challenge_enable_key, R.string.settings_game_challenge_enable_default)) {
			val workRequest = OneTimeWorkRequestBuilder<ChallengeWorker>()
					.setInitialDelay(1, TimeUnit.HOURS)
					.addTag("ChallengeQueue")
					.setConstraints(Constraints.Builder()
							.setRequiresBatteryNotLow(true)
							.build()
					).build()
			workManager.enqueue(workRequest)
		}
	}

	fun subscribeToBroadcast(listener: BroadcastListener) {
		listeners.add(listener)
	}

	fun unsusbscribeFromBroadcast(listener: BroadcastListener) {
		listeners.remove(listener)
	}

	fun onBroadcast(context: Context, name: KClass<out BroadcastReceiver>) {
		listeners.filter { it.type == name }.forEach { it.callback.invoke(context, it) }
	}
}

data class BroadcastListener(val type: KClass<out BroadcastReceiver>, val callback: (context: Context, listenerObject: BroadcastListener) -> Unit)