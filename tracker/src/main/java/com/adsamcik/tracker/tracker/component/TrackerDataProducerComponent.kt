package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.logger.assertTrue
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal abstract class TrackerDataProducerComponent(
    private val changeReceiver: TrackerDataProducerObserver,
    dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) {
    private val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main
    private val preferenceScope = CoroutineScope(SupervisorJob() + mainImmediate)
    private var preferenceJob: Job? = null

	protected abstract val keyRes: Int
	protected abstract val defaultRes: Int

	var isEnabled: Boolean = false
		private set

	var canBeEnabled: Boolean = false

	fun onAttach(context: Context) {
		// Flow emits initial value immediately, no need for separate sync read
		preferenceJob?.cancel()
		preferenceJob = PreferenceFlows.boolean(context, keyRes, defaultRes)
			.onEach { changeReceiver.onStateChange(it, this) }
			.launchIn(preferenceScope)
	}

	fun onDetach(context: Context) {
		preferenceJob?.cancel()
		preferenceJob = null
		preferenceScope.cancel()
	}

	@CallSuper
	open fun onEnable(context: Context) {
		assertTrue(canBeEnabled)
		isEnabled = true
	}

	@CallSuper
	open fun onDisable(context: Context) {
		assertTrue(isEnabled)
		isEnabled = false
	}

	abstract fun onDataRequest(builder: TrackingCycleBuilder)
}
