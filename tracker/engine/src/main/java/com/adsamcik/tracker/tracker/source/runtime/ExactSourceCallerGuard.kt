package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuard
import com.adsamcik.tracker.tracker.api.SourceCallerGuardRejection
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.SourceCallerRequest
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal class SourceCallerAuthoritySnapshot(
	currentDemandIdentities: Set<SourceCallerDemandIdentity>,
	val purposeAvailability: TrackingPurposeAvailabilitySnapshot,
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
		require(this.currentDemandIdentities.all { identity ->
			identity.purposeLeaseIdentity.executionRevision > 0L
		}) {
			"Current caller authority must omit unbound source-purpose identities"
		}
	}
}

internal fun interface SourceCallerAuthoritySnapshotReader {
	/** Reads demand and purpose authority from one coherent engine-owned boundary. */
	suspend fun read(request: SourceCallerRequest): SourceCallerAuthoritySnapshot
}

internal interface SourceCallerAcceptedAuthorityRepository {
	/** Persists normalized authority before a caller receives its replay reference. */
	suspend fun insertIfAbsent(
		reference: SourceCallerReplayReference,
		authority: StoredSourceCallerAuthority,
		createdAtMs: Long,
	): Boolean
	suspend fun load(reference: SourceCallerReplayReference): StoredSourceCallerAuthorityLoadResult
	suspend fun retire(
		reference: SourceCallerReplayReference,
		reason: String,
		retiredAtMs: Long,
	): Boolean
	suspend fun delete(reference: SourceCallerReplayReference): Boolean
	suspend fun pruneRetired(
		retiredBeforeOrAtMs: Long,
		limit: Int,
	): Int
}

internal enum class StoredSourceCallerOrigin {
	MANUAL,
	AUTOMATIC,
	RECOVERY,
	PURPOSE_OWNER,
	AMBIENT,
}

internal data class StoredSourceCallerAuthority(
	val origin: StoredSourceCallerOrigin,
	val purpose: TrackingPurpose,
	val permittedDemandIdentities: Set<SourceCallerDemandIdentity>,
)

internal sealed interface StoredSourceCallerAuthorityLoadResult {
	data class Available(
		val authority: StoredSourceCallerAuthority,
	) : StoredSourceCallerAuthorityLoadResult

	data object Missing : StoredSourceCallerAuthorityLoadResult
	data object Corrupt : StoredSourceCallerAuthorityLoadResult
	data object Retired : StoredSourceCallerAuthorityLoadResult
}

/**
 * Engine-owned acceptance boundary. The accepted capability is private to this module; public
 * callers receive an informational receipt only, and replay can recover authority only through the
 * trusted normalized repository.
 */
