package com.adsamcik.tracker.tracker.component

import android.content.Context
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.permission.PermissionData
import com.adsamcik.tracker.shared.utils.permission.PermissionManager
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.shared.utils.permission.PermissionRequestResult
import com.adsamcik.tracker.shared.utils.permission.PermissionResultCallback
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.trigger.AndroidLocationCollectionTrigger
import com.adsamcik.tracker.tracker.component.trigger.FusedLocationCollectionTrigger
import com.adsamcik.tracker.tracker.component.trigger.HandlerCollectionTrigger

/**
 * Tracker timer manager - provides simple access to tracker timers.
 */
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
		return Preferences
				.getPref(context)
				.getStringRes(R.string.settings_tracker_timer_key)
				?: getKey(availableTimers[0])
	}

	fun checkTrackingPermissions(
			context: Context,
			callback: PermissionResultCallback
	) {
		val selected = getSelected(context)
		val requiredPermissions = selected.requiredPermissions.map {
			PermissionData(
					it
			) { context ->
				val timerName = context.getString(selected.titleRes)
				context.getString(R.string.permissions_tracker_timer_message, timerName)
			}
		}

		if (requiredPermissions.isEmpty()) {
			callback(PermissionRequestResult(emptyList(), emptyList()))
		} else {
			PermissionManager.checkPermissionsWithRationaleDialog(
					PermissionRequest
							.with(context)
							.permissions(requiredPermissions)
							.onResult(callback)
							.build()
			)
		}
	}
}
