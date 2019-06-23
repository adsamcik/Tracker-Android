package com.adsamcik.signalcollector.tracker.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.activity.service.ActivityWatcherService
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.exception.PermissionException
import com.adsamcik.signalcollector.common.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.common.misc.NonNullLiveData
import com.adsamcik.signalcollector.common.misc.NonNullLiveMutableData
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.shortcut.Shortcuts
import com.adsamcik.signalcollector.tracker.component.DataComponentManager
import com.adsamcik.signalcollector.tracker.component.post.NotificationComponent
import com.adsamcik.signalcollector.tracker.component.post.PostTrackerComponent
import com.adsamcik.signalcollector.tracker.component.post.TrackerDataComponent
import com.adsamcik.signalcollector.tracker.component.pre.PreLocationTrackerComponent
import com.adsamcik.signalcollector.tracker.component.pre.PreTrackerComponent
import com.adsamcik.signalcollector.tracker.data.collection.CollectionDataEcho
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.session.TrackerSessionInfo
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY

class TrackerService : LifecycleService() {
	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock

	/**
	 * Previous location of collection
	 */
	private var previousLocation: Location? = null

	private lateinit var locationCallback: LocationCallback

	private lateinit var notificationComponent: NotificationComponent

	private val preComponentList = mutableListOf<PreTrackerComponent>()
	private lateinit var dataComponentManager: DataComponentManager
	private val postComponentList = mutableListOf<PostTrackerComponent>()

	//Kept here and used internally in case something went wrong and service was launched again with different info
	private lateinit var sessionInfo: TrackerSessionInfo


	/**
	 * Collects data from necessary places and sensors and creates new MutableCollectionData instance
	 */
	private fun updateData(locationResult: LocationResult) {
		val previousLocation = previousLocation
		val location = locationResult.lastLocation
		val distance = if (previousLocation == null) 0f else location.distanceTo(previousLocation)
		if (location.isFromMockProvider) {
			this.previousLocation = null
			return
		}
		//location.time is bugged... basically they subtract timezone from UTC.
		//I guess they get somewhere time in UTC and think it is timezone so they subtract timezone time
		//Awesome right?
		/*else if (location.time < dataComponentManager.session.start) {
			return
		}*/

		wakeLock.acquire(Time.MINUTE_IN_MILLISECONDS)

		val activityInfo = ActivityService.lastActivity

		//if we don't know the accuracy the location is worthless
		if (!preComponentList.all { it.onNewLocation(locationResult, previousLocation, distance) }) {
			wakeLock.release()
			return
		}

		val collectionData = MutableCollectionData(Time.nowMillis)

		dataComponentManager.onLocationUpdated(locationResult, previousLocation, distance, activityInfo, collectionData)

		postComponentList.forEach {
			it.onNewData(this, dataComponentManager.session, location, collectionData)
		}

		if (!sessionInfo.isInitiatedByUser && powerManager.isPowerSaveMode) stopSelf()

		this.previousLocation = location

		lastCollectionDataMutable.postValue(CollectionDataEcho(location, collectionData, dataComponentManager.session))

		wakeLock.release()
	}


	override fun onCreate() {
		super.onCreate()
		Reporter.initialize(this)

		//Get managers
		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signals:TrackerWakeLock")


		//Shortcut setup
		if (android.os.Build.VERSION.SDK_INT >= 25) {
			Shortcuts.updateShortcut(this,
					Shortcuts.TRACKING_ID,
					R.string.shortcut_stop_tracking,
					R.string.shortcut_stop_tracking_long,
					R.drawable.ic_pause_circle_filled_black_24dp,
					Shortcuts.ShortcutAction.STOP_COLLECTION)
		}
	}

