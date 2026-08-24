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
import kotlinx.coroutines.CancellationException

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
internal class AndroidWifiSourceBackend internal constructor(
	private val wifiManager: WifiManager?,
	private val registerReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
	private val unregisterReceiver: (BroadcastReceiver) -> Unit,
) {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(
		wifiManager = context.applicationContext.getSystemService(WifiManager::class.java),
		registerReceiver = { receiver, filter ->
			ContextCompat.registerReceiver(
				context.applicationContext,
				receiver,
				filter,
				ContextCompat.RECEIVER_NOT_EXPORTED,
			)
			Unit
		},
		unregisterReceiver = context.applicationContext::unregisterReceiver,
	)

	private var receiver: BroadcastReceiver? = null

	@Synchronized
	fun start(callback: (WifiBackendEvent) -> Unit): Boolean {
		if (receiver != null || wifiManager == null) return false
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
		// Retain the exact provisional receiver before crossing into Android. Registration can
		// side-effect and then throw; cleanup must remain a separate, runtime-ordered operation.
		receiver = next
		return try {
			registerReceiver(
				next,
				IntentFilter().apply {
					addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
					addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
				},
			)
			true
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			false
		}
	}

	@Synchronized
	fun stop(): Boolean {
		val active = receiver ?: return true
		return try {
			unregisterReceiver(active)
			if (receiver === active) receiver = null
			true
		} catch (_: IllegalArgumentException) {
			// Android's exact "receiver not registered" result proves there is no remaining
			// provider work for this handle, including a pre-side-effect registration failure.
			if (receiver === active) receiver = null
			true
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			false
		}
	}

	@Suppress("DEPRECATION")
	fun requestScan(): WifiRequestOutcome = try {
		if (wifiManager?.startScan() == true) WifiRequestOutcome.ACCEPTED else WifiRequestOutcome.THROTTLED
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: SecurityException) {
		WifiRequestOutcome.PERMISSION_BLOCKED
	} catch (_: RuntimeException) {
		WifiRequestOutcome.PROVIDER_FAILED
	}

	fun readSnapshot(): WifiBackendSnapshot? = try {
		wifiManager?.scanResults
			?.map { it.toBackendAccessPoint() }
			?.let(::WifiBackendSnapshot)
	} catch (cancelled: CancellationException) {
		throw cancelled
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
