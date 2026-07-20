package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.engine.BuildConfig
import com.adsamcik.tracker.tracker.component.DataProducerManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.component.trigger.AmbientCollectionTrigger
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.PlaneTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SailingTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SkiSegmentWriter
import com.adsamcik.tracker.tracker.component.consumer.post.SkiTrackingComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.isLocationObservationOnly
import com.adsamcik.tracker.tracker.data.collection.hasPersistableProducerPayload
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.TrackingPipeline
import com.adsamcik.tracker.tracker.pipeline.toLocationObservationSignal
import com.adsamcik.tracker.tracker.pipeline.stages.DataCollectionStage
import com.adsamcik.tracker.tracker.pipeline.stages.PolicyUpdateStage
import com.adsamcik.tracker.tracker.pipeline.stages.PostProcessingStage
import com.adsamcik.tracker.tracker.pipeline.stages.SessionUpdateStage
import com.adsamcik.tracker.tracker.pipeline.stages.SignalDispatchStage
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates core tracking logic: component initialization, per-cycle data
 * collection, and component teardown.
 *
 * This is a pure Kotlin class with no Android Service inheritance. It accepts all
 * dependencies via constructor and receives [Context] as a method parameter where
 * needed, making it unit-testable without Robolectric.
 *
 * Android lifecycle concerns (foreground notification, wake lock, timer, shortcuts,
 * intents) remain in [TrackerService], which delegates business logic here.
 */
