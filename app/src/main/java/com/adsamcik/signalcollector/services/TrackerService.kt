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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.LaunchActivity
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.extensions.getSystemServiceTyped
import com.adsamcik.signalcollector.receivers.NotificationReceiver
import com.adsamcik.signalcollector.utility.*
import com.adsamcik.signalcollector.utility.Constants.MINUTE_IN_MILLISECONDS
import com.crashlytics.android.Crashlytics
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.math.RoundingMode
import java.text.DecimalFormat

class TrackerService : LifecycleService() {
	private var wifiScanTime: Long = 0
	private var wasWifiEnabled = false
	private var saveAttemptsFailed = 0
	private var locationListener: LocationListener? = null
	private var wifiScanData: Array<ScanResult>? = null
	private var wifiReceiver: WifiReceiver? = null
	private var notificationManager: NotificationManager? = null

	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock
	private lateinit var locationManager: LocationManager
	private lateinit var telephonyManager: TelephonyManager
	private var subscriptionManager: SubscriptionManager? = null
	private var wifiManager: WifiManager? = null

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

	/**
	 * Collects data from necessary places and sensors and creates new RawData instance
	 */
	private fun updateData(location: Location) {
		if (location.isFromMockProvider) {
			prevMocked = true
			return
		} else if (prevMocked && prevLocation != null) {
			prevMocked = false
			if (location.distanceTo(prevLocation) < minDistanceInMeters)
				return
		}

		if (location.altitude > 5600) {
			TrackingLocker.lockTimeLock(this, Constants.MINUTE_IN_MILLISECONDS * 45)
			//todo add notification
			if (!isBackgroundActivated)
				stopSelf()
			return
		}

		wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)
		val d = RawData(System.currentTimeMillis())

		if (wifiManager != null) {
			if (prevLocation != null) {
				if (wifiScanData != null) {
					val timeDiff = (wifiScanTime - prevLocation!!.time).toDouble() / (d.time - prevLocation!!.time).toDouble()
					if (timeDiff >= 0) {
						val distTo = location.distanceTo(Assist.interpolateLocation(prevLocation!!, location, timeDiff))
						distanceToWifi = distTo.toInt()

						if (distTo <= UPDATE_MAX_DISTANCE_TO_WIFI && distTo > 0)
							d.setWifi(wifiScanData, wifiScanTime)
					}
					wifiScanData = null
				} else {
					val timeDiff = (wifiScanTime - prevLocation!!.time).toDouble() / (d.time - prevLocation!!.time).toDouble()
					if (timeDiff >= 0) {
						val distTo = location.distanceTo(Assist.interpolateLocation(prevLocation!!, location, timeDiff))
						distanceToWifi += distTo.toInt()
					}
				}
			}

			wifiManager!!.startScan()
		}

		if (!Assist.isAirplaneModeEnabled(this)) {
			d.addCell(telephonyManager)
		}

		val activityInfo = ActivityService.lastActivity

		if (Preferences.getPref(this).getBoolean(Preferences.PREF_TRACKING_LOCATION_ENABLED, Preferences.DEFAULT_TRACKING_LOCATION_ENABLED))
			d.setLocation(location).setActivity(activityInfo.resolvedActivity)

		rawDataEcho.postValue(d)

		prevLocation = location
		prevLocation!!.time = d.time

		notificationManager!!.notify(NOTIFICATION_ID_SERVICE, generateNotification(true, d))

		Log.d("Tracker", location.toString())

		saveData(d)

		if (isBackgroundActivated && powerManager.isPowerSaveMode)
			stopSelf()

