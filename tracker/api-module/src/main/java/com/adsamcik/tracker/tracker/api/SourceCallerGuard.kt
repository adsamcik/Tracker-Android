package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose as CanonicalTrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource as CanonicalTrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity as CanonicalSourcePurpose

/**
 * Distinguishes unchanged service redelivery from recovery and policy changes that require fresh
 * acceptance.
 */
enum class SourceCallerReplayKind {
	/** Exact prepared intent delivered to the foreground service. */
	FOREGROUND_SERVICE_DELIVERY,
	/** Android redelivery of the unchanged prepared manifest and lease vector. */
	ACTIVE_REDELIVERY,
	/** Process recovery validates the old manifest before issuing any replacement acceptance. */
	PROCESS_RECOVERY,
	/** Never replayable: policy changes require a fresh acceptance and reference. */
	POLICY_RECONCILIATION,
	@Deprecated(
		message = "Use FOREGROUND_SERVICE_DELIVERY",
		replaceWith = ReplaceWith("SourceCallerReplayKind.FOREGROUND_SERVICE_DELIVERY"),
	)
	FOREGROUND_SERVICE,
	@Deprecated(
		message = "Use ACTIVE_REDELIVERY",
		replaceWith = ReplaceWith("SourceCallerReplayKind.ACTIVE_REDELIVERY"),
	)
	RESTART,
	@Deprecated(
		message = "Use PROCESS_RECOVERY",
		replaceWith = ReplaceWith("SourceCallerReplayKind.PROCESS_RECOVERY"),
	)
	RECOVERY,
	;

	@Suppress("DEPRECATION")
	val canonicalKind: SourceCallerReplayKind
		get() = when (this) {
			FOREGROUND_SERVICE_DELIVERY,
			ACTIVE_REDELIVERY,
			PROCESS_RECOVERY,
			POLICY_RECONCILIATION,
			-> this
			FOREGROUND_SERVICE -> FOREGROUND_SERVICE_DELIVERY
			RESTART -> ACTIVE_REDELIVERY
			RECOVERY -> PROCESS_RECOVERY
		}
}

/** Identifies the exact logical session manifest a captured demand is allowed to use. */
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

/** A caller request to accept, retire or replay an exact set of source-purpose demands. */
sealed interface SourceCallerRequest {
	val requestedDemandIdentities: Set<SourceCallerDemandIdentity>

