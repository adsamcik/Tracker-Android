package com.adsamcik.signalcollector.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.adsamcik.signalcollector.NoiseTracker
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.MainActivity
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.enums.ResolvedActivity
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.interfaces.ICallback
import com.adsamcik.signalcollector.receivers.NotificationReceiver
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Constants.*
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.Shortcuts
import com.google.firebase.crash.FirebaseCrash
import com.google.gson.Gson
import java.lang.ref.WeakReference
import java.math.RoundingMode
import java.nio.charset.Charset
import java.text.DecimalFormat
import java.util.*

class TrackerService : Service() {
    private val MIN_DISTANCE_M = 5f

    private val MAX_NOISE_TRACKING_SPEED_KM = 18f
    private val TRACKING_ACTIVE_SINCE = System.currentTimeMillis()
    private val data = ArrayList<RawData>()

    private var wifiScanTime: Long = 0
    private var wasWifiEnabled = false
    private var saveAttemptsFailed = 0
    private var locationListener: LocationListener? = null
    private var wifiScanData: Array<ScanResult>? = null
    private var wifiReceiver: WifiReceiver? = null
    private var notificationManager: NotificationManager? = null

    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var subscriptionManager: SubscriptionManager? = null
    private var wifiManager: WifiManager? = null
    private val gson = Gson()

    private var noiseTracker: NoiseTracker? = null
    private var noiseActive = false

    /**
     * True if previous collection was mocked
     */
    private var prevMocked = false

    /**
     * Previous location of collection
     */
    private var prevLocation: Location? = null

