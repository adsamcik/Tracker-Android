package com.adsamcik.tracker.shared.preferences.tracking

import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.Clock
import kotlinx.coroutines.flow.Flow

enum class SourcePurpose(val stableName: String) {
	SESSION_CAPTURE("SESSION_CAPTURE"),
	CONTROL("CONTROL"),
	AMBIENT_PRODUCT("AMBIENT_PRODUCT"),
}

enum class SourceQos(val stableCode: Int) {
	OFF(0),
	BATTERY_SAVER(1),
	BALANCED(2),
	RESPONSIVE(3);

	companion object {
		fun fromStableCode(value: Int): SourceQos = entries.singleOrNull { it.stableCode == value }
			?: OFF
	}
}

data class SourcePolicyEffectiveTime(
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
) {
	init {
		require(bootId.isNotBlank()) { "Policy boot identity must not be blank" }
		require(elapsedRealtimeNanos >= 0L) { "Policy elapsed realtime must not be negative" }
	}
}

fun interface SourcePolicyEffectiveTimeProvider {
	fun now(): SourcePolicyEffectiveTime
}

/**
 * Boot-aware effective-time provider. If BOOT_COUNT is unavailable, a process-unique domain is
 * deliberately used so elapsed values can never be joined across an unverified process boundary.
 */
class AndroidSourcePolicyEffectiveTimeProvider(
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val clock: Clock,
) : SourcePolicyEffectiveTimeProvider {
	override fun now(): SourcePolicyEffectiveTime = SourcePolicyEffectiveTime(
		bootId = bootClockDomainProvider.current(),
		elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
		wallTimeMs = clock.currentTimeMillis(),
	)
}

data class SourcePolicy(
	val source: TrackingSourceComponent,
	val enabled: Boolean,
	val qos: SourceQos,
	val locationMinTimeSeconds: Int?,
	val locationMinDistanceMeters: Int?,
	val locationRequiredAccuracyMeters: Int?,
	val captureConsentEpoch: Long?,
	val controlConsentEpoch: Long?,
	val ambientConsentEpoch: Long?,
	val capturePersistenceEligible: Boolean,
	val controlPersistenceEligible: Boolean,
	val ambientPersistenceEligible: Boolean,
	val effectiveTime: SourcePolicyEffectiveTime,
	val policyRevision: Long,
) {
	init {
		require(enabled == (qos != SourceQos.OFF)) {
			"Source enablement and QoS must describe one effective state"
		}
		require(enabled == capturePersistenceEligible) {
			"Enabled session capture must be durably eligible, and disabled capture must not persist"
		}
		require(enabled == (captureConsentEpoch != null)) {
			"Enabled session capture must reference exactly one eligible consent epoch"
		}
		if (source == TrackingSourceComponent.LOCATION) {
			requireNotNull(locationMinTimeSeconds).also { require(it > 0) }
			requireNotNull(locationMinDistanceMeters).also { require(it > 0) }
			requireNotNull(locationRequiredAccuracyMeters).also { require(it > 0) }
		} else {
			require(locationMinTimeSeconds == null)
			require(locationMinDistanceMeters == null)
			require(locationRequiredAccuracyMeters == null)
		}
		require(!capturePersistenceEligible || captureConsentEpoch != null) {
			"Capture persistence requires an eligible capture consent epoch"
		}
		require(!controlPersistenceEligible || controlConsentEpoch != null) {
			"Control persistence requires an eligible control consent epoch"
		}
		require(!ambientPersistenceEligible || ambientConsentEpoch != null) {
			"Ambient persistence requires an eligible ambient consent epoch"
		}
	}

	fun consentEpoch(purpose: SourcePurpose): Long? = when (purpose) {
		SourcePurpose.SESSION_CAPTURE -> captureConsentEpoch
		SourcePurpose.CONTROL -> controlConsentEpoch
		SourcePurpose.AMBIENT_PRODUCT -> ambientConsentEpoch
	}

	fun persistenceEligible(purpose: SourcePurpose): Boolean = when (purpose) {
		SourcePurpose.SESSION_CAPTURE -> capturePersistenceEligible
		SourcePurpose.CONTROL -> controlPersistenceEligible
		SourcePurpose.AMBIENT_PRODUCT -> ambientPersistenceEligible
	}
}

data class SourcePolicySnapshot(
	val revision: Long,
	val policies: Map<TrackingSourceComponent, SourcePolicy>,
) {
	init {
		require(revision > 0L) { "An active policy revision must be positive" }
		require(policies.keys == TrackingSourceComponent.entries.toSet()) {
			"An effective policy snapshot must contain every supported source"
		}
		require(policies.values.all { it.policyRevision == revision }) {
			"Every source must belong to the effective policy revision"
		}
	}

	operator fun get(source: TrackingSourceComponent): SourcePolicy = requireNotNull(policies[source])
}

sealed interface SourcePolicyAuthorityState {
	data object Uninitialized : SourcePolicyAuthorityState
	data class Active(val snapshot: SourcePolicySnapshot) : SourcePolicyAuthorityState
	data class Invalid(val reason: String) : SourcePolicyAuthorityState
}

class ConcurrentSourcePolicyMutationException(expected: Long, actual: Long) :
	IllegalStateException("Source policy revision changed: expected $expected, found $actual")

interface SourcePolicyRepository {
	/** Fail-closed observable authority state. Only [SourcePolicyAuthorityState.Active] is usable. */
	val states: Flow<SourcePolicyAuthorityState>

	suspend fun currentState(): SourcePolicyAuthorityState

	/** One-time import. An incomplete legacy migration must never be bootstrapped as defaults. */
	suspend fun bootstrapFromLegacy(settings: TrackingParamsState): SourcePolicySnapshot

	/**
	 * Reconciles capture policy and the legacy automatic-tracking control intent at an explicit
	 * revision; frequency can never revive a false capture toggle.
	 */
	suspend fun replaceCaptureSettings(
		expectedPolicyRevision: Long,
		settings: TrackingParamsState,
		reason: String,
	): SourcePolicySnapshot

	/** Changes non-capture consent without changing SESSION_CAPTURE eligibility. */
	suspend fun setNonCaptureConsent(
		expectedPolicyRevision: Long,
		source: TrackingSourceComponent,
		purpose: SourcePurpose,
		eligible: Boolean,
		persistenceEligible: Boolean,
		reason: String,
	): SourcePolicySnapshot
}
