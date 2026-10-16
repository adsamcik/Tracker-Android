package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.SavedSessionCutoff
import com.adsamcik.tracker.shared.base.database.dao.WifiPortableWalIdentityRow
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemAuthority
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualification
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualifier
import com.adsamcik.tracker.shared.base.database.data.RetainedCaptureScopeIdentity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayload
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.FIRST_DELETION_GENERATION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CONTROL_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE

internal data class WifiCapturedLineage(
	val logicalFactId: String,
	val revisions: List<WifiCapturedFactRevisionEntity>,
	val earliestPossibleWallTimeMs: Long,
	val aggregateOwnerLogicalFactId: String?,
	val aggregateOwnerSemanticRevision: Long?,
	val aggregateOwnerCursorRevision: Long?,
) {
	val scope: WifiCapturedRunScope
		get() = revisions.first().let { revision ->
			WifiCapturedRunScope(revision.logicalTrackingId, revision.serviceRunId)
		}
}

internal data class WifiCapturedRunScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

internal fun WifiCapturedRunScope.retainedIdentity(
	collectedDataEpoch: Long,
): RetainedCaptureScopeIdentity = RetainedCaptureScopeIdentity(
	sourceKind = WIFI_SOURCE,
	logicalTrackingId = logicalTrackingId,
	serviceRunId = serviceRunId,
	collectedDataEpoch = collectedDataEpoch,
	scopeDeletionGeneration = 0L,
	logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
		WIFI_SOURCE,
		CAPTURE_PURPOSE,
		logicalTrackingId,
		serviceRunId,
	),
)

internal data class WifiAggregateReuseAuthority(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val sessionSegmentId: Long,
	val capturedSources: Set<SourceKind>,
	val controlSources: Set<SourceKind>,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val manifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val collectedDataEpoch: Long,
	val scopeDeletionGeneration: Long,
	val clockDomainId: String,
	val storedZoneId: String,
	val planPayloadVersion: Int,
	val planPayloadChecksum: String,
	val maximumObservationAgeNanos: Long,
	val resultContract: String,
	val registrationAppliedAtNanos: Long,
	val providerAcceptanceStartNanos: Long,
	val providerAcceptanceEndNanos: Long,
	val authorizationEffectStartNanos: Long,
	val authorizationEffectEndNanos: Long,
	val sessionRunEffectStartNanos: Long,
	val sessionRunEffectEndNanos: Long,
)

internal data class AuthenticatedWifiWal(
	val scope: WifiCapturedRunScope,
	val authority: WifiCaptureAuthority,
	val evidence: WifiWalObservationEvidence,
	val derivedAggregate: WifiCapturedFact.Aggregate?,
	val writerBindingGeneration: Long,
	val writerOwnerGeneration: Long,
) {
	val reuseAuthority = WifiAggregateReuseAuthority(
		authority.logicalTrackingId.value,
		authority.serviceRunId.value,
		authority.sessionSegmentId,
		authority.capturedSources,
		authority.controlSources,
		authority.sourceInstanceId.value,
		authority.registrationGeneration,
		authority.configurationRevision,
		authority.physicalConfigurationFingerprint,
		authority.authorizationRevision,
		authority.authorizationFingerprint,
		authority.purposeEligibilityMask,
		authority.sourcePolicyRevision,
		authority.captureConsentEpoch,
		authority.sessionManifestRevision,
		authority.lifecycleLeaseGeneration,
		authority.collectedDataEpoch,
		authority.scopeDeletionGeneration,
		authority.clockDomainId,
		authority.zoneId,
		authority.serializedAcquisitionPlan.payloadVersion,
		authority.serializedAcquisitionPlan.payloadChecksum,
		authority.acquisitionConfiguration.maximumObservationAgeNanos,
		authority.acquisitionConfiguration.resultContract.name,
		requireNotNull(authority.appliedRegistration.appliedAtElapsedRealtimeNanos),
		authority.temporalAuthority.providerAcceptance.startInclusiveNanos,
		authority.temporalAuthority.providerAcceptance.endExclusiveNanos,
		authority.temporalAuthority.authorizationEffect.startInclusiveNanos,
		authority.temporalAuthority.authorizationEffect.endExclusiveNanos,
		authority.temporalAuthority.sessionRunEffect.startInclusiveNanos,
		authority.temporalAuthority.sessionRunEffect.endExclusiveNanos,
	)
}

