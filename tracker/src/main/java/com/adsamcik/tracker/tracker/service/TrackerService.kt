package com.adsamcik.tracker.tracker.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.MutableCollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.debug.Reporter
import com.adsamcik.tracker.common.exception.PermissionException
import com.adsamcik.tracker.common.extension.forEachIf
import com.adsamcik.tracker.common.extension.getSystemServiceTyped
import com.adsamcik.tracker.common.extension.hasLocationPermission
import com.adsamcik.tracker.common.extension.hasSelfPermissions
import com.adsamcik.tracker.common.misc.NonNullLiveData
import com.adsamcik.tracker.common.misc.NonNullLiveMutableData
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.common.service.CoreService
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.TrackerNotificationManager
import com.adsamcik.tracker.tracker.component.DataProducerManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.NoTimer
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.ActivityTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.CellTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.WifiTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseCellComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseLocationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseWifiComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.LocationPreTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.StepPreTrackerComponent
import com.adsamcik.tracker.tracker.component.timer.FusedLocationTrackerTimer
import com.adsamcik.tracker.tracker.component.timer.TimeTrackerTimer
import com.adsamcik.tracker.tracker.data.collection.CollectionDataEcho
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

//todo move TrackerService to it's own process
internal class TrackerService : CoreService(), TrackerTimerReceiver {
	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock

	private val componentMutex = Mutex()

	private var timerComponent: TrackerTimerComponent = NoTimer()

	private val notificationComponent: NotificationComponent = NotificationComponent()

	private var dataProducerManager: DataProducerManager? = null

	private val preComponentList = mutableListOf<PreTrackerComponent>()
	private val postComponentList = mutableListOf<PostTrackerComponent>()
	private val dataComponentList = mutableListOf<DataTrackerComponent>()

	//Kept here and used internally in case something went wrong and service was launched again with different info
	private var sessionInfo: TrackerSessionInfo? = null
	private var sessionComponent: SessionTrackerComponent? = null
	private val session: TrackerSession get() = requireNotNull(sessionComponent).session

	/**
	 * Collects data from necessary places and sensors and creates new MutableCollectionData instance
	 */
	@WorkerThread
	private suspend fun updateData(tempData: MutableCollectionTempData) {
		requireNotNull(dataProducerManager).getData(tempData)

		//if we don't know the accuracy the location is worthless
		if (!preComponentList.all {
					if (it.requirementsMet(tempData)) {
						it.onNewData(tempData)
					} else {
						true
					}
				}) {
			return
		}

		val collectionData = MutableCollectionData(tempData.timeMillis)

		dataComponentList.forEachIf({ it.requirementsMet(tempData) }) {
			it.onDataUpdated(tempData, collectionData)
		}

		requireNotNull(sessionComponent).onDataUpdated(tempData, collectionData)

		postComponentList.forEachIf({ it.requirementsMet(tempData) }) {
			it.onNewData(this, session, collectionData, tempData)
		}

		if (!requireNotNull(sessionInfo).isInitiatedByUser && powerManager.isPowerSaveMode) stopSelf()

		lastCollectionDataMutable.postValue(CollectionDataEcho(collectionData, session))
	}