		wakeLock.release()
	}

	private fun saveData(data: RawData) {
		val appContext = applicationContext
		val database = AppDatabase.getAppDatabase(appContext)

		val location = data.location
		if (location != null) {
			GlobalScope.launch {
				database.locationDao().insert(location.toDatabase())
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
					ActivityInfo.getResolvedActivityName(this, d.activity!!))).append(", ")

		val wifi = d.wifi
		if (wifi != null) {
			sb.append(resources.getString(R.string.notification_wifi, wifi.inRange.size)).append(", ")
			isEmpty = false
		}

		val cell = d.cell
		if (cell != null && cell.registeredCells.isNotEmpty()) {
			val mainCell = cell.registeredCells[0]
			sb.append(resources.getString(R.string.notification_cell_current, mainCell.typeString, mainCell.dbm)).append(' ').append(resources.getQuantityString(R.plurals.notification_cell_count, cell.totalCount, cell.totalCount)).append(", ")
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

		//Get managers
		locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
		notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signals:TrackerWakeLock")

		//Enable location update
		locationListener = object : LocationListener {
			override fun onLocationChanged(location: Location) {
				updateData(location)

			}

			override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
				if (status == LocationProvider.TEMPORARILY_UNAVAILABLE || status == LocationProvider.OUT_OF_SERVICE)
					notificationManager!!.notify(NOTIFICATION_ID_SERVICE, generateNotification(false, null))
			}

			override fun onProviderEnabled(provider: String) {}

			override fun onProviderDisabled(provider: String) {
				if (provider == LocationManager.GPS_PROVIDER)
					stopSelf()
			}
		}

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
			minUpdateDelayInSeconds = sp.getInt(resources.getString(R.string.settings_tracking_min_time_key), resources.getInteger(R.integer.settings_tracking_min_time_default))
			minDistanceInMeters = sp.getInt(resources.getString(R.string.settings_tracking_min_distance_key), resources.getInteger(R.integer.settings_tracking_min_distance_default)).toFloat()
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minUpdateDelayInSeconds.toLong() * Constants.SECOND_IN_MILLISECONDS, minDistanceInMeters, locationListener)
		} else {
			Crashlytics.logException(Exception("Tracker does not have sufficient permissions"))
			stopSelf()
			return
		}

		//Wifi tracking setup
		if (sp.getBoolean(Preferences.PREF_TRACKING_WIFI_ENABLED, true)) {
			wifiManager = getSystemServiceTyped(Context.WIFI_SERVICE)
			assert(wifiManager != null)
			wasWifiEnabled = !(wifiManager!!.isScanAlwaysAvailable || wifiManager!!.isWifiEnabled)
			if (wasWifiEnabled)
				wifiManager!!.isWifiEnabled = true

			wifiManager!!.startScan()
			wifiReceiver = WifiReceiver()
			registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
		}

		//Cell tracking setup
		if (sp.getBoolean(Preferences.PREF_TRACKING_CELL_ENABLED, true)) {
			telephonyManager = getSystemServiceTyped(Context.TELEPHONY_SERVICE)
			subscriptionManager = if (Build.VERSION.SDK_INT >= 22)
				getSystemServiceTyped(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
			else
				null
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

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		isBackgroundActivated = intent == null || intent.getBooleanExtra("backTrack", false)
		startForeground(NOTIFICATION_ID_SERVICE, generateNotification(false, null))

		if (isBackgroundActivated)
			ActivityService.requestAutoTracking(this, javaClass)
		else
			ActivityService.requestActivity(this, javaClass, minUpdateDelayInSeconds)

		ActivityWatcherService.poke(this, false)

		if (isBackgroundActivated) {
			TrackingLocker.isLocked.observe(this) {
				if (it)
					stopSelf()
			}
		}

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

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			locationManager.removeUpdates(locationListener)

		if (wifiReceiver != null)
			unregisterReceiver(wifiReceiver)

		if (wasWifiEnabled) {
			if (!powerManager.isInteractive)
				wifiManager!!.isWifiEnabled = false
		}

		val sp = Preferences.getPref(this)
		sp.edit {
			putInt(Preferences.PREF_STATS_MINUTES,
					sp.getInt(Preferences.PREF_STATS_MINUTES, 0) + ((System.currentTimeMillis() - TRACKING_ACTIVE_SINCE) / MINUTE_IN_MILLISECONDS).toInt())
		}

		if (android.os.Build.VERSION.SDK_INT >= 25) {
			Shortcuts.initializeShortcuts(this)
			Shortcuts.updateShortcut(this, Shortcuts.TRACKING_ID, getString(R.string.shortcut_start_tracking), getString(R.string.shortcut_start_tracking_long), R.drawable.ic_play_circle_filled_black_24dp, Shortcuts.ShortcutType.START_COLLECTION)
		}
	}

	private inner class WifiReceiver : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			wifiScanTime = System.currentTimeMillis()
			val result = wifiManager!!.scanResults
			wifiScanData = result.toTypedArray()
		}
	}

	companion object {
		//Constants
		private const val TAG = "SignalsTracker"
		private const val NOTIFICATION_ID_SERVICE = -7643

		private const val UPDATE_MAX_DISTANCE_TO_WIFI = 40

		private val TRACKING_ACTIVE_SINCE = System.currentTimeMillis()

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