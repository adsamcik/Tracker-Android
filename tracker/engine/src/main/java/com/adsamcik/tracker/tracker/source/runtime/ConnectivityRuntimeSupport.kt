package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

internal data class WifiDeviceState(
	val apiLevel: Int,
	val wifiFeatureAvailable: Boolean,
	val fineLocationPermission: Boolean,
	val nearbyWifiPermission: Boolean,
	val locationServicesEnabled: Boolean,
	val deviceIdle: Boolean,
)

internal data class WifiPlanApplication(
	val plan: WifiPlan,
	val status: SourceApplyStatus,
	val reasons: Set<SourceDegradedReason> = emptySet(),
	val activeAttemptsDeferred: Boolean = false,
)

internal object WifiPrerequisiteEvaluator {
	fun evaluate(plan: WifiPlan, state: WifiDeviceState): WifiPlanApplication {
		if (!plan.enabled) return WifiPlanApplication(plan, SourceApplyStatus.APPLIED)
		val blockers = buildSet {
			if (!state.wifiFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
			if (!state.fineLocationPermission ||
				(state.apiLevel >= Build.VERSION_CODES.TIRAMISU && !state.nearbyWifiPermission)
			) add(SourceDegradedReason.PERMISSION_MISSING)
			if (!state.locationServicesEnabled) add(SourceDegradedReason.PROVIDER_UNAVAILABLE)
		}
		if (blockers.isNotEmpty()) return WifiPlanApplication(plan, SourceApplyStatus.BLOCKED, blockers)
		val deferred = plan.mode == WifiMode.ACTIVE_ATTEMPTS && state.deviceIdle
		return WifiPlanApplication(
			plan,
			if (deferred) SourceApplyStatus.DEGRADED else SourceApplyStatus.APPLIED,
			if (deferred) setOf(SourceDegradedReason.DOZE) else emptySet(),
			activeAttemptsDeferred = deferred,
		)
	}
}

internal data class CellDeviceState(
	val apiLevel: Int,
	val radioFeatureAvailable: Boolean,
	val fineLocationPermission: Boolean,
	val readPhoneStatePermission: Boolean,
	val refreshApiAvailable: Boolean,
)

internal data class CellPlanApplication(
	val plan: CellPlan,
	val status: SourceApplyStatus,
	val reasons: Set<SourceDegradedReason> = emptySet(),
)

internal object CellPrerequisiteEvaluator {
	fun evaluate(plan: CellPlan, state: CellDeviceState): CellPlanApplication {
		if (!plan.enabled) return CellPlanApplication(plan, SourceApplyStatus.APPLIED)
		val blockers = buildSet {
			if (!state.radioFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
			if (!state.fineLocationPermission || !state.readPhoneStatePermission) {
				add(SourceDegradedReason.PERMISSION_MISSING)
			}
		}
		if (blockers.isNotEmpty()) return CellPlanApplication(plan, SourceApplyStatus.BLOCKED, blockers)
		val degraded = plan.mode == CellMode.OBSERVE_AND_SPARSE_REFRESH && !state.refreshApiAvailable
		return CellPlanApplication(
			plan,
			if (degraded) SourceApplyStatus.DEGRADED else SourceApplyStatus.APPLIED,
			if (degraded) setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE) else emptySet(),
		)
	}
}

internal interface ConnectivityDeviceStateProvider {
	fun wifi(): WifiDeviceState
	fun cell(): CellDeviceState
}

@Singleton
internal class AndroidConnectivityDeviceStateProvider @Inject constructor(
	@ApplicationContext private val context: Context,
) : ConnectivityDeviceStateProvider {
	override fun wifi(): WifiDeviceState = WifiDeviceState(
		apiLevel = Build.VERSION.SDK_INT,
		wifiFeatureAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
		fineLocationPermission = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
		nearbyWifiPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
			context.hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES),
		locationServicesEnabled = context.locationServicesEnabled(),
		deviceIdle = context.getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true,
	)

