package com.adsamcik.tracker.tracker.component.producer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.Manifest
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.wifiManager
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.notification.WifiPermissionHintNotifier
import com.adsamcik.tracker.tracker.data.collection.WifiScanData
import com.adsamcik.tracker.logger.Reporter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class WifiDataProducer(changeReceiver: TrackerDataProducerObserver) :
    TrackerDataProducerComponent(changeReceiver) {
    override val keyRes: Int = com.adsamcik.tracker.shared.preferences.R.string.settings_wifi_enabled_key
    override val defaultRes: Int = com.adsamcik.tracker.shared.preferences.R.string.settings_wifi_enabled_default

    private lateinit var wifiManager: WifiManager
    private var receiver: WifiReceiver = WifiReceiver()
    private lateinit var appContext: Context
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Reporter.report(e) }
    )

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
        if (this::appContext.isInitialized) {
            if (hasWifiScanPermission()) {
                requestScan()
            } else {
                scope.launch { WifiPermissionHintNotifier.maybeNotify(appContext) }
            }
        }
    }

    @Synchronized
    private fun requestScan() {
    if (!hasWifiScanPermission()) {
            scope.launch { WifiPermissionHintNotifier.maybeNotify(appContext) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (now - lastScanRequest > Time.SECOND_IN_MILLISECONDS * 15 &&
                (scanTime == -1L || now - scanTimeRelative > Time.SECOND_IN_MILLISECONDS * 10)
            ) {
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
        scope.cancel()
        context.unregisterReceiver(receiver)
    }

    override fun onEnable(context: Context) {
        super.onEnable(context)
    appContext = context.applicationContext
        wifiManager = context.wifiManager

        //Let's not waste precious scan requests onDataUpdated Pie and newer
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && hasWifiScanPermission()) {
            @Suppress("deprecation")
            isScanRequested = wifiManager.startScan()
            lastScanRequest = SystemClock.elapsedRealtime()
        }

        receiver = WifiReceiver().also {
            ContextCompat.registerReceiver(
                context,
                it,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private inner class WifiReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scanDataLock.withLock {
                isScanRequested = false
                scanTime = Time.nowMillis
                scanTimeRelative = Time.elapsedRealtimeNanos
                if (hasWifiScanPermission()) {
                    val result = wifiManager.scanResults
                    scanData = result.toTypedArray()
                } else {
                    scanData = null
                }
            }
        }
    }

    private fun hasWifiScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Prior to API 33, Wi‑Fi scans/results are gated by location permission
            val fine = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }
}

