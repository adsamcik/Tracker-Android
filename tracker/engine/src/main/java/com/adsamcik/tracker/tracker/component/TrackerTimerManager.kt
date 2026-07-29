package com.adsamcik.tracker.tracker.component

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.tracker.component.trigger.AndroidLocationCollectionTrigger
import com.adsamcik.tracker.tracker.component.trigger.FusedLocationCollectionTrigger
import com.adsamcik.tracker.tracker.component.trigger.HandlerCollectionTrigger
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Provides simple access to tracker timers.
 */
object TrackerTimerManager {
	private data class TimerDefinition(
		val key: String,
		val factory: (CoroutineDispatcher) -> CollectionTriggerComponent,
	)

	private val timerDefinitions = listOf(
		TimerDefinition(
			key = FusedLocationCollectionTrigger::class.java.simpleName,
			factory = { FusedLocationCollectionTrigger() },
		),
		TimerDefinition(
			key = AndroidLocationCollectionTrigger::class.java.simpleName,
			factory = { AndroidLocationCollectionTrigger() },
		),
		TimerDefinition(
			key = HandlerCollectionTrigger::class.java.simpleName,
			factory = ::HandlerCollectionTrigger,
		)
	)

	private val defaultDefinition get() = timerDefinitions.first()

	internal suspend fun getSelected(
		context: Context,
		dispatcher: CoroutineDispatcher,
	): CollectionTriggerComponent {
		val selectedKey = getSelectedKey(context)
		return get(selectedKey, dispatcher)
	}

	internal fun get(
		key: String,
		dispatcher: CoroutineDispatcher,
	): CollectionTriggerComponent {
		val definition = timerDefinitions.find { it.key == key }
		return if (definition == null) {
			Reporter.report("Timer with key $key was not found.")
			defaultDefinition.factory(dispatcher)
		} else {
			definition.factory(dispatcher)
		}
	}

	private suspend fun getSelectedKey(context: Context): String {
		return Preferences(context)
			.fetchString(PreferenceKeys.TRACKER_TIMER)
			?: defaultDefinition.key
	}
}
