package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.stats.api.repository.CellHistoryAvailability
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryChildCompleteness
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryObservation
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistorySignalQuality
import com.adsamcik.tracker.stats.api.repository.CellHistorySubscriptionGrouping
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.value.EpochMs
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Pure fail-closed composition of one bounded, transactionally loaded Cell snapshot. */
@Suppress("LargeClass", "TooManyFunctions")
internal object CellHistoryComposer {
	fun composeSelected(
		seed: SessionSegment,
		snapshot: CellHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): CellHistoryEntry? {
		val logicalId = seed.logicalTrackingId?.takeIf(String::isNotBlank)
			?: return legacy(seed)
		if (seed.serviceRunId.isNullOrBlank()) {
			return failed(logicalId, listOf(seed), CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		return composeGroup(logicalId, snapshot, laneExecutionAuthority)
	}

	fun composeRecent(
		snapshot: CellHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): List<ComposedCellEntry> = snapshot.expansion.segments.asSequence()
		.mapNotNull(SessionSegment::logicalTrackingId)
		.filter(String::isNotBlank)
		.distinct()
		.mapNotNull { logicalId ->
			val members = members(logicalId, snapshot)
			val entry = composeGroup(logicalId, snapshot, laneExecutionAuthority) ?: return@mapNotNull null
			ComposedCellEntry(
				logicalTrackingId = logicalId,
				recencyStartTimeMs = members.maxOfOrNull(SessionSegment::startTimeMs) ?: 0L,
				recencySegmentId = members.maxOfOrNull(SessionSegment::id) ?: 0L,
				entry = entry,
			)
		}.toList()

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun composeGroup(
		logicalId: String,
		snapshot: CellHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): CellHistoryEntry? {
		val segments = members(logicalId, snapshot)
		if (segments.isEmpty()) return null
		if (snapshot.overflow) return failed(logicalId, segments, CellHistoryCause.READ_BUDGET_EXCEEDED)
		snapshot.expansion.failures[logicalId]?.let { return failed(logicalId, segments, it) }

		val runIds = segments.mapNotNull(SessionSegment::serviceRunId)
		if (runIds.size != segments.size || runIds.distinct().size != runIds.size) {
			return failed(logicalId, segments, CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val runs = runIds.mapNotNull(snapshot.runs::get)
		if (runs.size != runIds.size || runs.any { !it.ownsExactSegment(logicalId, segments) }) {
			return failed(logicalId, segments, CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		if (runs.any { !hasConsistentCompletion(it.state, it.completedAtMs) }) {
			return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val manifestsByRun = runs.associate { run ->
			run.serviceRunId to snapshot.manifestsByRun[run.serviceRunId].orEmpty()
				.sortedBy(SessionManifestVersionEntity::manifestRevision)
		}
		if (!hasValidManifestHistory(logicalId, runs, manifestsByRun, snapshot)) {
			return failed(logicalId, segments, CellHistoryCause.MANIFEST_INTEGRITY_FAILED)
		}
		val manifests = manifestsByRun.values.flatten()
		val zones = manifests.mapTo(linkedSetOf(), SessionManifestVersionEntity::zoneId)
		if (zones.any { runCatching { ZoneId.of(it) }.isFailure }) {
			return failed(logicalId, segments, CellHistoryCause.STORED_ZONE_INVALID)
		}
		val bindings = manifests.mapNotNull { manifest ->
			val matches = snapshot.sourcesByManifest[CellManifestKey(logicalId, manifest.manifestRevision)]
				.orEmpty().filter(::isCellCaptureMembership)
			if (matches.size > 1) return failed(logicalId, segments, CellHistoryCause.MANIFEST_INTEGRITY_FAILED)
			matches.singleOrNull()?.let { manifest.manifestRevision to it }
		}.toMap()
		// Cursor scope is the immutable discovery carrier. A current fact that moves its own
		// scope therefore remains in this set and fails the exact scope check below instead of
		// disappearing. Direct aggregate owners are the only permitted one-hop expansion.
		val carriedFactIds = snapshot.cursors.asSequence()
			.filter { it.logicalTrackingId == logicalId && it.serviceRunId in runIds }
			.mapTo(linkedSetOf(), CellCapturedFactCursorEntity::logicalFactId)
		val ownerFactIds = snapshot.revisions.asSequence()
			.filter { it.logicalFactId in carriedFactIds }
			.mapNotNull(CellCapturedFactRevisionEntity::aggregateOwnerLogicalFactId)
			.toSet()
		val relatedFactIds = carriedFactIds + ownerFactIds
		val relatedRevisions = snapshot.revisions.filter { it.logicalFactId in relatedFactIds }
		if (carriedFactIds.any { carriedId ->
				relatedRevisions.none { revision -> revision.logicalFactId == carriedId }
			}
		) {
			return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (relatedRevisions.any { it.logicalTrackingId != logicalId || it.serviceRunId !in runIds }) {
			return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (bindings.isEmpty()) {
			return if (relatedRevisions.isEmpty()) unavailable(logicalId, segments, zones,
				CellHistoryCause.SOURCE_NOT_CAPTURED) else failed(logicalId, segments,
				CellHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (!hasValidCaptureAuthority(manifests, bindings, snapshot)) {
			return failed(logicalId, segments, CellHistoryCause.PLAN_INTEGRITY_FAILED)
		}

		val lane = snapshot.lanes.singleOrNull(::isCellLane)
		val requiredModeMask = manifests.fold(0L) { mask, manifest ->
			mask or when (manifest.sessionMode) {
				"MANUAL" -> MANUAL_CAPTURE_MASK
				"AUTOMATIC" -> AUTOMATIC_CAPTURE_MASK
				else -> 0L
			}
		}
		if (lane == null || !laneExecutionAuthority.owns(lane) || !isValidLane(lane) ||
			requiredModeMask == 0L || lane.captureModeMask and requiredModeMask != requiredModeMask ||
			manifests.any { lane.activatedRolloutRevision > it.rolloutRevision }
		) return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)

		val session = snapshot.sessions[logicalId]
			?: return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		val cellCompleteness = runs.flatMap { snapshot.completenessByRun[it.serviceRunId].orEmpty() }
			.filter { it.sourceKind == CELL_SOURCE }
		if (!hasValidCompleteness(cellCompleteness, logicalId, runIds)) {
			return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		if (runs.all { it.completedAtMs != null } && relatedRevisions.isNotEmpty() &&
			runIds.any { runId -> cellCompleteness.none { it.serviceRunId == runId } }
		) return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		val targetOrdinal = maxOfOrNull(
			relatedRevisions.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal),
			cellCompleteness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
		)
		if (!hasValidSessionSettlement(session, runs, manifestsByRun, targetOrdinal) ||
			targetOrdinal?.let { target -> target < lane.activationOrdinal ||
				lane.captureAdmissionCutoffOrdinal?.let { it < target } == true } == true ||
			snapshot.terminalFailures.isNotEmpty()
		) return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)

		val evidenceState = snapshot.evidenceState
			?: return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
		val current = mutableListOf<Pair<CellCapturedFactRevisionEntity, CellCapturedFactRevisionEntity>>()
		for ((_, lineage) in relatedRevisions.groupBy(CellCapturedFactRevisionEntity::logicalFactId)) {
			val latest = validateLineage(lineage.sortedBy(CellCapturedFactRevisionEntity::semanticRevision), snapshot)
				?: return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
			val aggregate = resolveAggregate(latest, snapshot)
				?: return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
			if (!factHasExactAuthority(latest, aggregate, snapshot, manifestsByRun, bindings, evidenceState)) {
				return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			current += latest to aggregate
		}

		val deletedRuns = runIds.filter { runId ->
			val generation = snapshot.deletionGenerations[logicalId to runId]
			generation != null && generation.collectedDataEpoch != evidenceState.collectedDataEpoch
		}.toSet()
		if (deletedRuns.isNotEmpty()) return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)

		val currentEpoch = current.filter { (fact, aggregate) ->
			fact.hasCurrentPrivacyAuthority(logicalId, evidenceState, snapshot) &&
				aggregate.hasCurrentPrivacyAuthority(logicalId, evidenceState, snapshot)
		}
		val epochLoss = currentEpoch.size != current.size
		val retained = runCatching {
			currentEpoch.filter { (fact, aggregate) ->
				evidenceState.retainedFromMs?.let { floor ->
					earliestPossibleWallTimeMs(fact) >= floor &&
						earliestPossibleWallTimeMs(aggregate) >= floor
				} != false
			}
		}.getOrElse { return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED) }
		val retentionLoss = retained.size != currentEpoch.size
		if (retained.isEmpty()) {
			return when {
				current.isNotEmpty() && epochLoss -> unavailable(logicalId, segments, zones,
					CellHistoryCause.PRIVACY_EPOCH_MISMATCH)
				currentEpoch.isNotEmpty() && retentionLoss -> unavailable(logicalId, segments, zones,
					CellHistoryCause.RETENTION_LIMIT)
				targetOrdinal != null && lane.contiguousAdmissionOrdinal < targetOrdinal ->
					materializing(logicalId, segments, zones)
				isActive(session, runs) -> materializing(logicalId, segments, zones)
				cellCompleteness.any { it.stopStatus in PROVIDER_UNAVAILABLE_STATUSES } ->
					unavailable(logicalId, segments, zones, CellHistoryCause.PROVIDER_UNAVAILABLE)
				else -> missing(logicalId, segments, zones)
			}
		}

		val observations = runCatching {
			retained.sortedWith(compareBy<Pair<CellCapturedFactRevisionEntity, CellCapturedFactRevisionEntity>>(
				{ it.first.observedWallTimeMs }, { it.first.sourceAdmissionOrdinal }, { it.first.logicalFactId },
			)).map { (fact, aggregate) -> toObservation(fact, aggregate) }
		}.getOrElse { return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED) }
		val causes = linkedSetOf<CellHistoryCause>()
		if (isActive(session, runs)) causes += CellHistoryCause.SESSION_ACTIVE
		if (lane.contiguousAdmissionOrdinal < (targetOrdinal ?: lane.activationOrdinal)) {
			causes += CellHistoryCause.MATERIALIZATION_BEHIND
		}
		if (epochLoss) causes += CellHistoryCause.PRIVACY_EPOCH_MISMATCH
		if (retentionLoss) causes += CellHistoryCause.RETENTION_LIMIT
		if (cellCompleteness.any { row ->
			row.unresolvedSequenceStart != null || row.unresolvedSequenceEnd != null ||
				row.providerCoverage != COMPLETE_PROVIDER_COVERAGE || row.stopStatus != COMPLETE_STOP_STATUS
		}) causes += CellHistoryCause.ACQUISITION_INCOMPLETE
		if (retained.any { it.first.childCompleteness == CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_PARTIAL }) {
			causes += CellHistoryCause.CHILDREN_PARTIAL
		}
		// v1 deliberately cannot prove complete multi-SIM grouping.
		causes += CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN
		val coverage = if (causes.any { it == CellHistoryCause.CHILDREN_PARTIAL ||
			it == CellHistoryCause.RETENTION_LIMIT || it == CellHistoryCause.PRIVACY_EPOCH_MISMATCH }) {
			CellHistoryCoverage.PARTIAL
		} else {
			CellHistoryCoverage.UNKNOWN
		}
		val state = if (causes.isEmpty()) CellHistoryProductState.READY else CellHistoryProductState.PARTIAL
		return entry(logicalId, segments, zones, state, coverage, observations, causes)
	}

	private fun hasValidManifestHistory(
		logicalId: String,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		snapshot: CellHistorySnapshot,
	): Boolean = SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
		manifestsByRun.values.map { it.map(SessionManifestVersionEntity::manifestRevision) },
	) && runs.all { run ->
		val manifests = manifestsByRun[run.serviceRunId].orEmpty()
		SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) && manifests.all { manifest ->
			manifest.logicalTrackingId == logicalId && manifest.serviceRunId == run.serviceRunId &&
				SessionManifestIntegrity.verify(
					manifest,
					snapshot.sourcesByManifest[CellManifestKey(logicalId, manifest.manifestRevision)].orEmpty(),
				)
		}
	}

	private fun hasValidCaptureAuthority(
		manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
		snapshot: CellHistorySnapshot,
	): Boolean = bindings.all { (revision, binding) ->
		val manifest = manifests.singleOrNull { it.manifestRevision == revision } ?: return@all false
		val policy = snapshot.policies[manifest.sourcePolicyRevision]
		val consent = snapshot.consents[binding.consentEpoch]
		val planHeader = snapshot.planHeaders[manifest.acquisitionPlanRevision]
		val desiredPlan = snapshot.desiredPlans[manifest.acquisitionPlanRevision]
		val plan = desiredPlan?.let(CellHistoryPlanIntegrity::decode)
		isExactCellWriter(binding) && policy != null && consent != null && planHeader != null &&
			plan != null && plan.enabled && plan.revision == manifest.acquisitionPlanRevision &&
			planHeader.revision == manifest.acquisitionPlanRevision &&
			planHeader.sourcePolicyRevision == manifest.sourcePolicyRevision &&
			policy.sourceKind == CELL_SOURCE && policy.policyRevision == manifest.sourcePolicyRevision &&
			policy.enabled && policy.capturePersistenceEligible && policy.qosCode == binding.qosCode &&
			policy.captureConsentEpoch == binding.consentEpoch &&
			policy.effectiveBootId == manifest.effectiveBootId &&
			policy.effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos &&
			consent.sourceKind == CELL_SOURCE && consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			consent.epoch == binding.consentEpoch && consent.policyRevision <= policy.policyRevision &&
			consent.eligible && consent.persistenceEligible && consent.effectiveBootId == manifest.effectiveBootId &&
			consent.effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos
	}

	private fun validateLineage(
		ordered: List<CellCapturedFactRevisionEntity>,
		snapshot: CellHistorySnapshot,
	): CellCapturedFactRevisionEntity? {
		if (ordered.isEmpty() || ordered.map(CellCapturedFactRevisionEntity::semanticRevision) !=
			(1L..ordered.size.toLong()).toList()) return null
		if (ordered.any { !CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(it) }) return null
		if (ordered.zipWithNext().any { (previous, next) -> !next.isExactSuccessorOf(previous) }) return null
		val latest = ordered.last()
		val cursor = snapshot.cursors.singleOrNull { it.logicalFactId == latest.logicalFactId }
		return latest.takeIf { cursor?.matches(latest) == true }
	}

	private fun resolveAggregate(
		fact: CellCapturedFactRevisionEntity,
		snapshot: CellHistorySnapshot,
	): CellCapturedFactRevisionEntity? = when (fact.factKind) {
		CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> fact
		CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> {
			val ownerId = fact.aggregateOwnerLogicalFactId ?: return null
			val ownerRevision = fact.aggregateOwnerSemanticRevision ?: return null
			val ownerLineage = snapshot.revisions.filter { it.logicalFactId == ownerId }
			val owner = ownerLineage.singleOrNull { it.semanticRevision == ownerRevision } ?: return null
			val currentOwner = validateLineage(ownerLineage.sortedBy(CellCapturedFactRevisionEntity::semanticRevision),
				snapshot) ?: return null
			owner.takeIf { owner.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
				ownerLineage.all { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE } &&
				currentOwner.logicalFactId == ownerId && owner.hasCompatibleAggregateAuthority(fact, snapshot) }
		}
		else -> null
	}

	@Suppress("ComplexCondition", "LongMethod")
	private fun factHasExactAuthority(
		fact: CellCapturedFactRevisionEntity,
		aggregate: CellCapturedFactRevisionEntity,
		snapshot: CellHistorySnapshot,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		bindings: Map<Long, SessionManifestSourceEntity>,
		evidenceState: SourceEvidenceState,
	): Boolean {
		val run = snapshot.runs[fact.serviceRunId] ?: return false
		val manifests = manifestsByRun[fact.serviceRunId].orEmpty()
		val manifestIndex = manifests.indexOfFirst { it.manifestRevision == fact.manifestRevision }
		if (manifestIndex < 0) return false
		val manifest = manifests[manifestIndex]
		val nextManifest = manifests.getOrNull(manifestIndex + 1)
		val binding = bindings[fact.manifestRevision] ?: return false
		val plan = snapshot.desiredPlans[fact.configurationRevision]?.let(CellHistoryPlanIntegrity::decode)
			?: return false
		val provider = snapshot.providerRegistrations[fact.registrationGeneration] ?: return false
		val authorizations = snapshot.authorizationsByRegistration[fact.registrationGeneration].orEmpty()
		val session = snapshot.sessions[fact.logicalTrackingId] ?: return false
		val sessionEnd = session.cutoffElapsedNanos
			.takeIf { session.lifecycleBootId == fact.clockDomainId } ?: Long.MAX_VALUE
		val providerEnd = provider.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		val consent = snapshot.consents[fact.captureConsentEpoch] ?: return false
		val expectedManifestEnd = nextManifest?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, providerEnd, fact.authorizationEffectEndNanos)
		val maximumObservationAgeNanos = runCatching {
			Math.multiplyExact(plan.maximumAgeMs, 1_000_000L)
		}.getOrNull() ?: return false
		return fact.writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
			fact.writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
			fact.writerBindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION &&
			fact.writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
			fact.purpose == SourceBrokerPurpose.SESSION_CAPTURE && isExactCellWriter(binding) &&
			fact.sessionSegmentId == run.sessionSegmentId && fact.logicalTrackingId == run.logicalTrackingId &&
			fact.manifestRevision == manifest.manifestRevision &&
			fact.sourcePolicyRevision == manifest.sourcePolicyRevision &&
			fact.captureConsentEpoch == binding.consentEpoch && fact.lifecycleLeaseGeneration == run.leaseGeneration &&
			fact.clockDomainId == run.bootId && fact.storedZoneId == manifest.zoneId &&
			fact.hasExactStoredDay() &&
			fact.consentEffectStartNanos == consent.effectiveElapsedRealtimeNanos &&
			fact.consentEffectEndNanos == fact.authorizationEffectEndNanos &&
			fact.sessionRunEffectStartNanos == manifest.effectiveElapsedRealtimeNanos &&
			fact.sessionRunEffectEndNanos == expectedManifestEnd &&
			fact.deletionEffectStartNanos == run.startedElapsedNanos &&
			fact.deletionEffectEndNanos == sessionEnd &&
			fact.coverageIntervalStartNanos >= manifest.effectiveElapsedRealtimeNanos &&
			(nextManifest == null || fact.coverageIntervalEndNanos < nextManifest.effectiveElapsedRealtimeNanos) &&
			plan.revision == fact.configurationRevision &&
			manifest.acquisitionPlanRevision == fact.configurationRevision &&
			plan.physicalConfigurationFingerprint == fact.physicalConfigurationFingerprint &&
			fact.maximumObservationAgeNanos == maximumObservationAgeNanos &&
			provider.sourceKind == CELL_SOURCE && provider.registrationGeneration == fact.registrationGeneration &&
			provider.sourceInstanceId == fact.sourceInstanceId && provider.clockDomainId == fact.clockDomainId &&
			provider.ownerScope == "source-broker:$CELL_SOURCE" &&
			provider.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
			provider.status in ACCEPTED_REGISTRATION_STATES &&
			provider.physicalConfigurationFingerprint == fact.physicalConfigurationFingerprint &&
			provider.collectedDataEpoch == fact.collectedDataEpoch &&
			provider.acceptedElapsedRealtimeNanos == fact.providerAcceptanceStartNanos &&
			providerEnd == fact.providerAcceptanceEndNanos &&
			hasExactAuthorization(fact, authorizations, providerEnd, snapshot.demands) &&
			fact.availability == CellCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE &&
			fact.subscriptionCompleteness == CellCapturedFactRevisionEntity.SUBSCRIPTION_COMPLETENESS_UNKNOWN &&
			aggregate.aggregateShapeIsValid() &&
			fact.collectedDataEpoch <= evidenceState.collectedDataEpoch
	}

	private fun hasExactAuthorization(
		fact: CellCapturedFactRevisionEntity,
		rows: List<SourceAuthorizationEntity>,
		providerEnd: Long,
		demands: Map<String, SourceDemandEntity>,
	): Boolean {
		val exact = rows.filter { it.authorizationRevision == fact.authorizationRevision }
		if (exact.isEmpty() || exact.any { row ->
			row.sourceKind != CELL_SOURCE || row.registrationGeneration != fact.registrationGeneration ||
				row.authorizationFingerprint != fact.authorizationFingerprint ||
				row.purposeEligibilityMask != fact.purposeEligibilityMask ||
				row.effectiveBootId != fact.clockDomainId ||
				row.effectiveElapsedRealtimeNanos != fact.authorizationEffectStartNanos
		}) return false
		val demandIds = exact.mapNotNull(SourceAuthorizationEntity::demandId)
		val exactDemands = demandIds.mapNotNull(demands::get)
		val first = exact.first()
		val recomputed = runCatching {
			SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
				registrationGeneration = fact.registrationGeneration,
				authorizationRevision = fact.authorizationRevision,
				demands = exactDemands,
				effectiveBootId = first.effectiveBootId,
				effectiveElapsedRealtimeNanos = first.effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = first.effectiveWallTimeMs,
			)
		}.getOrNull()
		if (demandIds.distinct().size != demandIds.size || exactDemands.size != demandIds.size ||
			recomputed?.sortedBy(SourceAuthorizationEntity::memberId) !=
			exact.sortedBy(SourceAuthorizationEntity::memberId)
		) return false
		val memberMatches = exact.any { row ->
			!row.isDenyAll && row.purpose == SourceBrokerPurpose.SESSION_CAPTURE && row.persistenceEligible &&
				row.logicalTrackingId == fact.logicalTrackingId && row.serviceRunId == fact.serviceRunId &&
				row.manifestRevision == fact.manifestRevision &&
				row.lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration &&
				row.sourcePolicyRevision == fact.sourcePolicyRevision && row.consentEpoch == fact.captureConsentEpoch
		}
		val nextAuthorizationStart = rows.asSequence().filter { row ->
			row.effectiveBootId == fact.clockDomainId &&
				(row.effectiveElapsedRealtimeNanos > fact.authorizationEffectStartNanos ||
					row.effectiveElapsedRealtimeNanos == fact.authorizationEffectStartNanos &&
						row.authorizationRevision > fact.authorizationRevision)
		}.minWithOrNull(compareBy(SourceAuthorizationEntity::effectiveElapsedRealtimeNanos,
			SourceAuthorizationEntity::authorizationRevision))?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE
		return memberMatches && minOf(providerEnd, nextAuthorizationStart) == fact.authorizationEffectEndNanos
	}

	private fun toObservation(
		fact: CellCapturedFactRevisionEntity,
		aggregate: CellCapturedFactRevisionEntity,
	): CellHistoryObservation {
		val spanMs = Math.subtractExact(fact.observedElapsedNanos, fact.coverageIntervalStartNanos) / 1_000_000L
		val start = Math.subtractExact(fact.observedWallTimeMs, spanMs)
		val rejected = listOf(fact.staleChildCount, fact.futureTimeChildCount, fact.missingTimeChildCount,
			fact.clockUnverifiableChildCount, fact.authorityMismatchChildCount,
			fact.unsupportedTechnologyChildCount).fold(0, Math::addExact)
		val technology = linkedMapOf<CellHistoryTechnology, Int>()
		fun put(kind: CellHistoryTechnology, count: Int?) { if (requireNotNull(count) > 0) technology[kind] = count }
		put(CellHistoryTechnology.GSM, aggregate.gsmCount)
		put(CellHistoryTechnology.CDMA, aggregate.cdmaCount)
		put(CellHistoryTechnology.WCDMA, aggregate.wcdmaCount)
		put(CellHistoryTechnology.TDSCDMA, aggregate.tdscdmaCount)
		put(CellHistoryTechnology.LTE, aggregate.lteCount)
		put(CellHistoryTechnology.NR, aggregate.nrCount)
		return CellHistoryObservation(
			intervalStartTime = EpochMs(start),
			observedTime = EpochMs(fact.observedWallTimeMs),
			wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
			availability = CellHistoryAvailability.AVAILABLE,
			subscriptionGrouping = CellHistorySubscriptionGrouping.UNKNOWN,
			childCompleteness = CellHistoryChildCompleteness.valueOf(fact.childCompleteness),
			submittedChildCount = fact.submittedChildCount,
			acceptedChildCount = fact.acceptedChildCount,
			rejectedChildCount = rejected,
			registeredObservationCount = requireNotNull(aggregate.registeredObservationCount),
			technologyMix = technology.toMap(),
			signalQuality = CellHistorySignalQuality(
				requireNotNull(aggregate.qualityUnknownCount),
				requireNotNull(aggregate.qualityNoneOrUnknownCount),
				requireNotNull(aggregate.qualityPoorCount),
				requireNotNull(aggregate.qualityModerateCount),
				requireNotNull(aggregate.qualityGoodCount),
				requireNotNull(aggregate.qualityGreatCount),
			),
			weakObservationCount = requireNotNull(aggregate.weakObservationCount),
			allKnownQualityIsWeak = requireNotNull(aggregate.allKnownQualityIsWeak),
			sourceQualityFlags = fact.qualityFlags,
			sourceQualityConfidence = fact.qualityConfidence,
			storedZoneId = fact.storedZoneId,
		)
	}

	private fun CellCapturedFactRevisionEntity.aggregateShapeIsValid(): Boolean =
		factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
			observationCount == acceptedChildCount &&
			listOf(gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount)
				.filterNotNull().fold(0, Math::addExact) == observationCount &&
			listOf(qualityUnknownCount, qualityNoneOrUnknownCount, qualityPoorCount,
				qualityModerateCount, qualityGoodCount, qualityGreatCount)
				.filterNotNull().fold(0, Math::addExact) == observationCount

	private fun CellCapturedFactRevisionEntity.hasCompatibleAggregateAuthority(
		dependent: CellCapturedFactRevisionEntity,
		snapshot: CellHistorySnapshot,
	): Boolean {
		val ownerSources = snapshot.manifestAuthorityShape(logicalTrackingId, manifestRevision) ?: return false
		val dependentSources = snapshot.manifestAuthorityShape(
			dependent.logicalTrackingId, dependent.manifestRevision,
		) ?: return false
		return logicalTrackingId == dependent.logicalTrackingId && serviceRunId == dependent.serviceRunId &&
			sessionSegmentId == dependent.sessionSegmentId && collectedDataEpoch == dependent.collectedDataEpoch &&
			scopeDeletionGeneration == dependent.scopeDeletionGeneration && storedZoneId == dependent.storedZoneId &&
			structuralEpochDay == dependent.structuralEpochDay &&
			purposeEligibilityMask == dependent.purposeEligibilityMask &&
			captureConsentEpoch == dependent.captureConsentEpoch && ownerSources == dependentSources
	}

	private fun CellCapturedFactRevisionEntity.hasCurrentPrivacyAuthority(
		logicalId: String,
		evidenceState: SourceEvidenceState,
		snapshot: CellHistorySnapshot,
	): Boolean = collectedDataEpoch == evidenceState.collectedDataEpoch &&
		sourceAdmissionOrdinal > evidenceState.deletedSourceEventHighWaterOrdinal &&
		scopeDeletionGeneration ==
			(snapshot.deletionGenerations[logicalId to serviceRunId]?.generation ?: 0L) &&
		(logicalId to serviceRunId) !in snapshot.deletedScopes

	private fun CellHistorySnapshot.manifestAuthorityShape(
		logicalTrackingId: String,
		manifestRevision: Long,
	): Pair<Set<Int>, Set<Int>>? {
		val sources = sourcesByManifest[CellManifestKey(logicalTrackingId, manifestRevision)] ?: return null
		val captured = sources.filter { source ->
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible
		}.mapTo(linkedSetOf(), SessionManifestSourceEntity::sourceKind)
		val control = sources.filter { it.purpose == SessionManifestPurposeCode.CONTROL }
			.mapTo(linkedSetOf(), SessionManifestSourceEntity::sourceKind)
		return captured to control
	}

	private fun CellCapturedFactRevisionEntity.isExactSuccessorOf(
		previous: CellCapturedFactRevisionEntity,
	): Boolean = fixedCorrectionAuthority() == previous.fixedCorrectionAuthority() &&
		providerAcceptanceEndNanos.settles(previous.providerAcceptanceEndNanos) &&
		authorizationEffectEndNanos.settles(previous.authorizationEffectEndNanos) &&
		consentEffectEndNanos.settles(previous.consentEffectEndNanos) &&
		sessionRunEffectEndNanos.settles(previous.sessionRunEffectEndNanos) &&
		deletionEffectEndNanos.settles(previous.deletionEffectEndNanos)

	@Suppress("LongMethod")
	private fun CellCapturedFactRevisionEntity.fixedCorrectionAuthority(): List<Any?> = listOf(
		writerProjectionId, writerProjectionVersion, writerBindingGeneration, writerOwnerGeneration,
		logicalFactId, logicalTrackingId, serviceRunId, sessionSegmentId, purpose, sourceDeliveryIdentity,
		sourceEventId, sourceAdmissionOrdinal, walIntegrityIdentity, payloadChecksum, deliveryUnitIndex,
		deliveryUnitCount, sourceSequence, planAttribution, payloadVersion, canonicalProviderSemanticsDigest,
		sourceInstanceId, registrationGeneration, configurationRevision, physicalConfigurationFingerprint,
		authorizationRevision, authorizationFingerprint, purposeEligibilityMask, sourcePolicyRevision,
		captureConsentEpoch, manifestRevision, lifecycleLeaseGeneration, collectedDataEpoch,
		scopeDeletionGeneration, clockDomainId, storedZoneId, structuralEpochDay,
		providerAcceptanceStartNanos, authorizationEffectStartNanos, consentEffectStartNanos,
		sessionRunEffectStartNanos, deletionEffectStartNanos, maximumObservationAgeNanos,
		observedIntervalStartNanos, observedElapsedNanos, receivedElapsedNanos,
		observedWallTimeMs, wallTimeUncertaintyMs, acquiredAtMs, createdAtMs, qualityFlags, qualityConfidence,
		factKind, aggregateOwnerLogicalFactId, aggregateOwnerSemanticRevision,
		coverageIntervalStartNanos, coverageIntervalEndNanos, availability,
		submittedChildCount, acceptedChildCount, staleChildCount, futureTimeChildCount,
		missingTimeChildCount, clockUnverifiableChildCount, authorityMismatchChildCount,
		unsupportedTechnologyChildCount, subscriptionCompleteness, childCompleteness,
		observationCount, registeredObservationCount, gsmCount, cdmaCount, wcdmaCount, tdscdmaCount,
		lteCount, nrCount, qualityUnknownCount, qualityNoneOrUnknownCount, qualityPoorCount,
		qualityModerateCount, qualityGoodCount, qualityGreatCount, weakObservationCount,
		knownQualityObservationCount, allKnownQualityIsWeak,
	)

	private fun Long.settles(previous: Long): Boolean =
		this == previous || previous == Long.MAX_VALUE && this < Long.MAX_VALUE

	private fun CellCapturedFactCursorEntity.matches(fact: CellCapturedFactRevisionEntity): Boolean =
		writerProjectionId == fact.writerProjectionId && writerProjectionVersion == fact.writerProjectionVersion &&
		logicalFactId == fact.logicalFactId && logicalTrackingId == fact.logicalTrackingId &&
		serviceRunId == fact.serviceRunId && sessionSegmentId == fact.sessionSegmentId &&
		writerOwnerGeneration == fact.writerOwnerGeneration && collectedDataEpoch == fact.collectedDataEpoch &&
		scopeDeletionGeneration == fact.scopeDeletionGeneration &&
		latestSemanticRevision == fact.semanticRevision && latestMutationId == fact.mutationId &&
		latestEffectChecksum == fact.effectChecksum && latestSourceAdmissionOrdinal == fact.sourceAdmissionOrdinal &&
		cursorRevision == fact.semanticRevision && updatedAtMs == fact.appliedAtMs

	private fun hasValidCompleteness(
		rows: List<SourceSessionCompletenessEntity>, logicalId: String, runIds: List<String>,
	): Boolean = rows.groupBy(SourceSessionCompletenessEntity::serviceRunId).all { (runId, runRows) ->
		runId in runIds && runRows.map(SourceSessionCompletenessEntity::registrationGeneration).distinct().size ==
			runRows.size && runRows.all { row ->
				row.logicalTrackingId == logicalId && row.sourceInstanceId.isNotBlank() &&
					row.registrationGeneration >= 0L && row.lastAdmissionOrdinal?.let { it > 0L } != false &&
					row.lastSourceSequence?.let { it >= 0L } != false &&
					(row.unresolvedSequenceStart == null) == (row.unresolvedSequenceEnd == null) &&
					row.providerCoverage in PROVIDER_COVERAGE_VALUES && row.stopStatus in STOP_STATUS_VALUES &&
					row.updatedAtMs >= 0L
			}
	}

	private fun hasValidSessionSettlement(
		session: LogicalTrackingSessionEntity,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		targetOrdinal: Long?,
	): Boolean {
		if (!hasConsistentCompletion(session.state, session.completedAtMs)) return false
		val latestByRun = runs.associateWith { run ->
			manifestsByRun[run.serviceRunId].orEmpty()
				.maxByOrNull(SessionManifestVersionEntity::manifestRevision) ?: return false
		}
		if (latestByRun.any { (run, manifest) ->
			run.desiredPlanRevision != manifest.acquisitionPlanRevision ||
				run.rolloutRevision != manifest.rolloutRevision
		}) return false
		val latestLogicalManifest = latestByRun.values.maxByOrNull(SessionManifestVersionEntity::manifestRevision)
			?: return false
		if (session.desiredPlanRevision != latestLogicalManifest.acquisitionPlanRevision ||
			session.rolloutRevision != latestLogicalManifest.rolloutRevision ||
			session.sessionMode != latestLogicalManifest.sessionMode
		) return false
		val active = runs.filter { it.completedAtMs == null && it.state !in TERMINAL_STATES }
		if (active.isNotEmpty()) {
			val run = active.singleOrNull() ?: return false
			val latestManifest = manifestsByRun[run.serviceRunId].orEmpty()
				.maxByOrNull(SessionManifestVersionEntity::manifestRevision) ?: return false
			val normalAdmission = session.state in ADMISSION_SESSION_STATES &&
				run.state in ADMISSION_RUN_STATES
			val suspendingRun = session.state == "ACTIVE" && run.state == "STOPPING"
			val stoppingSession = session.state == "STOPPING" && run.state == "STOPPING"
			val validPair = normalAdmission || suspendingRun || stoppingSession
			val cutoffValid = if (stoppingSession) {
				session.cutoffAtMs != null && session.cutoffElapsedNanos != null
			} else session.cutoffAtMs == null && session.cutoffElapsedNanos == null
			return validPair && cutoffValid && session.currentServiceRunId == run.serviceRunId &&
				session.currentManifestRevision == latestManifest.manifestRevision &&
				session.lifecycleLeaseGeneration == run.leaseGeneration &&
				session.lifecycleBootId == run.bootId && session.finalAdmissionOrdinal == null
		}
		return session.state in TERMINAL_STATES && session.cutoffAtMs != null &&
			session.cutoffElapsedNanos != null && session.currentServiceRunId == null &&
			session.finalAdmissionOrdinal?.let { targetOrdinal == null || targetOrdinal <= it } == true
	}

	private fun hasConsistentCompletion(state: String, completedAtMs: Long?): Boolean =
		(state in TERMINAL_STATES) == (completedAtMs != null) && state in ALL_SESSION_STATES

	private fun isActive(session: LogicalTrackingSessionEntity, runs: List<SourceServiceRunEntity>): Boolean =
		session.completedAtMs == null && runs.any { it.completedAtMs == null }

	private fun SourceServiceRunEntity.ownsExactSegment(
		logicalId: String, segments: List<SessionSegment>,
	): Boolean {
		val segment = segments.singleOrNull { it.id == sessionSegmentId } ?: return false
		return logicalTrackingId == logicalId && segment.serviceRunId == serviceRunId &&
			segment.logicalTrackingId == logicalId &&
			presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
	}

	private fun earliestPossibleWallTimeMs(fact: CellCapturedFactRevisionEntity): Long =
		Math.subtractExact(
			Math.subtractExact(fact.observedWallTimeMs,
				Math.subtractExact(fact.observedElapsedNanos, fact.coverageIntervalStartNanos) / 1_000_000L),
			fact.wallTimeUncertaintyMs,
		)

	private fun CellCapturedFactRevisionEntity.hasExactStoredDay(): Boolean = runCatching {
		val zone = ZoneId.of(storedZoneId)
		val earliest = earliestPossibleWallTimeMs(this)
		val latest = Math.addExact(observedWallTimeMs, wallTimeUncertaintyMs)
		val firstDay = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate().toEpochDay()
		val lastDay = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate().toEpochDay()
		earliest >= 0L && firstDay == lastDay && firstDay == structuralEpochDay
	}.getOrDefault(false)

	private fun isExactCellWriter(source: SessionManifestSourceEntity): Boolean =
		source.sourceKind == CELL_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible &&
			source.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
			source.writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
			source.writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
			source.writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
			source.writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
			source.writerBindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION

	private fun isCellCaptureMembership(source: SessionManifestSourceEntity): Boolean =
		source.sourceKind == CELL_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible

	private fun isCellLane(lane: SourceProductProjectionLaneEntity): Boolean =
		lane.sourceKind == CELL_SOURCE && lane.bindingGeneration ==
			SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION &&
			lane.projectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
			lane.projectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION

	private fun isValidLane(lane: SourceProductProjectionLaneEntity): Boolean =
		lane.captureModeMask > 0L && lane.captureModeMask and ALL_CAPTURE_MASK.inv() == 0L &&
		lane.activationOrdinal > 0L && lane.contiguousAdmissionOrdinal >= lane.activationOrdinal - 1L &&
		when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE -> lane.captureAdmissionCutoffOrdinal == null &&
				lane.terminalDisposition == null && lane.terminalAtMs == null && lane.retentionRequired
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> lane.captureAdmissionCutoffOrdinal != null &&
				lane.contiguousAdmissionOrdinal >= requireNotNull(lane.captureAdmissionCutoffOrdinal) &&
				lane.terminalDisposition == SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
				lane.terminalAtMs != null && !lane.retentionRequired
			else -> false
		}

	private fun members(logicalId: String, snapshot: CellHistorySnapshot): List<SessionSegment> =
		snapshot.expansion.segments.filter { it.logicalTrackingId == logicalId }
			.sortedWith(compareBy(SessionSegment::startTimeMs, SessionSegment::id))

	private fun entry(
		logicalId: String,
		segments: List<SessionSegment>,
		zones: Set<String>,
		state: CellHistoryProductState,
		coverage: CellHistoryCoverage,
		observations: List<CellHistoryObservation>,
		causes: Set<CellHistoryCause>,
	): CellHistoryEntry {
		val start = segments.minOf(SessionSegment::startTimeMs).coerceAtLeast(0L)
		val end = segments.maxOf(SessionSegment::endTimeMs).coerceAtLeast(start)
		return CellHistoryEntry(CellHistoryEntryKey("cell-logical:$logicalId"), EpochMs(start), EpochMs(end),
			zones, state, coverage, observations, causes)
	}

	private fun failed(logicalId: String, segments: List<SessionSegment>, cause: CellHistoryCause) =
		entry(logicalId, segments, emptySet(), CellHistoryProductState.FAILED, CellHistoryCoverage.NONE,
			emptyList(), setOf(cause))

	private fun unavailable(
		logicalId: String, segments: List<SessionSegment>, zones: Set<String>, cause: CellHistoryCause,
	) = entry(logicalId, segments, zones, CellHistoryProductState.UNAVAILABLE, CellHistoryCoverage.NONE,
		emptyList(), setOf(cause))

	private fun materializing(logicalId: String, segments: List<SessionSegment>, zones: Set<String>) =
		entry(logicalId, segments, zones, CellHistoryProductState.MATERIALIZING, CellHistoryCoverage.NONE,
			emptyList(), setOf(CellHistoryCause.MATERIALIZATION_BEHIND))

	private fun missing(logicalId: String, segments: List<SessionSegment>, zones: Set<String>) =
		entry(logicalId, segments, zones, CellHistoryProductState.MISSING, CellHistoryCoverage.NONE,
			emptyList(), setOf(CellHistoryCause.NO_QUALIFIED_FACTS))

	private fun legacy(segment: SessionSegment) = CellHistoryEntry(
		CellHistoryEntryKey("cell-legacy:${segment.id}"), EpochMs(segment.startTimeMs.coerceAtLeast(0L)),
		EpochMs(segment.endTimeMs.coerceAtLeast(segment.startTimeMs.coerceAtLeast(0L))), emptySet(),
		CellHistoryProductState.UNAVAILABLE, CellHistoryCoverage.NONE, emptyList(),
		setOf(CellHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun maxOfOrNull(left: Long?, right: Long?): Long? = when {
		left == null -> right
		right == null -> left
		else -> maxOf(left, right)
	}

	private val TERMINAL_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
	private val ALL_SESSION_STATES = TERMINAL_STATES + setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
	private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
	private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
	private val PROVIDER_UNAVAILABLE_STATUSES = setOf("PERMISSION_LOST", "PROVIDER_FAILED")
	private val PROVIDER_COVERAGE_VALUES = setOf(
		"CALLBACKS_ENTERED_BEFORE_BARRIER", "PROVIDER_COMPLETENESS_UNOBSERVABLE",
	)
	private val STOP_STATUS_VALUES = setOf(
		"COMPLETE", "TIMED_OUT", "PERMISSION_LOST", "PROVIDER_FAILED", "PROCESS_RESTARTED",
	)
	private const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
	private const val COMPLETE_STOP_STATUS = "COMPLETE"
	private val ACCEPTED_REGISTRATION_STATES = setOf(
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		ProviderRegistrationGenerationEntity.STATUS_RETIRING,
		ProviderRegistrationGenerationEntity.STATUS_RETIRED,
	)
	private const val MANUAL_CAPTURE_MASK = 1L
	private const val AUTOMATIC_CAPTURE_MASK = 2L
	private const val ALL_CAPTURE_MASK = MANUAL_CAPTURE_MASK or AUTOMATIC_CAPTURE_MASK
}

/** Canonical v1 Cell desired-plan verifier kept local to the read model. */
internal object CellHistoryPlanIntegrity {
	fun decode(row: SourceDesiredPlanEntity): CellPlanEvidence? = runCatching {
		if (row.payloadVersion != 1 || row.payload.size > MAX_PAYLOAD_BYTES || sha256(row.payload) != row.payloadChecksum) {
			return null
		}
		DataInputStream(ByteArrayInputStream(row.payload)).use { input ->
			require(input.readInt() == 1)
			require(input.readUTF() == "CELL")
			val revision = input.readLong()
			val mode = input.readUTF()
			val minimumRefreshMs = input.readLong()
			val maximumAgeMs = input.readLong()
			val count = input.readInt()
			require(count in 0..MAX_SUBSCRIPTIONS)
			val subscriptions = buildSet(count) { repeat(count) { require(add(input.readInt())) } }
			val initialBackoffMs = input.readLong()
			val maximumBackoffMs = input.readLong()
			val multiplier = input.readDouble()
			require(input.available() == 0)
			require(revision == row.revision && row.sourceKind == CELL_SOURCE)
			require(minimumRefreshMs >= 0L && maximumAgeMs >= 0L)
			require(initialBackoffMs >= 0L && maximumBackoffMs >= initialBackoffMs &&
				multiplier.isFinite() && multiplier >= 1.0)
			require(mode in setOf("OFF", "OBSERVE_CHANGES", "OBSERVE_AND_SPARSE_REFRESH"))
			val canonical = when (mode) {
				"OFF" -> listOf("CELL", mode)
				"OBSERVE_CHANGES" -> listOf("CELL", "CHANGE_CALLBACK", subscriptions.sorted().joinToString(","))
				else -> listOf("CELL", "CHANGE_CALLBACK", "EXPLICIT_REFRESH",
					subscriptions.sorted().joinToString(","), minimumRefreshMs,
					initialBackoffMs, maximumBackoffMs, multiplier)
			}.joinToString("\u001f")
			CellPlanEvidence(
				revision,
				mode != "OFF",
				sha256(canonical.toByteArray(Charsets.UTF_8)),
				maximumAgeMs,
			)
		}
	}.getOrNull()

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }

	private const val MAX_PAYLOAD_BYTES = 64 * 1024
	private const val MAX_SUBSCRIPTIONS = 10_000
}

internal data class CellPlanEvidence(
	val revision: Long,
	val enabled: Boolean,
	val physicalConfigurationFingerprint: String,
	val maximumAgeMs: Long,
)

internal data class CellMembershipExpansion(
	val segments: List<SessionSegment>,
	val failures: Map<String, CellHistoryCause>,
	val overflow: Boolean = false,
)

internal data class CellManifestKey(val logicalTrackingId: String, val manifestRevision: Long)

@Suppress("LongParameterList")
internal data class CellHistorySnapshot(
	val expansion: CellMembershipExpansion,
	val sessions: Map<String, LogicalTrackingSessionEntity>,
	val runs: Map<String, SourceServiceRunEntity>,
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByManifest: Map<CellManifestKey, List<SessionManifestSourceEntity>>,
	val policies: Map<Long, SourcePolicyEntity>,
	val consents: Map<Long, SourceConsentEpochEntity>,
	val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
	val revisions: List<CellCapturedFactRevisionEntity>,
	val cursors: List<CellCapturedFactCursorEntity>,
	val deletionGenerations: Map<Pair<String, String>, CellCaptureDeletionGenerationEntity>,
	val deletedScopes: Set<Pair<String, String>>,
	val planHeaders: Map<Long, AcquisitionPlanRevisionEntity>,
	val desiredPlans: Map<Long, SourceDesiredPlanEntity>,
	val providerRegistrations: Map<Long, ProviderRegistrationGenerationEntity>,
	val authorizationsByRegistration: Map<Long, List<SourceAuthorizationEntity>>,
	val demands: Map<String, SourceDemandEntity>,
	val lanes: List<SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val evidenceState: SourceEvidenceState?,
	val overflow: Boolean,
) {
	companion object {
		fun empty(expansion: CellMembershipExpansion) = CellHistorySnapshot(expansion, emptyMap(), emptyMap(),
			emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(),
			emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), null,
			false)
	}
}

internal data class ComposedCellEntry(
	val logicalTrackingId: String,
	val recencyStartTimeMs: Long,
	val recencySegmentId: Long,
	val entry: CellHistoryEntry,
)

internal const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
