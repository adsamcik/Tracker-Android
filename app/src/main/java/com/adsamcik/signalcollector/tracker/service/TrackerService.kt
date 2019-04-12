package com.adsamcik.signalcollector.tracker.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import android.location.Location
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.activity.service.ActivityWatcherService
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.worker.ChallengeWorker
import com.adsamcik.signalcollector.misc.NonNullLiveMutableData
import com.adsamcik.signalcollector.misc.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.misc.shortcut.Shortcuts
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.component.data.DataTrackerComponent
import com.adsamcik.signalcollector.tracker.component.post.NotificationComponent
import com.adsamcik.signalcollector.tracker.component.post.PostTrackerComponent
import com.adsamcik.signalcollector.tracker.component.pre.PreTrackerComponent
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.TrackerSession
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.crashlytics.android.Crashlytics
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

class TrackerService : LifecycleService(), SensorEventListener {
	private lateinit var notificationManager: NotificationManager
	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock


	private val telephonyManager: TelephonyManager by lazy { getSystemServiceTyped<TelephonyManager>(Context.TELEPHONY_SERVICE) }

	private var minDistanceInMeters = -1f

	/**
	 * True if previous collection was mocked
	 */
	private var prevMocked = false

	/**
	 * Previous location of collection
	 */
	private var previousLocation: Location? = null

	private lateinit var locationCallback: LocationCallback

	private val session = TrackerSession(System.currentTimeMillis())

	private var lastStepCount = -1

	private lateinit var keyCellEnabled: String
	private var defaultCellEnabled: Boolean = false

	private lateinit var keyLocationEnabled: String
	private var defaultLocationEnabled: Boolean = false


	private lateinit var notificationComponent: NotificationComponent

	private val preComponentList = mutableListOf<PreTrackerComponent>()
	private val dataComponentList = mutableListOf<DataTrackerComponent>()
	private val postComponentList = mutableListOf<PostTrackerComponent>()


	/**
	 * Collects data from necessary places and sensors and creates new MutableCollectionData instance
	 */
	private fun updateData(locationResult: LocationResult) {
		val previousLocation = previousLocation
		val location = locationResult.lastLocation
		val distance = if (previousLocation == null) 0f else location.distanceTo(previousLocation)
		if (location.isFromMockProvider) {
			prevMocked = true
			this.previousLocation = null
			return
		} else if (prevMocked && previousLocation != null) {
			prevMocked = false
			if (distance < minDistanceInMeters)
				return
		}

		wakeLock.acquire(Constants.MINUTE_IN_MILLISECONDS)

		val preferences = Preferences.getPref(this)

		val activityInfo = ActivityService.lastActivity

		//if we don't know the accuracy the location is worthless
		if (!preComponentList.all { it.onNewLocation(locationResult, previousLocation, distance, activityInfo) }) {
			wakeLock.release()
			return
		}

		val rawData = MutableCollectionData(location.time)

		dataComponentList.forEach {
			it.onLocationUpdated(locationResult, previousLocation, distance,, rawData)
		}

		if (preferences.getBoolean(keyWifiEnabled, defaultWifiEnabled)) {
			setWifi(locationResult, location, previousLocation, distance, rawData)
		}

		if (preferences.getBoolean(keyCellEnabled, defaultCellEnabled) && !Assist.isAirplaneModeEnabled(this)) {
			rawData.addCell(telephonyManager)
		}

		if (preferences.getBoolean(keyLocationEnabled, defaultLocationEnabled))
			rawData.setLocation(location).setActivity(activityInfo)


		notificationComponent.onNewData(this, location, rawData)

		saveData(rawData)
		trackerEcho.postValue(Pair(session, rawData))

		if (isBackgroundActivated && powerManager.isPowerSaveMode)
			stopSelf()

		this.previousLocation = location

		wakeLock.release()
	}


