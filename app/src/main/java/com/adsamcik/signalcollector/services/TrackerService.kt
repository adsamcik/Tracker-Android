package com.adsamcik.signalcollector.services

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
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.LaunchActivity
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.data.TrackingSession
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseCellData
import com.adsamcik.signalcollector.database.data.DatabaseWifiData
import com.adsamcik.signalcollector.extensions.getSystemServiceTyped
import com.adsamcik.signalcollector.receivers.NotificationReceiver
import com.adsamcik.signalcollector.utility.*
import com.crashlytics.android.Crashlytics
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.math.RoundingMode
import java.text.DecimalFormat

class TrackerService : LifecycleService(), SensorEventListener {
	private var wifiScanTime: Long = 0
	private var wifiScanData: Array<ScanResult>? = null
	private var wifiReceiver: WifiReceiver? = null

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
	private var prevLocation: Location? = null

	private lateinit var locationCallback: LocationCallback

	private val session = TrackingSession(System.currentTimeMillis())

	private var lastStepCount = -1

	private lateinit var keyWifiEnabled: String
	private var defaultWifiEnabled: Boolean = false

	private lateinit var keyCellEnabled: String
	private var defaultCellEnabled: Boolean = false

	private lateinit var keyLocationEnabled: String
	private var defaultLocationEnabled: Boolean = false


	/**
	 * Collects data from necessary places and sensors and creates new RawData instance
	 */
	private fun updateData(location: Location) {
		val distance = if (prevLocation == null) 0f else location.distanceTo(prevLocation)
		if (location.isFromMockProvider) {
			prevMocked = true
			return
		} else if (prevMocked && prevLocation != null) {
			prevMocked = false
			if (distance < minDistanceInMeters)
				return
		}

		wakeLock.acquire(10 * Constants.MINUTE_IN_MILLISECONDS)

		session.apply {
			distanceInM += distance
			collections++
		}

		val preferences = Preferences.getPref(this)
		val d = RawData(System.currentTimeMillis())

		if (preferences.getBoolean(keyWifiEnabled, defaultWifiEnabled)) {
			val prevLocation = prevLocation
			if (prevLocation != null) {
				if (wifiScanData != null) {
					val timeDiff = (wifiScanTime - prevLocation.time).toDouble() / (d.time - prevLocation.time).toDouble()
					if (timeDiff >= 0) {
						val distTo = location.distanceTo(Assist.interpolateLocation(prevLocation, location, timeDiff))
						distanceToWifi = distTo.toInt()

						if (distTo <= UPDATE_MAX_DISTANCE_TO_WIFI && distTo > 0)
							d.setWifi(wifiScanData, wifiScanTime)
					}
					wifiScanData = null
				} else {
					val timeDiff = (wifiScanTime - prevLocation.time).toDouble() / (d.time - prevLocation.time).toDouble()
					if (timeDiff >= 0) {
						val distTo = location.distanceTo(Assist.interpolateLocation(prevLocation, location, timeDiff))
						distanceToWifi += distTo.toInt()
					}
				}
			}

			wifiManager.startScan()
		}

		if (preferences.getBoolean(keyCellEnabled, defaultCellEnabled) && !Assist.isAirplaneModeEnabled(this)) {
			d.addCell(telephonyManager)
		}

		val activityInfo = ActivityService.lastActivity

		if (preferences.getBoolean(keyLocationEnabled, defaultLocationEnabled))
			d.setLocation(location).setActivity(activityInfo)

		rawDataEcho.postValue(d)

		prevLocation = location
		prevLocation!!.time = d.time

		notificationManager.notify(NOTIFICATION_ID_SERVICE, generateNotification(true, d))

		saveData(d)

		if (isBackgroundActivated && powerManager.isPowerSaveMode)
			stopSelf()

		wakeLock.release()
	}

	private fun saveData(data: RawData) {
		GlobalScope.launch {
			val appContext = applicationContext
			val database = AppDatabase.getAppDatabase(appContext)

			database.runInTransaction {
				val location = data.location
				var locationId: Long? = null
				if (location != null)
					locationId = database.locationDao().insert(location.toDatabase(data.activity!!))

				val cell = data.cell
				cell?.registeredCells?.forEach {
					if (database.cellDao().insertWithUpdate(DatabaseCellData(locationId, data.time, data.time, it)) == 0)
						database.cellDao().update(it.cellId, locationId, data.time, it.type, it.asu)
				}

				val wifi = data.wifi
				if (wifi != null) {
					val wifiDao = database.wifiDao()
					wifi.inRange.forEach {
						if (wifiDao.insertWithUpdate(DatabaseWifiData(locationId, wifi.time, wifi.time, it)) == 0)
							wifiDao.update(locationId, it.BSSID, it.SSID, it.capabilities, it.frequency, wifi.time, it.level)
					}
				}
			}
		}
	}

