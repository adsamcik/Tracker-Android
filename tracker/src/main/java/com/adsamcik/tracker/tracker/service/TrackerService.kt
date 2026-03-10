package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.DataProducerManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.NoTimer
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.trigger.AmbientCollectionTrigger
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.SignalAdapter
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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

	@Inject
	lateinit var trackingParamsRepository: TrackingParamsRepository

	@Inject
	lateinit var trackerSettingsRepository: TrackerSettingsRepository

	@Inject
	lateinit var appDatabase: AppDatabase

	private var processorPipeline: ProcessorPipeline? = null

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

	// Extracted delegates
	private val componentFactory by lazy { TrackerComponentFactory(appDatabase, trackingParamsRepository, trackerSettingsRepository) }
	private val policyFeeder = TrackerPolicyFeeder()
	private lateinit var tierEscalationHandler: TrackerTierEscalationHandler

	/**
	 * Collects data from necessary places and sensors and creates new MutableCollectionData instance
	 */
	@WorkerThread
	private suspend fun updateData(tempData: MutableCollectionTempData) {
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
						it.onNewData(this, session, collectionData, tempData)
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
					activityTypeCode = collectionData.activity?.activityType,
					activityConfidence = collectionData.activity?.confidence,
					stepDelta = cycle.stepDelta,
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
				policyFeeder.feed(policyMgr, collectionData, tempData, this@TrackerService)
			}
		}

		if (!requireNotNull(sessionInfo).isInitiatedByUser && powerManager.isPowerSaveMode) stopSelf()

		trackerListenerManager.send(this, session, collectionData)
	}


	override fun onCreate() {
		super.onCreate()

		ensureForegroundStarted()

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

		// Initialize tier escalation handler
		tierEscalationHandler = TrackerTierEscalationHandler(
			componentMutex = componentMutex,
			controller = controller,
			componentFactory = componentFactory,
		).apply {
			currentTier = initialTier
			this.dataComponentList = this@TrackerService.dataComponentList
			this.postComponentList = this@TrackerService.postComponentList
			this.dataProducerManager = this@TrackerService.dataProducerManager
			this.persistenceErrorCollector = null // set below after factory creates it
			this.processorPipeline = null // set below after pipeline creation
			this.onProducerManagerChanged = { newManager ->
				this@TrackerService.dataProducerManager = newManager
			}
			this.timerAccessor = object : TrackerTierEscalationHandler.TimerAccessor {
				override fun get() = timerComponent
				override fun set(timer: CollectionTriggerComponent) {
					timerComponent = timer
				}
			}
		}

		// Observe policy changes and delegate tier/interval updates
		trackingPolicyManager?.let { policyManager ->
			launch {
				policyManager.currentPolicy.collect { newPolicy ->
					tierEscalationHandler.onPolicyChanged(
						policy = newPolicy,
						context = this@TrackerService,
						timerReceiver = this@TrackerService,
						scope = this@TrackerService,
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
			context = this@TrackerService,
			isSessionUserInitiated = isSessionUserInitiated,
			tier = initialTier,
			notificationComponent = notificationComponent,
			trackingPolicyManager = trackingPolicyManager,
			escalationEngine = escalationEngine,
			controller = controller,
			scope = this@TrackerService,
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
			scope = this@TrackerService,
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

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		ensureForegroundStarted()

		val recoveredSessionInfo = sessionInfo ?: controller.sessionInfoFlow.value
		val resolvedIntent = intent ?: run {
			if (recoveredSessionInfo == null) {
				Reporter.w("TrackerService", "Restarted with null intent and no recoverable session state; stopping service")
				stopSelfResult(startId)
				return START_NOT_STICKY
			}

			Reporter.w("TrackerService", "Restarted with null intent; recovering with sticky defaults")
			null
		}

		val isUserInitiated = resolvedIntent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
			?: recoveredSessionInfo?.isInitiatedByUser
			?: DEFAULT_IS_USER_INITIATED
		val isAmbient = resolvedIntent?.getBooleanExtra(ARG_IS_AMBIENT, false)
			?: !isUserInitiated

		// Determine initial tier from intent flags
		val initialTier = when {
			isUserInitiated -> PolicyTier.PRECISION
			isAmbient -> PolicyTier.AMBIENT
			else -> PolicyTier.AMBIENT // auto-tracking starts ambient, escalates via engine
		}
		currentTier = initialTier
		controller.updatePolicyTier(initialTier)

		// Select timer based on tier: AMBIENT uses lightweight handler, ACTIVE+ uses GPS-based timer
		// Timer selection for non-ambient tiers is deferred to the launch block below
		// because getSelected is now a suspend function.
		if (initialTier == PolicyTier.AMBIENT) {
			timerComponent = AmbientCollectionTrigger()
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
			if (initialTier != PolicyTier.AMBIENT) {
				timerComponent = TrackerTimerManager.getSelected(this@TrackerService)
			}

			val componentInitialization = async(Dispatchers.Default) {
				initializeComponents(
					isSessionUserInitiated = isUserInitiated,
					initialTier = initialTier
				)
			}

			componentInitialization.await()

			if (timerComponent.hasRequiredPermissions(this@TrackerService)) {
				timerComponent.onEnable(this@TrackerService, this@TrackerService)
			} else {
				Reporter.report("Missing permissions for ${timerComponent.javaClass}")
				stopSelf()
			}
		}

		// User-initiated sessions should restart after process death to preserve tracking.
		// Auto-tracking sessions can be re-triggered by ActivityWatcherService.
		return if (isUserInitiated) START_STICKY else START_NOT_STICKY
	}

	private fun ensureForegroundStarted() {
		val notification = TrackerNotificationManager.getForegroundNotification(this)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			startForeground(
				TrackerNotificationManager.NOTIFICATION_ID,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
			)
		} else {
			startForeground(
				TrackerNotificationManager.NOTIFICATION_ID,
				notification
			)
		}
	}

	override fun onUpdate(tempData: MutableCollectionTempData): Job = launch(Dispatchers.Default) {
		componentMutex.withLock {
			if (!controller.isServiceRunning) {
				return@launch
			}

			wakeLock.acquire(Time.SECOND_IN_MILLISECONDS * 10L)
			try {
				updateData(tempData)
			} catch (e: CancellationException) {
				throw e
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

	private fun onDestroyServiceMetaData() {
		controller.updateServiceRunning(false)
		controller.updateSessionInfo(null)
		controller.updateSession(null)
		controller.updateCollectionData(null)
		controller.updatePersistenceErrorFlow(null)
		controller.updatePolicyState(null)
		controller.updatePolicyTier(com.adsamcik.tracker.stats.api.PolicyTier.OFF)
		// L2 fix: ensure error collector scope is always cancelled
		(persistenceErrorCollector as? DefaultPersistenceErrorCollector)?.clear()
		persistenceErrorCollector = null
	}


	override fun onDestroy() {
		// Capture references before super.onDestroy() cancels the coroutine scope
		val timerRef = timerComponent
		val postComponents = postComponentList.toList()
		val context: Context = this

		super.onDestroy()
		stopForeground(STOP_FOREGROUND_REMOVE)
		
		// Cancel lock observation job to prevent leaks
		lockObservationJob?.cancel()
		lockObservationJob = null

		// Fire-and-forget cleanup on an independent scope to avoid blocking the main thread.
		// CoreService.onDestroy() already cancelled our CoroutineScope, so we use a standalone
		// scope with a bounded lifetime to ensure cleanup completes without causing ANR.
		val cleanupScope = kotlinx.coroutines.CoroutineScope(
			Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
		)
		cleanupScope.launch {
			try {
				kotlinx.coroutines.withTimeoutOrNull(4_000L) {
					componentMutex.withLock {
						timerRef.onDisable(context)
						onDestroyComponents(context)
					}
				}
			} finally {
				cleanupScope.cancel()
			}
		}

		onDestroyServiceMetaData()

		ActivityWatcherService.poke(this@TrackerService, trackerRunning = false)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			Shortcuts.updateShortcut(
				this@TrackerService,
				ShortcutData(
					Shortcuts.TRACKING_ID,
					R.string.shortcut_start_tracking,
					R.string.shortcut_start_tracking_long,
					com.adsamcik.tracker.shared.base.R.drawable.ic_play_circle_filled_black_24dp,
					Shortcuts.ShortcutAction.START_COLLECTION
				)
			)
		}
	}

	companion object {
		const val ARG_IS_USER_INITIATED = "userInitiated"
		const val ARG_IS_AMBIENT = "isAmbient"
		private const val DEFAULT_IS_USER_INITIATED = false
	}
}
