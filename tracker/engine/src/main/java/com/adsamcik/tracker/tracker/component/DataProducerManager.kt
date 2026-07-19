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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
	private val stepProducer = StepDataProducer(this@DataProducerManager, trackingParamsRepository)
	private val producerList = listOf(
		WifiDataProducer(this@DataProducerManager, trackingParamsRepository),
		CellDataProducer(this@DataProducerManager, trackingParamsRepository),
		ActivityDataProducer(this@DataProducerManager, trackingParamsRepository),
		stepProducer,
		BarometerDataProducer(this@DataProducerManager, trackingParamsRepository),
	)

	private val activeProducerList = CopyOnWriteArrayList<TrackerDataProducerComponent>()
	private val lifecycleMutex = Mutex()
	private val disableRetryLock = Any()
	private val disableRetryJobs = mutableMapOf<TrackerDataProducerComponent, Job>()

	suspend fun onEnable() = lifecycleMutex.withLock {
		producerList.forEach { producer ->
			if (producer.canBeEnabled && !producer.isEnabled) {
				producer.onEnable(appContext)
				activeProducerList.addIfAbsent(producer)
			}
			producer.onAttach(appContext)
		}
	}

	override suspend fun onStateChange(
		shouldBeEnabled: Boolean,
		component: TrackerDataProducerComponent,
	) = lifecycleMutex.withLock {
		if (component.canBeEnabled == shouldBeEnabled && component.isEnabled == shouldBeEnabled) {
			return@withLock
		}

		if (shouldBeEnabled) {
			cancelDisableRetry(component)
			val previousCanBeEnabled = component.canBeEnabled
			component.canBeEnabled = true
			if (component.isEnabled) {
				activeProducerList.addIfAbsent(component)
				return@withLock
			}
			try {
				component.onEnable(appContext)
				activeProducerList.addIfAbsent(component)
			} catch (e: CancellationException) {
				component.canBeEnabled = previousCanBeEnabled
				throw e
			} catch (e: Exception) {
				try {
					component.onDisable(appContext)
				} catch (cleanupFailure: Exception) {
					e.addSuppressed(cleanupFailure)
				}
				component.canBeEnabled = previousCanBeEnabled
				Log.e(TAG, "Failed to enable ${component::class.simpleName}", e)
			}
		} else if (component.isEnabled) {
			component.canBeEnabled = false
			activeProducerList.remove(component)
			try {
				component.onDisable(appContext)
				cancelDisableRetry(component)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.e(TAG, "Failed to disable ${component::class.simpleName}", e)
				scheduleDisableRetry(component)
			}
		} else {
			component.canBeEnabled = false
			activeProducerList.remove(component)
			cancelDisableRetry(component)
		}
	}


	suspend fun onDisable() {
		cancelAllDisableRetries()
		lifecycleMutex.withLock {
			val failures = mutableListOf<IllegalStateException>()
			producerList.forEach { producer ->
				try {
					producer.onDetach(appContext)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					failures += IllegalStateException(
						"Failed to detach ${producer::class.simpleName}",
						e,
					)
				}
			}
			producerList.forEach { producer ->
				producer.canBeEnabled = false
				activeProducerList.remove(producer)
				try {
					if (producer.isEnabled) {
						producer.onDisable(appContext)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					failures += IllegalStateException(
						"Failed to disable ${producer::class.simpleName}",
						e,
					)
				}
			}
			if (failures.isNotEmpty()) {
				throw IllegalStateException(
					"Failed to clean up ${failures.size} tracking data producer operation(s)",
					failures.first(),
				).also { aggregate ->
					failures.drop(1).forEach(aggregate::addSuppressed)
				}
			}
		}
	}

	private fun scheduleDisableRetry(component: TrackerDataProducerComponent) {
		val retryJob = launch(start = CoroutineStart.LAZY) {
			var retryDelayMillis = PRODUCER_CLEANUP_RETRY_DELAY_MILLIS
			val currentJob = currentCoroutineContext()[Job]
			try {
				while (!component.canBeEnabled && component.isEnabled) {
					delay(retryDelayMillis)
					val cleaned = lifecycleMutex.withLock {
						if (component.canBeEnabled || !component.isEnabled) {
							true
						} else {
							try {
								component.onDisable(appContext)
								activeProducerList.remove(component)
								true
							} catch (e: CancellationException) {
								throw e
							} catch (e: Exception) {
								Log.e(TAG, "Retry failed to disable ${component::class.simpleName}", e)
								false
							}
						}
					}
					if (cleaned) return@launch
					retryDelayMillis = (retryDelayMillis * 2)
						.coerceAtMost(PRODUCER_CLEANUP_MAX_RETRY_DELAY_MILLIS)
				}
			} finally {
				synchronized(disableRetryLock) {
					if (disableRetryJobs[component] === currentJob) {
						disableRetryJobs.remove(component)
					}
				}
			}
		}
		val previousJob = synchronized(disableRetryLock) {
			disableRetryJobs.put(component, retryJob)
		}
		previousJob?.cancel()
		retryJob.start()
	}

	private fun cancelDisableRetry(component: TrackerDataProducerComponent) {
		synchronized(disableRetryLock) {
			disableRetryJobs.remove(component)
		}?.cancel()
	}

	private fun cancelAllDisableRetries() {
		val jobs = synchronized(disableRetryLock) {
			disableRetryJobs.values.toList().also { disableRetryJobs.clear() }
		}
		jobs.forEach(Job::cancel)
	}

	suspend fun flushPendingSensorBatches() {
		lifecycleMutex.withLock {
			if (stepProducer.isEnabled) {
				stepProducer.flushPendingEvents()
			}
		}
	}

	/**
	 * Runs all active producers, merges their output with the trigger-supplied
	 * [incomingCycle] (which may already carry location data from a GPS trigger),
	 * and returns an enriched [TrackingCycle].
	 */
	suspend fun getData(incomingCycle: TrackingCycle): TrackingCycle = lifecycleMutex.withLock {
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
		builder.build()
	}

	companion object {
		private const val TAG = "DataProducerManager"
		private const val PRODUCER_CLEANUP_RETRY_DELAY_MILLIS = 1_000L
		private const val PRODUCER_CLEANUP_MAX_RETRY_DELAY_MILLIS = 30_000L
	}
}