	override fun onCreate() {
		super.onCreate()

		service = WeakReference(this)
		val sp = Preferences.getPref(this)
		val resources = resources

		val packageManager = packageManager

		//Get managers
		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signals:TrackerWakeLock")

		//Database initialization
		GlobalScope.launch {
			val sessionDao = AppDatabase.getDatabase(applicationContext).sessionDao()
			session.id = sessionDao.insert(session)
		}

		//Enable location update
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
			minUpdateDelayInSeconds = sp.getIntRes(resources.getString(R.string.settings_tracking_min_time_key), resources.getInteger(R.integer.settings_tracking_min_time_default))
			minDistanceInMeters = sp.getIntRes(resources.getString(R.string.settings_tracking_min_distance_key), resources.getInteger(R.integer.settings_tracking_min_distance_default)).toFloat()

			val client = LocationServices.getFusedLocationProviderClient(this)
			val request = LocationRequest.create().apply {
				interval = minUpdateDelayInSeconds.toLong() * Constants.SECOND_IN_MILLISECONDS
				fastestInterval = minUpdateDelayInSeconds.toLong()
				smallestDisplacement = minDistanceInMeters
				priority = PRIORITY_HIGH_ACCURACY
			}

			locationCallback = object : LocationCallback() {
				override fun onLocationResult(result: LocationResult) {
					updateData(result)
				}

				override fun onLocationAvailability(availability: LocationAvailability) {
					if (!availability.isLocationAvailable)
						notificationManager.notify(NOTIFICATION_ID_SERVICE, generateNotification())
				}

			}

			client.requestLocationUpdates(request, locationCallback, Looper.myLooper())
		} else {
			Crashlytics.logException(Exception("Tracker does not have sufficient permissions"))
			stopSelf()
			return
		}


		if (packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
			val sensorManager = getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
			val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
			sensorManager.registerListener(this, stepCounter, SENSOR_DELAY_NORMAL)
		}


		//Shortcut setup
		if (android.os.Build.VERSION.SDK_INT >= 25) {
			Shortcuts.initializeShortcuts(this)
			Shortcuts.updateShortcut(this,
					Shortcuts.TRACKING_ID,
					getString(R.string.shortcut_stop_tracking),
					getString(R.string.shortcut_stop_tracking_long),
					R.drawable.ic_pause_circle_filled_black_24dp,
					Shortcuts.ShortcutType.STOP_COLLECTION)
		}

		//Challenge cancel
		WorkManager.getInstance().cancelAllWorkByTag("ChallengeQueue")
	}

	override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

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
		service = null
		isServiceRunning.value = false

		ActivityWatcherService.poke(this, trackerRunning = false)
		ActivityService.removeActivityRequest(this, this::class)

		LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(locationCallback)

		if (android.os.Build.VERSION.SDK_INT >= 25) {
			Shortcuts.initializeShortcuts(this)
			Shortcuts.updateShortcut(this, Shortcuts.TRACKING_ID, getString(R.string.shortcut_start_tracking), getString(R.string.shortcut_start_tracking_long), R.drawable.ic_play_circle_filled_black_24dp, Shortcuts.ShortcutType.START_COLLECTION)
		}

		val sensorManager = getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
		sensorManager.unregisterListener(this)

		//Save data to database
		session.end = System.currentTimeMillis()

		GlobalScope.launch {
			val sessionDao = AppDatabase.getDatabase(applicationContext).sessionDao()

			if (session.collections <= 1)
				sessionDao.delete(session)
			else
				sessionDao.update(session)

			ChallengeDatabase.getDatabase(applicationContext).sessionDao.insert(session)
		}

		//Challenges

		val workManager = WorkManager.getInstance()

		val resources = resources
		val challengeEnabledKey = resources.getString(R.string.settings_game_challenge_enable_key)
		val challengeEnabledDefault = resources.getString(R.string.settings_game_challenge_enable_default).toBoolean()

		if (Preferences.getPref(this).getBoolean(challengeEnabledKey, challengeEnabledDefault)) {
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

	override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {

	}

	override fun onSensorChanged(event: SensorEvent) {
		val sensor = event.sensor
		if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
			val stepCount = event.values.first().toInt()
			if (lastStepCount >= 0) {
				//in case sensor would overflow and reset to 0 at some point
				if (lastStepCount > stepCount)
					session.steps += stepCount
				else
					session.steps += stepCount - lastStepCount
			}

			lastStepCount = stepCount
		}
	}

	companion object {
		//Constants
		private const val TAG = "SignalsTracker"

		/**
		 * LiveData containing information about whether the service is currently running
		 */
		val isServiceRunning: NonNullLiveMutableData<Boolean> = NonNullLiveMutableData(false)

		/**
		 * MutableCollectionData from previous collection
		 */
		//todo look at how to improve this
		var trackerEcho: MutableLiveData<Pair<TrackerSession, MutableCollectionData>> = MutableLiveData()

		/**
		 * Extra information about distance for tracker
		 */
		var distanceToWifi: Float = 0f

		/**
		 * Weak reference to service for AutoLock and check if service is running
		 */
		private var service: WeakReference<TrackerService>? = null

		/**
		 * Checks if tracker was activated in background
		 *
		 * @return true if activated by the app
		 */
		var isBackgroundActivated: Boolean = false
			private set
	}
}