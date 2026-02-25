package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.extension.hasSelfPermissions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.DataProducerManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.NoTimer
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.ActivityTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.CellTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.WifiTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.ActivitySnapshotWriter
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseCellComponent
import com.adsamcik.tracker.tracker.component.consumer.post.ExplorationWriter
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseLocationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseWifiComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseWifiLocationCountComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.RawLocationWriter
import com.adsamcik.tracker.tracker.component.consumer.post.SessionSegmentWriter
import com.adsamcik.tracker.tracker.component.consumer.post.StepIntervalWriter
import com.adsamcik.tracker.tracker.component.consumer.post.StreamingAggregatorWriter
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.component.trigger.AmbientCollectionTrigger
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.consumer.pre.LocationPreTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.PolicyAwareLocationPreTrackerComponent
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.SignalAdapter
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/***
 * Service which is responsible for tracking.
 */
@AndroidEntryPoint
internal class TrackerService : CoreService(), TrackerTimerReceiver {
	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock

	// Injected controller for state management (replaces static appGraph access)
	@Inject
	lateinit var controller: TrackerServiceController
	
	// Injected lock manager for lock state observation
	@Inject
	lateinit var lockManager: LockManager
	
	// Injected listener manager for sending tracker updates
	@Inject
	lateinit var trackerListenerManager: TrackerListenerManager

	// Injected stats pipeline dependencies
	@Inject
	lateinit var signalProcessors: Set<@JvmSuppressWildcards SignalProcessor>

	@Inject
	lateinit var domainEventRepository: DomainEventRepository

	private var processorPipeline: ProcessorPipeline? = null

	// Dual-run cycle counter for comparison logging
	private var dualRunCycleCount: Int = 0

	private val componentMutex = Mutex()
	
	// Job for observing lock state (cancelled on service destroy)
	private var lockObservationJob: Job? = null

	private var timerComponent: CollectionTriggerComponent = NoTimer()

	private val notificationComponent: NotificationComponent = NotificationComponent()

	private var dataProducerManager: DataProducerManager? = null
	private var trackingPolicyManager: TrackingPolicyManager? = null

	private val preComponentList = mutableListOf<PreTrackerComponent>()
	private val postComponentList = mutableListOf<PostTrackerComponent>()
	private val dataComponentList = mutableListOf<DataTrackerComponent>()
	
	// Persistence error collector for database components
	private var persistenceErrorCollector: PersistenceErrorCollector? = null

	// Kept here and used internally in case something went wrong and service was launched again with different info
	private var sessionInfo: TrackerSessionInfo? = null
	private var sessionComponent: SessionTrackerComponent? = null
	private val session: TrackerSession get() = requireNotNull(sessionComponent).session

	// Current tracking tier - determines which components/producers are active
	private var currentTier: PolicyTier = PolicyTier.PRECISION

	// Policy update state tracking
	private var lastActivityType: Int = -1
	private var lastLocation: com.adsamcik.tracker.shared.base.data.Location? = null
	private var accumulatedStepCount: Int = 0

