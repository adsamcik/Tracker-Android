package com.adsamcik.tracker.tracker.component

import android.Manifest
import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import com.adsamcik.tracker.shared.base.extension.hasSelfPermissions

/**
 * Collection trigger component.
 * Has responsibility for triggering collections.
 */
internal interface CollectionTriggerComponent {
	/**
	 * Title resource id
	 */
	val titleRes: Int

	/**
	 * List of required permission
	 */
	val requiredPermissions: Collection<String>

	/** Whether this trigger actively requests device location. */
	val isLocationTrigger: Boolean get() = false

	/**
	 * Checks if component has all required permissions to run.
	 * For location permissions, accepts partial grants (either fine OR coarse).
	 */
	fun hasRequiredPermissions(context: Context): Boolean {
		// Special handling for location permissions: accept if ANY location permission is granted
		val locationPermissions = setOf(
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION
		)
		
		val hasLocationPermission = requiredPermissions.any { it in locationPermissions }
		
		if (hasLocationPermission) {
			// Check if at least one location permission is granted
			val hasAnyLocationGranted = locationPermissions.any { context.hasSelfPermission(it) }
			if (!hasAnyLocationGranted) return false
			
			// Check all non-location permissions
			val nonLocationPermissions = requiredPermissions.filterNot { it in locationPermissions }
			return context.hasSelfPermissions(nonLocationPermissions).all { it }
		}
		
		// No location permissions required, check all normally
		return context.hasSelfPermissions(requiredPermissions).all { it }
	}

	/**
	 * Called when component is enabled.
	 *
	 * @param context Context
	 * @param receiver Receiver called to trigger collection. Must be called on WorkerThread.
	 */
	fun onEnable(context: Context, @WorkerThread receiver: TrackerTimerReceiver)

	/**
	 * Called when component is disabled.
	 * It should immediately stop triggering collections and prepare for GC collection.
	 * However [onEnable] can be still called later on on this instance.
	 */
	fun onDisable(context: Context)
}

/**
 * Extended collection trigger interface for timers that support dynamic interval updates.
 * Allows policy-based adjustment of collection frequency without restarting the timer.
 */
internal interface DynamicIntervalCollectionTrigger : CollectionTriggerComponent {
	/**
	 * Update the collection interval dynamically.
	 *
	 * @param context Context
	 * @param intervalSeconds New interval in seconds between collections
	 * @param minDistanceMeters Minimum distance in meters for location-based triggers
	 */
	fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int)
}

/**
 *
 */
internal class NoTimer : CollectionTriggerComponent {
	override val titleRes: Int get() = 0

	override val requiredPermissions: Collection<String> get() = emptyList()

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		throw TrackerTimerNotInitializedException()
	}

	// Teardown must be safe to call even when no real timer was ever installed
	// (e.g. TrackerService.onDestroy after OS-initiated kill on permission revoke).
	// Throwing here surfaced as a crash in TrackingOrchestrator.shutdown when the
	// service shut down before it had a chance to swap the placeholder.
	override fun onDisable(context: Context) = Unit
}

internal class TrackerTimerNotInitializedException : Exception()
