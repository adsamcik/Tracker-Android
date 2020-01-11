package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.annotation.WorkerThread

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
 *
 */
internal class NoTimer : CollectionTriggerComponent {
	override val titleRes: Int get() = 0

	override val requiredPermissions: Collection<String> get() = emptyList()

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		throw TrackerTimerNotInitializedException()
	}

	override fun onDisable(context: Context) {
		throw TrackerTimerNotInitializedException()
	}
}

internal class TrackerTimerNotInitializedException : Exception()
