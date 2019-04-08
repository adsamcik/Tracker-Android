package com.adsamcik.signalcollector.tracker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.GroupedActivity
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.activity.service.ActivityWatcherService
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.app.activity.LaunchActivity
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseCellData
import com.adsamcik.signalcollector.database.data.DatabaseWifiData
import com.adsamcik.signalcollector.misc.NonNullLiveMutableData
import com.adsamcik.signalcollector.misc.extension.LocationExtensions
import com.adsamcik.signalcollector.misc.extension.formatDistance
import com.adsamcik.signalcollector.misc.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.misc.shortcut.Shortcuts
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.data.RawData
import com.adsamcik.signalcollector.tracker.data.TrackerSession
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.receiver.TrackerNotificationReceiver
import com.crashlytics.android.Crashlytics
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.max

class TrackerService : LifecycleService(), SensorEventListener {
	private var wifiScanTime: Long = 0
	private var wifiScanData: Array<ScanResult>? = null
	private var wifiReceiver: WifiReceiver? = null
	private var wifiLastScanRequest: Long = 0
	private var wifiScanRequested: Boolean = false

	private lateinit var notificationManager: NotificationManager
	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock


	private val telephonyManager: TelephonyManager by lazy { getSystemServiceTyped<TelephonyManager>(Context.TELEPHONY_SERVICE) }
	private val wifiManager: WifiManager by lazy { getSystemServiceTyped<WifiManager>(Context.WIFI_SERVICE) }

	private var minUpdateDelayInSeconds = -1
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

	private lateinit var keyWifiEnabled: String
	private var defaultWifiEnabled: Boolean = false

	private lateinit var keyCellEnabled: String
	private var defaultCellEnabled: Boolean = false

	private lateinit var keyLocationEnabled: String
	private var defaultLocationEnabled: Boolean = false

	private lateinit var keyRequiredLocationAccuracy: String
	private var defaultRequiredLocationAccuracy: Int = 0


	/**
	 * Collects data from necessary places and sensors and creates new RawData instance
	 */
	private fun updateData(locationResult: LocationResult) {
		val previousLocation = previousLocation
		val location = locationResult.lastLocation
		val distance = if (previousLocation == null) 0f else location.distanceTo(previousLocation)
		if (location.isFromMockProvider) {
			prevMocked = true
			return
		} else if (prevMocked && previousLocation != null) {
			prevMocked = false
			if (distance < minDistanceInMeters)
				return
		}

		wakeLock.acquire(Constants.MINUTE_IN_MILLISECONDS)

		val preferences = Preferences.getPref(this)

		//if we don't know the accuracy the location is worthless
		if (!location.hasAccuracy() || location.accuracy > preferences.getInt(keyRequiredLocationAccuracy, defaultRequiredLocationAccuracy)) {
			wakeLock.release()
			return
		}

		val activityInfo = ActivityService.lastActivity

		session.apply {
			distanceInM += distance
			collections++

			if (previousLocation != null &&
					(location.time - previousLocation.time < max(Constants.SECOND_IN_MILLISECONDS * 20, minUpdateDelayInSeconds * 2 * Constants.SECOND_IN_MILLISECONDS) ||
							distance <= minDistanceInMeters * 2f)) {
				when (activityInfo.groupedActivity) {
					GroupedActivity.ON_FOOT -> distanceOnFootInM += distance
					GroupedActivity.IN_VEHICLE -> distanceInVehicleInM += distance
					else -> {
					}
				}
			}
		}

		val rawData = RawData(location.time)

		if (preferences.getBoolean(keyWifiEnabled, defaultWifiEnabled)) {
			setWifi(locationResult, location, previousLocation, distance, rawData)
		}

		if (preferences.getBoolean(keyCellEnabled, defaultCellEnabled) && !Assist.isAirplaneModeEnabled(this)) {
			rawData.addCell(telephonyManager)
		}

		if (preferences.getBoolean(keyLocationEnabled, defaultLocationEnabled))
			rawData.setLocation(location).setActivity(activityInfo)


		notificationManager.notify(NOTIFICATION_ID_SERVICE, generateNotification(location, rawData))

		saveData(rawData)
		trackerEcho.postValue(Pair(session, rawData))

		if (isBackgroundActivated && powerManager.isPowerSaveMode)
			stopSelf()

		this.previousLocation = location

		wakeLock.release()
	}

	private fun setWifi(locationResult: LocationResult, location: Location, previousLocation: Location?, distance: Float, rawData: RawData) {
		synchronized(wifiScanTime) {
			if (wifiScanData != null) {
				val locations = locationResult.locations
				if (locations.size == 2) {
					val nearestLocation = locations.sortedBy { abs(wifiScanTime - it.time) }.take(2)
					val firstIndex = if (nearestLocation[0].time < nearestLocation[1].time) 0 else 1

					val first = nearestLocation[firstIndex]
					val second = nearestLocation[(firstIndex + 1).rem(2)]
					setWifi(first, second, first.distanceTo(second), rawData)
				} else if (previousLocation != null) {
					setWifi(previousLocation, location, distance, rawData)
				}

				wifiScanData = null
				wifiScanTime = -1L
			}

			val now = System.currentTimeMillis()
			if (Build.VERSION.SDK_INT >= 28) {
				if (now - wifiLastScanRequest > Constants.SECOND_IN_MILLISECONDS * 20 && (wifiScanTime == -1L || now - wifiScanTime > Constants.SECOND_IN_MILLISECONDS * 15)) {
					wifiScanRequested = wifiManager.startScan()
					wifiLastScanRequest = now
				}
			} else if (!wifiScanRequested) {
				wifiManager.startScan()
				wifiLastScanRequest = now
			}
		}
	}

