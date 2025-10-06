package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.extension.hasSelfPermissions
import com.adsamcik.tracker.shared.base.misc.NonNullLiveData
import com.adsamcik.tracker.shared.base.misc.NonNullLiveMutableData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.broadcast.SessionBroadcaster
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
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseLocationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseWifiComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseWifiLocationCountComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.RawLocationWriter
import com.adsamcik.tracker.tracker.component.consumer.post.StepIntervalWriter
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.tracker.component.consumer.pre.LocationPreTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.PolicyAwareLocationPreTrackerComponent
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/***
 * Service which is responsible for tracking.
 */
internal class TrackerService : CoreService(), TrackerTimerReceiver {
	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock

	private val componentMutex = Mutex()

	private var timerComponent: CollectionTriggerComponent = NoTimer()

	private val notificationComponent: NotificationComponent = NotificationComponent()

	private var dataProducerManager: DataProducerManager? = null
	private var trackingPolicyManager: TrackingPolicyManager? = null

	private val preComponentList = mutableListOf<PreTrackerComponent>()
	private val postComponentList = mutableListOf<PostTrackerComponent>()
	private val dataComponentList = mutableListOf<DataTrackerComponent>()

	// Kept here and used internally in case something went wrong and service was launched again with different info
	private var sessionInfo: TrackerSessionInfo? = null
	private var sessionComponent: SessionTrackerComponent? = null
	private val session: TrackerSession get() = requireNotNull(sessionComponent).session

	// Policy update state tracking
	private var lastActivityType: Int = -1
	private var lastLocation: com.adsamcik.tracker.shared.base.data.Location? = null
	private var lastStepCount: Int = 0

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

		postComponentList
				.asSequence()
				.filter { it.requirementsMet(tempData) }
				.forEach {
					tryWithReport {
						it.onNewData(this, session, collectionData, tempData)
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

				// Feed location changes for displacement detection
				collectionData.location?.let { location ->
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

				// Feed step updates from temp data (if available from StepDataProducer)
				tempData.tryGet<Int>("step_count")?.let { currentStepCount ->
					if (lastStepCount > 0 && currentStepCount > lastStepCount) {
						launch {
							policyMgr.onStepUpdate(
								stepCount = currentStepCount,
								timeMs = currentTimeMs
							)
						}
					}
					lastStepCount = currentStepCount
				}
			}
		}

		if (!requireNotNull(sessionInfo).isInitiatedByUser && powerManager.isPowerSaveMode) stopSelf()

		// Emit state to Flow for Compose UI observation
		_sessionFlow.value = session
		_collectionDataFlow.value = collectionData

		TrackerListenerManager.send(this, session, collectionData)
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

		initializeTimer()
	}

	@MainThread
	private suspend fun initializeComponents(isSessionUserInitiated: Boolean) {
		sessionComponent = SessionTrackerComponent(isSessionUserInitiated).apply {
			onEnable(this@TrackerService)
		}

		// DispatchersProvider injection from AppGraph intentionally avoided to keep tracker module
		// independent of app module. DataProducerManager falls back to its internal default provider.
		dataProducerManager = DataProducerManager(this).apply { onEnable() }

		// Initialize adaptive tracking policy manager
		trackingPolicyManager = TrackingPolicyManager(
			context = this,
			isUserInitiated = isSessionUserInitiated
		).apply {
			start() // Start tracking run
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
			add(CellTrackerComponent())
			add(LocationTrackerComponent())
			add(WifiTrackerComponent())
		}.forEach { it.onEnable(this) }

		// todo add only components that can actually be used
		postComponentList.apply {
			add(notificationComponent)
			add(DatabaseCellComponent())
			add(DatabaseLocationComponent())
			add(DatabaseWifiComponent())
			add(DatabaseWifiLocationCountComponent())
			// Phase 1: Sessionless tracking raw writers
			add(RawLocationWriter())
			add(StepIntervalWriter())
			add(ActivitySnapshotWriter())
		}.forEach { it.onEnable(this) }
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		if (intent == null) {
			Reporter.report(NullPointerException("Intent is null"))
		}

		val isUserInitiated = intent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
				?: DEFAULT_IS_USER_INITIATED

		_isServiceRunning.value = true

		this.sessionInfo = TrackerSessionInfo(isUserInitiated)
		sessionInfoMutable.value = this.sessionInfo

		if (!isUserInitiated) {
			TrackerLocker.isLocked.observe(this) {
				if (it) stopSelf()
			}
		}

		ActivityWatcherService.poke(this, trackerRunning = true)

		launch {
			val componentInitialization = async(Dispatchers.Main) {
				initializeComponents(isSessionUserInitiated = isUserInitiated)
			}

			componentInitialization.await()

			if (hasSelfPermissions(timerComponent.requiredPermissions).all { it }) {
				sendSessionStartBroadcast()
				timerComponent.onEnable(this@TrackerService, this@TrackerService)
			} else {
				stopSelf()
				Reporter.report("Missing permissions for ${timerComponent.javaClass}")
			}
		}

		return START_NOT_STICKY
	}

