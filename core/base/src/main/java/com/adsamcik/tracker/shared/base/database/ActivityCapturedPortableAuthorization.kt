package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedPortableWalTargetRow
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

internal fun validatePortableActivityAuthorizationTimelines(
	providersByGeneration: Map<Long, ProviderRegistrationGenerationEntity>,
	authorizationsByKey: Map<PortableActivityAuthorizationKey, List<SourceAuthorizationEntity>>,
): Map<Long, List<PortableActivityAuthorizationRevision>> {
	val timelines = authorizationsByKey.map { (key, rows) ->
		decodePortableActivityAuthorizationRevision(key, rows)
	}.groupBy { revision -> revision.key.registrationGeneration }
	if (timelines.keys != providersByGeneration.keys) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	return timelines.mapValues { (generation, unsorted) ->
		validatedPortableActivityProviderTimeline(providersByGeneration.getValue(generation), unsorted)
	}
}

internal fun validatePortableActivityWalAuthorizations(
	walRows: List<ActivityCapturedPortableWalTargetRow>,
	authorizationTimelines: Map<Long, List<PortableActivityAuthorizationRevision>>,
	providersByGeneration: Map<Long, ProviderRegistrationGenerationEntity>,
	demandsById: Map<String, SourceDemandEntity>,
) {
	walRows.forEach { wal ->
		val timeline = authorizationTimelines[wal.registrationGeneration]
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val provider = providersByGeneration[wal.registrationGeneration]
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val effective = timeline.lastOrNull { revision ->
			revision.effectiveBootId == wal.clockDomainId &&
				revision.effectiveElapsedRealtimeNanos <= wal.observedElapsedNanos
		}
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val snapshot = effective.rows.toAuthorizationSnapshotOrNull()
			?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		validatePortableActivityWalSnapshot(wal, effective, provider, snapshot, demandsById)
	}
}

internal fun validatePortableActivityCaptureClosingAuthority(
	row: SourceSessionCompletenessEntity,
	run: SourceServiceRunEntity,
	provider: ProviderRegistrationGenerationEntity,
	timeline: List<PortableActivityAuthorizationRevision>,
	retainedWalRows: List<ActivityCapturedPortableWalTargetRow>,
) {
	val captureIndexes = timeline.indices.filter { index ->
		timeline[index].hasCapturedRun(row.logicalTrackingId, row.serviceRunId)
	}
	if (captureIndexes.isEmpty()) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	val lastCaptureIndex = captureIndexes.last()
	val lastCapture = timeline[lastCaptureIndex]
	val closingIndex = (lastCaptureIndex + 1 until timeline.size).firstOrNull { index ->
		!timeline[index].hasCapturedRun(row.logicalTrackingId, row.serviceRunId)
	} ?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	val closing = timeline[closingIndex]
	if (timeline.drop(closingIndex).any { revision ->
			revision.hasCapturedRun(row.logicalTrackingId, row.serviceRunId)
		}) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	validatePortableActivityCaptureClosingBounds(provider, run, lastCapture, closing)
	val runWal = retainedWalRows.filter { wal ->
		wal.serviceRunId == row.serviceRunId &&
			wal.registrationGeneration == row.registrationGeneration
	}
	if (runWal.any { wal ->
			wal.observedElapsedNanos >= closing.effectiveElapsedRealtimeNanos
		}
	) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	if (provider.status in setOf(
			ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		) && (closing.effectiveElapsedRealtimeNanos >
			requireNotNull(provider.retiredElapsedRealtimeNanos) ||
		closing.effectiveWallTimeMs > requireNotNull(provider.retiredAtMs))
	) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
}

private fun decodePortableActivityAuthorizationRevision(
	key: PortableActivityAuthorizationKey, rows: List<SourceAuthorizationEntity>,
): PortableActivityAuthorizationRevision {
	val snapshot = rows.toAuthorizationSnapshotOrNull()
		?: abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	val first = rows.first()
	if (first.registrationGeneration != key.registrationGeneration ||
		first.authorizationRevision != key.authorizationRevision
	) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	return PortableActivityAuthorizationRevision(
		key = key,
		rows = rows,
		effectiveBootId = snapshot.effectiveBootId,
		effectiveElapsedRealtimeNanos = snapshot.effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = first.effectiveWallTimeMs,
	)
}

private fun validatedPortableActivityProviderTimeline(
	provider: ProviderRegistrationGenerationEntity, unsorted: List<PortableActivityAuthorizationRevision>,
): List<PortableActivityAuthorizationRevision> {
	val revisions = unsorted.sortedWith(PORTABLE_AUTHORIZATION_REVISION_ORDER)
	if (revisions.isEmpty() || revisions.map { it.key.authorizationRevision }.distinct().size !=
		revisions.size || revisions.any { revision ->
			revision.exceedsPortableActivityProviderBounds(provider)
		} || revisions.zipWithNext().any { (previous, current) ->
			current.precedesPortableActivityAuthorization(previous)
		}
	) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	val barrierRevision = provider.captureCallbackBarrierAuthorizationRevision
	if (barrierRevision > 0L && revisions.singleOrNull { revision ->
			revision.key.authorizationRevision == barrierRevision
		}?.hasAnyPersistentCapture != true
	) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	validatePortableActivityAuthorizationEnd(provider, revisions)
	return revisions
}

