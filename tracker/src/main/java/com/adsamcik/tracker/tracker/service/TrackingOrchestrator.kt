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
import com.adsamcik.tracker.tracker.BuildConfig
import com.adsamcik.tracker.tracker.component.DataProducerManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SkiSegmentWriter
import com.adsamcik.tracker.tracker.component.consumer.post.SkiTrackingComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.TrackingPipeline
import com.adsamcik.tracker.tracker.pipeline.stages.DataCollectionStage
import com.adsamcik.tracker.tracker.pipeline.stages.PolicyUpdateStage
import com.adsamcik.tracker.tracker.pipeline.stages.PostProcessingStage
import com.adsamcik.tracker.tracker.pipeline.stages.PreValidationStage
import com.adsamcik.tracker.tracker.pipeline.stages.SessionUpdateStage
import com.adsamcik.tracker.tracker.pipeline.stages.SignalDispatchStage
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
	private var sessionComponent: SessionTrackerComponent? = null
	private var persistenceErrorCollector: PersistenceErrorCollector? = null

	private val preComponentList = mutableListOf<PreTrackerComponent>()
	private var skiTrackingComponent: SkiTrackingComponent? = null
	private var skiSegmentWriter: SkiSegmentWriter? = null
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
		currentTier = initialTier

		// Clear existing components to prevent duplicates and ConcurrentModificationException
		// if onStartCommand is called multiple times or concurrently with onUpdate.
		preComponentList.forEach { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable pre-component: ${component::class.simpleName}", e)
			}
		}
		dataComponentList.forEach { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable data-component: ${component::class.simpleName}", e)
			}
		}
		try {
			notificationComponent.onDisable(context)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Failed to disable notification component: ${notificationComponent::class.simpleName}", e)
		}
		skiTrackingComponent?.let { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable ski tracking component: ${component::class.simpleName}", e)
			}
		}
		skiSegmentWriter?.let { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable ski segment writer: ${component::class.simpleName}", e)
			}
		}
		preComponentList.clear()
		dataComponentList.clear()
		skiTrackingComponent = null
		skiSegmentWriter = null

		// Cleanup previous managers if re-initializing
		dataProducerManager?.onDisable()
		trackingPolicyManager?.stop()

		dataProducerManager = DataProducerManager(
			context = context,
			initialTier = initialTier,
			trackingParamsRepository = trackingParamsRepository,
		).apply { onEnable() }

		// Initialize the new 4-tier escalation engine
		val escalationEngine = DefaultPolicyEscalationEngine()

		// Initialize adaptive tracking policy manager with engine delegation
		trackingPolicyManager = TrackingPolicyManager(
			context = context,
			isUserInitiated = isSessionUserInitiated,
			escalationEngine = escalationEngine,
			scope = scope,
			database = appDatabase,
			dispatchers = dispatchers,
		).apply {
			start() // Start tracking run + engine
		}

		// Observe engine state changes for UI and timer updates
		scope.launch {
			escalationEngine.policyState.collect { state ->
				controller.updatePolicyState(state)
			}
		}

		// Initialize tier escalation handler
		tierEscalationHandler = TrackerTierEscalationHandler(
			componentMutex = componentMutex,
			controller = controller,
			componentFactory = componentFactory,
			trackingParamsRepository = trackingParamsRepository,
		).apply {
			this.currentTier = initialTier
			this.dataComponentList = this@TrackingOrchestrator.dataComponentList
			this.dataProducerManager = this@TrackingOrchestrator.dataProducerManager
			this.processorPipeline = null // set below after pipeline creation
			this.onProducerManagerChanged = { newManager ->
				this@TrackingOrchestrator.dataProducerManager = newManager
			}
			this.timerAccessor = timerAccessor
		}

		// Observe policy changes and delegate tier/interval updates
		trackingPolicyManager?.let { policyManager ->
			scope.launch {
				policyManager.currentPolicy.collect { newPolicy ->
					tierEscalationHandler.onPolicyChanged(
						policy = newPolicy,
						context = context,
						timerReceiver = timerReceiver,
						scope = scope,
					)
				}
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
			scope = scope,
		)

		sessionComponent = componentSet.sessionComponent
		preComponentList.addAll(componentSet.preComponents)
		dataComponentList.addAll(componentSet.dataComponents)
		skiTrackingComponent = componentSet.skiTrackingComponent
		skiSegmentWriter = componentSet.skiSegmentWriter

		persistenceErrorCollector = componentSet.errorCollector
		controller.updatePersistenceErrorFlow(componentSet.errorCollector.errors)

		// Emit initial session via controller
		controller.updateSession(session)

		// Initialize the stats ProcessorPipeline
		val pipeline = ProcessorPipeline(
			processors = signalProcessors,
			scope = scope,
			onDomainEvents = { events -> domainEventRepository.persist(events) },
		)
		processorPipeline = pipeline
		pipeline.start(
			tier = initialTier,
			startTimestamp = EpochMs(Time.nowMillis),
			sessionId = session.id,
		)
		trackingPipeline = createTrackingPipeline(scope)

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
		destroyComponents(context)
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
		// Ensure error collector scope is always cancelled
		(persistenceErrorCollector as? DefaultPersistenceErrorCollector)?.clear()
		persistenceErrorCollector = null
	}

	// ---- private implementation ----

	/**
	 * Collects data from producers/components, feeds the processor pipeline,
	 * updates the tracking policy, and notifies listeners.
	 */
	private suspend fun collectAndProcess(
		context: Context,
		triggerCycle: TrackingCycle,
	) {
		val cycle = requireNotNull(dataProducerManager).getData(triggerCycle)

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
			val session = requireNotNull(cycleContext.session) {
				"Session must be populated by SessionUpdateStage"
			}
			trackerListenerManager.send(context, session, cycleContext.collectionData)
		}
	}

	private fun createTrackingPipeline(scope: CoroutineScope): TrackingPipeline {
		return TrackingPipeline(
			stages = listOf(
				PreValidationStage(preComponentList),
				DataCollectionStage(dataComponentList),
				SessionUpdateStage(requireNotNull(sessionComponent), controller),
				PostProcessingStage(notificationComponent, skiSegmentWriter, skiTrackingComponent),
				SignalDispatchStage(
					processorPipelineProvider = { processorPipeline },
					currentTierProvider = { currentTier },
				),
				PolicyUpdateStage(policyFeeder, trackingPolicyManager, scope),
			),
			collectMetrics = BuildConfig.DEBUG,
		)
	}

	private suspend fun destroyComponents(context: Context): ShutdownResult {
		// Finalize session data BEFORE stopping the pipeline. The pipeline's
		// stop() calls AggregatorProcessor.onStop() which emits SessionEnded.
		// Downstream consumers (ChallengeWorker, AchievementWorker,
		// DailySummaryMaterializationWorker) read session_segment rows after
		// receiving SessionEnded, so the final row must already be persisted.
		sessionComponent?.let { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable session component during shutdown: ${component::class.simpleName}", e)
			}
		}
		sessionComponent = null

		// Stop the stats ProcessorPipeline (final flush + SessionEnded delivery)
		try {
			processorPipeline?.stop()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Reporter.report(IllegalStateException("Failed to stop processor pipeline during shutdown", e))
		} finally {
			processorPipeline = null
			trackingPipeline = null
		}

		dataProducerManager?.onDisable()
		try {
			trackingPolicyManager?.stop()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Reporter.report(IllegalStateException("Failed to stop tracking policy manager during shutdown", e))
		} finally {
			trackingPolicyManager = null
		}
		preComponentList.forEach { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable pre-component during shutdown: ${component::class.simpleName}", e)
			}
		}
		dataComponentList.forEach { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable data-component during shutdown: ${component::class.simpleName}", e)
			}
		}
		try {
			notificationComponent.onDisable(context)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Failed to disable notification component during shutdown: ${notificationComponent::class.simpleName}", e)
		}
		skiTrackingComponent?.let { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable ski tracking component during shutdown: ${component::class.simpleName}", e)
			}
		}
		skiSegmentWriter?.let { component ->
			try {
				component.onDisable(context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to disable ski segment writer during shutdown: ${component::class.simpleName}", e)
			}
		}
		skiTrackingComponent = null
		skiSegmentWriter = null

		// Materialize daily summary from session segments now that the session
		// component has saved its final segment to the database.
		val dailySummaryMaterialized = try {
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

		val fallbackEnqueued = if (dailySummaryMaterialized) {
			false
		} else {
			enqueueDailySummaryFallback(context)
		}
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