internal class TrackingOrchestrator(
	private val controller: TrackerServiceController,
	private val trackerListenerManager: TrackerListenerManager,
	private val signalProcessors: Set<SignalProcessor>,
	private val domainEventRepository: DomainEventRepository,
	private val dispatchers: DispatchersProvider,
	private val appDatabase: AppDatabase,
	private val trackingParamsRepository: TrackingParamsRepository,
	trackerSettingsRepository: TrackerSettingsRepository,
	private val dailySummaryFallbackEnqueuer: (Context) -> Unit = DailySummaryMaterializationWorker::runOnce,
	private val enableNotifications: Boolean = true,
	private val foregroundServiceTypeUpdater: (Boolean, Boolean, Boolean) -> Boolean =
		{ _, _, _ -> true },
	private val runtimeTierAdjuster: (PolicyTier) -> PolicyTier = { it },
	/**
	 * Optional callback invoked AFTER the in-orchestrator `DailySummaryAggregator` writes a row.
	 * The unified rule engine injects a callback that marks the `daily_summary` table dirty in
	 * the `MetricDirtyTracker` so the signal processor's next flush re-evaluates only metrics
	 * backed by daily_summary, not the whole catalog. Default no-op keeps tests + legacy
	 * call sites working without DI churn.
	 */
	private val onDailySummaryWritten: () -> Unit = {},
) {
	private companion object {
		const val TAG = "TrackingOrchestrator"
	}

	private val componentMutex = Mutex()

	private var dataProducerManager: DataProducerManager? = null
	private var trackingPolicyManager: TrackingPolicyManager? = null
	private var processorPipeline: ProcessorPipeline? = null
	private var trackingPipeline: TrackingPipeline? = null
	private var pendingFinalCycle: TrackingCycle? = null
	private var sessionComponent: SessionTrackerComponent? = null
	private var persistenceErrorCollector: PersistenceErrorCollector? = null
	private var sessionJob: Job? = null

	private val preComponentList = mutableListOf<PreTrackerComponent>()
	private var skiTrackingComponent: SkiTrackingComponent? = null
	private var skiSegmentWriter: SkiSegmentWriter? = null
	private var sailingTrackingComponent: SailingTrackingComponent? = null
	private var planeTrackingComponent: PlaneTrackingComponent? = null
	private val dataComponentList = mutableListOf<DataTrackerComponent>()

	val notificationComponent: NotificationComponent = NotificationComponent()

	// Current tier used for signal building — set during initialize
	@Volatile
	private var currentTier: PolicyTier = PolicyTier.PRECISION

	private val componentFactory by lazy {
		TrackerComponentFactory(
			appDatabase = appDatabase,
			trackingParamsRepository = trackingParamsRepository,
			trackerSettingsRepository = trackerSettingsRepository,
			dispatchers = dispatchers,
			enableNotifications = enableNotifications,
		)
	}
	private val policyFeeder = TrackerPolicyFeeder()
	private lateinit var tierEscalationHandler: TrackerTierEscalationHandler

	private val session: TrackerSession get() = requireNotNull(sessionComponent).session

	/**
	 * Initialize all tracking components for a new session.
	 *
	 * @param context              Android context for component lifecycle calls.
	 * @param isSessionUserInitiated whether the session was started by the user.
	 * @param initialTier          initial [PolicyTier] for this session.
	 * @param scope                coroutine scope for launching observation coroutines.
	 * @param timerReceiver        timer callback receiver (the service).
	 * @param timerAccessor        accessor for swapping the timer during tier escalation.
	 */
	@Suppress("LongMethod")
	suspend fun initialize(
		context: Context,
		isSessionUserInitiated: Boolean,
		initialTier: PolicyTier,
		scope: CoroutineScope,
		timerReceiver: TrackerTimerReceiver,
		timerAccessor: TrackerTierEscalationHandler.TimerAccessor,
	) = componentMutex.withLock {
		val previousSessionJob = sessionJob
		try {
			if (hasSessionState()) {
				retryTrackingShutdown {
					destroyComponents(context)
				}
			}
		} finally {
			previousSessionJob?.cancelAndJoin()
			if (sessionJob === previousSessionJob) {
				sessionJob = null
			}
		}

		val newSessionJob = SupervisorJob(scope.coroutineContext[Job])
		val sessionScope = CoroutineScope(scope.coroutineContext + newSessionJob)
		sessionJob = newSessionJob
		currentTier = initialTier

		dataProducerManager = DataProducerManager(
			context = context,
			trackingParamsRepository = trackingParamsRepository,
		).apply { onEnable() }

		// Initialize the new 4-tier escalation engine
		val escalationEngine = DefaultPolicyEscalationEngine()

		// Initialize adaptive tracking policy manager with engine delegation
		trackingPolicyManager = TrackingPolicyManager(
			context = context,
			isUserInitiated = isSessionUserInitiated,
			escalationEngine = escalationEngine,
			scope = sessionScope,
			database = appDatabase,
			dispatchers = dispatchers,
			initialTier = initialTier,
		).apply {
			start() // Start tracking run + engine
		}

		// Observe engine state changes for UI and timer updates
		sessionScope.launch {
			escalationEngine.policyState.collect { state ->
				controller.updatePolicyState(state)
			}
		}

		// Initialize tier escalation handler. Component/producer lists are now built up-front by
		// source toggle, so the handler only swaps the collection trigger + escalates the pipeline.
		tierEscalationHandler = TrackerTierEscalationHandler(
			componentMutex = componentMutex,
			controller = controller,
			trackingParamsRepository = trackingParamsRepository,
			tierAdjuster = runtimeTierAdjuster,
			gpsTriggerFactory = { TrackerTimerManager.getSelected(it, dispatchers.main) },
			ambientTriggerFactory = { AmbientCollectionTrigger(dispatchers.main) },
			foregroundServiceTypeUpdater = foregroundServiceTypeUpdater,
			onEffectiveTierChanged = { currentTier = it },
		).apply {
			this.currentTier = initialTier
			this.processorPipeline = null // set below after pipeline creation
			this.timerAccessor = timerAccessor
		}
		tierEscalationHandler.configureCurrentLocationRequest(
			requireNotNull(trackingPolicyManager).currentPolicy.value,
		)

		// Observe policy changes and delegate tier/interval updates
		trackingPolicyManager?.let { policyManager ->
			sessionScope.launch {
				policyManager.currentPolicy.collect { newPolicy ->
					tierEscalationHandler.onPolicyChanged(
						policy = newPolicy,
						context = context,
						timerReceiver = timerReceiver,
						scope = sessionScope,
					)
				}
			}
		}

		// Apply cadence, fidelity preset, and location-toggle changes to the running trigger.
		// Producers already observe their individual toggles; consumers are always present.
		sessionScope.launch {
			trackingParamsRepository.data
				.distinctUntilChanged()
				.drop(1)
				.collect {
					val policy = trackingPolicyManager?.currentPolicy?.value ?: return@collect
					tierEscalationHandler.onPolicyChanged(
						policy, context, timerReceiver, sessionScope,
					)
				}
		}

		// Reset policy feeder state for new session
		policyFeeder.reset()

		// Build components via factory
		// Clear previous collector's scope before replacing
		(persistenceErrorCollector as? DefaultPersistenceErrorCollector)?.clear()

		val componentSet = componentFactory.create(
			context = context,
			isSessionUserInitiated = isSessionUserInitiated,
			tier = initialTier,
			notificationComponent = notificationComponent,
			trackingPolicyManager = trackingPolicyManager,
			escalationEngine = escalationEngine,
			controller = controller,
			scope = sessionScope,
		)

		sessionComponent = componentSet.sessionComponent
		preComponentList.addAll(componentSet.preComponents)
		dataComponentList.addAll(componentSet.dataComponents)
		skiTrackingComponent = componentSet.skiTrackingComponent
		skiSegmentWriter = componentSet.skiSegmentWriter
		sailingTrackingComponent = componentSet.sailingTrackingComponent
		planeTrackingComponent = componentSet.planeTrackingComponent

		persistenceErrorCollector = componentSet.errorCollector
		controller.updatePersistenceErrorFlow(componentSet.errorCollector.errors)

		// Emit initial session via controller
		controller.updateSession(session)

		// Initialize the stats ProcessorPipeline
		val pipeline = ProcessorPipeline(
			processors = signalProcessors,
			scope = sessionScope,
			onDomainEvents = { events -> domainEventRepository.persist(events) },
		)
		processorPipeline = pipeline
		pipeline.start(
			tier = initialTier,
			startTimestamp = EpochMs(Time.nowMillis),
			sessionId = session.id,
		)
		trackingPipeline = createTrackingPipeline(sessionScope)

		// Wire mutable references into tier escalation handler
		tierEscalationHandler.processorPipeline = processorPipeline
	}

	/**
	 * Process a single data collection cycle.
	 * Acquires the component mutex internally. Returns immediately if the service
	 * is not running.
	 *
	 * @param context  Android context for post-component callbacks.
	 * @param cycle    tracking cycle from the timer trigger.
	 */
	suspend fun onCycleUpdate(
		context: Context,
		cycle: TrackingCycle,
	) {
		componentMutex.withLock {
			if (!controller.isServiceRunning) return

			collectAndProcess(context, cycle)
		}
	}

	/**
	 * Shut down all tracking components.
	 *
	 * @param context     Android context for component lifecycle calls.
	 * @param preShutdown optional action to run inside the component lock before
	 *                    teardown (e.g., disabling the timer component).
	 */
	suspend fun shutdown(
		context: Context,
		preShutdown: (suspend () -> Unit)? = null,
	): ShutdownResult = componentMutex.withLock {
		preShutdown?.invoke()
		try {
			destroyComponents(context)
		} finally {
			sessionJob?.cancelAndJoin()
			sessionJob = null
		}
	}

	internal fun enqueueDailySummaryFallback(context: Context): Boolean {
		return try {
			dailySummaryFallbackEnqueuer(context)
			true
		} catch (e: Exception) {
			Log.w(TAG, "Failed to enqueue one-shot daily summary worker", e)
			false
		}
	}

	fun onBatteryLevelChanged(
		context: Context,
		timerReceiver: TrackerTimerReceiver,
		scope: CoroutineScope,
	) {
		val policy = trackingPolicyManager?.currentPolicy?.value ?: return
		tierEscalationHandler.onPolicyChanged(policy, context, timerReceiver, scope)
	}

	/**
	 * Reset controller metadata after the service stops.
	 * Call from the service's onDestroy after [shutdown].
	 */
	fun resetMetadata() {
		controller.updateServiceRunning(false)
		controller.updateSessionInfo(null)
		controller.updateSession(null)
		controller.updateCollectionData(null)
		controller.updatePersistenceErrorFlow(null)
		controller.updatePolicyState(null)
		controller.updatePolicyTier(PolicyTier.OFF)
		controller.updateSkiState(null)
		controller.updateSailingState(null)
		controller.updatePlaneState(null)
		// Ensure error collector scope is always cancelled
		(persistenceErrorCollector as? DefaultPersistenceErrorCollector)?.clear()
		persistenceErrorCollector = null
	}

	fun markServiceStopped() {
		controller.updateServiceRunning(false)
	}

	// ---- private implementation ----

	private fun hasSessionState(): Boolean {
		return dataProducerManager != null ||
			trackingPolicyManager != null ||
			processorPipeline != null ||
			trackingPipeline != null ||
			sessionComponent != null ||
			preComponentList.isNotEmpty() ||
			dataComponentList.isNotEmpty() ||
			skiTrackingComponent != null ||
			skiSegmentWriter != null ||
			sailingTrackingComponent != null ||
			planeTrackingComponent != null
	}

	/**
	 * Collects data from producers/components, feeds the processor pipeline,
	 * updates the tracking policy, and notifies listeners.
	 */
	private suspend fun collectAndProcess(
		context: Context,
		triggerCycle: TrackingCycle,
	) {
		// Capture the immutable provider observation before pre-validation or data components can
		// reject or correct the location used by the curated tracking stream.
		if (triggerCycle.locationObservations.isNotEmpty()) {
			val observationSignals = triggerCycle.locationObservations.map { observation ->
				observation.toLocationObservationSignal(
					policyTier = currentTier,
					policyName = trackingPolicyManager?.currentPolicy?.value?.name,
				)
			}
			// Rejected-only callbacks never enter ProcessorPipeline, so they would otherwise remain
			// only in volatile staging. Checkpoint every provider delivery before any rejection path.
			if (processorPipeline?.checkpointDurableSignals(observationSignals) != true) {
				Reporter.report("Unable to durably checkpoint raw location observations")
			}
		}
		if (triggerCycle.isLocationObservationOnly()) return

		for (component in preComponentList) {
			if (!component.requirementsMet(triggerCycle)) continue
			val accepted = tryWithResultAndReport({ true }) {
				component.onNewData(triggerCycle)
			}
			if (!accepted) return
		}

		val cycle = requireNotNull(dataProducerManager).getData(triggerCycle)
		executeCycle(context, cycle)
	}

	private suspend fun executeCycle(context: Context, cycle: TrackingCycle) {
		val cycleContext = CycleContext(
			cycle = cycle,
			collectionData = MutableCollectionData(cycle.timestampMs),
		)

		val result = requireNotNull(trackingPipeline) {
			"Tracking pipeline must be initialized before processing cycles"
		}.execute(context, cycleContext)

		if (BuildConfig.DEBUG) {
			result.metrics.forEach { m ->
				Log.d("TrackingPipeline", "${m.stageName}: ${m.durationMs}ms -> ${m.result}")
			}
		}

		// Notify listeners only when the pipeline completed (not skipped)
		if (result.completedSuccessfully) {
			val session = cycleContext.session
			if (session != null) {
				try {
					trackerListenerManager.send(context, session, cycleContext.collectionData)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Reporter.report(IllegalStateException("Failed to notify tracking listeners", e))
				}
			}
		}
	}

	private fun createTrackingPipeline(scope: CoroutineScope): TrackingPipeline {
		return TrackingPipeline(
			stages = listOf(
				DataCollectionStage(dataComponentList),
				SessionUpdateStage(requireNotNull(sessionComponent), controller),
				PostProcessingStage(
					notificationComponent,
					skiSegmentWriter,
					skiTrackingComponent,
					sailingTrackingComponent,
					planeTrackingComponent,
				),
				SignalDispatchStage(
					processorPipelineProvider = { processorPipeline },
					currentTierProvider = { currentTier },
					currentPolicyNameProvider = { trackingPolicyManager?.currentPolicy?.value?.name },
				),
				PolicyUpdateStage(policyFeeder, trackingPolicyManager, scope),
			),
			collectMetrics = BuildConfig.DEBUG,
		)
	}

	private suspend fun destroyComponents(context: Context): ShutdownResult {
		var shutdownFailure: IllegalStateException? = null
		fun recordCriticalFailure(message: String, exception: Exception) {
			val failure = IllegalStateException(message, exception)
			Reporter.report(failure)
			if (shutdownFailure == null) shutdownFailure = failure
		}

		if (sessionComponent != null && trackingPipeline != null) {
			val manager = dataProducerManager
			if (manager != null) {
				if (pendingFinalCycle != null) {
					try {
						executeCycle(context, requireNotNull(pendingFinalCycle))
						pendingFinalCycle = null
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						recordCriticalFailure("Failed to replay final tracking cycle", e)
					}
				}
			}
			if (manager != null && pendingFinalCycle == null) {
				try {
					manager.flushPendingSensorBatches()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					recordCriticalFailure("Failed to flush final sensor batches", e)
				}
				try {
					val finalCycle = manager.getData(
						TrackingCycle(
							timestampMs = Time.nowMillis,
							elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
						),
					)
					if (finalCycle.hasPersistableProducerPayload()) {
						pendingFinalCycle = finalCycle
						executeCycle(context, requireNotNull(pendingFinalCycle))
						pendingFinalCycle = null
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					recordCriticalFailure("Failed to process final tracking cycle", e)
				}
			}
		}

		// Finalize session data BEFORE stopping the pipeline. The pipeline's
		// stop() calls AggregatorProcessor.onStop() which emits SessionEnded.
		// Downstream consumers (AchievementWorker,
		// DailySummaryMaterializationWorker) read session_segment rows after
		// receiving SessionEnded, so the final row must already be persisted.
		if (shutdownFailure == null) {
			sessionComponent?.let { component ->
				try {
					component.onDisable(context)
					controller.updateSession(component.session)
					sessionComponent = null
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					recordCriticalFailure("Failed to finalize tracking session", e)
				}
			}
		}

		// Stop the stats ProcessorPipeline (final flush + SessionEnded delivery)
		if (shutdownFailure == null) {
			try {
				processorPipeline?.stop()
				processorPipeline = null
				trackingPipeline = null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordCriticalFailure("Failed to stop tracking processor pipeline", e)
			}
		}

		shutdownFailure?.let { throw it }

		dataProducerManager?.let { manager ->
			try {
				manager.onDisable()
				dataProducerManager = null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordCriticalFailure("Failed to disable tracking data producers", e)
			}
		}
		try {
			trackingPolicyManager?.stop()
			trackingPolicyManager = null
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			recordCriticalFailure("Failed to stop tracking policy manager during shutdown", e)
		}
		preComponentList.toList().forEach { component ->
			try {
				component.onDisable(context)
				preComponentList.remove(component)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordCriticalFailure(
					"Failed to disable pre-component ${component::class.simpleName}",
					e,
				)
			}
		}
		dataComponentList.toList().forEach { component ->
			try {
				component.onDisable(context)
				dataComponentList.remove(component)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordCriticalFailure(
					"Failed to disable data-component ${component::class.simpleName}",
					e,
				)
			}
		}
		try {
			notificationComponent.onDisable(context)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			recordCriticalFailure("Failed to disable notification component", e)
		}
		skiTrackingComponent?.let { component ->
			try {
				component.onDisable(context)
				skiTrackingComponent = null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordCriticalFailure("Failed to disable ski tracking component", e)
			}
		}
		skiSegmentWriter?.let { component ->
			try {
				component.onDisable(context)
				skiSegmentWriter = null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordCriticalFailure("Failed to disable ski segment writer", e)
			}
		}
		sailingTrackingComponent?.let { component ->
			try {
				component.onDisable(context)
				sailingTrackingComponent = null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordCriticalFailure("Failed to disable sailing tracking component", e)
			}
		}
		planeTrackingComponent?.let { component ->
			try {
				component.onDisable(context)
				planeTrackingComponent = null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordCriticalFailure("Failed to disable plane tracking component", e)
			}
		}
		if (::tierEscalationHandler.isInitialized) {
			tierEscalationHandler.processorPipeline = null
		}

		// Materialize daily summary from session segments now that the session
		// component has saved its final segment to the database.
		val dailySummaryMaterialized = if (sessionComponent == null) {
			try {
				val aggregator = DailySummaryAggregator(
					dailySummaryDao = appDatabase.dailySummaryDao(),
					sessionSegmentDao = appDatabase.sessionSegmentDao(),
					onDailySummaryWritten = onDailySummaryWritten,
				)
				aggregator.materializeToday()
				true
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to materialize daily summary on shutdown", e)
				false
			}
		} else {
			false
		}

		val fallbackEnqueued = if (dailySummaryMaterialized || sessionComponent != null) {
			false
		} else {
			enqueueDailySummaryFallback(context)
		}
		shutdownFailure?.let { throw it }
		return ShutdownResult(
			dailySummaryMaterialized = dailySummaryMaterialized,
			fallbackEnqueued = fallbackEnqueued,
		)
	}
}

internal data class ShutdownResult(
	val dailySummaryMaterialized: Boolean,
	val fallbackEnqueued: Boolean,
)