    private fun updateData(location: Location) {
        if (location.isFromMockProvider) {
            prevMocked = true
            return
        } else if (prevMocked && prevLocation != null) {
            prevMocked = false
            if (location.distanceTo(prevLocation) < MIN_DISTANCE_M)
                return
        }

        if (location.altitude > 5600) {
            setAutoLock()
            //todo add notification
            if (!isBackgroundActivated)
                stopSelf()
            return
        }

        wakeLock!!.acquire(10 * 60 * 1000L /*10 minutes*/)
        val d = RawData(System.currentTimeMillis())

        if (wifiManager != null) {
            if (prevLocation != null) {
                if (wifiScanData != null) {
                    val timeDiff = (wifiScanTime - prevLocation!!.time).toDouble() / (d.time - prevLocation!!.time).toDouble()
                    if (timeDiff >= 0) {
                        val distTo = location.distanceTo(Assist.interpolateLocation(prevLocation!!, location, timeDiff))
                        distanceToWifi = distTo.toInt()
                        //Log.d(TAG, "dist to wifi " + distTo);
                        val UPDATE_MAX_DISTANCE_TO_WIFI = 40
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

        if (telephonyManager != null && !Assist.isAirplaneModeEnabled(this)) {
            d.addCell(telephonyManager!!)
        }

        val activityInfo = ActivityService.lastActivity

        if (noiseTracker != null) {
            val MAX_NOISE_TRACKING_SPEED_M = (MAX_NOISE_TRACKING_SPEED_KM / 3.6).toFloat()
            if ((activityInfo.resolvedActivity == ResolvedActivity.ON_FOOT || noiseActive && activityInfo.resolvedActivity == ResolvedActivity.UNKNOWN) && location.speed < MAX_NOISE_TRACKING_SPEED_M) {
                noiseTracker!!.start()
                val value = noiseTracker!!.getSample(10)
                if (value >= 0)
                    d.setNoise(value)
                noiseActive = true
            } else {
                noiseTracker!!.stop()
                noiseActive = false
            }
        }

        if (Preferences.get(this).getBoolean(Preferences.PREF_TRACKING_LOCATION_ENABLED, Preferences.DEFAULT_TRACKING_LOCATION_ENABLED))
            d.setLocation(location).setActivity(activityInfo.resolvedActivity)

        data.add(d)
        rawDataEcho = d

        DataStore.incData(this, gson.toJson(d).toByteArray(Charset.defaultCharset()).size.toLong(), 1)

        prevLocation = location
        prevLocation!!.time = d.time

        notificationManager!!.notify(NOTIFICATION_ID_SERVICE, generateNotification(true, d))

        if (onNewDataFound != null) {
            onNewDataFound!!.callback()
        }

        if (data.size > 5)
            saveData()

        if (isBackgroundActivated && powerManager!!.isPowerSaveMode)
            stopSelf()

        wakeLock!!.release()
    }


    private fun saveData() {
        if (data.size == 0) return

        val sp = Preferences.get(this)
        Preferences.checkStatsDay(this)

        var wifiCount: Int
        var cellCount: Int
        val locations: Int

        wifiCount = sp.getInt(Preferences.PREF_STATS_WIFI_FOUND, 0)
        cellCount = sp.getInt(Preferences.PREF_STATS_CELL_FOUND, 0)
        locations = sp.getInt(Preferences.PREF_STATS_LOCATIONS_FOUND, 0)
        for (d in data) {
            if (d.wifi != null)
                wifiCount += d.wifi!!.size
            if (d.cellCount != null)
                cellCount += d.cellCount!!
        }

        val result = DataStore.saveData(this, data.toTypedArray())
        if (result === DataStore.SaveStatus.SAVE_FAILED) {
            saveAttemptsFailed++
            if (saveAttemptsFailed >= 5)
                stopSelf()
        } else {
            sp.edit()
                    .putInt(Preferences.PREF_STATS_WIFI_FOUND, wifiCount)
                    .putInt(Preferences.PREF_STATS_CELL_FOUND, cellCount)
                    .putInt(Preferences.PREF_STATS_LOCATIONS_FOUND, locations + data.size)
                    .putInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, sp.getInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, 0) + data.size)
                    .apply()
            data.clear()
            if (result === DataStore.SaveStatus.SAVE_SUCCESS_FILE_DONE &&
                    !Preferences.get(this).getBoolean(Preferences.PREF_AUTO_UPLOAD_SMART, Preferences.DEFAULT_AUTO_UPLOAD_SMART) &&
                    DataStore.sizeOfData(this) >= Constants.U_MEGABYTE * Preferences.get(this).getInt(Preferences.PREF_AUTO_UPLOAD_AT_MB, Preferences.DEFAULT_AUTO_UPLOAD_AT_MB)) {
                UploadService.requestUpload(this, UploadService.UploadScheduleSource.BACKGROUND)
                FirebaseCrash.log("Requested upload from tracking")
            }
        }
    }

    private fun generateNotification(gpsAvailable: Boolean, d: RawData?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val builder = NotificationCompat.Builder(this, getString(R.string.channel_track_id))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.ic_signals)  // the done icon
                .setTicker(getString(R.string.notification_tracker_active_ticker))  // the done text
                .setWhen(System.currentTimeMillis())  // the time stamp
                .setContentIntent(PendingIntent.getActivity(this, 0, intent, 0)) // The intent to send when the entry is clicked
                .setColor(ContextCompat.getColor(this, R.color.color_accent))

        val stopIntent = Intent(this, NotificationReceiver::class.java)
        stopIntent.putExtra(NotificationReceiver.ACTION_STRING, if (isBackgroundActivated) 0 else 1)
        val stop = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        if (isBackgroundActivated)
            builder.addAction(R.drawable.ic_battery_alert_black_24dp, getString(R.string.notification_stop_til_recharge), stop)
        else
            builder.addAction(R.drawable.ic_pause, getString(R.string.notification_stop), stop)

        if (!gpsAvailable)
            builder.setContentTitle(getString(R.string.notification_looking_for_gps))
        else {
            builder.setContentTitle(getString(R.string.notification_tracking_active))
            builder.setContentText(buildNotificationText(d))
        }

        return builder.build()
    }

    private fun buildNotificationText(d: RawData?): String {
        val sb = StringBuilder()
        val df = DecimalFormat("#.#")
        df.roundingMode = RoundingMode.HALF_UP
        if (d!!.wifi != null)
            sb.append(d.wifi!!.size).append(" wifi ")
        if (d.cellCount != null)
            sb.append(d.cellCount!!).append(" cell ")
        if (d.noise > 0)
            sb.append(df.format(Assist.amplitudeToDbm(d.noise))).append(" dB ")
        if (sb.isNotEmpty())
            sb.setLength(sb.length - 1)
        else
            sb.append("Nothing found")
        return sb.toString()
    }


    override fun onCreate() {
        service = WeakReference(this)
        Assist.initialize(this)
        val sp = Preferences.get(this)

        ActivityService.requestActivity(this, javaClass, UPDATE_TIME_SEC)

        //Get managers
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        assert(powerManager != null)
        wakeLock = powerManager!!.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackerWakeLock")

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

        val UPDATE_TIME_MILLISEC = UPDATE_TIME_SEC * SECOND_IN_MILLISECONDS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_TIME_MILLISEC.toLong(), MIN_DISTANCE_M, locationListener)
        else {
            FirebaseCrash.report(Exception("Tracker does not have sufficient permissions"))
            stopSelf()
            return
        }

        //Wifi tracking setup
        if (sp.getBoolean(Preferences.PREF_TRACKING_WIFI_ENABLED, true)) {
            wifiManager = this.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 22)
                subscriptionManager = SubscriptionManager.from(this)
            else
                subscriptionManager = null
        }

