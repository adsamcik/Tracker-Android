package com.adsamcik.tracker.tracker.service

import dev.tracebox.Tracebox
import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.PlaneTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SailingTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SkiSegmentWriter
import com.adsamcik.tracker.tracker.component.consumer.post.SkiTrackingComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController

import com.adsamcik.tracker.tracker.control.NoOpTrackingControlOutputSink
import com.adsamcik.tracker.tracker.control.TrackingControlOutputSink
import com.adsamcik.tracker.tracker.control.TrackingControlShadow
import com.adsamcik.tracker.tracker.control.TrackingDecisionFeatureFlags
import com.adsamcik.tracker.tracker.data.TrackingClockDomain
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.isLocationObservationOnly
import com.adsamcik.tracker.tracker.data.session.toSnapshot
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.TrackingPipeline
import com.adsamcik.tracker.tracker.pipeline.toLocationObservationSignal
import com.adsamcik.tracker.tracker.pipeline.stages.DataCollectionStage
import com.adsamcik.tracker.tracker.pipeline.stages.PolicyUpdateStage
import com.adsamcik.tracker.tracker.pipeline.stages.PostProcessingStage
import com.adsamcik.tracker.tracker.pipeline.stages.SessionUpdateStage
import com.adsamcik.tracker.tracker.pipeline.stages.SignalDispatchStage
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.tracker.policy.RoomTrackerStateEvidenceWriter
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.TrackingSessionOwnership
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
	private val signalProcessors: Set<SignalProcessor>,
	private val domainEventRepository: DomainEventRepository,
	private val dispatchers: DispatchersProvider,
	private val appDatabase: AppDatabase,
	private val trackingParamsRepository: TrackingParamsRepository,
	private val trackingRolloutStateStore: TrackingRolloutStateStore = RoomTrackingRolloutStateStore(appDatabase),
	private val dailySummaryFallbackEnqueuer: (Context) -> Unit = DailySummaryMaterializationWorker::runOnce,
	private val enableNotifications: Boolean = true,
	private val runtimeTierAdjuster: (PolicyTier) -> PolicyTier = { it },
	/**
	 * Optional callback invoked AFTER the in-orchestrator `DailySummaryAggregator` writes a row.
	 * The unified rule engine injects a callback that marks the `daily_summary` table dirty in
	 * the `MetricDirtyTracker` so the signal processor's next flush re-evaluates only metrics
	 * backed by daily_summary, not the whole catalog. Default no-op keeps tests + legacy
	 * call sites working without DI churn.
	 */
	private val onDailySummaryWritten: () -> Unit = {},
	/** Feature-gated uncertainty-aware tracking path. All default switches are off. */
	private val trackingDecisionFeatureFlags: TrackingDecisionFeatureFlags = TrackingDecisionFeatureFlags(),
	/** Debug/research sink for ordered shadow decisions; production default intentionally discards. */
	private val trackingControlOutputSink: TrackingControlOutputSink = NoOpTrackingControlOutputSink,
	/** Delivers semantic settings plus policy evidence requirements to the event coordinator. */
	private val onSourcePlanInputsChanged: suspend (TrackingParamsState, List<SourceDemand>) -> Unit = { _, _ -> },
) {
	private val componentMutex = Mutex()

	private var trackingPolicyManager: TrackingPolicyManager? = null
	private var processorPipeline: ProcessorPipeline? = null
	private var trackingPipeline: TrackingPipeline? = null
	private var sessionComponent: SessionTrackerComponent? = null
	private var sessionJob: Job? = null

	@Volatile
	private var controlLocationEnabled: Boolean = true

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
			dispatchers = dispatchers,
			enableNotifications = enableNotifications,
		)
	}
	private val policyFeeder = TrackerPolicyFeeder()
	private val trackingControlShadow by lazy {
		TrackingControlShadow(
			flags = trackingDecisionFeatureFlags,
			outputSink = trackingControlOutputSink,
		)
	}
	private lateinit var tierEscalationHandler: TrackerTierEscalationHandler

	private val session: TrackerSession get() = requireNotNull(sessionComponent).session

	/**
	 * Initialize all tracking components for a new session.
	 *
	 * @param context              Android context for component lifecycle calls.
	 * @param isSessionUserInitiated whether the session was started by the user.
	 * @param initialTier          initial [PolicyTier] for this session.
	 * @param scope                coroutine scope for launching observation coroutines.
	 */
	@Suppress("LongMethod")
	suspend fun initialize(
		context: Context,
		isSessionUserInitiated: Boolean,
		initialTier: PolicyTier,
		scope: CoroutineScope,
		/** Durable logical identity supplied by the service; legacy callers fall back to segment id. */
		logicalTrackingId: String? = null,
		/** Immutable physical-source ownership snapshot for this service run. */
		rolloutState: TrackingRolloutState? = null,
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
		val clockDomainId = TrackingClockDomain.beginSession(context)

		val effectiveRolloutState = rolloutState ?: trackingRolloutStateStore.load()
		val initialTrackingParams = trackingParamsRepository.data.first()
		// This runtime needs physical acquisition ownership only. Canonical product writers are
		// activated independently per source after their own query, deletion, and cutover proof.
		TrackingSessionOwnership.resolve(effectiveRolloutState, initialTrackingParams)

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
			clockDomainId = clockDomainId,
			trackerStateEvidenceWriter = RoomTrackerStateEvidenceWriter(appDatabase),
		).apply {
			start() // Start tracking run + engine
		}
		val policyManager = requireNotNull(trackingPolicyManager)
		// Lifecycle evidence is refreshed independently of collection callbacks or location timers.
		sessionScope.launch {
			while (isActive) {
				policyManager.heartbeatIfDue()
				delay(POLICY_LIFECYCLE_TICK_MILLIS)
			}
		}
		sessionScope.launch {
			combine(
				trackingParamsRepository.data,
				policyManager.sourceDemands,
			) { settings, demands -> settings to demands }
				.distinctUntilChanged()
				.collect { (settings, demands) -> onSourcePlanInputsChanged(settings, demands) }
		}

		// Observe engine state changes for UI and timer updates
		sessionScope.launch {
			escalationEngine.policyState.collect { state ->
				controller.updatePolicyState(state)
			}
		}

		// The tier handler updates processing state only. Per-source runtimes own acquisition.
		tierEscalationHandler = TrackerTierEscalationHandler(
			componentMutex = componentMutex,
			controller = controller,
			tierAdjuster = runtimeTierAdjuster,
			onEffectiveTierChanged = { currentTier = it },
		).apply {
			this.currentTier = initialTier
			this.processorPipeline = null // set below after pipeline creation
		}
		// Observe policy changes and delegate tier/interval updates
		trackingPolicyManager?.let { policyManager ->
			sessionScope.launch {
				policyManager.currentPolicy.collect { newPolicy ->
					tierEscalationHandler.onPolicyChanged(
						policy = newPolicy,
						scope = sessionScope,
					)
					componentMutex.withLock {
						if (trackingControlShadow.snapshot()?.logicalTrackingId != null) {
							trackingControlShadow.onPolicy(
								policy = newPolicy,
								wallTimeMs = Time.nowMillis,
								elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
								locationEnabled = controlLocationEnabled,
							)
						}
					}
				}
			}
		}

		// Semantic settings revisions are forwarded to the event coordinator; no global trigger is
		// swapped or rescheduled here.
		sessionScope.launch {
			trackingParamsRepository.data
				.distinctUntilChanged()
				.drop(1)
				.collect { params ->
					controlLocationEnabled = params.locationEnabled
					val policy = trackingPolicyManager?.currentPolicy?.value ?: return@collect
					tierEscalationHandler.onPolicyChanged(
						policy = policy,
						scope = sessionScope,
					)
					componentMutex.withLock {
						if (trackingControlShadow.snapshot()?.logicalTrackingId != null) {
							trackingControlShadow.onPolicy(
								policy = policy,
								wallTimeMs = Time.nowMillis,
								elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
								locationEnabled = controlLocationEnabled,
							)
						}
					}
				}
		}

		// Reset policy feeder state for new session
		policyFeeder.reset()

		// Build components via factory
		val componentSet = componentFactory.create(
			context = context,
			isSessionUserInitiated = isSessionUserInitiated,
			notificationComponent = notificationComponent,
			controller = controller,
			scope = sessionScope,
		)

		sessionComponent = componentSet.sessionComponent
		dataComponentList.addAll(componentSet.dataComponents)
		skiTrackingComponent = componentSet.skiTrackingComponent
		skiSegmentWriter = componentSet.skiSegmentWriter
		sailingTrackingComponent = componentSet.sailingTrackingComponent
		planeTrackingComponent = componentSet.planeTrackingComponent
		controlLocationEnabled = initialTrackingParams.locationEnabled
		trackingControlShadow.begin(
			logicalTrackingId = logicalTrackingId ?: "segment:${session.id}",
			isUserInitiated = isSessionUserInitiated,
			wallTimeMs = Time.nowMillis,
			elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
			clockDomainId = clockDomainId,
			initialTier = initialTier,
			locationEnabled = controlLocationEnabled,
		)
		// Emit initial session via controller
		controller.updateSession(session.toSnapshot())

		// Initialize the stats ProcessorPipeline
		val pipeline = ProcessorPipeline(
			processors = signalProcessors,
			onDomainEvents = { events -> domainEventRepository.persist(events) },
			requireDurableAdmission = true,
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
		// TrackerService can exist briefly as an honest providerless foreground shell while the
		// process-wide recovery gate is closed. That shell has no tracking session to finalize and
		// must not open Room merely because Android destroys the Service.
		if (!hasSessionState()) return@withLock ShutdownResult(
			dailySummaryMaterialized = false,
			fallbackEnqueued = false,
		)
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
			false
		}
	}

	fun onBatteryLevelChanged(scope: CoroutineScope) {
		val policy = trackingPolicyManager?.currentPolicy?.value ?: return
		tierEscalationHandler.onPolicyChanged(policy, scope)
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
		controller.updatePolicyState(null)
		controller.updatePolicyTier(PolicyTier.OFF)
		controller.updateSkiState(null)
		controller.updateSailingState(null)
		controller.updatePlaneState(null)
	}

	fun markServiceStopped() {
		controller.updateServiceRunning(false)
	}

	// ---- private implementation ----

	private fun hasSessionState(): Boolean {
		return trackingPolicyManager != null ||
			processorPipeline != null ||
			trackingPipeline != null ||
			sessionComponent != null ||
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
		// Capture the immutable provider observation before data components can correct the
		// location used by the curated tracking stream.
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
				Tracebox.log.error("Tracking signal checkpoint failed")
				// Do not admit an accepted location when its reconstructable raw source has not reached
				// the WAL. The durability processor retains staged evidence for a later checkpoint; this
				// curated cycle intentionally remains unresolved rather than becoming source-less.
				return
			}
			// Shadow control is intentionally downstream of the raw WAL checkpoint: every decision can
			// be replayed from durable evidence rather than a volatile callback.
			trackingControlShadow.onRawLocationObservations(triggerCycle)
		}
		if (triggerCycle.isLocationObservationOnly()) return
		trackingControlShadow.onCuratedLocationDecision(triggerCycle, accepted = true)
		val cycle = triggerCycle
		trackingPolicyManager?.currentPolicy?.value?.let { policy ->
			trackingControlShadow.onPolicy(
				policy = policy,
				wallTimeMs = cycle.timestampMs,
				elapsedRealtimeNanos = cycle.elapsedRealtimeNanos,
				locationEnabled = controlLocationEnabled,
			)
		}
		trackingControlShadow.onCycleSignals(cycle)
		executeCycle(context, cycle)
	}

	private suspend fun executeCycle(context: Context, cycle: TrackingCycle) {
		val cycleContext = CycleContext(
			cycle = cycle,
			collectionData = MutableCollectionData(cycle.timestampMs),
		)

		requireNotNull(trackingPipeline) {
			"Tracking pipeline must be initialized before processing cycles"
		}.execute(context, cycleContext)
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
		)
	}

	private suspend fun destroyComponents(context: Context): ShutdownResult {
		var shutdownFailure: IllegalStateException? = null
		fun recordCriticalFailure(message: String, exception: Exception) {
			val failure = IllegalStateException(message, exception)
			if (shutdownFailure == null) shutdownFailure = failure
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
					controller.updateSession(component.session.toSnapshot())
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

		try {
			trackingPolicyManager?.stop()
			trackingPolicyManager = null
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			recordCriticalFailure("Failed to stop tracking policy manager during shutdown", e)
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
		trackingControlShadow.finish(
			reason = "ORCHESTRATOR_SHUTDOWN",
			wallTimeMs = Time.nowMillis,
			elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
		)
		shutdownFailure?.let { throw it }
		return ShutdownResult(
			dailySummaryMaterialized = dailySummaryMaterialized,
			fallbackEnqueued = fallbackEnqueued,
		)
	}

	fun currentSourceDemands(): List<SourceDemand> = trackingPolicyManager?.sourceDemands?.value.orEmpty()

	private companion object {
		const val POLICY_LIFECYCLE_TICK_MILLIS = 30_000L
	}
}

internal data class ShutdownResult(
	val dailySummaryMaterialized: Boolean,
	val fallbackEnqueued: Boolean,
)