	private fun setWifi(firstLocation: Location, secondLocation: Location, distanceBetweenFirstAndSecond: Float, rawData: RawData) {
		val timeDelta = (wifiScanTime - firstLocation.time).toDouble() / (secondLocation.time - firstLocation.time).toDouble()
		val wifiDistance = distanceBetweenFirstAndSecond * timeDelta
		if (wifiDistance <= UPDATE_MAX_DISTANCE_TO_WIFI) {
			val interpolatedLocation = LocationExtensions.interpolateLocation(firstLocation, secondLocation, timeDelta)
			rawData.setWifi(interpolatedLocation, wifiScanTime, wifiScanData)
			distanceToWifi = distanceBetweenFirstAndSecond
		}
	}

	private fun saveData(data: RawData) {
		GlobalScope.launch {
			val appContext = applicationContext
			val database = AppDatabase.getAppDatabase(appContext)

			val location = data.location
			var locationId: Long? = null
			val activity = data.activity
			if (location != null && activity != null)
				locationId = database.locationDao().insert(location.toDatabase(activity))

			val cellData = data.cell
			val cellDao = database.cellDao()
			cellData?.registeredCells?.map { DatabaseCellData(locationId, data.time, data.time, it) }?.let { cellDao.upsert(it) }

			val wifiData = data.wifi
			if (wifiData != null) {
				val wifiDao = database.wifiDao()

				val estimatedWifiLocation = com.adsamcik.signalcollector.tracker.data.Location(wifiData.location)
				wifiData.inRange.map { DatabaseWifiData(estimatedWifiLocation, it) }.let { wifiDao.upsert(it) }
			}
		}
	}

	/**
	 * Generates tracking notification
	 */
	private fun generateNotification(location: Location? = null, d: RawData? = null): Notification {
		val intent = Intent(this, LaunchActivity::class.java)

		val builder = NotificationCompat.Builder(this, getString(R.string.channel_track_id))
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setSmallIcon(R.drawable.ic_signals)  // the done icon
				.setTicker(getString(R.string.notification_tracker_active_ticker))  // the done text
				.setWhen(System.currentTimeMillis())  // the time stamp
				.setColor(ContextCompat.getColor(this, R.color.color_accent))
				.setContentIntent(TaskStackBuilder.create(this).run {
					addNextIntentWithParentStack(intent)
					getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
				})

		val stopIntent = Intent(this, TrackerNotificationReceiver::class.java)
		stopIntent.putExtra(TrackerNotificationReceiver.ACTION_STRING, if (isBackgroundActivated) TrackerNotificationReceiver.LOCK_RECHARGE_ACTION else TrackerNotificationReceiver.STOP_TRACKING_ACTION)
		val stop = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT)
		if (isBackgroundActivated) {
			builder.addAction(R.drawable.ic_battery_alert_black_24dp, getString(R.string.notification_stop_til_recharge), stop)

			val stopForMinutes = 30
			val stopForMinutesIntent = Intent(this, TrackerNotificationReceiver::class.java)
			stopForMinutesIntent.putExtra(TrackerNotificationReceiver.ACTION_STRING, TrackerNotificationReceiver.STOP_MINUTES_EXTRA)
			stopForMinutesIntent.putExtra(TrackerNotificationReceiver.STOP_MINUTES_EXTRA, stopForMinutes)
			val stopForMinutesAction = PendingIntent.getBroadcast(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT)
			builder.addAction(R.drawable.ic_stop_black_24dp, getString(R.string.notification_stop_for_minutes, stopForMinutes), stopForMinutesAction)
		} else
			builder.addAction(R.drawable.ic_pause_circle_filled_black_24dp, getString(R.string.notification_stop), stop)

		if (location == null)
			builder.setContentTitle(getString(R.string.notification_looking_for_gps))
		else {
			builder.setContentTitle(getString(R.string.notification_tracking_active))
			builder.setContentText(buildNotificationText(location, d!!))
		}

