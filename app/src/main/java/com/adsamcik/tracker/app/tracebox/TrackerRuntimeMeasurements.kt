package com.adsamcik.tracker.app.tracebox

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.api.LogCategory
import dev.tracebox.api.LogLevel
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.public
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Low-frequency, payload-free process observations owned by Tracker.
 *
 * Samples are taken only at process startup and foreground/background boundaries, and only while
 * Tracebox's independently controlled performance category is enabled. This is intentionally not
 * a polling or aggregation facility.
 */
internal class TrackerRuntimeMeasurements(
	private val logger: TraceboxLogger,
	private val source: TrackerRuntimeMeasurementSource,
	private val scope: CoroutineScope,
	private val dispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {
	private val foregroundEntries = AtomicLong()

	fun recordStartup() {
		if (!performanceMeasurementsEnabled()) {
			return
		}
		val processElapsedMs = (
			source.elapsedRealtimeMs() - source.processStartedAtElapsedRealtimeMs()
		).coerceAtLeast(0L)
		logger.performanceEvent(
			TrackerTraceboxTemplates.APPLICATION_STARTUP_MEASUREMENT,
			public(processElapsedMs),
			public(source.processCpuTimeMs().coerceAtLeast(0L)),
		)
	}

	override fun onStart(owner: LifecycleOwner) {
		recordResourceSnapshot(
			foreground = true,
			foregroundEntryCount = foregroundEntries.incrementAndGet(),
		)
	}

	override fun onStop(owner: LifecycleOwner) {
		recordResourceSnapshot(
			foreground = false,
			foregroundEntryCount = foregroundEntries.get(),
		)
	}

	private fun recordResourceSnapshot(foreground: Boolean, foregroundEntryCount: Long) {
		if (!performanceMeasurementsEnabled()) {
			return
		}
		scope.launch(dispatcher) {
			if (!performanceMeasurementsEnabled()) {
				return@launch
			}
			val snapshot = try {
				source.resourceSnapshot()
			} catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
				logger.error(error, TrackerTraceboxTemplates.APPLICATION_RESOURCE_MEASUREMENT_FAILED)
				return@launch
			}
			recordBatteryPowerSnapshot(foreground, foregroundEntryCount, snapshot)
			recordMemorySnapshot(foreground, foregroundEntryCount, snapshot)
			recordRestrictionSnapshot(foreground, foregroundEntryCount, snapshot)
			recordLocationCapabilitySnapshot(foreground, foregroundEntryCount, snapshot)
		}
	}

	private fun recordBatteryPowerSnapshot(
		foreground: Boolean,
		foregroundEntryCount: Long,
		snapshot: TrackerResourceSnapshot,
	) {
		logger.performanceEvent(
			TrackerTraceboxTemplates.APPLICATION_BATTERY_POWER_MEASUREMENT,
			public(foreground),
			public(foregroundEntryCount),
			public(snapshot.batteryServiceAvailable),
			public(snapshot.batteryPercent != null),
			public(snapshot.batteryPercent ?: 0),
			public(snapshot.charging),
			public(snapshot.chargeCounterUah != null),
			public(snapshot.chargeCounterUah ?: 0),
			public(snapshot.energyCounterNwh != null),
			public(snapshot.energyCounterNwh ?: 0L),
			public(snapshot.interactive),
			public(snapshot.deviceIdle),
			public(snapshot.powerSave),
		)
	}

	private fun recordMemorySnapshot(
		foreground: Boolean,
		foregroundEntryCount: Long,
		snapshot: TrackerResourceSnapshot,
	) {
		logger.performanceEvent(
			TrackerTraceboxTemplates.APPLICATION_MEMORY_MEASUREMENT,
			public(foreground),
			public(foregroundEntryCount),
			public(snapshot.processPssKiB),
			public(snapshot.javaHeapUsedKiB),
			public(snapshot.nativeHeapAllocatedKiB),
			public(snapshot.trimLevel),
		)
	}

	private fun recordRestrictionSnapshot(
		foreground: Boolean,
		foregroundEntryCount: Long,
		snapshot: TrackerResourceSnapshot,
	) {
		logger.performanceEvent(
			TrackerTraceboxTemplates.APPLICATION_PLATFORM_RESTRICTION_MEASUREMENT,
			public(foreground),
			public(foregroundEntryCount),
			public(snapshot.batteryOptimizationsExempt != null),
			public(snapshot.batteryOptimizationsExempt ?: false),
			public(snapshot.backgroundRestricted != null),
			public(snapshot.backgroundRestricted ?: false),
			public(snapshot.appStandbyBucket != null),
			public(snapshot.appStandbyBucket ?: 0),
		)
	}

	private fun recordLocationCapabilitySnapshot(
		foreground: Boolean,
		foregroundEntryCount: Long,
		snapshot: TrackerResourceSnapshot,
	) {
		logger.performanceEvent(
			TrackerTraceboxTemplates.APPLICATION_LOCATION_CAPABILITY_MEASUREMENT,
			public(foreground),
			public(foregroundEntryCount),
			public(snapshot.locationServiceAvailable),
			public(snapshot.locationServicesEnabled != null),
			public(snapshot.locationServicesEnabled ?: false),
			public(snapshot.gpsProviderAvailable != null),
			public(snapshot.gpsProviderAvailable ?: false),
			public(snapshot.gpsProviderEnabled != null),
			public(snapshot.gpsProviderEnabled ?: false),
			public(snapshot.networkProviderAvailable != null),
			public(snapshot.networkProviderAvailable ?: false),
			public(snapshot.networkProviderEnabled != null),
			public(snapshot.networkProviderEnabled ?: false),
		)
	}

	private fun performanceMeasurementsEnabled(): Boolean =
		logger.isEnabled(LogLevel.DEBUG, LogCategory.PERFORMANCE)
}

