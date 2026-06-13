package com.adsamcik.tracker.tracker.component

import android.content.Context
import com.adsamcik.tracker.tracker.component.producer.ActivityDataProducer
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.CellDataProducer
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.component.producer.WifiDataProducer
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import java.util.concurrent.CopyOnWriteArrayList
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Manages all data producers (Wi‑Fi, cell, activity, steps, barometer) and runs their collection
 * concurrently.
 *
 * Source separation: producers are no longer gated by the battery tier. Every producer is always
 * constructed; each one self-gates on its own user toggle (via the `enabledFlow` in
 * [TrackerDataProducerComponent]), so any combination of sources can be collected independently of
 * the battery tier / GPS state. The tier only governs the collection trigger cadence, not which
 * sources exist.
 *
 * Performance: Previously this scope was bound to Dispatchers.Main which risked doing telephony
 * and wifi polling work on the main thread. We now switch to an injected Default dispatcher to
 * avoid UI thread contention and potential jank. DispatchersProvider is used for testability.
 */
internal class DataProducerManager(
	context: Context,
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	private val trackingParamsRepository: TrackingParamsRepository? = null,
) : TrackerDataProducerObserver, CoroutineScope {
	private val appContext = context.applicationContext

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = dispatchers.default + job

	/**
	 * Keeps all producers from being recycled. Producers should take only very little memory so this is fine.
	 *
	 * All producers are always created; each self-gates on its own toggle so a disabled source is
	 * simply never enabled (it never joins [activeProducerList]).
	 */
	@Suppress("unused")
	private val producerList = listOf(
		WifiDataProducer(this@DataProducerManager, trackingParamsRepository),
		CellDataProducer(this@DataProducerManager, trackingParamsRepository),
		ActivityDataProducer(this@DataProducerManager, trackingParamsRepository),
		StepDataProducer(this@DataProducerManager, trackingParamsRepository),
		BarometerDataProducer(this@DataProducerManager),
	)

	private val activeProducerList = CopyOnWriteArrayList<TrackerDataProducerComponent>()

	suspend fun onEnable() = coroutineScope {
		producerList.forEach {
			if (it.canBeEnabled) {
				it.onEnable(appContext)
			}
			it.onAttach(appContext)
		}
	}

	override fun onStateChange(shouldBeEnabled: Boolean, component: TrackerDataProducerComponent) {
		if (component.canBeEnabled == shouldBeEnabled) return

		component.canBeEnabled = shouldBeEnabled

		if (shouldBeEnabled) {
			activeProducerList.add(component)
			component.onEnable(appContext)
		} else {
			activeProducerList.remove(component)
			component.onDisable(appContext)
		}
	}


	suspend fun onDisable() = coroutineScope {
		producerList.forEach {
			if (it.isEnabled) {
				it.onDisable(appContext)
			}
			it.onDetach(appContext)
		}
		activeProducerList.clear()
	}

	/**
	 * Runs all active producers, merges their output with the trigger-supplied
	 * [incomingCycle] (which may already carry location data from a GPS trigger),
	 * and returns an enriched [TrackingCycle].
	 */
	suspend fun getData(incomingCycle: TrackingCycle): TrackingCycle {
		val builder = TrackingCycleBuilder(incomingCycle.timestampMs, incomingCycle.elapsedRealtimeNanos)
		// Carry over location from the trigger (if present)
		builder.location = incomingCycle.location
		// Run all active producers in parallel on a background dispatcher to keep main thread free.
		withContext(coroutineContext) {
			activeProducerList.map { producer ->
				async {
					try {
						producer.onDataRequest(builder)
					} catch (e: CancellationException) {
						throw e
					} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
						Log.e(TAG, "Producer ${producer::class.simpleName} failed: ${e.message}", e)
					}
				}
			}.awaitAll()
		}
		return builder.build()
	}

	companion object {
		private const val TAG = "DataProducerManager"
	}
}

