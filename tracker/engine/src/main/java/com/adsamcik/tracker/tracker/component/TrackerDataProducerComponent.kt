package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.logger.assertTrue
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal abstract class TrackerDataProducerComponent(
    private val changeReceiver: TrackerDataProducerObserver,
    dispatchers: DispatchersProvider = DefaultDispatchersProvider,
    private val enabledFlow: Flow<Boolean>? = null,
) {
    private val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main
    private val preferenceScope = CoroutineScope(SupervisorJob() + mainImmediate)
    private var preferenceJob: Job? = null

	protected abstract val preferenceKey: String
	protected abstract val preferenceDefault: Boolean

	var isEnabled: Boolean = false
		private set

	var canBeEnabled: Boolean = false

	fun onAttach(context: Context) {
		// Flow emits initial value immediately, no need for separate sync read
		preferenceJob?.cancel()
		preferenceJob = (enabledFlow ?: PreferenceFlows.boolean(context, preferenceKey, preferenceDefault))
			.distinctUntilChanged()
			.onEach { changeReceiver.onStateChange(it, this) }
			.launchIn(preferenceScope)
	}

	suspend fun onDetach(context: Context) {
		preferenceJob?.cancelAndJoin()
		preferenceJob = null
		// Only cancel the job, not the scope — scope is reused across attach/detach cycles
	}

	@CallSuper
	open suspend fun onEnable(context: Context) {
		assertTrue(canBeEnabled)
		isEnabled = true
	}

	@CallSuper
	open suspend fun onDisable(context: Context) {
		isEnabled = false
	}

	abstract fun onDataRequest(builder: TrackingCycleBuilder)
}