		return builder.build()
	}

	/**
	 * Builds text for tracking notification
	 */
	private fun buildNotificationText(location: Location, d: RawData): String {
		val resources = resources
		val sb = StringBuilder()
		val df = DecimalFormat.getNumberInstance()
		df.roundingMode = RoundingMode.HALF_UP

		val lengthSystem = Preferences.getLengthSystem(this)
		val delimiter = ", "

		sb.append(resources.getString(R.string.notification_location,
				Location.convert(location.latitude, Location.FORMAT_DEGREES),
				Location.convert(location.longitude, Location.FORMAT_DEGREES)
		))
				.append(delimiter)
				.append(resources.getString(R.string.info_altitude, resources.formatDistance(location.altitude, 2, lengthSystem)))
				.append(delimiter)

		val activity = d.activity
		if (activity != null) {
			sb.append(resources.getString(R.string.notification_activity,
					activity.getGroupedActivityName(this))).append(delimiter)
		}

		val wifi = d.wifi
		if (wifi != null) {
			sb.append(resources.getString(R.string.notification_wifi, wifi.inRange.size)).append(delimiter)
		}

		val cell = d.cell
		if (cell != null && cell.registeredCells.isNotEmpty()) {
			val mainCell = cell.registeredCells[0]
			sb
					.append(resources.getString(R.string.notification_cell_current, mainCell.type.name, mainCell.dbm))
					.append(' ')
					.append(resources.getQuantityString(R.plurals.notification_cell_count, cell.totalCount, cell.totalCount))
					.append(delimiter)
		}

		sb.setLength(sb.length - 2)

		return sb.toString()
	}


	override fun onCreate() {
		super.onCreate()

		service = WeakReference(this)
		val sp = Preferences.getPref(this)
		val resources = resources

		val packageManager = packageManager

		//Initialize keys and defaults
		keyCellEnabled = resources.getString(R.string.settings_cell_enabled_key)
		keyLocationEnabled = resources.getString(R.string.settings_location_enabled_key)
		keyWifiEnabled = resources.getString(R.string.settings_wifi_enabled_key)
		keyRequiredLocationAccuracy = resources.getString(R.string.settings_tracking_required_accuracy_key)

		defaultCellEnabled = resources.getString(R.string.settings_cell_enabled_default).toBoolean()
		defaultLocationEnabled = resources.getString(R.string.settings_location_enabled_default).toBoolean()
		defaultWifiEnabled = resources.getString(R.string.settings_wifi_enabled_default).toBoolean()
		defaultRequiredLocationAccuracy = resources.getInteger(R.integer.settings_tracking_required_accuracy_default)

		//Get managers
		notificationManager = getSystemServiceTyped(Context.NOTIFICATION_SERVICE)
		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signals:TrackerWakeLock")

		//Database initialization
		GlobalScope.launch {
			val sessionDao = AppDatabase.getAppDatabase(applicationContext).sessionDao()
			session.id = sessionDao.insert(session)
		}

		//Enable location update
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
			minUpdateDelayInSeconds = sp.getInt(resources.getString(R.string.settings_tracking_min_time_key), resources.getInteger(R.integer.settings_tracking_min_time_default))
			minDistanceInMeters = sp.getInt(resources.getString(R.string.settings_tracking_min_distance_key), resources.getInteger(R.integer.settings_tracking_min_distance_default)).toFloat()

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

		//Wifi tracking setup
		if (sp.getBoolean(keyWifiEnabled, defaultWifiEnabled)) {
			//Let's not waste precious scan requests
			if (Build.VERSION.SDK_INT < 28) {
				wifiScanRequested = wifiManager.startScan()
				wifiLastScanRequest = System.currentTimeMillis()
			}
			wifiReceiver = WifiReceiver()
			registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
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
	}

	override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		isBackgroundActivated = intent.getBooleanExtra("backTrack", false)
		startForeground(NOTIFICATION_ID_SERVICE, generateNotification())

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

		if (wifiReceiver != null)
			unregisterReceiver(wifiReceiver)

		if (android.os.Build.VERSION.SDK_INT >= 25) {
			Shortcuts.initializeShortcuts(this)
			Shortcuts.updateShortcut(this, Shortcuts.TRACKING_ID, getString(R.string.shortcut_start_tracking), getString(R.string.shortcut_start_tracking_long), R.drawable.ic_play_circle_filled_black_24dp, Shortcuts.ShortcutType.START_COLLECTION)
		}

		val sensorManager = getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
		sensorManager.unregisterListener(this)


		//Save data to database
		session.end = System.currentTimeMillis()

		GlobalScope.launch {
			val sessionDao = AppDatabase.getAppDatabase(applicationContext).sessionDao()

			if (session.collections <= 1)
				sessionDao.delete(session)
			else
				sessionDao.update(session)
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

	private inner class WifiReceiver : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			synchronized(wifiScanTime) {
				wifiScanRequested = false
				wifiScanTime = System.currentTimeMillis()
				val result = wifiManager.scanResults
				wifiScanData = result.toTypedArray()
			}
		}
	}

	companion object {
		//Constants
		private const val TAG = "SignalsTracker"
		private const val NOTIFICATION_ID_SERVICE = -7643

		private const val UPDATE_MAX_DISTANCE_TO_WIFI = 40

		/**
		 * LiveData containing information about whether the service is currently running
		 */
		val isServiceRunning: NonNullLiveMutableData<Boolean> = NonNullLiveMutableData(false)

		/**
		 * RawData from previous collection
		 */
		//todo look at how to improve this
		var trackerEcho: MutableLiveData<Pair<TrackerSession, RawData>> = MutableLiveData()

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