internal interface TrackerRuntimeMeasurementSource {
	fun processStartedAtElapsedRealtimeMs(): Long
	fun elapsedRealtimeMs(): Long
	fun processCpuTimeMs(): Long
	fun resourceSnapshot(): TrackerResourceSnapshot
}

internal data class TrackerResourceSnapshot(
	val batteryServiceAvailable: Boolean,
	val batteryPercent: Int?,
	val charging: Boolean,
	val chargeCounterUah: Int?,
	val energyCounterNwh: Long?,
	val interactive: Boolean,
	val deviceIdle: Boolean,
	val powerSave: Boolean,
	val batteryOptimizationsExempt: Boolean?,
	val backgroundRestricted: Boolean?,
	val appStandbyBucket: Int?,
	val locationServiceAvailable: Boolean,
	val locationServicesEnabled: Boolean?,
	val gpsProviderAvailable: Boolean?,
	val gpsProviderEnabled: Boolean?,
	val networkProviderAvailable: Boolean?,
	val networkProviderEnabled: Boolean?,
	val processPssKiB: Long,
	val javaHeapUsedKiB: Long,
	val nativeHeapAllocatedKiB: Long,
	val trimLevel: Int,
)

internal class AndroidTrackerRuntimeMeasurementSource(context: Context) :
	TrackerRuntimeMeasurementSource {
	private val batteryManager = context.getSystemService(BatteryManager::class.java)
	private val powerManager = context.getSystemService(PowerManager::class.java)
	private val activityManager = context.getSystemService(ActivityManager::class.java)
	private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
	private val locationManager = context.getSystemService(LocationManager::class.java)
	private val packageName = context.packageName

	override fun processStartedAtElapsedRealtimeMs(): Long =
		android.os.Process.getStartElapsedRealtime()

	override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

	override fun processCpuTimeMs(): Long = android.os.Process.getElapsedCpuTime()

	override fun resourceSnapshot(): TrackerResourceSnapshot {
		val runtime = Runtime.getRuntime()
		val processInfo = ActivityManager.RunningAppProcessInfo()
		ActivityManager.getMyMemoryState(processInfo)
		val gpsProviderAvailable = providerAvailable(LocationManager.GPS_PROVIDER)
		val networkProviderAvailable = providerAvailable(LocationManager.NETWORK_PROVIDER)
		return TrackerResourceSnapshot(
			batteryServiceAvailable = batteryManager != null,
			batteryPercent = batteryManager
				?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
				?.takeIf { it in 0..100 },
			charging = batteryManager?.isCharging == true,
			chargeCounterUah = batteryManager
				?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
				?.takeIf { it >= 0 },
			energyCounterNwh = batteryManager
				?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
				?.takeIf { it >= 0L },
			interactive = powerManager?.isInteractive == true,
			deviceIdle = powerManager?.isDeviceIdleMode == true,
			powerSave = powerManager?.isPowerSaveMode == true,
			batteryOptimizationsExempt = powerManager
				?.isIgnoringBatteryOptimizations(packageName),
			backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				activityManager?.isBackgroundRestricted
			} else {
				null
			},
			appStandbyBucket = readAppStandbyBucket(),
			locationServiceAvailable = locationManager != null,
			locationServicesEnabled = readLocationServicesEnabled(),
			gpsProviderAvailable = gpsProviderAvailable,
			gpsProviderEnabled = providerEnabled(
				LocationManager.GPS_PROVIDER,
				gpsProviderAvailable,
			),
			networkProviderAvailable = networkProviderAvailable,
			networkProviderEnabled = providerEnabled(
				LocationManager.NETWORK_PROVIDER,
				networkProviderAvailable,
			),
			processPssKiB = Debug.getPss().coerceAtLeast(0L),
			javaHeapUsedKiB = (
				(runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L) / BYTES_PER_KIB
			),
			nativeHeapAllocatedKiB = (
				Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L) / BYTES_PER_KIB
			),
			trimLevel = processInfo.lastTrimLevel,
		)
	}

	private fun readAppStandbyBucket(): Int? {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			return null
		}
		return try {
			usageStatsManager?.appStandbyBucket
		} catch (_: SecurityException) {
			null
		}
	}

	private fun readLocationServicesEnabled(): Boolean? {
		val manager = locationManager ?: return null
		return try {
			LocationManagerCompat.isLocationEnabled(manager)
		} catch (_: SecurityException) {
			null
		}
	}

	private fun providerAvailable(provider: String): Boolean? {
		val manager = locationManager ?: return null
		return try {
			provider in manager.allProviders
		} catch (_: SecurityException) {
			null
		}
	}

	private fun providerEnabled(provider: String, available: Boolean?): Boolean? {
		val manager = locationManager ?: return null
		if (available != true) {
			return null
		}
		return try {
			manager.isProviderEnabled(provider)
		} catch (_: SecurityException) {
			null
		} catch (_: IllegalArgumentException) {
			null
		}
	}

	private companion object {
		const val BYTES_PER_KIB = 1_024L
	}
}
