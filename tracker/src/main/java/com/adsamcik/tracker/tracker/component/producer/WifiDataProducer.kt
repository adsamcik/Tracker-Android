package com.adsamcik.tracker.tracker.component.producer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.shared.base.extension.wifiManager
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import com.adsamcik.tracker.tracker.notification.WifiPermissionHintNotifier
import com.adsamcik.tracker.tracker.data.collection.WifiScanData
import com.adsamcik.tracker.logger.Reporter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class WifiDataProducer(
    changeReceiver: TrackerDataProducerObserver,
    trackingParamsRepository: TrackingParamsRepository? = null,
    private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : TrackerDataProducerComponent(
    changeReceiver,
    dispatchers,
    trackingParamsRepository?.data?.map { it.wifiEnabled },
) {
    override val preferenceKey: String = PreferenceKeys.WIFI_ENABLED
    override val preferenceDefault: Boolean = PreferenceKeys.WIFI_ENABLED_DEFAULT

    private lateinit var wifiManager: WifiManager
    private var receiver: WifiReceiver = WifiReceiver()
    private lateinit var appContext: Context
    private var scope = CoroutineScope(
        SupervisorJob() + dispatchers.io + CoroutineExceptionHandler { _, e -> Reporter.report(e) }
    )

    private var scanTime: Long = -1L
    private var scanTimeRelative: Long = -1L
    private var scanData: Array<ScanResult>? = null
    private var lastScanRequest: Long = 0
    private var isScanRequested: Boolean = false

    private val scanDataLock = ReentrantLock()

    override fun onDataRequest(builder: TrackingCycleBuilder) {
        scanDataLock.withLock {
            val scanData = scanData
            if (scanData != null) {
                builder.wifiScan = WifiScanData(scanTime, scanTimeRelative, scanData)

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
        // Recreate scope on each enable (previous scope cancelled in onDisable)
        scope = CoroutineScope(
            SupervisorJob() + dispatchers.io + CoroutineExceptionHandler { _, e -> Reporter.report(e) }
        )
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
                scanData = readScanResultsOrNull()
            }
        }
    }

    private fun readScanResultsOrNull(): Array<ScanResult>? {
        if (!appContext.hasWifiScanPermission) {
            return null
        }

        return try {
            wifiManager.scanResults.toTypedArray()
        } catch (exception: SecurityException) {
            Reporter.report(exception)
            null
        }
    }

    private fun hasWifiScanPermission(): Boolean {
        return appContext.hasWifiScanPermission
    }
}