	/**
	 * Collects data from necessary places and sensors and creates new MutableCollectionData instance
	 */
	@WorkerThread
	private suspend fun updateData(tempData: MutableCollectionTempData) {
		requireNotNull(dataProducerManager).getData(tempData)

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
						it.onNewData(this, session, collectionData, tempData)
					}
				}

		dualRunCycleCount++
		Logger.log(LogData(
			message = "Cycle #$dualRunCycleCount: PostComponents=${postComponentsRun.size}/${postComponentList.size} processed",
			source = DUAL_RUN_LOG_SOURCE
		))

		// Feed data to the new ProcessorPipeline (dual-run alongside PostTrackerComponents)
		processorPipeline?.let { pipeline ->
			tryWithReport {
				val signal = SignalAdapter.buildSignal(
					timestampMs = tempData.timeMillis,
					latitude = collectionData.location?.latitude,
					longitude = collectionData.location?.longitude,
					accuracy = collectionData.location?.horizontalAccuracy,
					speed = collectionData.location?.speed,
					altitude = collectionData.location?.altitude?.toFloat(),
					activityTypeCode = collectionData.activity?.activityType,
					activityConfidence = collectionData.activity?.confidence,
					stepDelta = tempData.tryGet<Int>(StepDataProducer.NEW_STEPS_ARG),
				)
				pipeline.onSignal(signal)
				Logger.log(LogData(
					message = "Cycle #$dualRunCycleCount: Pipeline signal delivered (ts=${tempData.timeMillis})",
					source = DUAL_RUN_LOG_SOURCE
				))
			}
		}

		// Update tracking policy based on collected data (adaptive tracking)
		trackingPolicyManager?.let { policyMgr ->
			tryWithReport {
				val currentTimeMs = tempData.timeMillis

				// Feed activity transitions to policy manager
				collectionData.activity?.let { activity ->
					val currentActivityType = activity.activityType
					if (lastActivityType >= 0 && lastActivityType != currentActivityType) {
						// Activity transition detected
						launch {
							policyMgr.onActivityTransition(
								activityType = currentActivityType,
								confidence = activity.confidence,
								timeMs = currentTimeMs
							)
						}
					}
					lastActivityType = currentActivityType
				}

				// Feed location changes for displacement detection and speed updates
				collectionData.location?.let { location ->
					// Update speed in the escalation engine for GPS interval refinement
					policyMgr.escalationEngine?.updateSpeed(location.speed)

					lastLocation?.let { prevLocation ->
						val distance = prevLocation.distance(
							location,
							com.adsamcik.tracker.shared.base.data.LengthUnit.Meter
						).toFloat()
						if (distance > 0f) {
							launch {
								policyMgr.onLocationChange(
									displacementMeters = distance,
									timeMs = currentTimeMs
								)
							}
						}
					}
					lastLocation = location
				}

				// Feed step updates from temp data (accumulate NEW_STEPS_ARG)
				tempData.tryGet<Int>(StepDataProducer.NEW_STEPS_ARG)?.let { newSteps ->
					if (newSteps > 0) {
						accumulatedStepCount += newSteps
						launch {
							policyMgr.onStepUpdate(
								stepCount = accumulatedStepCount,
								timeMs = currentTimeMs
							)
						}
					}
				}
			}
		}

		if (!requireNotNull(sessionInfo).isInitiatedByUser && powerManager.isPowerSaveMode) stopSelf()

		trackerListenerManager.send(this, session, collectionData)
	}


	override fun onCreate() {
		super.onCreate()

		startForeground(
				TrackerNotificationManager.NOTIFICATION_ID,
				TrackerNotificationManager.getForegroundNotification(this)
		)

		// Get managers
		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(
				PowerManager.PARTIAL_WAKE_LOCK,
				"signals:TrackerWakeLock"
		)


		// Shortcut setup
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			Shortcuts.updateShortcut(
					this,
					ShortcutData(
							Shortcuts.TRACKING_ID,
							R.string.shortcut_stop_tracking,
							R.string.shortcut_stop_tracking_long,
							com.adsamcik.tracker.shared.base.R.drawable.ic_pause_circle_filled_black_24dp,
							Shortcuts.ShortcutAction.STOP_COLLECTION
					)
			)
		}

	}

	@MainThread
	private suspend fun initializeComponents(
		isSessionUserInitiated: Boolean,
		initialTier: PolicyTier
	) = componentMutex.withLock {
		// Clear existing components to prevent duplicates and ConcurrentModificationException
		// if onStartCommand is called multiple times or concurrently with onUpdate.
		preComponentList.forEach { tryWithReport { it.onDisable(this@TrackerService) } }
		dataComponentList.forEach { tryWithReport { it.onDisable(this@TrackerService) } }
		postComponentList.forEach { tryWithReport { it.onDisable(this@TrackerService) } }
		preComponentList.clear()
		dataComponentList.clear()
		postComponentList.clear()

		sessionComponent = SessionTrackerComponent(isSessionUserInitiated).apply {
			onEnable(this@TrackerService)
		}

		// Emit initial session via controller
		controller.updateSession(session)

		// Cleanup previous managers if re-initializing
		dataProducerManager?.onDisable()
		trackingPolicyManager?.stop()

		// DispatchersProvider injection from AppGraph intentionally avoided to keep tracker module
		// independent of app module. DataProducerManager falls back to its internal default provider.
		dataProducerManager = DataProducerManager(this, initialTier).apply { onEnable() }

		// Initialize the new 4-tier escalation engine
		val escalationEngine = DefaultPolicyEscalationEngine()

		// Initialize adaptive tracking policy manager with engine delegation
		trackingPolicyManager = TrackingPolicyManager(
			context = this,
			isUserInitiated = isSessionUserInitiated,
			escalationEngine = escalationEngine,
			scope = this@TrackerService,
		).apply {
			start() // Start tracking run + engine
		}

		// Observe engine state changes for UI and timer updates
		launch {
			escalationEngine.policyState.collect { state ->
				controller.updatePolicyState(state)
			}
		}

		// Phase 4: Observe policy changes and update timer intervals dynamically
		trackingPolicyManager?.let { policyManager ->
			launch {
				policyManager.currentPolicy.collect { newPolicy ->
					updateTimerIntervalForPolicy(newPolicy)
				}
			}
		}

		preComponentList.apply {
			// Phase 3: Use policy-aware location component for adaptive tracking
			trackingPolicyManager?.let { policyMgr ->
				add(PolicyAwareLocationPreTrackerComponent(policyMgr.currentPolicy))
			} ?: run {
				// Fallback: Use original accuracy checker if policy manager unavailable
				add(LocationPreTrackerComponent())
			}
		}.forEach { it.onEnable(this) }

		dataComponentList.apply {
			add(ActivityTrackerComponent())
			if (initialTier.isGpsEnabled) {
				add(CellTrackerComponent())
				add(LocationTrackerComponent())
				add(WifiTrackerComponent())
			}
		}.forEach { it.onEnable(this) }

		// Create persistence error collector for database components
		val errorCollector = DefaultPersistenceErrorCollector()
		persistenceErrorCollector = errorCollector
		controller.updatePersistenceErrorFlow(errorCollector.errors)

		// Post-processing components, filtered by tier.
		// AMBIENT: notification + sessionless step/activity writers + aggregator.
		// ACTIVE+: full set including GPS-dependent DB writers and trip detection.
		postComponentList.apply {
			add(notificationComponent)
			add(StepIntervalWriter())
			add(ActivitySnapshotWriter())
			add(StreamingAggregatorWriter())
			if (initialTier.isGpsEnabled) {
				add(DatabaseCellComponent().also { it.setErrorCollector(errorCollector) })
				add(DatabaseLocationComponent().also { it.setErrorCollector(errorCollector) })
				add(DatabaseWifiComponent().also { it.setErrorCollector(errorCollector) })
				add(DatabaseWifiLocationCountComponent())
				add(RawLocationWriter())
				add(SessionSegmentWriter().also {
					it.setEscalationEngine(escalationEngine)
					it.setUserInitiated(isSessionUserInitiated)
				})
				add(ExplorationWriter())
			}
		}.forEach { it.onEnable(this) }

		// Initialize the stats ProcessorPipeline (dual-run alongside PostTrackerComponents)
		val pipeline = ProcessorPipeline(
			processors = signalProcessors,
			scope = this@TrackerService,
			onDomainEvents = { events -> domainEventRepository.persist(events) },
		)
		processorPipeline = pipeline
		pipeline.start(
			tier = initialTier,
			startTimestamp = EpochMs(Time.nowMillis),
			sessionId = session.id,
		)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		if (intent == null) {
			Reporter.report(NullPointerException("Intent is null"))
		}

		val isUserInitiated = intent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
				?: DEFAULT_IS_USER_INITIATED
		val isAmbient = intent?.getBooleanExtra(ARG_IS_AMBIENT, false) ?: false

		// Determine initial tier from intent flags
		val initialTier = when {
			isUserInitiated -> PolicyTier.PRECISION
			isAmbient -> PolicyTier.AMBIENT
			else -> PolicyTier.AMBIENT // auto-tracking starts ambient, escalates via engine
		}
		currentTier = initialTier
		controller.updatePolicyTier(initialTier)

		// Select timer based on tier: AMBIENT uses lightweight handler, ACTIVE+ uses GPS-based timer
		timerComponent = if (initialTier == PolicyTier.AMBIENT) {
			AmbientCollectionTrigger()
		} else {
			TrackerTimerManager.getSelected(this)
		}

		controller.updateServiceRunning(true)

		this.sessionInfo = TrackerSessionInfo(isUserInitiated)
		controller.updateSessionInfo(this.sessionInfo)

		if (!isUserInitiated) {
			// Observe lock state via injected LockManager (Flow-based, replaces LiveData)
			lockObservationJob = launch {
				lockManager.isLockedFlow.collect { isLocked ->
					if (isLocked) stopSelf()
				}
			}
		}

		ActivityWatcherService.poke(this, trackerRunning = true)

		launch {
			val componentInitialization = async(Dispatchers.Main) {
				initializeComponents(
					isSessionUserInitiated = isUserInitiated,
					initialTier = initialTier
				)
			}

			componentInitialization.await()

			if (hasSelfPermissions(timerComponent.requiredPermissions).all { it }) {
				timerComponent.onEnable(this@TrackerService, this@TrackerService)
			} else {
				stopSelf()
				Reporter.report("Missing permissions for ${timerComponent.javaClass}")
			}
		}

		// User-initiated sessions should restart after process death to preserve tracking.
		// Auto-tracking sessions can be re-triggered by ActivityWatcherService.
		return if (isUserInitiated) START_STICKY else START_NOT_STICKY
	}

	/**
	 * Update timer collection interval based on tracking policy level.
	 * Handles tier escalation (AMBIENT -> ACTIVE) by swapping timers and adding components.
	 *
	 * @param policy New tracking policy
	 */
	private fun updateTimerIntervalForPolicy(policy: com.adsamcik.tracker.tracker.policy.TrackingPolicy) {
		val newTier = com.adsamcik.tracker.tracker.policy.PolicyTierMapper.toTier(policy)
		val oldTier = currentTier

		// Update tier and notify UI
		if (newTier != oldTier) {
			currentTier = newTier
			controller.updatePolicyTier(newTier)

			// Handle tier escalation: AMBIENT -> ACTIVE requires timer swap + component additions
			if (!oldTier.isGpsEnabled && newTier.isGpsEnabled) {
				onTierEscalation(newTier)
			}
		}

		val timer = timerComponent
		if (timer !is com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger) {
			return
		}

		val intervalSeconds = com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistanceMeters = com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper.getMinDistanceMeters(policy)

		timer.updateInterval(this, intervalSeconds, minDistanceMeters)
	}

	/**
	 * Handle tier escalation from AMBIENT (no GPS) to ACTIVE/PRECISION (GPS enabled).
	 * Swaps the ambient timer for a GPS-based timer and recreates the DataProducerManager
	 * with full producers. GPS-dependent data and post components are added.
	 *
	 * All list mutations are protected by [componentMutex] to prevent
	 * ConcurrentModificationException with [onUpdate] iterating the same lists.
	 */
	private fun onTierEscalation(newTier: PolicyTier) {
		launch {
			componentMutex.withLock {
				// Swap timer: disable ambient, enable GPS-based (inside mutex so
				// no collection cycle can fire between disable and re-enable).
				timerComponent.onDisable(this@TrackerService)
				val gpsTimer = TrackerTimerManager.getSelected(this@TrackerService)
				timerComponent = gpsTimer

				// Recreate DataProducerManager with full producer set
				dataProducerManager?.onDisable()
				val newManager = DataProducerManager(this@TrackerService, newTier)
				newManager.onEnable()
				dataProducerManager = newManager

				// Add GPS-dependent data components
				val newDataComponents = listOf(
					CellTrackerComponent(),
					LocationTrackerComponent(),
					WifiTrackerComponent()
				)
				newDataComponents.forEach { it.onEnable(this@TrackerService) }
				dataComponentList.addAll(newDataComponents)

				// Add GPS-dependent post components
				val errorCollector = persistenceErrorCollector as? DefaultPersistenceErrorCollector
				val newPostComponents = mutableListOf<PostTrackerComponent>()
				if (errorCollector != null) {
					newPostComponents.add(DatabaseCellComponent().also { it.setErrorCollector(errorCollector) })
					newPostComponents.add(DatabaseLocationComponent().also { it.setErrorCollector(errorCollector) })
					newPostComponents.add(DatabaseWifiComponent().also { it.setErrorCollector(errorCollector) })
				}
				newPostComponents.add(DatabaseWifiLocationCountComponent())
				newPostComponents.add(RawLocationWriter())
				val isUserInitiated = sessionInfo?.isInitiatedByUser ?: false
				val escalationEngine = trackingPolicyManager?.escalationEngine
				newPostComponents.add(SessionSegmentWriter().also {
					if (escalationEngine != null) it.setEscalationEngine(escalationEngine)
					it.setUserInitiated(isUserInitiated)
				})
				newPostComponents.add(ExplorationWriter())
				newPostComponents.forEach { it.onEnable(this@TrackerService) }
				postComponentList.addAll(newPostComponents)

				// Escalate the stats ProcessorPipeline to new tier
				processorPipeline?.escalate(newTier, EpochMs(Time.nowMillis))

				// Enable the GPS timer after components are ready
				if (hasSelfPermissions(gpsTimer.requiredPermissions).all { it }) {
					gpsTimer.onEnable(this@TrackerService, this@TrackerService)
				} else {
					Reporter.report("Missing permissions for GPS timer during escalation")
				}
			}
		}
	}

	override fun onUpdate(tempData: MutableCollectionTempData): Job = launch {
		componentMutex.withLock {
			if (!controller.isServiceRunning) {
				return@launch
			}

			wakeLock.acquire(Time.SECOND_IN_MILLISECONDS * 10L)
			try {
				updateData(tempData)
			} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
				Reporter.report(e)
			} finally {
				wakeLock.release()
			}
		}
	}

	override fun onError(errorData: TrackerTimerErrorData) {
		when (errorData.severity) {
			TrackerTimerErrorSeverity.STOP_SERVICE -> stopSelf()
			TrackerTimerErrorSeverity.REPORT -> Reporter.report(errorData.internalMessage)
			TrackerTimerErrorSeverity.NOTIFY_USER -> notificationComponent.onError(
					this,
					errorData.messageRes
			)
			TrackerTimerErrorSeverity.WARNING -> Reporter.log(errorData.internalMessage)
		}
	}

	@MainThread
	private suspend fun onDestroyComponents(context: Context) {
		// Stop the stats ProcessorPipeline first (final flush + event delivery)
		tryWithReport {
			processorPipeline?.stop()
			Logger.log(LogData(
				message = "Session ended: PostComponents ran $dualRunCycleCount cycles, Pipeline processed $dualRunCycleCount signals",
				source = DUAL_RUN_LOG_SOURCE
			))
			processorPipeline = null
			dualRunCycleCount = 0
		}

		dataProducerManager?.onDisable()
		trackingPolicyManager?.stop()
		trackingPolicyManager = null
		preComponentList.forEach { tryWithReport { it.onDisable(context) } }
		dataComponentList.forEach { tryWithReport { it.onDisable(context) } }
		postComponentList.forEach { tryWithReport { it.onDisable(context) } }
		// Session finalization is handled by ProcessorPipeline.stop() which
		// emits SessionEnded domain events consumed by event consumers.
	}

	private fun onDestroyServiceMetaData() {
		controller.updateServiceRunning(false)
		controller.updateSessionInfo(null)
		controller.updateSession(null)
		controller.updateCollectionData(null)
		controller.updatePersistenceErrorFlow(null)
		controller.updatePolicyState(null)
		controller.updatePolicyTier(com.adsamcik.tracker.stats.api.PolicyTier.OFF)
		persistenceErrorCollector = null
	}


	override fun onDestroy() {
		super.onDestroy()
		stopForeground(STOP_FOREGROUND_REMOVE)
		
		// Cancel lock observation job to prevent leaks
		lockObservationJob?.cancel()
		lockObservationJob = null

		// Perform final data collection BEFORE setting isServiceRunning=false,
		// otherwise onUpdate will return early due to isServiceRunning check.
		val tempData = MutableCollectionTempData(Time.nowMillis, Time.elapsedRealtimeNanos)
		onUpdate(tempData).invokeOnCompletion {
			onDestroyServiceMetaData()
			onDestroyCleanup()
		}
	}

	private fun onDestroyCleanup() {
		val context = this
		launch(Dispatchers.Main) {
			componentMutex.withLock {
				// Flush pending batched location inserts before disabling for durability.
				postComponentList.filterIsInstance<DatabaseLocationComponent>().firstOrNull()?.let { comp ->
					launch { comp.flushPending() }
				}

				timerComponent.onDisable(context)

				ActivityWatcherService.poke(context, trackerRunning = false)

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
					Shortcuts.updateShortcut(
						context,
						ShortcutData(
							Shortcuts.TRACKING_ID,
							R.string.shortcut_start_tracking,
							R.string.shortcut_start_tracking_long,
							com.adsamcik.tracker.shared.base.R.drawable.ic_play_circle_filled_black_24dp,
							Shortcuts.ShortcutAction.START_COLLECTION
						)
					)
				}

				onDestroyComponents(context)
			}
		}
	}

	companion object {
		const val ARG_IS_USER_INITIATED = "userInitiated"
		const val ARG_IS_AMBIENT = "isAmbient"
		private const val DEFAULT_IS_USER_INITIATED = false
		private const val DUAL_RUN_LOG_SOURCE = "DualRun"
	}
}

