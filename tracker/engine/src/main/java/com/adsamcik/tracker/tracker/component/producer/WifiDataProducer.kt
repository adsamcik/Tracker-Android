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

    // Freshest per-AP timestamp (micros since boot) of the last scan we recorded. Used to record
    // each distinct scan only once, even when the cached-results fallback observes it repeatedly.
    @Volatile
    private var lastRecordedScanMicros: Long = Long.MIN_VALUE

    private val scanDataLock = ReentrantLock()

    override fun onDataRequest(builder: TrackingCycleBuilder) {
        if (!this::appContext.isInitialized) return

        if (!hasWifiScanPermission()) {
            scope.launch { WifiPermissionHintNotifier.maybeNotify(appContext) }
            return
        }

        resolveScanData()?.let { builder.wifiScan = it }
        requestScan()
    }

    /**
     * Resolve the Wi-Fi scan to attach to this cycle. Prefers a scan freshly delivered via the
     * `SCAN_RESULTS_AVAILABLE` broadcast; otherwise falls back to the system's most recent cached
     * results.
     *
     * The cached fallback is the background-reliability fix: with the screen off `startScan()` is
     * throttled to near-zero and broadcasts are sparse, but the system still performs occasional
     * scans whose results remain available via [WifiManager.getScanResults]. Each distinct scan is
     * recorded at most once (see [WifiScanGate]) so the fallback never floods the database with
     * duplicates of a stationary access point.
     */
    private fun resolveScanData(): WifiScanData? {
        val buffered = scanDataLock.withLock {
            val data = scanData
            if (data != null) {
                val result = WifiScanData(scanTime, scanTimeRelative, data)
                scanData = null
                scanTime = -1L
                scanTimeRelative = -1L
                result
            } else {
                null
            }
        }

        // A fresh SCAN_RESULTS_AVAILABLE broadcast always means a new scan completed — record it
        // unconditionally (do not run it through the de-duplication gate, which would wrongly drop
        // scans on devices/emulators whose ScanResult.timestamp does not advance every scan). Track
        // its timestamp so the cached fallback below does not re-record the same scan.
        if (buffered != null) {
            buffered.data.maxOfOrNull { it.timestamp }?.let {
                lastRecordedScanMicros = maxOf(lastRecordedScanMicros, it)
            }
            return buffered
        }

        // No fresh broadcast: fall back to the system's most recent cached results, recording each
        // distinct scan at most once (see [WifiScanGate]).
        val cached = readCachedScanOrNull() ?: return null
        val freshestMicros = cached.data.maxOfOrNull { it.timestamp } ?: return cached
        if (!WifiScanGate.shouldRecord(
                freshestTimestampMicros = freshestMicros,
                lastRecordedTimestampMicros = lastRecordedScanMicros,
                nowElapsedRealtimeNanos = Time.elapsedRealtimeNanos,
                maxAgeNanos = MAX_SCAN_AGE_NANOS,
            )
        ) {
            return null
        }
        lastRecordedScanMicros = freshestMicros
        return cached
    }

    private fun readCachedScanOrNull(): WifiScanData? {
        val results = readScanResultsOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val freshestMicros = results.maxOf { it.timestamp }
        return WifiScanData(Time.nowMillis, freshestMicros * MICROS_TO_NANOS, results)
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

    private companion object {
        private const val MICROS_TO_NANOS = 1_000L

        // A cached scan older than this is considered stale and is not recorded. Two minutes keeps
        // location-tagged Wi-Fi useful (APs are stationary) without resurrecting hours-old scans
        // when Wi-Fi is simply left on without movement.
        private val MAX_SCAN_AGE_NANOS = 2 * 60 * Time.SECOND_IN_NANOSECONDS
    }
}