@Singleton
internal class ExactSourceCallerGuard @Inject constructor(
	private val authorityReader: SourceCallerAuthoritySnapshotReader,
	private val authorityRepository: SourceCallerAcceptedAuthorityRepository,
) : SourceCallerGuard {
	override suspend fun accept(request: SourceCallerRequest): SourceCallerGuardResult {
		if (request is SourceCallerRequest.PurposeOwnerRetirement) {
			return acceptPurposeOwnerRetirement(request)
		}
		if (request is SourceCallerRequest.Replay &&
			request.replayKind == SourceCallerReplayKind.POLICY_RECONCILIATION
		) {
			return rejected(SourceCallerRejectionReason.REPLAY_KIND_REQUIRES_FRESH_ACCEPTANCE)
		}
		validateBoundExecution(request.requestedDemandIdentities.toSet())?.let { return it }
		val currentAuthority = try {
			authorityReader.read(request)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return rejected(SourceCallerRejectionReason.AUTHORITY_STORAGE_UNAVAILABLE)
		}
		return when (request) {
			is SourceCallerRequest.ManualSessionStart ->
				acceptFresh(evaluateManual(request, currentAuthority))
			is SourceCallerRequest.AutomaticSessionStart ->
				acceptFresh(evaluateAutomatic(request, currentAuthority))
			is SourceCallerRequest.RecoverySessionStart ->
				acceptFresh(evaluateRecovery(request, currentAuthority))
			is SourceCallerRequest.PurposeOwnerMutation ->
				acceptFresh(evaluatePurposeOwnerMutation(request, currentAuthority))
			is SourceCallerRequest.PurposeOwnerRetirement ->
				error("Purpose-owner retirement is handled before current-authority acquisition")
			is SourceCallerRequest.Ambient ->
				acceptFresh(evaluateAmbient(request, currentAuthority))
			is SourceCallerRequest.Replay -> replay(request, currentAuthority)
		}
	}

	private suspend fun acceptPurposeOwnerRetirement(
		request: SourceCallerRequest.PurposeOwnerRetirement,
	): SourceCallerGuardResult {
		val loaded = try {
			authorityRepository.load(request.reference)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return rejected(SourceCallerRejectionReason.AUTHORITY_STORAGE_UNAVAILABLE)
		}
		val accepted = when (loaded) {
			is StoredSourceCallerAuthorityLoadResult.Available -> try {
				loaded.authority.toAccepted()
			} catch (_: IllegalArgumentException) {
				return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_CORRUPT)
			}
			StoredSourceCallerAuthorityLoadResult.Missing ->
				return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_UNAVAILABLE)
			StoredSourceCallerAuthorityLoadResult.Corrupt ->
				return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_CORRUPT)
			StoredSourceCallerAuthorityLoadResult.Retired ->
				return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_RETIRED)
		}
		val identity = accepted.permittedDemandIdentities.singleOrNull()
		if (identity == null ||
			accepted.origin != AcceptedSourceCallerOrigin.PURPOSE_OWNER ||
			accepted.purpose != request.purpose ||
			identity.sourcePurpose.source != request.source ||
			identity.sourcePurpose.purpose != request.purpose ||
			identity.manifestIdentity != null
		) {
			return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH)
		}
		return permitted(request.reference, accepted)
	}

	private suspend fun acceptFresh(
		evaluation: SourceCallerEvaluation,
	): SourceCallerGuardResult = when (evaluation) {
		is SourceCallerEvaluation.Rejected -> SourceCallerGuardResult.Rejected(evaluation.rejection)
		is SourceCallerEvaluation.Accepted -> {
			val reference = SourceCallerReplayReference(UUID.randomUUID().toString())
			try {
				if (!authorityRepository.insertIfAbsent(
					reference,
					evaluation.authority.toStored(),
					System.currentTimeMillis(),
				)) {
					return rejected(SourceCallerRejectionReason.AUTHORITY_PERSISTENCE_UNAVAILABLE)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				return rejected(SourceCallerRejectionReason.AUTHORITY_PERSISTENCE_UNAVAILABLE)
			}
			permitted(reference, evaluation.authority)
		}
	}

	private suspend fun replay(
		request: SourceCallerRequest.Replay,
		currentAuthority: SourceCallerAuthoritySnapshot,
	): SourceCallerGuardResult {
		val loaded = try {
			authorityRepository.load(request.reference)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return rejected(SourceCallerRejectionReason.AUTHORITY_STORAGE_UNAVAILABLE)
		}
		val accepted = when (loaded) {
			is StoredSourceCallerAuthorityLoadResult.Available -> try {
				loaded.authority.toAccepted()
			} catch (_: IllegalArgumentException) {
				return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_CORRUPT)
			}
			StoredSourceCallerAuthorityLoadResult.Missing ->
				return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_UNAVAILABLE)
			StoredSourceCallerAuthorityLoadResult.Corrupt ->
				return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_CORRUPT)
			StoredSourceCallerAuthorityLoadResult.Retired ->
				return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_RETIRED)
		}
		if (accepted.purpose != TrackingPurpose.SESSION_CAPTURE ||
			accepted.origin !in setOf(
				AcceptedSourceCallerOrigin.MANUAL,
				AcceptedSourceCallerOrigin.AUTOMATIC,
				AcceptedSourceCallerOrigin.RECOVERY,
			)
		) {
			return rejected(SourceCallerRejectionReason.REPLAY_KIND_NOT_PERMITTED)
		}
		val requestedDemands = request.requestedDemandIdentities.toSet()
		if (request.purpose != accepted.purpose) {
			return rejected(SourceCallerRejectionReason.REPLAY_PURPOSE_MISMATCH)
		}
		if (requestedDemands != accepted.permittedDemandIdentities) {
			return rejected(replayMismatchReason(request.purpose, requestedDemands, accepted))
		}
		validateReplayReadiness(accepted, currentAuthority.purposeAvailability)?.let { return it }
		validateCurrentAuthority(
			accepted.permittedDemandIdentities,
			currentAuthority.currentDemandIdentities,
		)?.let { return it }
		return permitted(request.reference, accepted)
	}
}