internal data class WifiCapturedWalAudit(
	val captureByEventId: Map<String, List<AuthenticatedWifiWal>>,
	val scopes: Set<WifiCapturedRunScope>,
	val latestDurableTimeMs: Long,
	val settlementByAdmissionOrdinal: Map<Long, WifiPortableWalSettlement> = emptyMap(),
	val portableAuthorityQueryCount: Int = 0,
	val portableAuthority: WifiPortableAuthoritySnapshot? = null,
)

internal data class WifiPortableWalSettlement(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val sourceSequence: Long,
	val captureScopes: Set<WifiCapturedRunScope>,
)

internal data class WifiCapturedAudit(
	val lineages: List<WifiCapturedLineage>,
	val lineagesById: Map<String, WifiCapturedLineage>,
	val cursorsById: Map<String, WifiCapturedFactCursorEntity>,
	val aggregateById: Map<String, WifiIdentityFreeAggregate>,
	val generationByScope: Map<WifiCapturedRunScope, WifiCaptureDeletionGenerationEntity>,
	val wal: WifiCapturedWalAudit,
	val latestDurableTimeMs: Long,
)

internal class WifiCapturedRetentionBlockedException(
	val reason: WifiCapturedRetentionBlockedReason,
) : IllegalStateException(reason.name)

internal class WifiCapturedSourceDeletionBlockedException(
	val reason: WifiCapturedSourceDeletionBlockedReason,
) : IllegalStateException(reason.name)

internal class WifiCapturedMaintenanceLimitExceeded : IllegalStateException()

internal fun block(reason: WifiCapturedRetentionBlockedReason): Nothing =
	throw WifiCapturedRetentionBlockedException(reason)

internal fun block(reason: WifiCapturedSourceDeletionBlockedReason): Nothing =
	throw WifiCapturedSourceDeletionBlockedException(reason)

internal fun List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>
	.toSnapshotOrNull(): SourceAuthorizationSnapshot? =
	runCatching { toAuthorizationSnapshotOrNull() }.getOrNull()

internal const val WIFI_PORTABLE_AUTHORITY_QUERY_LIMIT = 96

