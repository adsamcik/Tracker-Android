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
import androidx.lifecycle.MutableLiveData
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.activity.service.ActivityWatcherService
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.worker.ChallengeWorker
import com.adsamcik.signalcollector.misc.NonNullLiveMutableData
import com.adsamcik.signalcollector.misc.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.misc.shortcut.Shortcuts
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.component.DataComponentManager
import com.adsamcik.signalcollector.tracker.component.post.NotificationComponent
import com.adsamcik.signalcollector.tracker.component.post.PostTrackerComponent
import com.adsamcik.signalcollector.tracker.component.post.TrackerDataComponent
import com.adsamcik.signalcollector.tracker.component.pre.PreLocationTrackerComponent
import com.adsamcik.signalcollector.tracker.component.pre.PreTrackerComponent
import com.adsamcik.signalcollector.tracker.data.collection.CollectionDataEcho
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.crashlytics.android.Crashlytics
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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

		wakeLock.acquire(Constants.MINUTE_IN_MILLISECONDS)

		val activityInfo = ActivityService.lastActivity

		//if we don't know the accuracy the location is worthless
		if (!preComponentList.all { it.onNewLocation(locationResult, previousLocation, distance) }) {
			wakeLock.release()
			return
		}

		val collectionData = MutableCollectionData(location.time)

		dataComponentManager.onLocationUpdated(locationResult, previousLocation, distance, activityInfo, collectionData)

		postComponentList.forEach {
			it.onNewData(this, dataComponentManager.session, location, collectionData)
		}

		if (isBackgroundActivated && powerManager.isPowerSaveMode)
			stopSelf()

		this.previousLocation = location

		trackerEcho.postValue(CollectionDataEcho(location, collectionData, dataComponentManager.session))

		wakeLock.release()
	}


	override fun onCreate() {
		super.onCreate()

		//Get managers
		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signals:TrackerWakeLock")

		preComponentList.apply {
			add(PreLocationTrackerComponent())
		}.forEach { it.onEnable(this) }

		dataComponentManager = DataComponentManager(this).apply { onEnable() }

		postComponentList.apply {
			NotificationComponent().also {
				notificationComponent = it
				add(it)
			}
			add(TrackerDataComponent())
		}.forEach { it.onEnable(this) }

		//Shortcut setup
		if (android.os.Build.VERSION.SDK_INT >= 25) {
			Shortcuts.updateShortcut(this,
					Shortcuts.TRACKING_ID,
					R.string.shortcut_stop_tracking,
					R.string.shortcut_stop_tracking_long,
					R.drawable.ic_pause_circle_filled_black_24dp,
					Shortcuts.ShortcutAction.STOP_COLLECTION)
		}

		//Challenge cancel
		WorkManager.getInstance().cancelAllWorkByTag("ChallengeQueue")
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
				interval = minUpdateDelayInSeconds * Constants.SECOND_IN_MILLISECONDS
				fastestInterval = minUpdateDelayInSeconds * Constants.SECOND_IN_MILLISECONDS
				smallestDisplacement = minDistanceInMeters.toFloat()
				priority = PRIORITY_HIGH_ACCURACY
			}

			locationCallback = object : LocationCallback() {
				override fun onLocationResult(result: LocationResult) {
					updateData(result)
				}

				override fun onLocationAvailability(availability: LocationAvailability) {
					if (!availability.isLocationAvailable)
						notificationComponent.onLocationDataChange(this@TrackerService, null)
				}

			}

			client.requestLocationUpdates(request, locationCallback, Looper.myLooper())
		} else {
			Crashlytics.logException(Exception("Tracker does not have sufficient permissions"))
			stopSelf()
			return Service.START_NOT_STICKY
		}

		isBackgroundActivated = intent.getBooleanExtra("backTrack", false)

		val (notificationId, notification) = notificationComponent.foregroundServiceNotification(this)
		startForeground(notificationId, notification)

		if (isBackgroundActivated) {
			ActivityService.requestAutoTracking(this, this::class, minUpdateDelayInSeconds)
			TrackerLocker.isLocked.observe(this) {
				if (it)
					stopSelf()
			}
		} else
			ActivityService.requestActivity(this, this::class, minUpdateDelayInSeconds)

		ActivityWatcherService.poke(this, trackerRunning = true)

		isServiceRunning.value = true

		return START_NOT_STICKY
	}


	override fun onDestroy() {
		super.onDestroy()

		stopForeground(true)
		isServiceRunning.value = false

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

		//Challenges

		GlobalScope.launch {
			ChallengeDatabase.getDatabase(this@TrackerService).sessionDao.insert(dataComponentManager.session)
		}

		val workManager = WorkManager.getInstance()

		if (Preferences.getPref(this).getBooleanRes(R.string.settings_game_challenge_enable_key, R.string.settings_game_challenge_enable_default)) {
			val workRequest = OneTimeWorkRequestBuilder<ChallengeWorker>()
					.setInitialDelay(1, TimeUnit.HOURS)
					.addTag("ChallengeQueue")
					.setConstraints(Constraints.Builder()
							.setRequiresBatteryNotLow(true)
							.build()
					).build()
			workManager.enqueue(workRequest)
		}
	}


	companion object {
		/**
		 * LiveData containing information about whether the service is currently running
		 */
		val isServiceRunning: NonNullLiveMutableData<Boolean> = NonNullLiveMutableData(false)

		/**
		 * MutableCollectionData from previous collection
		 */
		var trackerEcho: MutableLiveData<CollectionDataEcho> = MutableLiveData()

		/**
		 * Checks if tracker was activated in background
		 *
		 * @return true if activated by the app
		 */
		var isBackgroundActivated: Boolean = false
			private set
	}
}