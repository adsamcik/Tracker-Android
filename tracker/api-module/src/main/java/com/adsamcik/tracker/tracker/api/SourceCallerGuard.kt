package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose as CanonicalTrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource as CanonicalTrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity as CanonicalSourcePurpose

enum class SourceCallerReplayKind {
	FOREGROUND_SERVICE,
	RESTART,
	RECOVERY,
}

data class SourceCallerManifestIdentity(
	val logicalTrackingId: String,
	val manifestRevision: Long,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(manifestRevision > 0L)
	}
}

/**
 * Exact demand requested by a caller. [purposeLeaseIdentity] is the canonical purpose authority
 * vector; the manifest identity exists only for session-capture demand.
 */
data class SourceCallerDemandIdentity(
	val purposeLeaseIdentity: TrackingPurposeLeaseIdentity,
	val manifestIdentity: SourceCallerManifestIdentity?,
) {
	val sourcePurpose: CanonicalSourcePurpose
		get() = purposeLeaseIdentity.sourcePurpose

	init {
		when (purposeLeaseIdentity.purpose) {
			CanonicalTrackingPurpose.SESSION_CAPTURE -> require(manifestIdentity != null) {
				"Session-capture demand requires a manifest identity"
			}
			CanonicalTrackingPurpose.CONTROL,
			CanonicalTrackingPurpose.AMBIENT_PRODUCT,
			-> require(manifestIdentity == null) {
				"Non-capture demand must remain sessionless"
			}
		}
	}
}

sealed interface SourceCallerRequest {
	val requestedDemandIdentities: Set<SourceCallerDemandIdentity>

	data class ManualSessionStart(
		val requestedCapturedSources: Set<CanonicalTrackingSource>,
		val manifestIdentity: SourceCallerManifestIdentity,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		companion object {
			@JvmStatic
			fun create(
				requestedCapturedSources: Set<CanonicalTrackingSource>,
				manifestIdentity: SourceCallerManifestIdentity,
				requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
			): ManualSessionStart = ManualSessionStart(
				requestedCapturedSources,
				manifestIdentity,
				requestedDemandIdentities,
			)
		}
	}

	data class AutomaticSessionStart(
		val requestedCapturedSources: Set<CanonicalTrackingSource>,
		val declaredControlDependencies: Set<CanonicalTrackingSource>,
		val manifestIdentity: SourceCallerManifestIdentity,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		companion object {
			@JvmStatic
			fun create(
				requestedCapturedSources: Set<CanonicalTrackingSource>,
				declaredControlDependencies: Set<CanonicalTrackingSource>,
				manifestIdentity: SourceCallerManifestIdentity,
				requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
			): AutomaticSessionStart = AutomaticSessionStart(
				requestedCapturedSources,
				declaredControlDependencies,
				manifestIdentity,
				requestedDemandIdentities,
			)
		}
	}

	data class Ambient(
		val source: CanonicalTrackingSource,
		val enabled: Boolean = false,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		companion object {
			@JvmStatic
			@JvmOverloads
			fun create(
				source: CanonicalTrackingSource,
				requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
				enabled: Boolean = false,
			): Ambient = Ambient(source, enabled, requestedDemandIdentities)
		}
	}

	data class Replay(
		val replayKind: SourceCallerReplayKind,
		val reference: SourceCallerReplayReference,
		val purpose: CanonicalTrackingPurpose,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		companion object {
			@JvmStatic
			fun create(
				replayKind: SourceCallerReplayKind,
				reference: SourceCallerReplayReference,
				purpose: CanonicalTrackingPurpose,
				requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
			): Replay = Replay(replayKind, reference, purpose, requestedDemandIdentities)
		}
	}
}

/** Java-friendly opaque lookup token. Constructing a value does not create accepted authority. */
data class SourceCallerReplayReference(
	val value: String,
) {
	init {
		require(value.isNotBlank())
	}
}

enum class SourceCallerRejectionReason {
	ZERO_CAPTURE_SOURCE_REQUEST,
	AUTOMATIC_CONTROL_SET_MISMATCH,
	MISSING_DEMAND_IDENTITY,
	UNDECLARED_DEMAND,
	DUPLICATE_DEMAND_IDENTITY,
	SESSION_MANIFEST_MISMATCH,
	AMBIENT_DISABLED,
	AMBIENT_SOURCE_NOT_SUPPORTED,
	AUTOMATIC_CONTROL_UNAVAILABLE,
	AMBIENT_SOURCE_UNAVAILABLE,
	READINESS_AUTHORITY_MISMATCH,
	UNBOUND_EXECUTION_AUTHORITY,
	DEMAND_AUTHORITY_UNAVAILABLE,
	STALE_MANIFEST_IDENTITY,
	STALE_POLICY_REVISION,
	STALE_CONSENT_EPOCH,
	STALE_COLLECTED_DATA_EPOCH,
	STALE_RETENTION_BOUNDARY,
	STALE_ROLLOUT_REVISION,
	STALE_EXECUTION_REVISION,
	STALE_OWNER_CAS_TOKEN,
	AUTHORITY_PERSISTENCE_UNAVAILABLE,
	REPLAY_AUTHORITY_UNAVAILABLE,
	REPLAY_PURPOSE_MISMATCH,
	REPLAY_AUTHORITY_ESCALATION,
	REPLAY_AUTHORITY_DOWNGRADE,
	REPLAY_AUTHORITY_MISMATCH,
}

data class SourceCallerGuardRejection(
	val reason: SourceCallerRejectionReason,
	val source: CanonicalTrackingSource? = null,
	val purpose: CanonicalTrackingPurpose? = null,
	val automaticUnavailableReason: AutomaticTrackingUnavailableReason? = null,
	val ambientAvailability: AmbientSourceOperationalAvailability? = null,
)

/**
 * Informational receipt only. Constructing or copying it grants no demand, provider, replay, or
 * broker authority; the engine retains the opaque accepted capability in its trusted repository.
 */
class SourceCallerAcceptanceReceipt(
	val reference: SourceCallerReplayReference,
	permittedDemandIdentities: Set<SourceCallerDemandIdentity>,
) {
	val permittedDemandIdentities = permittedDemandIdentities.toSet()

	override fun equals(other: Any?): Boolean =
		this === other ||
			other is SourceCallerAcceptanceReceipt &&
			reference == other.reference &&
			permittedDemandIdentities == other.permittedDemandIdentities

	override fun hashCode(): Int =
		31 * reference.hashCode() + permittedDemandIdentities.hashCode()

	override fun toString(): String =
		"SourceCallerAcceptanceReceipt(reference=$reference, " +
			"permittedDemandIdentities=$permittedDemandIdentities)"
}

sealed interface SourceCallerGuardResult {
	data class Permitted(
		val receipt: SourceCallerAcceptanceReceipt,
	) : SourceCallerGuardResult

	data class Rejected(
		val rejection: SourceCallerGuardRejection,
	) : SourceCallerGuardResult
}

fun interface SourceCallerGuard {
	/**
	 * The caller supplies only its request. Implementations acquire current authority internally,
	 * persist accepted authority before returning, and never start or register a provider.
	 */
	suspend fun accept(request: SourceCallerRequest): SourceCallerGuardResult
}