	/**
	 * Generates tracking notification
	 */
	private fun generateNotification(gpsAvailable: Boolean, d: RawData?): Notification {
		val intent = Intent(this, LaunchActivity::class.java)
		val builder = NotificationCompat.Builder(this, getString(R.string.channel_track_id))
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setSmallIcon(R.drawable.ic_signals)  // the done icon
				.setTicker(getString(R.string.notification_tracker_active_ticker))  // the done text
				.setWhen(System.currentTimeMillis())  // the time stamp
				.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0)) // The intent to send when the entry is clicked
				.setColor(ContextCompat.getColor(this, R.color.color_accent))

		val stopIntent = Intent(this, NotificationReceiver::class.java)
		stopIntent.putExtra(NotificationReceiver.ACTION_STRING, if (isBackgroundActivated) NotificationReceiver.LOCK_RECHARGE_ACTION else NotificationReceiver.STOP_TRACKING_ACTION)
		val stop = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT)
		if (isBackgroundActivated) {
			builder.addAction(R.drawable.ic_battery_alert_black_24dp, getString(R.string.notification_stop_til_recharge), stop)

			val stopForMinutes = 60
			val stopForMinutesIntent = Intent(this, NotificationReceiver::class.java)
			stopForMinutesIntent.putExtra(NotificationReceiver.ACTION_STRING, NotificationReceiver.STOP_MINUTES_EXTRA)
			stopForMinutesIntent.putExtra(NotificationReceiver.STOP_MINUTES_EXTRA, stopForMinutes)
			val stopForMinutesAction = PendingIntent.getBroadcast(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT)
			builder.addAction(R.drawable.ic_stop_black_24dp, getString(R.string.notification_stop_for_minutes, stopForMinutes), stopForMinutesAction)
		} else
			builder.addAction(R.drawable.ic_pause_circle_filled_black_24dp, getString(R.string.notification_stop), stop)

		if (!gpsAvailable)
			builder.setContentTitle(getString(R.string.notification_looking_for_gps))
		else {
			builder.setContentTitle(getString(R.string.notification_tracking_active))
			builder.setContentText(buildNotificationText(d!!))
		}

		return builder.build()
	}

	/**
	 * Builds text for tracking notification
	 */
	private fun buildNotificationText(d: RawData): String {
		val resources = resources
		val sb = StringBuilder()
		val df = DecimalFormat("#.#")
		df.roundingMode = RoundingMode.HALF_UP

		var isEmpty = true

		if (d.activity != null)
			sb.append(resources.getString(R.string.notification_activity,
					d.activity!!.getResolvedActivityName(this))).append(", ")

		val wifi = d.wifi
		if (wifi != null) {
			sb.append(resources.getString(R.string.notification_wifi, wifi.inRange.size)).append(", ")
			isEmpty = false
		}

		val cell = d.cell
		if (cell != null && cell.registeredCells.isNotEmpty()) {
			val mainCell = cell.registeredCells[0]
			sb.append(resources.getString(R.string.notification_cell_current, mainCell.type.name, mainCell.dbm)).append(' ').append(resources.getQuantityString(R.plurals.notification_cell_count, cell.totalCount, cell.totalCount)).append(", ")
			isEmpty = false
		}

		if (!isEmpty)
			sb.setLength(sb.length - 2)
		else
			sb.append(resources.getString(R.string.notification_nothing_found))

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

		defaultCellEnabled = resources.getString(R.string.settings_cell_enabled_default)!!.toBoolean()
		defaultLocationEnabled = resources.getString(R.string.settings_location_enabled_default)!!.toBoolean()
		defaultWifiEnabled = resources.getString(R.string.settings_wifi_enabled_default)!!.toBoolean()

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
					updateData(result.lastLocation)
				}

				override fun onLocationAvailability(availability: LocationAvailability) {
					if (!availability.isLocationAvailable)
						notificationManager.notify(NOTIFICATION_ID_SERVICE, generateNotification(false, null))
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
			wifiManager.startScan()
			wifiReceiver = WifiReceiver()
			registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
		}

		if(packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
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
		startForeground(NOTIFICATION_ID_SERVICE, generateNotification(false, null))

		if (isBackgroundActivated) {
			ActivityService.requestAutoTracking(this, javaClass)
			TrackingLocker.isLocked.observe(this) {
				if (it)
					stopSelf()
			}
		} else
			ActivityService.requestActivity(this, javaClass, minUpdateDelayInSeconds)

		ActivityWatcherService.poke(this, false)

		isServiceRunning.value = true

		return START_NOT_STICKY
	}


	override fun onDestroy() {
		super.onDestroy()

		stopForeground(true)
		service = null
		isServiceRunning.value = false

		ActivityWatcherService.pokeWithCheck(this)
		ActivityService.removeActivityRequest(this, javaClass)

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
			wifiScanTime = System.currentTimeMillis()
			val result = wifiManager.scanResults
			wifiScanData = result.toTypedArray()
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
		val isServiceRunning = NonNullLiveMutableData(false)

		/**
		 * RawData from previous collection
		 */
		var rawDataEcho = MutableLiveData<RawData>()

		/**
		 * Extra information about distance for tracker
		 */
		var distanceToWifi: Int = 0

		/**
		 * Weak reference to service for AutoLock and check if service is running
		 */
		private var service: WeakReference<TrackerService>? = null

		/**
		 * Checks if tracker was activated in background
		 *
		 * @return true if activated by the app
		 */
		var isBackgroundActivated = false
			private set
	}
}