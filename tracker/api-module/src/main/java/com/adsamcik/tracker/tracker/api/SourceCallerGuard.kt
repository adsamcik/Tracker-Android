package com.adsamcik.tracker.tracker.api

enum class SourceCallerStartKind {
	MANUAL,
	AUTOMATIC,
}

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
 * Exact authority presented for one requested demand. It describes no provider or acquisition
 * configuration; runtime and broker callers may only map an accepted identity to those effects.
 */
data class SourceCallerDemandIdentity(
	val sourcePurpose: TrackingSourcePurposeIdentity,
	val manifestIdentity: SourceCallerManifestIdentity?,
	val sourcePolicyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
) {
	init {
		require(sourcePolicyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
	}
}

class SourceCallerAuthoritySnapshot(
	currentDemandIdentities: Set<SourceCallerDemandIdentity>,
	val purposeAvailability: TrackingPurposeAvailabilitySnapshot =
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
) {
	val currentDemandIdentities = currentDemandIdentities.toSet()

	init {
		require(
			this.currentDemandIdentities
				.map(SourceCallerDemandIdentity::sourcePurpose)
				.distinct()
				.size == this.currentDemandIdentities.size,
		) {
			"Current caller authority must contain at most one identity per demand"
		}
	}
}

sealed interface SourceCallerRequest {
	val purpose: TrackingPurpose
	val requestedDemandIdentities: Set<SourceCallerDemandIdentity>

	data class SessionStart(
		val startKind: SourceCallerStartKind,
		val requestedCapturedSources: Set<TrackingCaptureSource>,
		val declaredControlDependencies: Set<TrackingCaptureSource> = emptySet(),
		val manifestIdentity: SourceCallerManifestIdentity,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		override val purpose = TrackingPurpose.SESSION_CAPTURE
	}

	data class Ambient(
		val source: AmbientTrackingSource,
		val enabled: Boolean = false,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest {
		override val purpose = TrackingPurpose.AMBIENT_PRODUCT
	}

	data class Replay(
		val replayKind: SourceCallerReplayKind,
		override val purpose: TrackingPurpose,
		override val requestedDemandIdentities: Set<SourceCallerDemandIdentity>,
	) : SourceCallerRequest
}

enum class AcceptedSourceCallerOrigin {
	MANUAL,
	AUTOMATIC,
	AMBIENT,
}

class AcceptedSourceCallerAuthority(
	val origin: AcceptedSourceCallerOrigin,
	val purpose: TrackingPurpose,
	permittedDemandIdentities: Set<SourceCallerDemandIdentity>,
) {
	val permittedDemandIdentities = permittedDemandIdentities.toSet()

	init {
		require(this.permittedDemandIdentities.isNotEmpty())
		require(
			this.permittedDemandIdentities
				.map(SourceCallerDemandIdentity::sourcePurpose)
				.distinct()
				.size == this.permittedDemandIdentities.size,
		) {
			"Accepted caller authority must contain exactly one identity per demand"
		}
		when (origin) {
			AcceptedSourceCallerOrigin.MANUAL -> {
				require(purpose == TrackingPurpose.SESSION_CAPTURE)
				require(this.permittedDemandIdentities.all { identity ->
					identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE &&
						identity.manifestIdentity != null
				})
				require(this.permittedDemandIdentities.map { identity -> identity.manifestIdentity }
					.distinct().size == 1)
			}
			AcceptedSourceCallerOrigin.AUTOMATIC -> {
				require(purpose == TrackingPurpose.SESSION_CAPTURE)
				require(this.permittedDemandIdentities.any { identity ->
					identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE &&
						identity.manifestIdentity != null
				})
				require(this.permittedDemandIdentities.all { identity ->
					when (identity.sourcePurpose.purpose) {
						TrackingPurpose.SESSION_CAPTURE ->
							identity.manifestIdentity != null
						TrackingPurpose.CONTROL ->
							identity.sourcePurpose.source == TrackingCaptureSource.ACTIVITY &&
								identity.manifestIdentity == null
						TrackingPurpose.AMBIENT_PRODUCT -> false
					}
				})
				require(this.permittedDemandIdentities
					.filter { identity ->
						identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE
					}
					.map { identity -> identity.manifestIdentity }
					.distinct().size == 1)
			}
			AcceptedSourceCallerOrigin.AMBIENT -> {
				require(purpose == TrackingPurpose.AMBIENT_PRODUCT)
				require(this.permittedDemandIdentities.single().let { identity ->
					identity.sourcePurpose.purpose == TrackingPurpose.AMBIENT_PRODUCT &&
						identity.manifestIdentity == null &&
						identity.sourcePurpose.source.toAmbientTrackingSourceOrNull() != null
				})
			}
		}
	}

	override fun equals(other: Any?): Boolean =
		this === other ||
			other is AcceptedSourceCallerAuthority &&
			origin == other.origin &&
			purpose == other.purpose &&
			permittedDemandIdentities == other.permittedDemandIdentities

	override fun hashCode(): Int {
		var result = origin.hashCode()
		result = 31 * result + purpose.hashCode()
		result = 31 * result + permittedDemandIdentities.hashCode()
		return result
	}

	override fun toString(): String =
		"AcceptedSourceCallerAuthority(origin=$origin, purpose=$purpose, " +
			"permittedDemandIdentities=$permittedDemandIdentities)"
}

data class SourceCallerGuardInput(
	val request: SourceCallerRequest,
	val currentAuthority: SourceCallerAuthoritySnapshot,
	/**
	 * Trusted, durably restored result of an earlier acceptance. Replay callers must not construct
	 * or widen this value from an Android Intent or recovery hint.
	 */
	val previouslyAcceptedAuthority: AcceptedSourceCallerAuthority? = null,
)

enum class SourceCallerRejectionReason {
	ZERO_CAPTURE_SOURCE_REQUEST,
	MANUAL_CONTROL_NOT_ALLOWED,
	CONTROL_SOURCE_NOT_ALLOWED,
	MISSING_DEMAND_IDENTITY,
	UNDECLARED_DEMAND,
	DUPLICATE_DEMAND_IDENTITY,
	SESSION_MANIFEST_MISMATCH,
	CONTROL_SESSION_CONFUSION,
	AMBIENT_DISABLED,
	AMBIENT_SESSION_CONFUSION,
	AUTOMATIC_CONTROL_UNAVAILABLE,
	AMBIENT_SOURCE_UNAVAILABLE,
	DEMAND_AUTHORITY_UNAVAILABLE,
	STALE_MANIFEST_IDENTITY,
	STALE_POLICY_REVISION,
	STALE_CONSENT_EPOCH,
	STALE_COLLECTED_DATA_EPOCH,
	STALE_ROLLOUT_REVISION,
	UNEXPECTED_REPLAY_AUTHORITY,
	REPLAY_AUTHORITY_REQUIRED,
	REPLAY_PURPOSE_MISMATCH,
	REPLAY_AUTHORITY_ESCALATION,
	REPLAY_AUTHORITY_MISMATCH,
}

data class SourceCallerGuardRejection(
	val reason: SourceCallerRejectionReason,
	val source: TrackingCaptureSource? = null,
	val purpose: TrackingPurpose? = null,
	val automaticUnavailableReason: AutomaticTrackingUnavailableReason? = null,
	val ambientAvailability: AmbientSourceOperationalAvailability? = null,
)

sealed interface SourceCallerGuardResult {
	data class Accepted(
		val authority: AcceptedSourceCallerAuthority,
	) : SourceCallerGuardResult

	data class Rejected(
		val rejection: SourceCallerGuardRejection,
	) : SourceCallerGuardResult
}

fun interface SourceCallerGuard {
	/**
	 * Pure acceptance boundary. Implementations return exact demand authority and never start,
	 * register, stop, or reconcile a provider.
	 */
	fun accept(input: SourceCallerGuardInput): SourceCallerGuardResult
}

fun evaluateSourceCallerGuard(input: SourceCallerGuardInput): SourceCallerGuardResult =
	when (val request = input.request) {
		is SourceCallerRequest.SessionStart -> evaluateSessionStart(
			request,
			input.currentAuthority,
			input.previouslyAcceptedAuthority,
		)
		is SourceCallerRequest.Ambient -> evaluateAmbient(
			request,
			input.currentAuthority,
			input.previouslyAcceptedAuthority,
		)
		is SourceCallerRequest.Replay -> evaluateReplay(
			request,
			input.currentAuthority,
			input.previouslyAcceptedAuthority,
		)
	}

private fun evaluateSessionStart(
	request: SourceCallerRequest.SessionStart,
	currentAuthority: SourceCallerAuthoritySnapshot,
	previouslyAcceptedAuthority: AcceptedSourceCallerAuthority?,
): SourceCallerGuardResult {
	if (previouslyAcceptedAuthority != null) {
		return rejected(SourceCallerRejectionReason.UNEXPECTED_REPLAY_AUTHORITY)
	}
	if (request.requestedCapturedSources.isEmpty()) {
		return rejected(SourceCallerRejectionReason.ZERO_CAPTURE_SOURCE_REQUEST)
	}
	when (request.startKind) {
		SourceCallerStartKind.MANUAL -> if (request.declaredControlDependencies.isNotEmpty()) {
			return rejected(SourceCallerRejectionReason.MANUAL_CONTROL_NOT_ALLOWED)
		}
		SourceCallerStartKind.AUTOMATIC -> {
			val unsupportedControl = request.declaredControlDependencies
				.filterNot { source -> source == TrackingCaptureSource.ACTIVITY }
				.minByOrNull(TrackingCaptureSource::ordinal)
			if (unsupportedControl != null) {
				return rejected(
					SourceCallerRejectionReason.CONTROL_SOURCE_NOT_ALLOWED,
					source = unsupportedControl,
					purpose = TrackingPurpose.CONTROL,
				)
			}
		}
	}

	val expectedKeys = buildSet {
		request.requestedCapturedSources.forEach { source ->
			add(source.forPurpose(TrackingPurpose.SESSION_CAPTURE))
		}
		request.declaredControlDependencies.forEach { source ->
			add(source.forPurpose(TrackingPurpose.CONTROL))
		}
	}
	validateDeclaredDemandSet(expectedKeys, request.requestedDemandIdentities)?.let { return it }
	request.requestedDemandIdentities.sortedByDemandKey().forEach { identity ->
		when (identity.sourcePurpose.purpose) {
			TrackingPurpose.SESSION_CAPTURE ->
				if (identity.manifestIdentity != request.manifestIdentity) {
					return rejected(
						SourceCallerRejectionReason.SESSION_MANIFEST_MISMATCH,
						identity.sourcePurpose,
					)
				}
			TrackingPurpose.CONTROL -> if (identity.manifestIdentity != null) {
				return rejected(
					SourceCallerRejectionReason.CONTROL_SESSION_CONFUSION,
					identity.sourcePurpose,
				)
			}
			TrackingPurpose.AMBIENT_PRODUCT -> return rejected(
				SourceCallerRejectionReason.UNDECLARED_DEMAND,
				identity.sourcePurpose,
			)
		}
	}
	if (
		request.startKind == SourceCallerStartKind.AUTOMATIC &&
		TrackingCaptureSource.ACTIVITY in request.declaredControlDependencies
	) {
		validateAutomaticControlAvailability(currentAuthority.purposeAvailability)?.let { return it }
	}
	validateCurrentAuthority(
		request.requestedDemandIdentities,
		currentAuthority.currentDemandIdentities,
	)?.let { return it }

	return SourceCallerGuardResult.Accepted(
		AcceptedSourceCallerAuthority(
			origin = when (request.startKind) {
				SourceCallerStartKind.MANUAL -> AcceptedSourceCallerOrigin.MANUAL
				SourceCallerStartKind.AUTOMATIC -> AcceptedSourceCallerOrigin.AUTOMATIC
			},
			purpose = request.purpose,
			permittedDemandIdentities = request.requestedDemandIdentities,
		),
	)
}

private fun evaluateAmbient(
	request: SourceCallerRequest.Ambient,
	currentAuthority: SourceCallerAuthoritySnapshot,
	previouslyAcceptedAuthority: AcceptedSourceCallerAuthority?,
): SourceCallerGuardResult {
	if (previouslyAcceptedAuthority != null) {
		return rejected(SourceCallerRejectionReason.UNEXPECTED_REPLAY_AUTHORITY)
	}
	if (!request.enabled) {
		return rejected(SourceCallerRejectionReason.AMBIENT_DISABLED)
	}
	val expectedKey = request.source.toTrackingCaptureSource()
		.forPurpose(TrackingPurpose.AMBIENT_PRODUCT)
	validateDeclaredDemandSet(setOf(expectedKey), request.requestedDemandIdentities)?.let {
		return it
	}
	val identity = request.requestedDemandIdentities.single()
	if (identity.manifestIdentity != null) {
		return rejected(
			SourceCallerRejectionReason.AMBIENT_SESSION_CONFUSION,
			identity.sourcePurpose,
		)
	}
	val availability = currentAuthority.purposeAvailability.ambientSources.getValue(request.source)
	if (!availability.isOperational) {
		return SourceCallerGuardResult.Rejected(
			SourceCallerGuardRejection(
				reason = SourceCallerRejectionReason.AMBIENT_SOURCE_UNAVAILABLE,
				source = identity.sourcePurpose.source,
				purpose = identity.sourcePurpose.purpose,
				ambientAvailability = availability,
			),
		)
	}
	validateCurrentAuthority(
		request.requestedDemandIdentities,
		currentAuthority.currentDemandIdentities,
	)?.let { return it }

	return SourceCallerGuardResult.Accepted(
		AcceptedSourceCallerAuthority(
			origin = AcceptedSourceCallerOrigin.AMBIENT,
			purpose = request.purpose,
			permittedDemandIdentities = request.requestedDemandIdentities,
		),
	)
}

private fun evaluateReplay(
	request: SourceCallerRequest.Replay,
	currentAuthority: SourceCallerAuthoritySnapshot,
	previouslyAcceptedAuthority: AcceptedSourceCallerAuthority?,
): SourceCallerGuardResult {
	val accepted = previouslyAcceptedAuthority
		?: return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_REQUIRED)
	if (request.purpose != accepted.purpose) {
		return rejected(SourceCallerRejectionReason.REPLAY_PURPOSE_MISMATCH)
	}
	if (request.requestedDemandIdentities != accepted.permittedDemandIdentities) {
		return rejected(replayMismatchReason(request, accepted))
	}
	when (accepted.origin) {
		AcceptedSourceCallerOrigin.AUTOMATIC -> if (
			accepted.permittedDemandIdentities.any { identity ->
				identity.sourcePurpose ==
					TrackingCaptureSource.ACTIVITY.forPurpose(TrackingPurpose.CONTROL)
			}
		) {
			validateAutomaticControlAvailability(currentAuthority.purposeAvailability)?.let {
				return it
			}
		}
		AcceptedSourceCallerOrigin.AMBIENT -> {
			val demand = accepted.permittedDemandIdentities.single()
			val ambientSource = demand.sourcePurpose.source.toAmbientTrackingSourceOrNull()
				?: return rejected(
					SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
					demand.sourcePurpose,
				)
			val availability =
				currentAuthority.purposeAvailability.ambientSources.getValue(ambientSource)
			if (!availability.isOperational) {
				return SourceCallerGuardResult.Rejected(
					SourceCallerGuardRejection(
						reason = SourceCallerRejectionReason.AMBIENT_SOURCE_UNAVAILABLE,
						source = demand.sourcePurpose.source,
						purpose = demand.sourcePurpose.purpose,
						ambientAvailability = availability,
					),
				)
			}
		}
		AcceptedSourceCallerOrigin.MANUAL -> Unit
	}
	validateCurrentAuthority(
		accepted.permittedDemandIdentities,
		currentAuthority.currentDemandIdentities,
	)?.let { return it }
	return SourceCallerGuardResult.Accepted(accepted)
}

private fun validateDeclaredDemandSet(
	expectedKeys: Set<TrackingSourcePurposeIdentity>,
	requestedIdentities: Set<SourceCallerDemandIdentity>,
): SourceCallerGuardResult.Rejected? {
	val duplicate = requestedIdentities.groupBy(SourceCallerDemandIdentity::sourcePurpose)
		.filterValues { identities -> identities.size > 1 }
		.keys
		.sortedWith(demandKeyComparator)
		.firstOrNull()
	if (duplicate != null) {
		return rejected(SourceCallerRejectionReason.DUPLICATE_DEMAND_IDENTITY, duplicate)
	}
	val requestedKeys = requestedIdentities.map(SourceCallerDemandIdentity::sourcePurpose).toSet()
	val undeclared = (requestedKeys - expectedKeys).sortedWith(demandKeyComparator).firstOrNull()
	if (undeclared != null) {
		return rejected(SourceCallerRejectionReason.UNDECLARED_DEMAND, undeclared)
	}
	val missing = (expectedKeys - requestedKeys).sortedWith(demandKeyComparator).firstOrNull()
	if (missing != null) {
		return rejected(SourceCallerRejectionReason.MISSING_DEMAND_IDENTITY, missing)
	}
	return null
}

private fun validateAutomaticControlAvailability(
	availability: TrackingPurposeAvailabilitySnapshot,
): SourceCallerGuardResult.Rejected? {
	val automatic = availability.automaticControl
	if (automatic is AutomaticTrackingOperationalAvailability.Ready) return null
	return SourceCallerGuardResult.Rejected(
		SourceCallerGuardRejection(
			reason = SourceCallerRejectionReason.AUTOMATIC_CONTROL_UNAVAILABLE,
			source = TrackingCaptureSource.ACTIVITY,
			purpose = TrackingPurpose.CONTROL,
			automaticUnavailableReason =
				(automatic as? AutomaticTrackingOperationalAvailability.Unavailable)?.reason,
		),
	)
}

private fun validateCurrentAuthority(
	requestedIdentities: Set<SourceCallerDemandIdentity>,
	currentIdentities: Set<SourceCallerDemandIdentity>,
): SourceCallerGuardResult.Rejected? {
	val currentByKey = currentIdentities.associateBy(SourceCallerDemandIdentity::sourcePurpose)
	requestedIdentities.sortedByDemandKey().forEach { requested ->
		val current = currentByKey[requested.sourcePurpose]
			?: return rejected(
				SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
				requested.sourcePurpose,
			)
		if (requested.manifestIdentity != current.manifestIdentity) {
			return rejected(
				SourceCallerRejectionReason.STALE_MANIFEST_IDENTITY,
				requested.sourcePurpose,
			)
		}
		if (requested.sourcePolicyRevision != current.sourcePolicyRevision) {
			return rejected(
				SourceCallerRejectionReason.STALE_POLICY_REVISION,
				requested.sourcePurpose,
			)
		}
		if (requested.consentEpoch != current.consentEpoch) {
			return rejected(
				SourceCallerRejectionReason.STALE_CONSENT_EPOCH,
				requested.sourcePurpose,
			)
		}
		if (requested.collectedDataEpoch != current.collectedDataEpoch) {
			return rejected(
				SourceCallerRejectionReason.STALE_COLLECTED_DATA_EPOCH,
				requested.sourcePurpose,
			)
		}
		if (requested.rolloutRevision != current.rolloutRevision) {
			return rejected(
				SourceCallerRejectionReason.STALE_ROLLOUT_REVISION,
				requested.sourcePurpose,
			)
		}
	}
	return null
}

private fun replayMismatchReason(
	request: SourceCallerRequest.Replay,
	accepted: AcceptedSourceCallerAuthority,
): SourceCallerRejectionReason {
	val acceptedByKey =
		accepted.permittedDemandIdentities.associateBy(SourceCallerDemandIdentity::sourcePurpose)
	val requestedByKey =
		request.requestedDemandIdentities.associateBy(SourceCallerDemandIdentity::sourcePurpose)
	if ((requestedByKey.keys - acceptedByKey.keys).isNotEmpty()) {
		return SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION
	}
	val upgrades = requestedByKey.any { (key, requested) ->
		val prior = acceptedByKey.getValue(key)
		requested.manifestIdentity.isManifestUpgradeFrom(prior.manifestIdentity) ||
			requested.sourcePolicyRevision > prior.sourcePolicyRevision ||
			requested.consentEpoch > prior.consentEpoch ||
			requested.collectedDataEpoch > prior.collectedDataEpoch ||
			requested.rolloutRevision > prior.rolloutRevision
	}
	return if (upgrades) {
		SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION
	} else {
		SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH
	}
}

private fun SourceCallerManifestIdentity?.isManifestUpgradeFrom(
	prior: SourceCallerManifestIdentity?,
): Boolean = when {
	prior == null -> this != null
	this == null -> false
	logicalTrackingId != prior.logicalTrackingId -> true
	else -> manifestRevision > prior.manifestRevision
}

private fun rejected(
	reason: SourceCallerRejectionReason,
	demand: TrackingSourcePurposeIdentity? = null,
	source: TrackingCaptureSource? = demand?.source,
	purpose: TrackingPurpose? = demand?.purpose,
): SourceCallerGuardResult.Rejected = SourceCallerGuardResult.Rejected(
	SourceCallerGuardRejection(
		reason = reason,
		source = source,
		purpose = purpose,
	),
)

private val demandKeyComparator =
	compareBy<TrackingSourcePurposeIdentity>(TrackingSourcePurposeIdentity::source)
		.thenBy(TrackingSourcePurposeIdentity::purpose)

private fun Set<SourceCallerDemandIdentity>.sortedByDemandKey(): List<SourceCallerDemandIdentity> =
	sortedWith { left, right ->
		demandKeyComparator.compare(left.sourcePurpose, right.sourcePurpose)
	}

private fun AmbientTrackingSource.toTrackingCaptureSource(): TrackingCaptureSource = when (this) {
	AmbientTrackingSource.STEPS -> TrackingCaptureSource.STEPS
	AmbientTrackingSource.LOCATION -> TrackingCaptureSource.LOCATION
	AmbientTrackingSource.WIFI -> TrackingCaptureSource.WIFI
	AmbientTrackingSource.CELL -> TrackingCaptureSource.CELL
}

private fun TrackingCaptureSource.toAmbientTrackingSourceOrNull(): AmbientTrackingSource? =
	when (this) {
		TrackingCaptureSource.STEPS -> AmbientTrackingSource.STEPS
		TrackingCaptureSource.LOCATION -> AmbientTrackingSource.LOCATION
		TrackingCaptureSource.WIFI -> AmbientTrackingSource.WIFI
		TrackingCaptureSource.CELL -> AmbientTrackingSource.CELL
		TrackingCaptureSource.ACTIVITY,
		TrackingCaptureSource.PRESSURE,
		-> null
	}