	override fun cell(): CellDeviceState = CellDeviceState(
		apiLevel = Build.VERSION.SDK_INT,
		radioFeatureAvailable = context.packageManager.hasSystemFeature(
			telephonyRadioFeatureName(Build.VERSION.SDK_INT),
		),
		fineLocationPermission = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
		readPhoneStatePermission = context.hasPermission(Manifest.permission.READ_PHONE_STATE),
		refreshApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
	)

	private fun Context.hasPermission(permission: String) =
		ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

	@Suppress("DEPRECATION")
	private fun Context.locationServicesEnabled(): Boolean =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			getSystemService(LocationManager::class.java)?.isLocationEnabled == true
		} else {
			Settings.Secure.getInt(
				contentResolver,
				Settings.Secure.LOCATION_MODE,
				Settings.Secure.LOCATION_MODE_OFF,
			) != Settings.Secure.LOCATION_MODE_OFF
		}
}

@SuppressLint("InlinedApi")
internal fun telephonyRadioFeatureName(apiLevel: Int): String =
	if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
		PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS
	} else {
		PackageManager.FEATURE_TELEPHONY
	}

internal data class RuntimeBackoffState(val consecutiveFailures: Int = 0) {
	fun succeeded() = RuntimeBackoffState()
	fun failed() = RuntimeBackoffState((consecutiveFailures + 1).coerceAtMost(MAX_FAILURES))
	fun delayMs(policy: RetryBackoff): Long {
		if (consecutiveFailures == 0) return 0L
		val scaled = policy.initialDelayMs.toDouble() *
			policy.multiplier.pow((consecutiveFailures - 1).toDouble())
		return scaled.coerceAtMost(policy.maximumDelayMs.toDouble()).toLong()
	}

	private companion object { const val MAX_FAILURES = 30 }
}

internal fun stableIdentifierToken(namespace: String, raw: String): String {
	val digest = MessageDigest.getInstance("SHA-256")
		.digest("$namespace:$raw".toByteArray(Charsets.UTF_8))
	return digest.take(16).joinToString("") { "%02x".format(it) }
}

internal fun resolveCellSubscriptionScope(
	requestedSubscriptionIds: Set<Int>,
	activeSubscriptionIds: Set<Int>,
): Set<Int?> = when {
	requestedSubscriptionIds.isNotEmpty() -> requestedSubscriptionIds.mapTo(linkedSetOf()) { it }
	activeSubscriptionIds.isNotEmpty() -> activeSubscriptionIds.mapTo(linkedSetOf()) { it }
	else -> setOf(null)
}

internal object ConnectivitySnapshotGate {
	fun shouldAdmitWifi(
		fingerprint: String,
		lastFingerprint: String?,
		receivedElapsedNanos: Long,
		lastAdmittedElapsedNanos: Long,
		dedupeWindowMs: Long,
	): Boolean = fingerprint != lastFingerprint ||
		receivedElapsedNanos - lastAdmittedElapsedNanos >= dedupeWindowMs * NANOS_PER_MILLISECOND

	fun shouldAdmitCell(
		fingerprint: String,
		providerTimestampNanos: Long?,
		lastFingerprint: String?,
		lastProviderTimestampNanos: Long?,
	): Boolean = fingerprint != lastFingerprint || providerTimestampNanos != lastProviderTimestampNanos

	private const val NANOS_PER_MILLISECOND = 1_000_000L
}

internal fun nextAttemptDeadlineMs(
	nowElapsedRealtimeMs: Long,
	lastAttemptElapsedRealtimeMs: Long?,
	minimumIntervalMs: Long,
	backoffDelayMs: Long,
): Long = maxOf(
	nowElapsedRealtimeMs,
	lastAttemptElapsedRealtimeMs?.plus(minimumIntervalMs) ?: nowElapsedRealtimeMs,
	nowElapsedRealtimeMs + backoffDelayMs,
)
