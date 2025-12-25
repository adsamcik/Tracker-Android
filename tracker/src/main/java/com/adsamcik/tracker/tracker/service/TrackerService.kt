package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
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
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
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

	// Injected component manager
	@Inject
	lateinit var componentManager: TrackerComponentManager

	private val componentMutex = Mutex()
	
	// Job for observing lock state (cancelled on service destroy)
	private var lockObservationJob: Job? = null

	private var timerComponent: CollectionTriggerComponent = NoTimer()

	// Kept here and used internally in case something went wrong and service was launched again with different info
	private var sessionInfo: TrackerSessionInfo? = null
	private val session: TrackerSession get() = requireNotNull(componentManager.mobileSessionComponent).session

	// Policy update state tracking
	private var lastActivityType: Int = -1
	private var lastLocation: com.adsamcik.tracker.shared.base.data.Location? = null
	private var accumulatedStepCount: Int = 0

	/**
	 * Collects data from necessary places and sensors and creates new MutableCollectionData instance
	 */
	@WorkerThread
	private suspend fun updateData(tempData: MutableCollectionTempData) {
		val collectionData = componentManager.updateData(
			tempData = tempData,
			context = this,
			session = session,
			controller = controller,
			trackerListenerManager = trackerListenerManager
		) ?: return

		// Adaptive tracking: Feed data back to policy manager
		val updatedState = componentManager.feedPolicyManager(
			tempData = tempData,
			collectionData = collectionData,
            
            scope = this,
            accumulatedStepCount = accumulatedStepCount,
            lastLocation = lastLocation,
            lastActivityType = lastActivityType
		)
		
		accumulatedStepCount = updatedState.first
		lastLocation = updatedState.second
		lastActivityType = updatedState.third

		if (!requireNotNull(sessionInfo).isInitiatedByUser && powerManager.isPowerSaveMode) stopSelf()
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
	private suspend fun initializeComponents(isSessionUserInitiated: Boolean) = componentMutex.withLock {
		componentManager.initialize(
			context = this,
			scope = this,
			isSessionUserInitiated = isSessionUserInitiated,
			controller = controller,
			onPolicyChanged = { newPolicy -> updateTimerIntervalForPolicy(newPolicy) }
		)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		if (intent == null) {
			Reporter.report(NullPointerException("Intent is null"))
		}

		val isUserInitiated = intent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
				?: DEFAULT_IS_USER_INITIATED

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
		val sessionComponent = requireNotNull(componentManager.mobileSessionComponent)

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

		if (!controller.isServiceRunning) {
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
			TrackerTimerErrorSeverity.NOTIFY_USER -> componentManager.notificationComponent.onError(
					this,
					errorData.messageRes
			)
			TrackerTimerErrorSeverity.WARNING -> Reporter.log(errorData.internalMessage)
		}
	}

	@MainThread
	private suspend fun onDestroyComponents(context: Context) {
		componentManager.disableAll(context)

		// Can be null if TrackerServices is immediately stopped after start
		val sessionComponent = componentManager.mobileSessionComponent
		if (sessionComponent != null) {
			SessionBroadcaster.broadcastSessionEnd(context, sessionComponent.session)
		}
	}

	private fun onDestroyServiceMetaData() {
		controller.updateServiceRunning(false)
		controller.updateSessionInfo(null)
		controller.updateSession(null)
		controller.updateCollectionData(null)
	}


	override fun onDestroy() {
		super.onDestroy()
		stopForeground(STOP_FOREGROUND_REMOVE)
		onDestroyServiceMetaData()
		
		// Cancel lock observation job to prevent leaks
		lockObservationJob?.cancel()
		lockObservationJob = null

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
				componentManager.flushPending(this)

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
		private const val DEFAULT_IS_USER_INITIATED = false
	}
}

