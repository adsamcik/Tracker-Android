package com.adsamcik.tracker.tracker.component

import android.content.Context
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.trigger.AndroidLocationCollectionTrigger
import com.adsamcik.tracker.tracker.component.trigger.FusedLocationCollectionTrigger
import com.adsamcik.tracker.tracker.component.trigger.HandlerCollectionTrigger

object TrackerTimerManager {
	internal val availableTimers: List<CollectionTriggerComponent>
		get() = listOf(
				FusedLocationCollectionTrigger(),
				AndroidLocationCollectionTrigger(),
				HandlerCollectionTrigger()
		)

	val availableTimerData: List<Pair<String, Int>>
		get() = availableTimers.map { getKey(it) to it.titleRes }

	private fun getKey(timerComponent: CollectionTriggerComponent) =
			timerComponent::class.java.simpleName

	internal fun getSelected(context: Context): CollectionTriggerComponent {
		val selectedKey = getSelectedKey(context)
		return requireNotNull(availableTimers.find { getKey(it) == selectedKey })
	}

	fun getSelectedKey(context: Context): String {
		return Preferences.getPref(context).getStringRes(R.string.settings_tracker_timer_key)
				?: getKey(availableTimers[0])
	}
}