        //Shortcut setup
        if (android.os.Build.VERSION.SDK_INT >= 25) {
            Shortcuts.initializeShortcuts(this)
            Shortcuts.updateShortcut(this, Shortcuts.TRACKING_ID, getString(R.string.shortcut_stop_tracking), getString(R.string.shortcut_stop_tracking_long), R.drawable.ic_pause, Shortcuts.ShortcutType.STOP_COLLECTION)
        }

        UploadService.cancelUploadSchedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lockedUntil = 0
        isBackgroundActivated = intent == null || intent.getBooleanExtra("backTrack", false)
        startForeground(NOTIFICATION_ID_SERVICE, generateNotification(false, null))
        if (onServiceStateChange != null)
            onServiceStateChange!!.callback()
        if (NOISE_ENABLED && Preferences.get(this).getBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, false))
            noiseTracker = NoiseTracker(this).start()
        return super.onStartCommand(intent, flags, startId)
    }


    override fun onDestroy() {
        stopForeground(true)
        service = null

        /*if (noiseTracker != null)
			noiseTracker.stop();*/

        ActivityService.removeActivityRequest(this, javaClass)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            locationManager!!.removeUpdates(locationListener)

        if (wifiReceiver != null)
            unregisterReceiver(wifiReceiver)

        saveData()
        if (onServiceStateChange != null)
            onServiceStateChange!!.callback()
        DataStore.cleanup(this)

        if (wasWifiEnabled) {
            if (!powerManager!!.isInteractive)
                wifiManager!!.isWifiEnabled = false
        }

        val sp = Preferences.get(this)
        sp.edit().putInt(Preferences.PREF_STATS_MINUTES, sp.getInt(Preferences.PREF_STATS_MINUTES, 0) + ((System.currentTimeMillis() - TRACKING_ACTIVE_SINCE) / MINUTE_IN_MILLISECONDS).toInt()).apply()

        if (android.os.Build.VERSION.SDK_INT >= 25) {
            Shortcuts.initializeShortcuts(this)
            Shortcuts.updateShortcut(this, Shortcuts.TRACKING_ID, getString(R.string.shortcut_start_tracking), getString(R.string.shortcut_start_tracking_long), R.drawable.ic_play, Shortcuts.ShortcutType.START_COLLECTION)
        }

        if (sp.getBoolean(Preferences.PREF_AUTO_UPLOAD_SMART, Preferences.DEFAULT_AUTO_UPLOAD_SMART))
            UploadService.requestUploadSchedule(this)
    }

    override fun onBind(intent: Intent): IBinder? = null


    private inner class WifiReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            wifiScanTime = System.currentTimeMillis()
            val result = wifiManager!!.scanResults
            wifiScanData = result.toTypedArray()
        }
    }

    companion object {
        //Constants
        private val TAG = "SignalsTracker"
        private val LOCK_TIME_IN_MINUTES = 30
        private val LOCK_TIME_IN_MILLISECONDS = LOCK_TIME_IN_MINUTES * MINUTE_IN_MILLISECONDS
        private val NOTIFICATION_ID_SERVICE = 7643

        val UPDATE_TIME_SEC = 2

        var onServiceStateChange: ICallback? = null
        var onNewDataFound: ICallback? = null

        /**
         * RawData from previous collection
         */
        var rawDataEcho: RawData = RawData(0)
        /**
         * Extra information about distance for tracker
         */
        var distanceToWifi: Int = 0

        /**
         * Weak reference to service for AutoLock and check if service is running
         */
        private var service: WeakReference<TrackerService>? = null
        private var lockedUntil: Long = 0
        /**
         * Checks if tracker was activated in background
         *
         * @return true if activated by the app
         */
        var isBackgroundActivated = false
            private set

        /**
         * Checks if service is running
         *
         * @return true if service is running
         */
        val isRunning: Boolean
            get() = service != null && service!!.get() != null

        /**
         * Checks if Tracker is auto locked
         *
         * @return true if locked
         */
        val isAutoLocked: Boolean
            get() = System.currentTimeMillis() < lockedUntil

        /**
         * Sets auto lock with predefined time [TrackerService.LOCK_TIME_IN_MINUTES]
         */
        fun setAutoLock(): Int {
            lockedUntil = System.currentTimeMillis() + LOCK_TIME_IN_MILLISECONDS

            if (isRunning && isBackgroundActivated)
                service!!.get()!!.stopSelf()
            return LOCK_TIME_IN_MINUTES
        }
    }
}