internal data class WifiPortableAuthoritySnapshot(
	val session: LogicalTrackingSessionEntity,
	val savedSessionCutoff: SavedSessionCutoff,
	val runsById: Map<String, SourceServiceRunEntity>,
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
	val segmentsById: Map<Long, SessionSegment>,
	val planHeadersByRevision: Map<Long, AcquisitionPlanRevisionEntity>,
	val desiredPlansByRevision: Map<Long, SourceDesiredPlanEntity>,
	val plansByRevision: Map<Long, WifiPlan>,
	val registrationsByGeneration: Map<Long, ProviderRegistrationGenerationEntity>,
	val authorizationsByKey: Map<Pair<Long, Long>, SourceAuthorizationSnapshot>,
	val authorizationsByGeneration: Map<Long, List<SourceAuthorizationSnapshot>>,
	val demandsById: Map<String, SourceDemandEntity>,
	val policiesByRevision: Map<Long, SourcePolicyEntity>,
	val consentsByIdentity: Map<String, SourceConsentEpochEntity>,
	val actionsByRun: Map<String, List<LifecycleDesiredActionEntity>>,
	val sequenceProofsByIdentity: Map<String, List<WifiPortableWalIdentityRow>>,
	val deliveryProofsByIdentity: Map<String, List<WifiPortableWalIdentityRow>>,
	val queryCount: Int,
) {
	/** Uses the same historical item rule as native admission, on this bounded export snapshot. */
	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	fun qualifyPurposeItems(
		wal: SourceEventWalEntity,
		payload: WifiWalPayload,
		providerStart: Long,
		providerEnd: Long,
	): RadioPurposeItems? {
		val revision = wal.authorizationRevision ?: return null
		val observedStart = wal.observedIntervalStartNanos ?: return null
		val all = authorizationsByGeneration[wal.registrationGeneration].orEmpty()
		val callback = authorizationsByKey[wal.registrationGeneration to revision] ?: return null
		if (authorizationAt(wal.registrationGeneration, wal.clockDomainId,
			wal.receivedElapsedNanos) != callback ||
			callback.authorizationFingerprint != wal.authorizationFingerprint ||
			callback.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask
		) {
			return null
		}
		val (timeline, demands) = qualifiedPurposeTimeline(all, revision, observedStart, callback) ?: return null
		val result = RadioPurposeItemQualifier.wifi(payload, RadioPurposeItemAuthority(
			WIFI_SOURCE, wal.registrationGeneration, wal.clockDomainId, providerStart, providerEnd,
			wal.receivedElapsedNanos, timeline, demands,
		), wal.payloadVersion) as? RadioPurposeItemQualification.Qualified ?: return null
		return RadioPurposeItems(callback, timeline, demands.associateBy(SourceDemandEntity::demandId),
			result.acceptedIndexesByDemandId)
	}

	private fun qualifiedPurposeTimeline(
		all: List<SourceAuthorizationSnapshot>,
		revision: Long,
		observedStart: Long,
		callback: SourceAuthorizationSnapshot,
	): Pair<List<SourceAuthorizationSnapshot>, List<SourceDemandEntity>>? {
		val first = all.lastOrNull { it.effectiveElapsedRealtimeNanos <= observedStart }
			?: all.firstOrNull() ?: return null
		val timeline = all.filter { it.authorizationRevision in first.authorizationRevision..revision }
		val demandIds = timeline.flatMap { it.authorizedMembers.mapNotNull { member -> member.demandId } }
			.distinct()
		val demands = demandIds.mapNotNull(demandsById::get)
		if (timeline.lastOrNull() != callback || demands.size != demandIds.size) {
			return null
		}
		return timeline to demands
	}

	fun authorizationAt(
		registrationGeneration: Long,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
	): SourceAuthorizationSnapshot? = authorizationsByGeneration[registrationGeneration].orEmpty()
		.lastOrNull { snapshot ->
			snapshot.effectiveBootId == bootId &&
				snapshot.effectiveElapsedRealtimeNanos <= observedElapsedRealtimeNanos
		}

	fun nextAuthorization(
		registrationGeneration: Long,
		current: SourceAuthorizationSnapshot,
	): SourceAuthorizationSnapshot? = authorizationsByGeneration[registrationGeneration].orEmpty()
		.firstOrNull { snapshot -> PORTABLE_AUTHORIZATION_ORDER.compare(snapshot, current) > 0 }

	fun plansForRegistration(registration: ProviderRegistrationGenerationEntity): List<WifiPlan> {
		val revisions = actionsByRun.values.flatten().filter { action ->
			action.sourceKind == WIFI_SOURCE && action.sourceInstanceId == registration.sourceInstanceId &&
				action.registrationGeneration == registration.registrationGeneration &&
				action.status == "START_ACCEPTED"
		}.map(LifecycleDesiredActionEntity::desiredPlanRevision).distinct()
		return revisions.mapNotNull(plansByRevision::get).filter { plan ->
			plan.physicalConfigurationFingerprint() == registration.physicalConfigurationFingerprint
		}.distinct()
	}

	fun planSatisfiesDemand(plan: WifiPlan, demand: SourceDemandEntity): Boolean =
		runCatching { demand.toSourceDemandContract() }.getOrNull()
			?.let(plan::satisfiesExactWifiContract) == true

	fun authenticatesHistoricalDemand(
		demand: SourceDemandEntity,
		authorization: SourceAuthorizationSnapshot,
	): Boolean {
		val purposes = demand.historicalWifiPurposes() ?: return false
		val policy = policiesByRevision[demand.sourcePolicyRevision] ?: return false
		val consent = consentsByIdentity[portableConsentIdentity(purposes.second, demand.consentEpoch)]
			?: return false
		return demand.matchesPortableHistoricalWifiAuthority(policy, consent, purposes, authorization)
	}
}

internal fun SourceDemandEntity.directPurposeIncludingCapture(): DirectSourceDemandPurpose? = when (purpose) {
	CAPTURE_PURPOSE -> DirectSourceDemandPurpose.SESSION_CAPTURE
	SourceBrokerPurpose.CONTROL_AUTOSTART -> DirectSourceDemandPurpose.CONTROL_AUTOSTART
	SourceBrokerPurpose.CONTROL_CONTINUATION -> DirectSourceDemandPurpose.CONTROL_CONTINUATION
	SourceBrokerPurpose.AMBIENT_PRODUCT -> DirectSourceDemandPurpose.AMBIENT_PRODUCT
	else -> null
}

