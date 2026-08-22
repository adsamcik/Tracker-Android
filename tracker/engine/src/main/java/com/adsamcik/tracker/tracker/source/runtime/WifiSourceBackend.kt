package com.adsamcik.tracker.tracker.source.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface WifiBackendEvent {
	data class Results(
		val snapshot: WifiBackendSnapshot?,
		val resultsUpdated: Boolean?,
		val receivedElapsedRealtimeNanos: Long,
		val receivedWallTimeMs: Long,
	) : WifiBackendEvent

	data object IdleStateChanged : WifiBackendEvent
}

internal data class WifiBackendAccessPoint(
	val bssid: String,
	val frequencyMhz: Int,
	val signalLevelDbm: Int,
	val platformTimestampMicros: Long,
) {
	val providerTimestampNanos: Long?
		get() = platformTimestampMicros
			.takeIf { it > 0L && it <= Long.MAX_VALUE / MICROS_TO_NANOS }
			?.times(MICROS_TO_NANOS)

	private companion object { const val MICROS_TO_NANOS = 1_000L }
}

internal data class WifiBackendSnapshot(val accessPoints: List<WifiBackendAccessPoint>) {
	val freshestTimestampNanos: Long?
		get() = accessPoints.mapNotNull(WifiBackendAccessPoint::providerTimestampNanos).maxOrNull()
}

internal enum class WifiRequestOutcome { ACCEPTED, THROTTLED, PERMISSION_BLOCKED, PROVIDER_FAILED }

@Singleton
internal class AndroidWifiSourceBackend @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val context = context.applicationContext
	private val wifiManager = context.getSystemService(WifiManager::class.java)
	private var receiver: BroadcastReceiver? = null

	fun start(callback: (WifiBackendEvent) -> Unit): Boolean {
		if (receiver != null || wifiManager == null) return wifiManager != null
		val next = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				when (intent.action) {
					WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
						val resultsUpdated = intent.getResultsUpdatedOrNull()
						callback(
							WifiBackendEvent.Results(
								snapshot = if (shouldReadWifiSnapshot(resultsUpdated)) readSnapshot() else null,
								resultsUpdated = resultsUpdated,
								receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
								receivedWallTimeMs = System.currentTimeMillis(),
							),
						)
					}
					PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> callback(WifiBackendEvent.IdleStateChanged)
				}
			}
		}
		return runCatching {
			ContextCompat.registerReceiver(
				context,
				next,
				IntentFilter().apply {
					addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
					addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
				},
				ContextCompat.RECEIVER_NOT_EXPORTED,
			)
			receiver = next
			true
		}.getOrDefault(false)
	}

	fun stop(): Boolean {
		val active = receiver ?: return true
		return runCatching { context.unregisterReceiver(active); receiver = null; true }
			.getOrElse { receiver = null; false }
	}

	@Suppress("DEPRECATION")
	fun requestScan(): WifiRequestOutcome = try {
		if (wifiManager?.startScan() == true) WifiRequestOutcome.ACCEPTED else WifiRequestOutcome.THROTTLED
	} catch (_: SecurityException) {
		WifiRequestOutcome.PERMISSION_BLOCKED
	} catch (_: RuntimeException) {
		WifiRequestOutcome.PROVIDER_FAILED
	}

	fun readSnapshot(): WifiBackendSnapshot? = try {
		wifiManager?.scanResults
			?.map { it.toBackendAccessPoint() }
			?.let(::WifiBackendSnapshot)
	} catch (_: SecurityException) {
		null
	} catch (_: RuntimeException) {
		null
	}

	private fun Intent.getResultsUpdatedOrNull(): Boolean? =
		if (hasExtra(WifiManager.EXTRA_RESULTS_UPDATED)) {
			getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
		} else {
			null
		}

	private fun ScanResult.toBackendAccessPoint() = WifiBackendAccessPoint(
		bssid = BSSID.orEmpty(),
		frequencyMhz = frequency,
		signalLevelDbm = level,
		platformTimestampMicros = timestamp.coerceAtLeast(0L),
	)
}
