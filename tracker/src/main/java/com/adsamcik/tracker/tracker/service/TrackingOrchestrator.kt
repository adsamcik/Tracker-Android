package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.DataProducerManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.SignalAdapter
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
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
	appDatabase: AppDatabase,
	trackingParamsRepository: TrackingParamsRepository,
	trackerSettingsRepository: TrackerSettingsRepository,
) {
	private val componentMutex = Mutex()

	private var dataProducerManager: DataProducerManager? = null
	private var trackingPolicyManager: TrackingPolicyManager? = null
	private var processorPipeline: ProcessorPipeline? = null
	private var sessionComponent: SessionTrackerComponent? = null
	private var persistenceErrorCollector: PersistenceErrorCollector? = null

	private val preComponentList = mutableListOf<PreTrackerComponent>()
	private val postComponentList = mutableListOf<PostTrackerComponent>()
	private val dataComponentList = mutableListOf<DataTrackerComponent>()

	val notificationComponent: NotificationComponent = NotificationComponent()

	// Current tier used for signal building — set during initialize
	private var currentTier: PolicyTier = PolicyTier.PRECISION

	private val componentFactory by lazy {
		TrackerComponentFactory(appDatabase, trackingParamsRepository, trackerSettingsRepository)
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
		preComponentList.forEach { tryWithReport { it.onDisable(context) } }
		dataComponentList.forEach { tryWithReport { it.onDisable(context) } }
		postComponentList.forEach { tryWithReport { it.onDisable(context) } }
		preComponentList.clear()
		dataComponentList.clear()
		postComponentList.clear()

		// Cleanup previous managers if re-initializing
		dataProducerManager?.onDisable()
		trackingPolicyManager?.stop()

		// DispatchersProvider injection from AppGraph intentionally avoided to keep tracker module
		// independent of app module. DataProducerManager falls back to its internal default provider.
		dataProducerManager = DataProducerManager(context, initialTier).apply { onEnable() }

		// Initialize the new 4-tier escalation engine
		val escalationEngine = DefaultPolicyEscalationEngine()

		// Initialize adaptive tracking policy manager with engine delegation
		trackingPolicyManager = TrackingPolicyManager(
			context = context,
			isUserInitiated = isSessionUserInitiated,
			escalationEngine = escalationEngine,
			scope = scope,
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
		).apply {
			this.currentTier = initialTier
			this.dataComponentList = this@TrackingOrchestrator.dataComponentList
			this.postComponentList = this@TrackingOrchestrator.postComponentList
			this.dataProducerManager = this@TrackingOrchestrator.dataProducerManager
			this.persistenceErrorCollector = null // set below after factory creates it
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
		postComponentList.addAll(componentSet.postComponents)

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

		// Wire mutable references into tier escalation handler
		tierEscalationHandler.persistenceErrorCollector =
			persistenceErrorCollector as? DefaultPersistenceErrorCollector
		tierEscalationHandler.processorPipeline = processorPipeline
	}

	/**
	 * Process a single data collection cycle.
	 * Acquires the component mutex internally. Returns immediately if the service
	 * is not running.
	 *
	 * @param context  Android context for post-component callbacks.
	 * @param tempData temporary data collected by the timer trigger.
	 * @param scope    coroutine scope for launching async policy updates.
	 */
	suspend fun onCycleUpdate(
		context: Context,
		tempData: MutableCollectionTempData,
		scope: CoroutineScope,
	) {
		componentMutex.withLock {
			if (!controller.isServiceRunning) return

			collectAndProcess(context, tempData, scope)
		}
	}

	/**
	 * Shut down all tracking components.
	 *
	 * @param context     Android context for component lifecycle calls.
	 * @param preShutdown optional action to run inside the component lock before
	 *                    teardown (e.g., disabling the timer component).
	 */
	suspend fun shutdown(context: Context, preShutdown: (suspend () -> Unit)? = null) {
		componentMutex.withLock {
			preShutdown?.invoke()
			destroyComponents(context)
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
	@Suppress("LongMethod")
	private suspend fun collectAndProcess(
		context: Context,
		tempData: MutableCollectionTempData,
		scope: CoroutineScope,
	) {
		val cycle = requireNotNull(dataProducerManager).getData(tempData)

		// if we don't know the accuracy the location is worthless
		if (!preComponentList.all {
					if (it.requirementsMet(tempData)) {
						tryWithResultAndReport(
								{ true }) {
							it.onNewData(tempData)
						}
					} else {
						true
					}
				}) {
			return
		}

		val collectionData = MutableCollectionData(tempData.timeMillis)

		dataComponentList
				.asSequence()
				.filter { it.requirementsMet(tempData) }
				.forEach {
					tryWithReport {
						it.onDataUpdated(tempData, collectionData)
					}
				}

		requireNotNull(sessionComponent).onDataUpdated(tempData, collectionData)

		// Emit updated session and collection data via controller
		controller.updateSession(session)
		controller.updateCollectionData(collectionData)

		val postComponentsRun = postComponentList
				.filter { it.requirementsMet(tempData) }
		postComponentsRun.forEach {
					tryWithReport {
						it.onNewData(context, session, collectionData, tempData)
					}
				}

		// Feed data to the ProcessorPipeline
		processorPipeline?.let { pipeline ->
			tryWithReport {
				val cellTowers = cycle.cellScan?.registeredCells?.map { cell ->
					com.adsamcik.tracker.stats.api.signal.CellTowerReading(
						cellId = cell.cellId,
						mcc = cell.networkOperator.mcc,
						mnc = cell.networkOperator.mnc,
						networkType = cell.type.ordinal,
						signalStrength = cell.asu,
					)
				}

				val wifiNetworks = cycle.wifiScan?.data?.map { sr ->
					com.adsamcik.tracker.stats.api.signal.WifiNetworkReading(
						bssid = sr.BSSID ?: "",
						ssid = sr.SSID ?: "",
						capabilities = sr.capabilities ?: "",
						frequency = sr.frequency,
						level = sr.level,
					)
				}

				val signal = SignalAdapter.buildSignal(
					timestampMs = tempData.timeMillis,
					elapsedRealtimeNanos = tempData.elapsedRealtimeNanos,
					latitude = collectionData.location?.latitude,
					longitude = collectionData.location?.longitude,
					accuracy = collectionData.location?.horizontalAccuracy,
					speed = collectionData.location?.speed,
					altitude = collectionData.location?.altitude?.toFloat(),
					rawGpsAltitude = cycle.rawGpsAltitude?.toFloat(),
					activityTypeCode = collectionData.activity?.activityType,
					activityConfidence = collectionData.activity?.confidence,
					stepDelta = cycle.stepDelta,
					totalStepsSinceBoot = cycle.totalStepsSinceBoot,
					stepSensorValueStart = cycle.stepSensorValueStart,
					stepSensorValueEnd = cycle.stepSensorValueEnd,
					stepSensorReset = cycle.stepSensorReset,
					cellTowers = cellTowers,
					wifiNetworks = wifiNetworks,
					pressureHpa = cycle.pressure?.pressureHpa,
					pressureAltitudeM = cycle.pressure?.altitudeM,
					policyTier = currentTier,
				)
				pipeline.onSignal(signal)
			}
		}

		// Update tracking policy based on collected data (adaptive tracking)
		trackingPolicyManager?.let { policyMgr ->
			tryWithReport {
				policyFeeder.feed(policyMgr, collectionData, tempData, scope)
			}
		}

		trackerListenerManager.send(context, session, collectionData)
	}

	private suspend fun destroyComponents(context: Context) {
		// Stop the stats ProcessorPipeline first (final flush + event delivery)
		tryWithReport {
			processorPipeline?.stop()
			processorPipeline = null
		}

		dataProducerManager?.onDisable()
		trackingPolicyManager?.stop()
		trackingPolicyManager = null
		preComponentList.forEach { tryWithReport { it.onDisable(context) } }
		dataComponentList.forEach { tryWithReport { it.onDisable(context) } }
		postComponentList.forEach { tryWithReport { it.onDisable(context) } }
		sessionComponent?.let { component ->
			tryWithReport {
				component.onDisable(context)
			}
		}
		sessionComponent = null
		// Session finalization is handled by ProcessorPipeline.stop() which
		// emits SessionEnded domain events consumed by event consumers.
	}
}