	override fun onCreate() {
		super.onCreate()

		//Get managers
		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(
				PowerManager.PARTIAL_WAKE_LOCK,
				"signals:TrackerWakeLock"
		)


		//Shortcut setup
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			Shortcuts.updateShortcut(
					this,
					ShortcutData(
							Shortcuts.TRACKING_ID,
							R.string.shortcut_stop_tracking,
							R.string.shortcut_stop_tracking_long,
							R.drawable.ic_pause_circle_filled_black_24dp,
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

		dataProducerManager = DataProducerManager(this).apply {
			onEnable()
		}

		preComponentList.apply {
			add(StepPreTrackerComponent())
			add(LocationPreTrackerComponent())
		}.forEach { it.onEnable(this) }

		dataComponentList.apply {
			add(ActivityTrackerComponent())
			add(CellTrackerComponent())
			add(LocationTrackerComponent())
			add(WifiTrackerComponent())
		}

		// todo add only components that can actually be used
		postComponentList.apply {
			add(notificationComponent)
			add(DatabaseCellComponent())
			add(DatabaseLocationComponent())
			add(DatabaseWifiComponent())
			add(DatabaseLocationComponent())
		}.forEach { it.onEnable(this) }
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		if (intent == null) {
			Reporter.report(NullPointerException("Intent is null"))
		}

		if (!this.hasLocationPermission) {
			Reporter.report(PermissionException("Tracker does not have sufficient permissions"))
			stopSelf()
			return Service.START_NOT_STICKY
		}

		val isUserInitiated = intent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
				?: DEFAULT_IS_USER_INITIATED

		isServiceRunningMutable.value = true

		this.sessionInfo = TrackerSessionInfo(isUserInitiated)
		sessionInfoMutable.value = this.sessionInfo

		val notificationBuilder = TrackerNotificationManager(this)
				.createBuilder()
				.apply {
					setContentTitle(getString(R.string.notification_starting))
				}

		startForeground(TrackerNotificationManager.NOTIFICATION_ID, notificationBuilder.build())

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

			val sessionStartIntent = Intent(TrackerSession.RECEIVER_SESSION_STARTED)
			LocalBroadcastManager.getInstance(this@TrackerService).sendBroadcast(sessionStartIntent)

			componentInitialization.await()

			if (hasSelfPermissions(timerComponent.requiredPermissions).all { it }) {
				timerComponent.onEnable(this@TrackerService, this@TrackerService)
			} else {
				stopSelf()
				Reporter.report("Missing permissions for ${timerComponent.javaClass}")
			}
		}

		return START_NOT_STICKY
	}

	private fun initializeTimer() {
		val preferences = Preferences.getPref(this)
		val useLocation = preferences.getBooleanRes(
				R.string.settings_location_enabled_key,
				R.string.settings_location_enabled_default
		)

		timerComponent = if (useLocation) {
			FusedLocationTrackerTimer()
		} else {
			TimeTrackerTimer()
		}
	}

	override fun onUpdate(tempData: MutableCollectionTempData): Job = launch {
		componentMutex.lock()

		if (!isServiceRunning.value) {
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
		preComponentList.forEach { it.onDisable(context) }
		postComponentList.forEach { it.onDisable(context) }

		//Can be null if TrackerServices is immediately stopped after start
		if (sessionComponent != null) {
			val sessionEndIntent = Intent(TrackerSession.RECEIVER_SESSION_ENDED).apply {
				putExtra(TrackerSession.RECEIVER_SESSION_ID, session.id)
				`package` = this@TrackerService.packageName
			}
			sendBroadcast(sessionEndIntent, "com.adsamcik.tracker.permission.TRACKER")
		}
	}

	private fun onDestroyServiceMetaData() {
		isServiceRunningMutable.value = false
		sessionInfoMutable.value = null
	}


	override fun onDestroy() {
		super.onDestroy()
		stopForeground(true)
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
				timerComponent.onDisable(context)

				ActivityWatcherService.poke(context, trackerRunning = false)

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
					Shortcuts.updateShortcut(
							context,
							ShortcutData(
									Shortcuts.TRACKING_ID,
									R.string.shortcut_start_tracking,
									R.string.shortcut_start_tracking_long,
									R.drawable.ic_play_circle_filled_black_24dp,
									Shortcuts.ShortcutAction.START_COLLECTION
							)
					)
				}

				onDestroyComponents(context)
			}
		}
	}

	companion object {
		private val isServiceRunningMutable: NonNullLiveMutableData<Boolean> = NonNullLiveMutableData(
				false
		)

		/**
		 * LiveData containing information about whether the service is currently running
		 */
		val isServiceRunning: NonNullLiveData<Boolean> get() = isServiceRunningMutable

		private val lastCollectionDataMutable: MutableLiveData<CollectionDataEcho> = MutableLiveData()

		/**
		 * Collection data from last collection
		 */
		val lastCollectionData: LiveData<CollectionDataEcho> get() = lastCollectionDataMutable


		private val sessionInfoMutable: MutableLiveData<TrackerSessionInfo> = MutableLiveData()

		/**
		 * Current information about session.
		 * Null when no session is active.
		 */
		val sessionInfo: LiveData<TrackerSessionInfo> get() = sessionInfoMutable


		const val ARG_IS_USER_INITIATED = "userInitiated"
		private const val DEFAULT_IS_USER_INITIATED = false

		const val ARG_TRACKER_DATA = "trackerData"
		private const val MESSAGE_TRACKER_UPDATE = 115
	}
}