internal fun SourceDemandEntity.policyPurposeIncludingCapture(): String? = when (purpose) {
	CAPTURE_PURPOSE -> CAPTURE_PURPOSE
	SourceBrokerPurpose.CONTROL_AUTOSTART,
	SourceBrokerPurpose.CONTROL_CONTINUATION,
	-> CONTROL_PURPOSE
	SourceBrokerPurpose.AMBIENT_PRODUCT -> SourceBrokerPurpose.AMBIENT_PRODUCT
	else -> null
}

internal fun SourcePolicyEntity.portableAuthorityFor(purpose: String): Pair<Long?, Boolean> = when (purpose) {
	CAPTURE_PURPOSE -> captureConsentEpoch to capturePersistenceEligible
	CONTROL_PURPOSE -> controlConsentEpoch to controlPersistenceEligible
	SourceBrokerPurpose.AMBIENT_PRODUCT -> ambientConsentEpoch to ambientPersistenceEligible
	else -> null to false
}

internal fun WifiCapturedRetentionBlockedReason.sourceDeletionReason(): WifiCapturedSourceDeletionBlockedReason =
	when (this) {
	WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED ->
		WifiCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED
	WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT ->
		WifiCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT
	WifiCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
		WifiCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED
	else -> WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE
}

internal fun latestWifiDurableTime(
	ownerUpdatedAtMs: Long?,
	wal: WifiCapturedWalAudit,
	lineages: List<WifiCapturedLineage>,
	cursors: Map<String, WifiCapturedFactCursorEntity>,
	generations: List<WifiCaptureDeletionGenerationEntity>,
): Long = maxOf(
	ownerUpdatedAtMs ?: 0L,
	wal.latestDurableTimeMs,
	lineages.maxOfOrNull { lineage ->
		lineage.revisions.maxOf(WifiCapturedFactRevisionEntity::appliedAtMs)
	} ?: 0L,
	cursors.values.maxOfOrNull(WifiCapturedFactCursorEntity::updatedAtMs) ?: 0L,
	generations.maxOfOrNull(WifiCaptureDeletionGenerationEntity::updatedAtMs) ?: 0L,
)

internal fun SourceDemandEntity.precedesAuthorization(authorization: SourceAuthorizationSnapshot): Boolean =
	requestedAtMs <= authorization.members.first().effectiveWallTimeMs &&
		(requestedBootId != authorization.effectiveBootId ||
			requestedElapsedRealtimeNanos <= authorization.effectiveElapsedRealtimeNanos)


internal fun requireOrderedWifiCursorPage(page: List<WifiCapturedFactCursorEntity>) {
	if (page != page.sortedBy(WifiCapturedFactCursorEntity::logicalFactId) ||
		page.distinctBy(WifiCapturedFactCursorEntity::logicalFactId).size != page.size
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}


internal fun WifiCapturedLineage.authenticatesWifiAggregateReference(
	referenced: WifiCapturedFactRevisionEntity?,
	ownerCursorRevision: Long,
): Boolean = referenced != null && revisions.last() == referenced &&
	ownerCursorRevision == referenced.semanticRevision &&
	revisions.all { it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }


internal fun requireWifiAggregateDependency(
	referenced: WifiCapturedFactRevisionEntity,
	dependent: WifiCapturedFactRevisionEntity,
	ownerAuthority: WifiAggregateReuseAuthority?,
	dependentAuthority: WifiAggregateReuseAuthority?,
	dependentAggregate: WifiIdentityFreeAggregate?,
) {
	if (dependent.factKind != WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ||
		ownerAuthority == null || dependentAuthority == null || ownerAuthority != dependentAuthority
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!referenced.hasFiniteAggregateReuseAuthority() || dependentAggregate == null ||
		!referenced.matchesIdentityFreeAggregate(dependentAggregate)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}


internal fun requireWifiDeletionGeneration(
	generation: WifiCaptureDeletionGenerationEntity,
	collectedDataEpoch: Long,
	scope: WifiCapturedRunScope,
	scopesWithFacts: Map<WifiCapturedRunScope, List<WifiCapturedLineage>>,
) {
	if (generation.collectedDataEpoch != collectedDataEpoch || scope in scopesWithFacts ||
		generation.generation != FIRST_DELETION_GENERATION
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

