package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

internal data class WifiDeviceState(
	val wifiFeatureAvailable: Boolean,
	val fineLocationPermission: Boolean,
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
			if (!state.fineLocationPermission) add(SourceDegradedReason.PERMISSION_MISSING)
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
	override fun wifi(): WifiDeviceState {
		val capabilities = context.trackingPermissionCapabilities()
		return WifiDeviceState(
			wifiFeatureAvailable = capabilities.wifiFeatureAvailable,
			fineLocationPermission = capabilities.preciseLocationGranted,
			locationServicesEnabled = capabilities.locationServicesEnabled,
			deviceIdle = context.getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true,
		)
	}

	override fun cell(): CellDeviceState = CellDeviceState(
		radioFeatureAvailable = context.packageManager.hasSystemFeature(
			telephonyRadioFeatureName(Build.VERSION.SDK_INT),
		),
		fineLocationPermission = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
		readPhoneStatePermission = context.hasPermission(Manifest.permission.READ_PHONE_STATE),
		refreshApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
	)

	private fun Context.hasPermission(permission: String) =
		ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
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

internal fun resolveCellSubscriptionScope(
	requestedSubscriptionIds: Set<Int>,
	activeSubscriptionIds: Set<Int>,
): Set<Int?> = when {
	requestedSubscriptionIds.isNotEmpty() -> requestedSubscriptionIds.mapTo(linkedSetOf()) { it }
	activeSubscriptionIds.isNotEmpty() -> activeSubscriptionIds.mapTo(linkedSetOf()) { it }
	else -> setOf(null)
}

internal class BoundedReplayIdentityGate(private val capacity: Int = DEFAULT_CAPACITY) {
	private val admittedIdentities = linkedSetOf<String>()

	init {
		require(capacity > 0)
	}

	fun shouldAdmit(snapshotIdentity: String): Boolean = snapshotIdentity !in admittedIdentities

	/** Record only after the delivery is known durable (or was durably present already). */
	fun record(snapshotIdentity: String) {
		if (!admittedIdentities.add(snapshotIdentity)) return
		while (admittedIdentities.size > capacity) {
			admittedIdentities.remove(admittedIdentities.first())
		}
	}

	private companion object {
		const val DEFAULT_CAPACITY = 128
	}
}

/**
 * A finite first-evidence budget for provider work that may consume material power.
 * Passive callbacks remain registered after this budget is exhausted or satisfied.
 */
internal class DirectAcquisitionBudget(
	maximumAttempts: Int,
	directCaptureRequested: Boolean,
) {
	private var remainingAttempts = if (directCaptureRequested) maximumAttempts else 0
	private var qualifiedEvidenceReceived = false

	init {
		require(maximumAttempts >= 0)
	}

	val canRequest: Boolean
		get() = remainingAttempts > 0 && !qualifiedEvidenceReceived

	fun consumeRequest(): Boolean {
		if (!canRequest) return false
		remainingAttempts -= 1
		return true
	}

	fun markQualifiedEvidence() {
		qualifiedEvidenceReceived = true
	}
}

/**
 * Keeps only observations whose provider timestamp proves that the item is current enough.
 *
 * Radio APIs can return one collection containing fresh, stale, and timestamp-unknown children.
 * Receipt time is not evidence that an individual child was freshly observed, so admission is
 * intentionally item based and fails closed for missing, non-positive, or future timestamps.
 */
internal fun <T> freshProviderObservations(
	observations: List<T>,
	receivedElapsedRealtimeNanos: Long,
	maximumAgeMs: Long,
	providerTimestampNanos: (T) -> Long?,
): List<T> {
	require(receivedElapsedRealtimeNanos >= 0L)
	require(maximumAgeMs >= 0L)
	val maximumAgeNanos = if (maximumAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else {
		maximumAgeMs * NANOS_PER_MILLISECOND
	}
	return observations.filter { observation ->
		val timestamp = providerTimestampNanos(observation) ?: return@filter false
		timestamp > 0L &&
			timestamp <= receivedElapsedRealtimeNanos &&
			receivedElapsedRealtimeNanos - timestamp <= maximumAgeNanos
	}
}

internal fun shouldReadWifiSnapshot(resultsUpdated: Boolean?): Boolean = resultsUpdated != false

/** A zero is evidence only when the provider confirmed a new delivery containing zero items. */
internal fun shouldAdmitCoverageOnly(
	providerItemCount: Int,
	eligibleItemCount: Int,
	providerDeliveryConfirmedFresh: Boolean,
): Boolean = providerDeliveryConfirmedFresh && providerItemCount == 0 && eligibleItemCount == 0

internal fun shouldScheduleCellRefresh(mode: CellMode, refreshApiAvailable: Boolean): Boolean =
	mode == CellMode.OBSERVE_AND_SPARSE_REFRESH && refreshApiAvailable

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

private const val NANOS_PER_MILLISECOND = 1_000_000L
internal const val WITHHELD_RADIO_IDENTIFIER_TOKEN = ""
