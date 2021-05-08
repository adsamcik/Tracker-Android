package com.adsamcik.tracker.tracker.component

import android.Manifest
import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.extension.contains
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
 * Provides simple access to tracker timers.
 */
object TrackerTimerManager {
	private val availableTimers: List<CollectionTriggerComponent>
		get() = listOf(
			FusedLocationCollectionTrigger(),
			AndroidLocationCollectionTrigger(),
			HandlerCollectionTrigger()
		)

	private val default get() = availableTimers[0]

	val availableTimerData: List<Pair<String, Int>>
		get() = availableTimers.map { getKey(it) to it.titleRes }

	private fun getKey(timerComponent: CollectionTriggerComponent) =
		timerComponent::class.java.simpleName

	internal fun getSelected(context: Context): CollectionTriggerComponent {
		val selectedKey = getSelectedKey(context)
		return get(selectedKey)
	}

	internal fun get(key: String): CollectionTriggerComponent {
		val timer = availableTimers.find { getKey(it) == key }
		return if (timer == null) {
			Reporter.report("Timer with key $key was not found.")
			default
		} else {
			timer
		}
	}

	/**
	 * Returns true if selected timer needs a location to work.
	 */
	fun currentTimerRequiresLocation(context: Context): Boolean {
		return timerWithKeyRequiredLocation(getSelectedKey(context))
	}

	/**
	 * Returns true if timer with provided key requires location.
	 * If key is not valid, behaviour is undefined.
	 */
	fun timerWithKeyRequiredLocation(key: String): Boolean {
		val timer = get(key)
		return timer.requiredPermissions.contains {
			it == Manifest.permission.ACCESS_FINE_LOCATION ||
					it == Manifest.permission.ACCESS_COARSE_LOCATION
		}
	}

	/**
	 * Returns selected timer key.
	 *
	 * @param context Context
	 * @return Selected timer key or default
	 */
	fun getSelectedKey(context: Context): String {
		return Preferences
			.getPref(context)
			.getStringRes(R.string.settings_tracker_timer_key)
			?: getKey(default)
	}

	/**
	 * Check if timer has all required permissions.
	 *
	 * @param context Context
	 * @param callback Result callback
	 */
	fun checkTimerPermissions(
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