	private fun initializeComponents(isSessionUserInitiated: Boolean) {
		preComponentList.apply {
			add(PreLocationTrackerComponent())
		}.forEach { it.onEnable(this) }

		dataComponentManager = DataComponentManager(this).apply { onEnable(isSessionUserInitiated) }

		postComponentList.apply {
			NotificationComponent().also {
				notificationComponent = it
				add(it)
			}
			add(TrackerDataComponent())
		}.forEach { it.onEnable(this) }
	}

	override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		val preferences = Preferences.getPref(this)
		val minUpdateDelayInSeconds = preferences.getIntRes(R.string.settings_tracking_min_time_key, R.integer.settings_tracking_min_time_default)
		val minDistanceInMeters = preferences.getIntRes(R.string.settings_tracking_min_distance_key, R.integer.settings_tracking_min_distance_default)

		//Enable location update
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
			val client = LocationServices.getFusedLocationProviderClient(this)
			val request = LocationRequest.create().apply {
				interval = minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS
				fastestInterval = minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS
				smallestDisplacement = minDistanceInMeters.toFloat()
				priority = PRIORITY_HIGH_ACCURACY
			}

			locationCallback = object : LocationCallback() {
				override fun onLocationResult(result: LocationResult) {
					updateData(result)
				}

				override fun onLocationAvailability(availability: LocationAvailability) {
					if (!availability.isLocationAvailable) {
						notificationComponent.onLocationDataChange(this@TrackerService, null)
					}
				}

			}

			client.requestLocationUpdates(request, locationCallback, Looper.myLooper())
		} else {
			Reporter.report(PermissionException("Tracker does not have sufficient permissions"))
			stopSelf()
			return Service.START_NOT_STICKY
		}

		val isUserInitiated = intent.getBooleanExtra(ARG_IS_USER_INITIATED, false)
		initializeComponents(isSessionUserInitiated = isUserInitiated)

		val (notificationId, notification) = notificationComponent.foregroundServiceNotification(this)
		startForeground(notificationId, notification)

		if (!isUserInitiated) {
			ActivityService.requestAutoTracking(this, this::class, minUpdateDelayInSeconds)
			TrackerLocker.isLocked.observe(this) {
				if (it) stopSelf()
			}
		} else {
			ActivityService.requestActivity(this, this::class, minUpdateDelayInSeconds)
		}

		ActivityWatcherService.poke(this, trackerRunning = true)

		isServiceRunningMutable.value = true

		sessionInfo = TrackerSessionInfo(isUserInitiated)
		sessionInfoMutable.postValue(sessionInfo)

		val sessionStartIntent = Intent(TrackerSession.RECEIVER_SESSION_STARTED)
		LocalBroadcastManager.getInstance(this).sendBroadcast(sessionStartIntent)
		return START_NOT_STICKY
	}


	override fun onDestroy() {
		super.onDestroy()

		stopForeground(true)
		isServiceRunningMutable.value = false

		ActivityWatcherService.poke(this, trackerRunning = false)
		ActivityService.removeActivityRequest(this, this::class)

		LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(locationCallback)

		if (android.os.Build.VERSION.SDK_INT >= 25) {
			Shortcuts.updateShortcut(this,
					Shortcuts.TRACKING_ID,
					R.string.shortcut_start_tracking,
					R.string.shortcut_start_tracking_long,
					R.drawable.ic_play_circle_filled_black_24dp,
					Shortcuts.ShortcutAction.START_COLLECTION)
		}

		dataComponentManager.onDisable()
		preComponentList.forEach { it.onDisable(this) }
		postComponentList.forEach { it.onEnable(this) }

		val sessionEndIntent = Intent(TrackerSession.RECEIVER_SESSION_ENDED).apply {
			putExtra(TrackerSession.RECEIVER_SESSION_ID, dataComponentManager.session.id)
		}
		LocalBroadcastManager.getInstance(this).sendBroadcast(sessionEndIntent)
	}


	companion object {
		private val isServiceRunningMutable: NonNullLiveMutableData<Boolean> = NonNullLiveMutableData(false)

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
	}
}