private sealed interface SourceCallerEvaluation {
	class Accepted(
		val authority: AcceptedSourceCallerAuthority,
	) : SourceCallerEvaluation

	class Rejected(
		val rejection: SourceCallerGuardRejection,
	) : SourceCallerEvaluation
}

internal enum class AcceptedSourceCallerOrigin {
	MANUAL,
	AUTOMATIC,
	RECOVERY,
	PURPOSE_OWNER,
	AMBIENT,
}

internal sealed interface AcceptedSourceCallerAuthority {
	val origin: AcceptedSourceCallerOrigin
	val purpose: TrackingPurpose
	val permittedDemandIdentities: Set<SourceCallerDemandIdentity>
}

internal class ExactAcceptedSourceCallerAuthority private constructor(
	override val origin: AcceptedSourceCallerOrigin,
	override val purpose: TrackingPurpose,
	permittedDemandIdentities: Set<SourceCallerDemandIdentity>,
) : AcceptedSourceCallerAuthority {
	override val permittedDemandIdentities = permittedDemandIdentities.toSet()

	companion object {
		fun issue(
			origin: AcceptedSourceCallerOrigin,
			purpose: TrackingPurpose,
			permittedDemandIdentities: Set<SourceCallerDemandIdentity>,
		): AcceptedSourceCallerAuthority {
			require(permittedDemandIdentities.isNotEmpty())
			require(
				permittedDemandIdentities
					.map(SourceCallerDemandIdentity::sourcePurpose)
					.distinct()
					.size == permittedDemandIdentities.size,
			)
			require(permittedDemandIdentities.all { identity ->
				identity.purposeLeaseIdentity.executionRevision > 0L
			})
			val captures = permittedDemandIdentities.filter { identity ->
				identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE
			}
			when (origin) {
				AcceptedSourceCallerOrigin.MANUAL -> {
					require(purpose == TrackingPurpose.SESSION_CAPTURE)
					require(captures.size == permittedDemandIdentities.size)
					require(captures.map(SourceCallerDemandIdentity::manifestIdentity)
						.distinct().size == 1)
				}
				AcceptedSourceCallerOrigin.RECOVERY -> {
					require(purpose == TrackingPurpose.SESSION_CAPTURE)
					require(captures.size == permittedDemandIdentities.size)
					require(captures.map(SourceCallerDemandIdentity::manifestIdentity)
						.distinct().size == 1)
				}
				AcceptedSourceCallerOrigin.AUTOMATIC -> {
					require(purpose == TrackingPurpose.SESSION_CAPTURE)
					require(captures.isNotEmpty())
					require(captures.map(SourceCallerDemandIdentity::manifestIdentity)
						.distinct().size == 1)
					require(permittedDemandIdentities.all { identity ->
						identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE ||
							identity.sourcePurpose.purpose == TrackingPurpose.CONTROL
					})
					require(
						permittedDemandIdentities
							.filter { identity ->
								identity.sourcePurpose.purpose == TrackingPurpose.CONTROL
							}
							.map(SourceCallerDemandIdentity::sourcePurpose)
							.toSet() == setOf(ACTIVITY_CONTROL),
					)
				}
				AcceptedSourceCallerOrigin.PURPOSE_OWNER -> {
					require(purpose == TrackingPurpose.CONTROL ||
						purpose == TrackingPurpose.AMBIENT_PRODUCT)
					require(permittedDemandIdentities.single().sourcePurpose.purpose == purpose)
					require(captures.isEmpty())
				}
				AcceptedSourceCallerOrigin.AMBIENT -> {
					require(purpose == TrackingPurpose.AMBIENT_PRODUCT)
					require(permittedDemandIdentities.single().sourcePurpose.purpose ==
						TrackingPurpose.AMBIENT_PRODUCT)
				}
			}
			return ExactAcceptedSourceCallerAuthority(
				origin,
				purpose,
				permittedDemandIdentities,
			)
		}
	}
}

