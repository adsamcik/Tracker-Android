package com.adsamcik.signalcollector.tracker.component.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.misc.extension.LocationExtensions
import com.adsamcik.signalcollector.misc.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.tracker.component.PreferenceDataTrackerComponent
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.adsamcik.signalcollector.tracker.service.TrackerService
import com.google.android.gms.location.LocationResult
import kotlin.math.abs

class WifiTrackerComponent(context: Context) : PreferenceDataTrackerComponent() {
	override val enabledKeyRes: Int
		get() = R.string.settings_wifi_enabled_key
	override val enabledDefaultRes: Int
		get() = R.string.settings_wifi_enabled_default


	private var wifiManager: WifiManager = context.getSystemServiceTyped(Context.WIFI_SERVICE)
	private var wifiReceiver: WifiReceiver = WifiReceiver()

	private var wifiScanTime: Long = 0
	private var wifiScanData: Array<ScanResult>? = null
	private var wifiLastScanRequest: Long = 0
	private var wifiScanRequested: Boolean = false

	init {
		if (isEnabled(context)) {
			//Let's not waste precious scan requests on Pie and newer
			if (Build.VERSION.SDK_INT < 28) {
				wifiScanRequested = wifiManager.startScan()
				wifiLastScanRequest = System.currentTimeMillis()
			}

			wifiReceiver = WifiReceiver().also {
				context.registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
			}
		}
	}

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
		}
		requestScan()
	}

	private fun requestScan() {
		val now = System.currentTimeMillis()
		if (Build.VERSION.SDK_INT >= 28) {
			if (now - wifiLastScanRequest > Constants.SECOND_IN_MILLISECONDS * 15 && (wifiScanTime == -1L || now - wifiScanTime > Constants.SECOND_IN_MILLISECONDS * 10)) {
				wifiScanRequested = wifiManager.startScan()
				wifiLastScanRequest = now
			}
		} else {
			if (!wifiScanRequested) {
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
			TrackerService.distanceToWifi = distanceBetweenFirstAndSecond
		}
	}

	override fun onDestroy(context: Context) {
		context.unregisterReceiver(wifiReceiver)
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
		private const val MAX_DISTANCE_TO_WIFI = 100
	}
}