private fun PortableActivityAuthorizationRevision.exceedsPortableActivityProviderBounds(
	provider: ProviderRegistrationGenerationEntity,
): Boolean = effectiveBootId != provider.clockDomainId ||
	effectiveElapsedRealtimeNanos < provider.reservedElapsedRealtimeNanos ||
	effectiveWallTimeMs < provider.reservedAtMs ||
	provider.retiredElapsedRealtimeNanos?.let { retired -> effectiveElapsedRealtimeNanos > retired } == true ||
	provider.retiredAtMs?.let { retired -> effectiveWallTimeMs > retired } == true

private fun PortableActivityAuthorizationRevision.precedesPortableActivityAuthorization(
	previous: PortableActivityAuthorizationRevision,
): Boolean = key.authorizationRevision <= previous.key.authorizationRevision ||
	effectiveElapsedRealtimeNanos < previous.effectiveElapsedRealtimeNanos ||
	effectiveWallTimeMs < previous.effectiveWallTimeMs

private fun validatePortableActivityAuthorizationEnd(
	provider: ProviderRegistrationGenerationEntity, revisions: List<PortableActivityAuthorizationRevision>,
) {
	when (provider.status) {
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE -> {
			if (provider.retiredAtMs != null || provider.retiredElapsedRealtimeNanos != null ||
				revisions.last().isDenied
			) {
				abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
		ProviderRegistrationGenerationEntity.STATUS_RETIRING,
		ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		-> if (!revisions.last().isDenied ||
			revisions.last().effectiveElapsedRealtimeNanos >
				requireNotNull(provider.retiredElapsedRealtimeNanos) ||
			revisions.last().effectiveWallTimeMs > requireNotNull(provider.retiredAtMs)
		) {
			abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		else -> abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
}

private fun validatePortableActivityWalSnapshot(
	wal: ActivityCapturedPortableWalTargetRow, effective: PortableActivityAuthorizationRevision,
	provider: ProviderRegistrationGenerationEntity, snapshot: SourceAuthorizationSnapshot,
	demandsById: Map<String, SourceDemandEntity>,
) {
	val qualifiedAuthorizations = qualifyPortableActivityWalDemands(wal, effective.rows, demandsById)
	val qualifiedPurposeMask = qualifiedAuthorizations.fold(0L) { mask, authorization ->
		mask or SourceBrokerPurpose.mask(requireNotNull(authorization.purpose))
	}
	val capturedAuthorizations = qualifiedAuthorizations.filter { authorization ->
		!authorization.isDenyAll && authorization.purpose == SourceBrokerPurpose.SESSION_CAPTURE
	}
	if (qualifiedAuthorizations.isEmpty() || capturedAuthorizations.size > 1) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	val captureMember = capturedAuthorizations.singleOrNull()?.takeIf { authorization ->
		authorization.matchesPortableActivityCaptureWal(wal)
	}
	val exactAttribution = wal.hasExactPortableActivityAttribution(captureMember, capturedAuthorizations, snapshot)
	validatePortableActivityWalEvidence(wal, effective, provider, snapshot, qualifiedPurposeMask, exactAttribution)
}

private fun qualifyPortableActivityWalDemands(
	wal: ActivityCapturedPortableWalTargetRow, rows: List<SourceAuthorizationEntity>,
	demandsById: Map<String, SourceDemandEntity>,
): List<SourceAuthorizationEntity> {
	val observedAgeNanos = wal.receivedElapsedNanos - wal.observedElapsedNanos
	return rows.mapNotNull { authorization ->
		val demandId = authorization.demandId ?: return@mapNotNull null
		val demand = demandsById[demandId] ?: return@mapNotNull null
		authorization.takeIf {
			demand.matchesPortableAuthorization(authorization) &&
				observedAgeNanos <= demand.maximumAgeMs.saturatedMillisecondsToNanos()
		}
	}
}

private fun SourceAuthorizationEntity.matchesPortableActivityCaptureWal(
	wal: ActivityCapturedPortableWalTargetRow,
): Boolean = persistenceEligible && logicalTrackingId == wal.logicalTrackingId &&
	serviceRunId == wal.serviceRunId && manifestRevision == wal.sessionManifestRevision &&
	lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration && sourcePolicyRevision == wal.sourcePolicyRevision &&
	consentEpoch == wal.captureConsentEpoch

private fun ActivityCapturedPortableWalTargetRow.hasExactPortableActivityAttribution(
	captureMember: SourceAuthorizationEntity?, capturedAuthorizations: List<SourceAuthorizationEntity>,
	snapshot: SourceAuthorizationSnapshot,
): Boolean {
	val hasCapturedShape = hasPortableActivityCaptureFields()
	return if (hasCapturedShape) {
		captureMember != null && logicalTrackingId?.isNotBlank() == true &&
			serviceRunId?.isNotBlank() == true && planAttribution ==
			ACTIVITY_PORTABLE_CAPTURED_PLAN_ATTRIBUTION
	} else {
		capturedAuthorizations.isEmpty() && !snapshot.isDenied &&
			planAttribution == RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION
	}
}

private fun ActivityCapturedPortableWalTargetRow.hasPortableActivityCaptureFields(): Boolean =
	logicalTrackingId != null || serviceRunId != null || sourcePolicyRevision != null ||
	captureConsentEpoch != null || sessionManifestRevision != null || lifecycleLeaseGeneration != null

private fun validatePortableActivityWalEvidence(
	wal: ActivityCapturedPortableWalTargetRow, effective: PortableActivityAuthorizationRevision,
	provider: ProviderRegistrationGenerationEntity, snapshot: SourceAuthorizationSnapshot,
	qualifiedPurposeMask: Long, exactAttribution: Boolean,
) {
	if (effective.key.authorizationRevision != wal.authorizationRevision ||
		wal.observedElapsedNanos < requireNotNull(provider.acceptedElapsedRealtimeNanos) ||
		provider.retiredElapsedRealtimeNanos?.let { retired ->
			wal.observedElapsedNanos >= retired
		} == true
	) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	if (snapshot.authorizationFingerprint != wal.authorizationFingerprint ||
		qualifiedPurposeMask != wal.authorizationPurposeEligibilityMask ||
		!exactAttribution
	) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
}

private fun validatePortableActivityCaptureClosingBounds(
	provider: ProviderRegistrationGenerationEntity, run: SourceServiceRunEntity,
	lastCapture: PortableActivityAuthorizationRevision, closing: PortableActivityAuthorizationRevision,
) {
	if (provider.captureCallbackBarrierAuthorizationRevision < lastCapture.key.authorizationRevision ||
		closing.effectiveWallTimeMs > requireNotNull(run.completedAtMs)
	) {
		abort(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
}

private fun SourceDemandEntity.matchesPortableAuthorization(
	authorization: SourceAuthorizationEntity,
): Boolean = sourceKind == authorization.sourceKind &&
	consumerId == authorization.consumerId &&
	purpose == authorization.purpose &&
	sourcePolicyRevision == authorization.sourcePolicyRevision &&
	consentEpoch == authorization.consentEpoch &&
	persistenceEligible == authorization.persistenceEligible &&
	logicalTrackingId == authorization.logicalTrackingId &&
	serviceRunId == authorization.serviceRunId &&
	manifestRevision == authorization.manifestRevision &&
	lifecycleLeaseGeneration == authorization.lifecycleLeaseGeneration

private fun Long.saturatedMillisecondsToNanos(): Long =
	if (this > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else {
		this * NANOS_PER_MILLISECOND
	}

internal data class PortableActivityAuthorizationKey(
	val registrationGeneration: Long,
	val authorizationRevision: Long,
)

internal data class PortableActivityAuthorizationRevision(
	val key: PortableActivityAuthorizationKey,
	val rows: List<SourceAuthorizationEntity>,
	val effectiveBootId: String,
	val effectiveElapsedRealtimeNanos: Long,
	val effectiveWallTimeMs: Long,
) {
	val isDenied: Boolean get() = rows.singleOrNull()?.isDenyAll == true
	val hasAnyPersistentCapture: Boolean get() = rows.any { row ->
		!row.isDenyAll && row.persistenceEligible &&
			row.purpose == SourceBrokerPurpose.SESSION_CAPTURE
	}

	fun hasCapturedRun(logicalTrackingId: String, serviceRunId: String): Boolean = rows.any { row ->
		!row.isDenyAll && row.persistenceEligible &&
			row.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
			row.logicalTrackingId == logicalTrackingId && row.serviceRunId == serviceRunId
	}
}

private val PORTABLE_AUTHORIZATION_REVISION_ORDER =
	compareBy<PortableActivityAuthorizationRevision>(
		PortableActivityAuthorizationRevision::effectiveElapsedRealtimeNanos,
	).thenBy { revision -> revision.key.authorizationRevision }

private fun abort(reason: PortableActivityExportUnverifiableReason): Nothing =
	throw PortableActivitySnapshotAbort(reason)

private const val RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION = 2
private const val NANOS_PER_MILLISECOND = 1_000_000L