private fun evaluateManual(
	request: SourceCallerRequest.ManualSessionStart,
	currentAuthority: SourceCallerAuthoritySnapshot,
): SourceCallerEvaluation {
	val capturedSources = request.requestedCapturedSources.toSet()
	val requestedDemands = request.requestedDemandIdentities.toSet()
	validateBoundExecution(requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	if (capturedSources.isEmpty()) {
		return rejectedEvaluation(SourceCallerRejectionReason.ZERO_CAPTURE_SOURCE_REQUEST)
	}
	val expected = capturedSources.mapTo(mutableSetOf()) { source ->
		source.forPurpose(TrackingPurpose.SESSION_CAPTURE)
	}
	validateDeclaredDemandSet(expected, requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	validateSessionManifest(request.manifestIdentity, requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	validateCurrentManifestDemandSet(
		request.manifestIdentity,
		requestedDemands,
		currentAuthority.currentDemandIdentities,
	)?.let { return SourceCallerEvaluation.Rejected(it.rejection) }
	validateCurrentAuthority(
		requestedDemands,
		currentAuthority.currentDemandIdentities,
	)?.let { return SourceCallerEvaluation.Rejected(it.rejection) }
	return acceptedEvaluation(
		AcceptedSourceCallerOrigin.MANUAL,
		TrackingPurpose.SESSION_CAPTURE,
		requestedDemands,
	)
}

private fun evaluateAutomatic(
	request: SourceCallerRequest.AutomaticSessionStart,
	currentAuthority: SourceCallerAuthoritySnapshot,
): SourceCallerEvaluation {
	val capturedSources = request.requestedCapturedSources.toSet()
	val declaredControls = request.declaredControlDependencies.toSet()
	val requestedDemands = request.requestedDemandIdentities.toSet()
	validateBoundExecution(requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	val availability = currentAuthority.purposeAvailability.automaticControl
	if (availability !is AutomaticTrackingOperationalAvailability.Ready) {
		return SourceCallerEvaluation.Rejected(
			SourceCallerGuardRejection(
				reason = SourceCallerRejectionReason.AUTOMATIC_CONTROL_UNAVAILABLE,
				source = TrackingSource.ACTIVITY,
				purpose = TrackingPurpose.CONTROL,
				automaticUnavailableReason =
					(availability as AutomaticTrackingOperationalAvailability.Unavailable).reason,
			),
		)
	}
	if (declaredControls != setOf(TrackingSource.ACTIVITY)) {
		return rejectedEvaluation(
			SourceCallerRejectionReason.AUTOMATIC_CONTROL_SET_MISMATCH,
			TrackingSource.ACTIVITY.forPurpose(TrackingPurpose.CONTROL),
		)
	}
	if (capturedSources.isEmpty()) {
		return rejectedEvaluation(SourceCallerRejectionReason.ZERO_CAPTURE_SOURCE_REQUEST)
	}
	val expected = capturedSources.mapTo(mutableSetOf()) { source ->
		source.forPurpose(TrackingPurpose.SESSION_CAPTURE)
	}.apply {
		add(ACTIVITY_CONTROL)
	}
	validateDeclaredDemandSet(expected, requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	validateSessionManifest(request.manifestIdentity, requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	validateCurrentManifestDemandSet(
		request.manifestIdentity,
		requestedDemands,
		currentAuthority.currentDemandIdentities,
	)?.let { return SourceCallerEvaluation.Rejected(it.rejection) }
	val controlDemand = requestedDemands.single { identity ->
		identity.sourcePurpose == ACTIVITY_CONTROL
	}
	if (availability.identity != controlDemand.purposeLeaseIdentity) {
		return rejectedEvaluation(
			SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
			ACTIVITY_CONTROL,
		)
	}
	validateCurrentAuthority(
		requestedDemands,
		currentAuthority.currentDemandIdentities,
	)?.let { return SourceCallerEvaluation.Rejected(it.rejection) }
	return acceptedEvaluation(
		AcceptedSourceCallerOrigin.AUTOMATIC,
		TrackingPurpose.SESSION_CAPTURE,
		requestedDemands,
	)
}

private fun evaluateRecovery(
	request: SourceCallerRequest.RecoverySessionStart,
	currentAuthority: SourceCallerAuthoritySnapshot,
): SourceCallerEvaluation {
	val capturedSources = request.requestedCapturedSources.toSet()
	val requestedDemands = request.requestedDemandIdentities.toSet()
	validateBoundExecution(requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	if (capturedSources.isEmpty()) {
		return rejectedEvaluation(SourceCallerRejectionReason.ZERO_CAPTURE_SOURCE_REQUEST)
	}
	val expected = capturedSources.mapTo(mutableSetOf()) { source ->
		source.forPurpose(TrackingPurpose.SESSION_CAPTURE)
	}
	validateDeclaredDemandSet(expected, requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	validateSessionManifest(request.manifestIdentity, requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	validateCurrentManifestDemandSet(
		request.manifestIdentity,
		requestedDemands,
		currentAuthority.currentDemandIdentities,
	)?.let { return SourceCallerEvaluation.Rejected(it.rejection) }
	validateCurrentAuthority(
		requestedDemands,
		currentAuthority.currentDemandIdentities,
	)?.let { return SourceCallerEvaluation.Rejected(it.rejection) }
	return acceptedEvaluation(
		AcceptedSourceCallerOrigin.RECOVERY,
		TrackingPurpose.SESSION_CAPTURE,
		requestedDemands,
	)
}

private fun evaluatePurposeOwnerMutation(
	request: SourceCallerRequest.PurposeOwnerMutation,
	currentAuthority: SourceCallerAuthoritySnapshot,
): SourceCallerEvaluation {
	val requestedDemands = request.requestedDemandIdentities.toSet()
	validateBoundExecution(requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	val expected = request.source.forPurpose(request.purpose)
	validateDeclaredDemandSet(setOf(expected), requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	validateCurrentAuthority(
		requestedDemands,
		currentAuthority.currentDemandIdentities,
	)?.let { return SourceCallerEvaluation.Rejected(it.rejection) }
	return acceptedEvaluation(
		AcceptedSourceCallerOrigin.PURPOSE_OWNER,
		request.purpose,
		requestedDemands,
	)
}

private fun evaluateAmbient(
	request: SourceCallerRequest.Ambient,
	currentAuthority: SourceCallerAuthoritySnapshot,
): SourceCallerEvaluation {
	val requestedDemands = request.requestedDemandIdentities.toSet()
	validateBoundExecution(requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	if (!request.source.supports(TrackingPurpose.AMBIENT_PRODUCT)) {
		return rejectedEvaluation(
			SourceCallerRejectionReason.AMBIENT_SOURCE_NOT_SUPPORTED,
			source = request.source,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
		)
	}
	if (!request.enabled) {
		return rejectedEvaluation(SourceCallerRejectionReason.AMBIENT_DISABLED)
	}
	val expected = request.source.forPurpose(TrackingPurpose.AMBIENT_PRODUCT)
	validateDeclaredDemandSet(setOf(expected), requestedDemands)?.let {
		return SourceCallerEvaluation.Rejected(it.rejection)
	}
	val demand = requestedDemands.single()
	val ambientSource = request.source.toAmbientTrackingSourceOrNull()
		?: return rejectedEvaluation(
			SourceCallerRejectionReason.AMBIENT_SOURCE_NOT_SUPPORTED,
			expected,
		)
	val availability =
		currentAuthority.purposeAvailability.ambientSources.getValue(ambientSource)
	if (!availability.isOperational) {
		return SourceCallerEvaluation.Rejected(
			SourceCallerGuardRejection(
				reason = SourceCallerRejectionReason.AMBIENT_SOURCE_UNAVAILABLE,
				source = request.source,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				ambientAvailability = availability,
			),
		)
	}
	if (availability.operationalIdentity != demand.purposeLeaseIdentity) {
		return rejectedEvaluation(
			SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
			expected,
		)
	}
	validateCurrentAuthority(
		requestedDemands,
		currentAuthority.currentDemandIdentities,
	)?.let { return SourceCallerEvaluation.Rejected(it.rejection) }
	return acceptedEvaluation(
		AcceptedSourceCallerOrigin.AMBIENT,
		TrackingPurpose.AMBIENT_PRODUCT,
		requestedDemands,
	)
}

private fun validateSessionManifest(
	expectedManifest: SourceCallerManifestIdentity,
	demands: Set<SourceCallerDemandIdentity>,
): SourceCallerGuardResult.Rejected? {
	val mismatch = demands.sortedByDemandKey().firstOrNull { identity ->
		identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE &&
			identity.manifestIdentity != expectedManifest
	}
	return mismatch?.let { identity ->
		rejected(
			SourceCallerRejectionReason.SESSION_MANIFEST_MISMATCH,
			identity.sourcePurpose,
		)
	}
}

private fun validateCurrentManifestDemandSet(
	manifestIdentity: SourceCallerManifestIdentity,
	requestedIdentities: Set<SourceCallerDemandIdentity>,
	currentIdentities: Set<SourceCallerDemandIdentity>,
): SourceCallerGuardResult.Rejected? {
	val currentManifestIdentities = currentIdentities.filterTo(linkedSetOf()) { identity ->
		identity.manifestIdentity == manifestIdentity ||
			(identity.sourcePurpose.purpose == TrackingPurpose.CONTROL &&
				identity.manifestIdentity == null)
	}
	val requestedKeys = requestedIdentities.map(SourceCallerDemandIdentity::sourcePurpose).toSet()
	val currentKeys = currentManifestIdentities.map(SourceCallerDemandIdentity::sourcePurpose).toSet()
	val undeclared = (currentKeys - requestedKeys).sortedWith(DEMAND_KEY_COMPARATOR).firstOrNull()
	if (undeclared != null) {
		return rejected(SourceCallerRejectionReason.UNDECLARED_DEMAND, undeclared)
	}
	return null
}

private fun validateDeclaredDemandSet(
	expectedKeys: Set<TrackingSourcePurposeIdentity>,
	requestedIdentities: Set<SourceCallerDemandIdentity>,
): SourceCallerGuardResult.Rejected? {
	val duplicate = requestedIdentities.groupBy(SourceCallerDemandIdentity::sourcePurpose)
		.filterValues { identities -> identities.size > 1 }
		.keys
		.sortedWith(DEMAND_KEY_COMPARATOR)
		.firstOrNull()
	if (duplicate != null) {
		return rejected(SourceCallerRejectionReason.DUPLICATE_DEMAND_IDENTITY, duplicate)
	}
	val requestedKeys = requestedIdentities.map(SourceCallerDemandIdentity::sourcePurpose).toSet()
	val undeclared = (requestedKeys - expectedKeys).sortedWith(DEMAND_KEY_COMPARATOR).firstOrNull()
	if (undeclared != null) {
		return rejected(SourceCallerRejectionReason.UNDECLARED_DEMAND, undeclared)
	}
	val missing = (expectedKeys - requestedKeys).sortedWith(DEMAND_KEY_COMPARATOR).firstOrNull()
	if (missing != null) {
		return rejected(SourceCallerRejectionReason.MISSING_DEMAND_IDENTITY, missing)
	}
	return null
}

private fun validateBoundExecution(
	requestedIdentities: Set<SourceCallerDemandIdentity>,
): SourceCallerGuardResult.Rejected? {
	val unbound = requestedIdentities.sortedByDemandKey().firstOrNull { identity ->
		identity.purposeLeaseIdentity.executionRevision <= 0L
	}
	return unbound?.let { identity ->
		rejected(
			SourceCallerRejectionReason.UNBOUND_EXECUTION_AUTHORITY,
			identity.sourcePurpose,
		)
	}
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
		val requestedLease = requested.purposeLeaseIdentity
		val currentLease = current.purposeLeaseIdentity
		if (requestedLease.policyRevision != currentLease.policyRevision) {
			return rejected(SourceCallerRejectionReason.STALE_POLICY_REVISION, requested.sourcePurpose)
		}
		if (requestedLease.consentEpoch != currentLease.consentEpoch) {
			return rejected(SourceCallerRejectionReason.STALE_CONSENT_EPOCH, requested.sourcePurpose)
		}
		if (requestedLease.collectedDataEpoch != currentLease.collectedDataEpoch) {
			return rejected(
				SourceCallerRejectionReason.STALE_COLLECTED_DATA_EPOCH,
				requested.sourcePurpose,
			)
		}
		if (requestedLease.retainedFromMs != currentLease.retainedFromMs) {
			return rejected(
				SourceCallerRejectionReason.STALE_RETENTION_BOUNDARY,
				requested.sourcePurpose,
			)
		}
		if (requestedLease.rolloutRevision != currentLease.rolloutRevision) {
			return rejected(SourceCallerRejectionReason.STALE_ROLLOUT_REVISION, requested.sourcePurpose)
		}
		if (requestedLease.executionRevision != currentLease.executionRevision) {
			return rejected(
				SourceCallerRejectionReason.STALE_EXECUTION_REVISION,
				requested.sourcePurpose,
			)
		}
		if (requestedLease.ownerCasToken != currentLease.ownerCasToken) {
			return rejected(SourceCallerRejectionReason.STALE_OWNER_CAS_TOKEN, requested.sourcePurpose)
		}
	}
	return null
}

private fun validateReplayReadiness(
	accepted: AcceptedSourceCallerAuthority,
	availability: TrackingPurposeAvailabilitySnapshot,
): SourceCallerGuardResult.Rejected? {
	return when (accepted.origin) {
		AcceptedSourceCallerOrigin.MANUAL,
		AcceptedSourceCallerOrigin.RECOVERY,
		-> null
		AcceptedSourceCallerOrigin.AUTOMATIC -> {
			val automatic = availability.automaticControl
			if (automatic !is AutomaticTrackingOperationalAvailability.Ready) {
				SourceCallerGuardResult.Rejected(
					SourceCallerGuardRejection(
						reason = SourceCallerRejectionReason.AUTOMATIC_CONTROL_UNAVAILABLE,
						source = TrackingSource.ACTIVITY,
						purpose = TrackingPurpose.CONTROL,
						automaticUnavailableReason =
							(automatic as AutomaticTrackingOperationalAvailability.Unavailable).reason,
					),
				)
			} else {
				val control = accepted.permittedDemandIdentities.single { identity ->
					identity.sourcePurpose == ACTIVITY_CONTROL
				}
				if (automatic.identity == control.purposeLeaseIdentity) {
					null
				} else {
					rejected(
						SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
						ACTIVITY_CONTROL,
					)
				}
			}
		}
		AcceptedSourceCallerOrigin.PURPOSE_OWNER -> null
		AcceptedSourceCallerOrigin.AMBIENT -> {
			val demand = accepted.permittedDemandIdentities.single()
			val ambientSource = demand.sourcePurpose.source.toAmbientTrackingSourceOrNull()
				?: return rejected(
					SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
					demand.sourcePurpose,
				)
			val current = availability.ambientSources.getValue(ambientSource)
			when {
				!current.isOperational -> SourceCallerGuardResult.Rejected(
					SourceCallerGuardRejection(
						reason = SourceCallerRejectionReason.AMBIENT_SOURCE_UNAVAILABLE,
						source = demand.sourcePurpose.source,
						purpose = demand.sourcePurpose.purpose,
						ambientAvailability = current,
					),
				)
				current.operationalIdentity != demand.purposeLeaseIdentity -> rejected(
					SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
					demand.sourcePurpose,
				)
				else -> null
			}
		}
	}
}

private fun replayMismatchReason(
	requestedPurpose: TrackingPurpose,
	requestedDemands: Set<SourceCallerDemandIdentity>,
	accepted: AcceptedSourceCallerAuthority,
): SourceCallerRejectionReason {
	if (requestedPurpose != accepted.purpose) {
		return SourceCallerRejectionReason.REPLAY_PURPOSE_MISMATCH
	}
	val acceptedByKey =
		accepted.permittedDemandIdentities.associateBy(SourceCallerDemandIdentity::sourcePurpose)
	val requestedByKey =
		requestedDemands.associateBy(SourceCallerDemandIdentity::sourcePurpose)
	if ((requestedByKey.keys - acceptedByKey.keys).isNotEmpty()) {
		return SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION
	}
	if ((acceptedByKey.keys - requestedByKey.keys).isNotEmpty()) {
		return SourceCallerRejectionReason.REPLAY_AUTHORITY_DOWNGRADE
	}
	var escalation = false
	var downgrade = false
	var mismatch = false
	requestedByKey.forEach { (key, requested) ->
		val prior = acceptedByKey.getValue(key)
		val manifestOrder = requested.manifestIdentity.compareAuthority(prior.manifestIdentity)
		val requestedLease = requested.purposeLeaseIdentity
		val priorLease = prior.purposeLeaseIdentity
		if (
			manifestOrder > 0 ||
			requestedLease.policyRevision > priorLease.policyRevision ||
			requestedLease.consentEpoch > priorLease.consentEpoch ||
			requestedLease.collectedDataEpoch > priorLease.collectedDataEpoch ||
			requestedLease.rolloutRevision > priorLease.rolloutRevision ||
			requestedLease.executionRevision > priorLease.executionRevision
		) {
			escalation = true
		}
		if (
			manifestOrder < 0 ||
			requestedLease.policyRevision < priorLease.policyRevision ||
			requestedLease.consentEpoch < priorLease.consentEpoch ||
			requestedLease.collectedDataEpoch < priorLease.collectedDataEpoch ||
			requestedLease.rolloutRevision < priorLease.rolloutRevision ||
			requestedLease.executionRevision < priorLease.executionRevision
		) {
			downgrade = true
		}
		if (
			manifestOrder == AUTHORITY_INCOMPARABLE ||
			requestedLease.retainedFromMs != priorLease.retainedFromMs ||
			requestedLease.ownerCasToken != priorLease.ownerCasToken
		) {
			mismatch = true
		}
	}
	return when {
		escalation -> SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION
		mismatch -> SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH
		downgrade -> SourceCallerRejectionReason.REPLAY_AUTHORITY_DOWNGRADE
		else -> SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH
	}
}

private fun SourceCallerManifestIdentity?.compareAuthority(
	prior: SourceCallerManifestIdentity?,
): Int = when {
	this == prior -> 0
	this == null || prior == null -> AUTHORITY_INCOMPARABLE
	logicalTrackingId != prior.logicalTrackingId -> AUTHORITY_INCOMPARABLE
	manifestRevision > prior.manifestRevision -> 1
	else -> -1
}

private fun acceptedEvaluation(
	origin: AcceptedSourceCallerOrigin,
	purpose: TrackingPurpose,
	demands: Set<SourceCallerDemandIdentity>,
): SourceCallerEvaluation.Accepted = SourceCallerEvaluation.Accepted(
	ExactAcceptedSourceCallerAuthority.issue(origin, purpose, demands),
)

private fun rejectedEvaluation(
	reason: SourceCallerRejectionReason,
	demand: TrackingSourcePurposeIdentity? = null,
	source: TrackingSource? = demand?.source,
	purpose: TrackingPurpose? = demand?.purpose,
): SourceCallerEvaluation.Rejected = SourceCallerEvaluation.Rejected(
	rejected(reason, demand, source, purpose).rejection,
)

private fun rejected(
	reason: SourceCallerRejectionReason,
	demand: TrackingSourcePurposeIdentity? = null,
	source: TrackingSource? = demand?.source,
	purpose: TrackingPurpose? = demand?.purpose,
): SourceCallerGuardResult.Rejected = SourceCallerGuardResult.Rejected(
	SourceCallerGuardRejection(
		reason = reason,
		source = source,
		purpose = purpose,
	),
)

private fun permitted(
	reference: SourceCallerReplayReference,
	authority: AcceptedSourceCallerAuthority,
): SourceCallerGuardResult.Permitted = SourceCallerGuardResult.Permitted(
	SourceCallerAcceptanceReceipt(
		reference = reference,
		permittedDemandIdentities = authority.permittedDemandIdentities,
	),
)

private fun AcceptedSourceCallerAuthority.toStored() = StoredSourceCallerAuthority(
	origin = StoredSourceCallerOrigin.valueOf(origin.name),
	purpose = purpose,
	permittedDemandIdentities = permittedDemandIdentities,
)

private fun StoredSourceCallerAuthority.toAccepted(): AcceptedSourceCallerAuthority =
	ExactAcceptedSourceCallerAuthority.issue(
		origin = AcceptedSourceCallerOrigin.valueOf(origin.name),
		purpose = purpose,
		permittedDemandIdentities = permittedDemandIdentities,
	)

private val ACTIVITY_CONTROL =
	TrackingSource.ACTIVITY.forPurpose(TrackingPurpose.CONTROL)
private val DEMAND_KEY_COMPARATOR =
	compareBy<TrackingSourcePurposeIdentity>(TrackingSourcePurposeIdentity::source)
		.thenBy(TrackingSourcePurposeIdentity::purpose)

private fun Set<SourceCallerDemandIdentity>.sortedByDemandKey(): List<SourceCallerDemandIdentity> =
	sortedWith { left, right ->
		DEMAND_KEY_COMPARATOR.compare(left.sourcePurpose, right.sourcePurpose)
	}

private fun TrackingSource.toAmbientTrackingSourceOrNull(): AmbientTrackingSource? =
	AmbientTrackingSource.entries.singleOrNull { source -> source.canonicalSource == this }

private const val AUTHORITY_INCOMPARABLE = Int.MIN_VALUE