	private fun sendSessionStartBroadcast() {
		val sessionComponent = requireNotNull(sessionComponent)

		SessionBroadcaster.broadcastSessionStart(
				this,
				sessionComponent.session,
				sessionComponent.isNewSession
		)
	}

	private fun initializeTimer() {
		timerComponent = TrackerTimerManager.getSelected(this)
	}

	/**
	 * Update timer collection interval based on tracking policy level.
	 * Phase 4: Dynamic interval adjustment for battery optimization.
	 *
	 * @param policy New tracking policy
	 */
	private fun updateTimerIntervalForPolicy(policy: com.adsamcik.tracker.tracker.policy.TrackingPolicy) {
		val timer = timerComponent
		if (timer !is com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger) {
			// Timer doesn't support dynamic updates, skip
			return
		}

		val intervalSeconds = com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistanceMeters = com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper.getMinDistanceMeters(policy)

		timer.updateInterval(this, intervalSeconds, minDistanceMeters)
	}

	override fun onUpdate(tempData: MutableCollectionTempData): Job = launch {
		componentMutex.lock()

		if (!isServiceRunning) {
			componentMutex.unlock()
			return@launch
		}

		wakeLock.acquire(Time.MINUTE_IN_MILLISECONDS)
		try {
			updateData(tempData)
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			Reporter.report(e)
		} finally {
			wakeLock.release()
			componentMutex.unlock()
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
		dataProducerManager?.onDisable()
		trackingPolicyManager?.stop()
		trackingPolicyManager = null
		preComponentList.forEach { it.onDisable(context) }
		postComponentList.forEach { it.onDisable(context) }

		// Can be null if TrackerServices is immediately stopped after start
		val sessionComponent = sessionComponent
		if (sessionComponent != null) {
			SessionBroadcaster.broadcastSessionEnd(context, sessionComponent.session)
		}
	}

	private fun onDestroyServiceMetaData() {
		_isServiceRunning.value = false
		sessionInfoMutable.value = null
		_sessionFlow.value = null
		_collectionDataFlow.value = null
	}


	override fun onDestroy() {
		super.onDestroy()
		stopForeground(STOP_FOREGROUND_REMOVE)
		onDestroyServiceMetaData()

		val tempData = MutableCollectionTempData(Time.nowMillis, Time.elapsedRealtimeNanos)
		onUpdate(tempData).invokeOnCompletion {
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
		private val _isServiceRunning = MutableStateFlow(false)
		val isServiceRunningFlow: StateFlow<Boolean> get() = _isServiceRunning
		val isServiceRunning: Boolean get() = _isServiceRunning.value

		private val sessionInfoMutable: MutableLiveData<TrackerSessionInfo?> = MutableLiveData()

		/**
		 * Current information about session.
		 * Null when no session is active.
		 */
		val sessionInfo: LiveData<TrackerSessionInfo?> get() = sessionInfoMutable

		// Flow-based state exposure (evergreen migration from LiveData)
		private val _sessionFlow = MutableStateFlow<TrackerSession?>(null)
		
		/**
		 * Current tracking session state (Flow).
		 * Emits session updates after each data collection cycle.
		 * Null when no session is active.
		 */
		val sessionFlow: StateFlow<TrackerSession?> get() = _sessionFlow

		private val _collectionDataFlow = MutableStateFlow<com.adsamcik.tracker.shared.base.data.CollectionData?>(null)
		
		/**
		 * Latest collection data (Flow).
		 * Emits after each successful data collection cycle.
		 * Null when no tracking is active or data collection failed.
		 * 
		 * Note: Emissions happen every 10-300 seconds depending on tracking policy.
		 * Use distinctUntilChanged or debounce if needed to reduce recomposition frequency.
		 */
		val collectionDataFlow: StateFlow<com.adsamcik.tracker.shared.base.data.CollectionData?> get() = _collectionDataFlow

		const val ARG_IS_USER_INITIATED = "userInitiated"
		private const val DEFAULT_IS_USER_INITIATED = false
	}
}

