package com.adsamcik.tracker.tracker.component.producer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.extension.getSystemServiceTyped
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.data.collection.WifiScanData
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class WifiDataProducer(changeReceiver: TrackerDataProducerObserver) : TrackerDataProducerComponent(
		changeReceiver) {
	override val keyRes: Int = R.string.settings_wifi_enabled_key
	override val defaultRes: Int = R.string.settings_wifi_enabled_default

	private lateinit var wifiManager: WifiManager
	private var receiver: WifiReceiver = WifiReceiver()

	private var scanTime: Long = -1L
	private var scanTimeRelative: Long = -1L
	private var scanData: Array<ScanResult>? = null
	private var lastScanRequest: Long = 0
	private var isScanRequested: Boolean = false

	private val scanDataLock = ReentrantLock()

	override fun onDataRequest(tempData: MutableCollectionTempData) {
		scanDataLock.withLock {
			val scanData = scanData
			if (scanData != null) {
				val wifiScanData = WifiScanData(scanTime, scanTimeRelative, scanData)
				tempData.set(TrackerComponentRequirement.WIFI.name, wifiScanData)

				this.scanData = null
				scanTime = -1L
				scanTimeRelative = -1L
			}
		}
		requestScan()
	}

	@Synchronized
	private fun requestScan() {
		val now = SystemClock.elapsedRealtime()
		if (Build.VERSION.SDK_INT >= 28) {
			if (now - lastScanRequest > Time.SECOND_IN_MILLISECONDS * 15 && (scanTime == -1L || now - scanTimeRelative > Time.SECOND_IN_MILLISECONDS * 10)) {
				@Suppress("deprecation")
				isScanRequested = wifiManager.startScan()
				lastScanRequest = now
			}
		} else {
			if (!isScanRequested) {
				@Suppress("deprecation")
				wifiManager.startScan()
				lastScanRequest = now
			}
		}
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		context.unregisterReceiver(receiver)
	}

	override fun onEnable(context: Context) {
		super.onEnable(context)
		wifiManager = context.getSystemServiceTyped(Context.WIFI_SERVICE)

		//Let's not waste precious scan requests onDataUpdated Pie and newer
		if (Build.VERSION.SDK_INT < 28) {
			@Suppress("deprecation")
			isScanRequested = wifiManager.startScan()
			lastScanRequest = SystemClock.elapsedRealtime()
		}

		receiver = WifiReceiver().also {
			context.registerReceiver(it, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
		}
	}

	private inner class WifiReceiver : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			scanDataLock.withLock {
				isScanRequested = false
				scanTime = Time.nowMillis
				scanTimeRelative = Time.elapsedRealtimeNanos
				val result = wifiManager.scanResults
				scanData = result.toTypedArray()
			}
		}
	}
}

