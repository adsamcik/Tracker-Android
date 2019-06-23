package com.adsamcik.signalcollector.tracker.component.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.extension.LocationExtensions
import com.adsamcik.signalcollector.common.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult
import kotlin.math.abs

class WifiTrackerComponent : DataTrackerComponent {
	private lateinit var wifiManager: WifiManager
	private var wifiReceiver: WifiReceiver = WifiReceiver()

	private var wifiScanTime: Long = -1L
	private var wifiScanTimeRelative: Long = -1L
	private var wifiScanData: Array<ScanResult>? = null
	private var wifiLastScanRequest: Long = 0
	private var wifiScanRequested: Boolean = false

	override fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData) {
		if (wifiScanData != null) {
			val location = locationResult.lastLocation
			val locations = locationResult.locations
			if (locations.size == 2) {
				val nearestLocation = locations.sortedBy { abs(wifiScanTime - it.time) }.take(2)
				val firstIndex = if (nearestLocation[0].time < nearestLocation[1].time) 0 else 1

				val first = nearestLocation[firstIndex]
				val second = nearestLocation[(firstIndex + 1).rem(2)]
				setWifi(first, second, first.distanceTo(second), collectionData)
			} else if (previousLocation != null) {
				setWifi(previousLocation, location, distance, collectionData)
			}

			wifiScanData = null
			wifiScanTime = -1L
			wifiScanTimeRelative = -1L
		}
		requestScan()
	}

	private fun requestScan() {
		val now = SystemClock.elapsedRealtime()
		if (Build.VERSION.SDK_INT >= 28) {
			if (now - wifiLastScanRequest > Time.SECOND_IN_MILLISECONDS * 15 && (wifiScanTime == -1L || now - wifiScanTimeRelative > Time.SECOND_IN_MILLISECONDS * 10)) {
				@Suppress("deprecation")
				wifiScanRequested = wifiManager.startScan()
				wifiLastScanRequest = now
			}
		} else {
			if (!wifiScanRequested) {
				@Suppress("deprecation")
				wifiManager.startScan()
				wifiLastScanRequest = now
			}
		}
	}

	private fun setWifi(firstLocation: Location, secondLocation: Location, distanceBetweenFirstAndSecond: Float, collectionData: MutableCollectionData) {
		val timeDelta = (wifiScanTime - firstLocation.time).toDouble() / (secondLocation.time - firstLocation.time).toDouble()
		val wifiDistance = distanceBetweenFirstAndSecond * timeDelta
		if (wifiDistance <= MAX_DISTANCE_TO_WIFI) {
			val interpolatedLocation = LocationExtensions.interpolateLocation(firstLocation, secondLocation, timeDelta)
			collectionData.setWifi(interpolatedLocation, wifiScanTime, wifiScanData)
		}
	}

	override fun onEnable(context: Context) {
		wifiManager = context.getSystemServiceTyped(Context.WIFI_SERVICE)

		//Let's not waste precious scan requests on Pie and newer
		if (Build.VERSION.SDK_INT < 28) {
			@Suppress("deprecation")
			wifiScanRequested = wifiManager.startScan()
			wifiLastScanRequest = SystemClock.elapsedRealtime()
		}

		wifiReceiver = WifiReceiver().also {
			context.registerReceiver(it, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
		}
	}

	override fun onDisable(context: Context) {
		context.unregisterReceiver(wifiReceiver)
	}

	private inner class WifiReceiver : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			synchronized(wifiScanTime) {
				wifiScanRequested = false
				wifiScanTime = Time.nowMillis
				wifiScanTimeRelative = SystemClock.elapsedRealtime()
				val result = wifiManager.scanResults
				wifiScanData = result.toTypedArray()
			}
		}
	}

	companion object {
		private const val MAX_DISTANCE_TO_WIFI = 100
	}
}