	/** Manual capture request bound to the selected sources and one exact session manifest. */
	data class ManualSessionStart(
		val requestedCapturedSources: Set<CanonicalTrackingSource>,
		val manifestIdentity: SourceCallerManifestIdentity,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		/** Java-friendly factories for this request without creating accepted demand authority. */
		companion object {
			/**
			 * Constructs this request from its declared identities; acceptance still requires the caller
			 * guard.
			 */
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

	/** Automatic capture request that separately declares its Activity control dependencies. */
	data class AutomaticSessionStart(
		val requestedCapturedSources: Set<CanonicalTrackingSource>,
		val declaredControlDependencies: Set<CanonicalTrackingSource>,
		val manifestIdentity: SourceCallerManifestIdentity,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		/** Java-friendly factories for this request without creating accepted demand authority. */
		companion object {
			/**
			 * Constructs this request from its declared identities; acceptance still requires the caller
			 * guard.
			 */
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

	/**
	 * Recovery request that preserves the original capture set and manifest before any replacement
	 * acceptance.
	 */
	data class RecoverySessionStart(
		val requestedCapturedSources: Set<CanonicalTrackingSource>,
		val manifestIdentity: SourceCallerManifestIdentity,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		/** Java-friendly factories for this request without creating accepted demand authority. */
		companion object {
			/**
			 * Constructs this request from its declared identities; acceptance still requires the caller
			 * guard.
			 */
			@JvmStatic
			fun create(
				requestedCapturedSources: Set<CanonicalTrackingSource>,
				manifestIdentity: SourceCallerManifestIdentity,
				requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
			): RecoverySessionStart = RecoverySessionStart(
				requestedCapturedSources,
				manifestIdentity,
				requestedDemandIdentities,
			)
		}
	}

	/** Sessionless CONTROL or all-day mutation for the specified source and exact lease vector. */
	data class PurposeOwnerMutation(
		val source: CanonicalTrackingSource,
		val purpose: CanonicalTrackingPurpose,
		val enabled: Boolean,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		init {
			require(purpose == CanonicalTrackingPurpose.CONTROL ||
				purpose == CanonicalTrackingPurpose.AMBIENT_PRODUCT)
			require(source.supports(purpose))
		}

		/** Java-friendly factories for this request without creating accepted demand authority. */
		companion object {
			/**
			 * Constructs this request from its declared identities; acceptance still requires the caller
			 * guard.
			 */
			@JvmStatic
			fun create(
				source: CanonicalTrackingSource,
				purpose: CanonicalTrackingPurpose,
				enabled: Boolean,
				requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
			): PurposeOwnerMutation = PurposeOwnerMutation(
				source,
				purpose,
				enabled,
				requestedDemandIdentities,
			)
		}
	}

	/** Retires an accepted sessionless purpose reference without requesting new demand. */
	data class PurposeOwnerRetirement(
		val source: CanonicalTrackingSource,
		val purpose: CanonicalTrackingPurpose,
		val reference: SourceCallerReplayReference,
	) : SourceCallerRequest {
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity> = emptySet()

		init {
			require(purpose == CanonicalTrackingPurpose.CONTROL ||
				purpose == CanonicalTrackingPurpose.AMBIENT_PRODUCT)
			require(source.supports(purpose))
		}

		/** Java-friendly factories for this request without creating accepted demand authority. */
		companion object {
			/**
			 * Constructs this request from its declared identities; acceptance still requires the caller
			 * guard.
			 */
			@JvmStatic
			fun create(
				source: CanonicalTrackingSource,
				purpose: CanonicalTrackingPurpose,
				reference: SourceCallerReplayReference,
			): PurposeOwnerRetirement = PurposeOwnerRetirement(source, purpose, reference)
		}
	}

	/** Compatibility request for one independently enabled all-day source. */
	data class Ambient(
		val source: CanonicalTrackingSource,
		val enabled: Boolean = false,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		/** Java-friendly factories for this request without creating accepted demand authority. */
		companion object {
			/**
			 * Constructs this request from its declared identities; acceptance still requires the caller
			 * guard.
			 */
			@JvmStatic
			@JvmOverloads
			fun create(
				source: CanonicalTrackingSource,
				requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
				enabled: Boolean = false,
			): Ambient = Ambient(source, enabled, requestedDemandIdentities)
		}
	}

	/**
	 * Reuses a stored acceptance only for the declared purpose, replay kind and unchanged demand
	 * identities.
	 */
	data class Replay(
		val replayKind: SourceCallerReplayKind,
		val reference: SourceCallerReplayReference,
		val purpose: CanonicalTrackingPurpose,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		/** Java-friendly factories for this request without creating accepted demand authority. */
		companion object {
			/**
			 * Constructs this request from its declared identities; acceptance still requires the caller
			 * guard.
			 */
			@JvmStatic
			fun create(
				replayKind: SourceCallerReplayKind,
				reference: SourceCallerReplayReference,
				purpose: CanonicalTrackingPurpose,
				requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
			): Replay = Replay(
				replayKind.canonicalKind,
				reference,
				purpose,
				requestedDemandIdentities,
			)
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

/** The authority, selection or replay mismatch that prevented source demand acceptance. */
enum class SourceCallerRejectionReason {
	ZERO_CAPTURE_SOURCE_REQUEST,
	AUTOMATIC_CONTROL_SET_MISMATCH,
	AUTOMATIC_CONTROL_DISABLED,
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
	AUTHORITY_INVARIANT_VIOLATION,
	AUTHORITY_PERSISTENCE_UNAVAILABLE,
	AUTHORITY_STORAGE_UNAVAILABLE,
	REPLAY_AUTHORITY_UNAVAILABLE,
	REPLAY_AUTHORITY_CORRUPT,
	REPLAY_AUTHORITY_RETIRED,
	REPLAY_PURPOSE_MISMATCH,
	REPLAY_AUTHORITY_ESCALATION,
	REPLAY_AUTHORITY_DOWNGRADE,
	REPLAY_AUTHORITY_MISMATCH,
	REPLAY_KIND_NOT_PERMITTED,
	REPLAY_KIND_REQUIRES_FRESH_ACCEPTANCE,
}

val SourceCallerRejectionReason.isRetryable: Boolean
	get() = this in setOf(
		SourceCallerRejectionReason.AUTHORITY_PERSISTENCE_UNAVAILABLE,
		SourceCallerRejectionReason.AUTHORITY_STORAGE_UNAVAILABLE,
	)

/**
 * Rejected caller authority with the affected source/purpose and any actionable availability details.
 */
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

/** Whether the caller obtained an accepted demand receipt or a typed rejection. */
sealed interface SourceCallerGuardResult {
	/** The caller was accepted for exactly the identities in the returned receipt. */
	data class Permitted(
		val receipt: SourceCallerAcceptanceReceipt,
	) : SourceCallerGuardResult

	/** No new demand authority was granted; the rejection identifies the failed check. */
	data class Rejected(
		val rejection: SourceCallerGuardRejection,
	) : SourceCallerGuardResult
}

/**
 * Validates source-purpose authority before requests reach the broker; copying a receipt cannot grant
 * authority.
 */
fun interface SourceCallerGuard {
	/**
	 * The caller supplies only its request. Implementations acquire current authority internally,
	 * persist accepted authority before returning, and never start or register a provider.
	 */
	suspend fun accept(request: SourceCallerRequest): SourceCallerGuardResult
}
