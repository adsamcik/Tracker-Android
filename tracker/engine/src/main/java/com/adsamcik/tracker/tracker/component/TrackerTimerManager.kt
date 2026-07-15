package com.adsamcik.tracker.tracker.component

import android.Manifest
import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.extension.contains
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.utils.permission.PermissionData
import com.adsamcik.tracker.shared.utils.permission.PermissionManager
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.shared.utils.permission.PermissionRequestResult
import com.adsamcik.tracker.shared.utils.permission.PermissionResultCallback
import com.adsamcik.tracker.tracker.R
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
		val titleRes: Int,
		val requiredPermissions: Collection<String>,
		val factory: (CoroutineDispatcher) -> CollectionTriggerComponent,
	)

	private val timerDefinitions = listOf(
		TimerDefinition(
			key = FusedLocationCollectionTrigger::class.java.simpleName,
			titleRes = FusedLocationCollectionTrigger.TITLE_RES,
			requiredPermissions = FusedLocationCollectionTrigger.REQUIRED_PERMISSIONS,
			factory = { FusedLocationCollectionTrigger() },
		),
		TimerDefinition(
			key = AndroidLocationCollectionTrigger::class.java.simpleName,
			titleRes = AndroidLocationCollectionTrigger.TITLE_RES,
			requiredPermissions = AndroidLocationCollectionTrigger.REQUIRED_PERMISSIONS,
			factory = { AndroidLocationCollectionTrigger() },
		),
		TimerDefinition(
			key = HandlerCollectionTrigger::class.java.simpleName,
			titleRes = HandlerCollectionTrigger.TITLE_RES,
			requiredPermissions = emptyList(),
			factory = ::HandlerCollectionTrigger,
		)
	)

	private val defaultDefinition get() = timerDefinitions.first()

	val availableTimerData: List<Pair<String, Int>>
		get() = timerDefinitions.map { it.key to it.titleRes }

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

	/**
	 * Returns true if selected timer needs a location to work.
	 */
	suspend fun currentTimerRequiresLocation(context: Context): Boolean {
		return timerWithKeyRequiredLocation(getSelectedKey(context))
	}

	/**
	 * Returns true if timer with provided key requires location.
	 * If key is not valid, behaviour is undefined.
	 */
	fun timerWithKeyRequiredLocation(key: String): Boolean {
		val definition = timerDefinitions.find { it.key == key } ?: defaultDefinition
		return definition.requiredPermissions.contains {
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
	suspend fun getSelectedKey(context: Context): String {
		return Preferences(context)
			.fetchString(PreferenceKeys.TRACKER_TIMER)
			?: defaultDefinition.key
	}

	/**
	 * Check if timer has all required permissions.
	 *
	 * @param context Context
	 * @param callback Result callback
	 */
	suspend fun checkTimerPermissions(
		context: Context,
		callback: PermissionResultCallback
	) {
		val selected = timerDefinitions.find { it.key == getSelectedKey(context) } ?: defaultDefinition
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
					.newInstance(context)
					.permissions(requiredPermissions)
					.onResult(callback)
					.build()
			)
		}
	}
}
