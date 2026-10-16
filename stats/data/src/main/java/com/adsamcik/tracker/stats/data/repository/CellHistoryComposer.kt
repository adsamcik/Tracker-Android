package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.SavedSessionCutoff
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationObservationInterval
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthenticator
import com.adsamcik.tracker.shared.base.database.CellPurposeItems
import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.cellPurposeWindow
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemAuthority
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualification
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualifier
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.aggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.AuthenticatedCellWalCarrier
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.CellWalPayload
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.CellHistoryAvailability
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryChildCompleteness
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryObservation
import com.adsamcik.tracker.stats.api.repository.CellObservedTower
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistorySignalQuality
import com.adsamcik.tracker.stats.api.repository.CellHistorySubscriptionGrouping
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.value.EpochMs
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Pure fail-closed composition of one bounded, transactionally loaded Cell snapshot. */
@Suppress("LargeClass", "TooManyFunctions")
internal object CellHistoryComposer {
	/** Qualifies one saved measured parent using its original source and permission state. */
	internal fun qualifyMeasuredFact(
		factId: String, revision: Long, snapshot: CellHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): QualifiedMeasuredCellFact? {
		if (snapshot.overflow) {
			throw MeasuredRadioParentFailure(
				com.adsamcik.tracker.stats.api.repository.HistoryProblemReason.DEPENDENCY_OVERFLOW)
		}
		val evidence = requireNotNull(snapshot.evidenceState)
		val lineage = snapshot.revisions.filter { it.logicalFactId == factId }.sortedBy { it.semanticRevision }
		val fact = requireNotNull(validateLineage(lineage, snapshot))
		check(fact.semanticRevision == revision)
		val aggregate = requireNotNull(resolveAggregate(fact, snapshot))
		val capture = measuredCaptureAuthority(fact, aggregate, snapshot, laneExecutionAuthority)
		checkMeasuredPrivacyGeneration(fact, aggregate, capture.run, snapshot, evidence)
		if (!hasRetainedPrivacy(fact, aggregate, snapshot, evidence)) { return null }
		val wal = requireNotNull(snapshot.admissions.singleOrNull { it.eventId == fact.sourceEventId })
		val ownerWal = requireNotNull(snapshot.admissions.singleOrNull { it.eventId == aggregate.sourceEventId })
		check(walMatchesFact(wal, fact) && walMatchesFact(ownerWal, aggregate))
		val authenticated = requireNotNull(authenticateCarriedFact(fact, aggregate, wal, ownerWal, snapshot))
		checkMeasuredFactAuthorities(authenticated, capture, snapshot, evidence)
		if (!withinRetention(authenticated, evidence)) { return null }
		val children = snapshot.childrenByRevision[fact.logicalFactId to fact.semanticRevision].orEmpty()
		val observation = toObservation(fact, aggregate, authenticated.coverageStartWallTimeMs, children)
		return QualifiedMeasuredCellFact(fact, observation, children, measuredFactIsPartial(fact, capture, snapshot))
	}

	private fun measuredCaptureAuthority(
		fact: CellCapturedFactRevisionEntity, aggregate: CellCapturedFactRevisionEntity,
		snapshot: CellHistorySnapshot, laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): MeasuredCellCaptureAuthority {
		val run = requireNotNull(snapshot.runs[fact.serviceRunId])
		val segment = snapshot.expansion.segments.singleOrNull { it.id == fact.sessionSegmentId }
		check(segment != null && run.ownsExactSegment(fact.logicalTrackingId, listOf(segment)) &&
			hasConsistentCompletion(run.state, run.completedAtMs))
		val manifests = snapshot.manifestsByRun[run.serviceRunId].orEmpty().sortedBy { it.manifestRevision }
		val byRun = mapOf(run.serviceRunId to manifests)
		check(hasValidManifestHistory(fact.logicalTrackingId, listOf(run), byRun, snapshot, allowSubset = true))
		val bindings = setOf(fact.manifestRevision, aggregate.manifestRevision).associateWith { revision ->
			requireNotNull(snapshot.sourcesByManifest[CellManifestKey(fact.logicalTrackingId, revision)]
				.orEmpty().singleOrNull(::isCellCaptureMembership))
		}
		check(hasValidCaptureAuthority(manifests, bindings, snapshot))
		val manifest = requireNotNull(manifests.singleOrNull { it.manifestRevision == fact.manifestRevision })
		val lane = requireNotNull(snapshot.lanes.singleOrNull {
			isCellLane(it) && it.bindingGeneration == fact.writerBindingGeneration })
		check(measuredLaneMatches(fact, manifest, lane, laneExecutionAuthority))
		checkMeasuredSession(fact, run, byRun, snapshot)
		return MeasuredCellCaptureAuthority(run, lane, byRun, bindings)
	}

	private fun measuredLaneMatches(
		fact: CellCapturedFactRevisionEntity, manifest: SessionManifestVersionEntity,
		lane: SourceProductProjectionLaneEntity, authority: SourceProductLaneExecutionAuthority,
	): Boolean {
		val mode = captureModeMask(manifest.sessionMode)
		val cutoff = lane.captureAdmissionCutoffOrdinal
		return qualifiedLane(lane, mode, listOf(manifest), authority) &&
			lane.activationOrdinal <= fact.sourceAdmissionOrdinal &&
			(cutoff == null || fact.sourceAdmissionOrdinal <= cutoff)
	}

	private fun checkMeasuredSession(
		fact: CellCapturedFactRevisionEntity, run: SourceServiceRunEntity,
		manifests: Map<String, List<SessionManifestVersionEntity>>, snapshot: CellHistorySnapshot,
	) {
		val session = requireNotNull(snapshot.sessions[fact.logicalTrackingId])
		check(hasConsistentCompletion(session.state, session.completedAtMs) &&
			session.lifecycleLeaseGeneration >= fact.lifecycleLeaseGeneration)
		// A current operation must agree exactly; completed runs keep their original authority.
		if (run.completedAtMs == null || session.currentServiceRunId == run.serviceRunId) {
			check(hasValidSessionSettlement(session, listOf(run), manifests, fact.sourceAdmissionOrdinal))
		}
	}

	private fun checkMeasuredPrivacyGeneration(
		fact: CellCapturedFactRevisionEntity, aggregate: CellCapturedFactRevisionEntity,
		run: SourceServiceRunEntity, snapshot: CellHistorySnapshot, evidence: SourceEvidenceState,
	) {
		check(snapshot.revisions.all {
			it.logicalTrackingId == fact.logicalTrackingId && it.serviceRunId == run.serviceRunId })
		check(snapshot.terminalFailures.none {
			it.admissionOrdinal == fact.sourceAdmissionOrdinal ||
                it.admissionOrdinal == aggregate.sourceAdmissionOrdinal })
		val generation = snapshot.deletionGenerations[fact.logicalTrackingId to run.serviceRunId]
		check(generation == null || evidence.acceptsRetainedEpoch(generation.collectedDataEpoch))
	}

	private fun hasRetainedPrivacy(
		fact: CellCapturedFactRevisionEntity, aggregate: CellCapturedFactRevisionEntity,
		snapshot: CellHistorySnapshot, evidence: SourceEvidenceState,
	): Boolean = fact.hasCurrentPrivacyAuthority(fact.logicalTrackingId, evidence, snapshot) &&
		aggregate.hasCurrentPrivacyAuthority(fact.logicalTrackingId, evidence, snapshot)

	private fun checkMeasuredFactAuthorities(
		authenticated: AuthenticatedCellHistoryFact, capture: MeasuredCellCaptureAuthority,
		snapshot: CellHistorySnapshot, evidence: SourceEvidenceState,
	) {
		val fact = authenticated.fact
		val aggregate = authenticated.aggregate
		check(factHasExactAuthority(fact, aggregate, authenticated.retentionLowerBoundMs, snapshot,
			capture.manifests, capture.bindings, evidence) &&
			(aggregate === fact || factHasExactAuthority(aggregate, aggregate,
				authenticated.aggregateRetentionLowerBoundMs, snapshot, capture.manifests, capture.bindings, evidence)))
	}

	private fun withinRetention(fact: AuthenticatedCellHistoryFact, evidence: SourceEvidenceState): Boolean =
		evidence.retainedFromMs?.let {
			fact.retentionLowerBoundMs < it || fact.aggregateRetentionLowerBoundMs < it } != true

	private fun measuredFactIsPartial(
		fact: CellCapturedFactRevisionEntity, capture: MeasuredCellCaptureAuthority, snapshot: CellHistorySnapshot,
	): Boolean {
		val completeness = snapshot.completenessByRun[capture.run.serviceRunId].orEmpty()
			.filter { it.sourceKind == CELL_SOURCE }
		return fact.childCompleteness == CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_PARTIAL ||
			snapshot.savedSessionCutoffs[fact.logicalTrackingId]?.clockUnknown == true ||
			capture.run.completedAtMs == null ||
                capture.lane.contiguousAdmissionOrdinal < fact.sourceAdmissionOrdinal ||
			measuredCompletenessIsPartial(completeness, fact.logicalTrackingId, capture.run.serviceRunId)
	}

	private fun measuredCompletenessIsPartial(
		rows: List<SourceSessionCompletenessEntity>, logicalId: String, runId: String,
	): Boolean = rows.isEmpty() || !hasValidCompleteness(rows, logicalId, listOf(runId)) ||
		rows.any { it.providerCoverage != COMPLETE_PROVIDER_COVERAGE ||
			it.stopStatus != COMPLETE_STOP_STATUS || it.unresolvedSequenceStart != null }

	private fun qualifiedLane(
		lane: SourceProductProjectionLaneEntity, mode: Long, manifests: List<SessionManifestVersionEntity>,
		authority: SourceProductLaneExecutionAuthority,
	): Boolean = authority.owns(lane) && isValidLane(lane) && mode != 0L &&
		lane.captureModeMask and mode == mode && manifests.none { lane.activatedRolloutRevision > it.rolloutRevision }

	private fun captureModeMask(mode: String): Long = when (mode) {
		"MANUAL" -> MANUAL_CAPTURE_MASK
		"AUTOMATIC" -> AUTOMATIC_CAPTURE_MASK
		else -> 0L
	}

	private fun walMatchesFact(wal: SourceEventWalEntity, fact: CellCapturedFactRevisionEntity): Boolean =
		wal.admissionOrdinal == fact.sourceAdmissionOrdinal && wal.deliveryIdentity == fact.sourceDeliveryIdentity

	private fun indexedWalMatches(
		wal: SourceEventWalEntity, fact: CellCapturedFactRevisionEntity,
		byOrdinal: Map<Long, SourceEventWalEntity>, byDelivery: Map<String?, SourceEventWalEntity>,
	): Boolean = byOrdinal[fact.sourceAdmissionOrdinal] == wal && byDelivery[fact.sourceDeliveryIdentity] == wal

	private fun authenticateCarriedFact(
		fact: CellCapturedFactRevisionEntity, aggregate: CellCapturedFactRevisionEntity,
		wal: SourceEventWalEntity, ownerWal: SourceEventWalEntity, snapshot: CellHistorySnapshot,
	): AuthenticatedCellHistoryFact? {
		val carrier = authenticateFactCarrier(fact, wal, snapshot)?.carrier ?: return null
		val ownerCarrier = authenticateFactCarrier(aggregate, ownerWal, snapshot)?.carrier ?: return null
		val lower = CellWalCarrierAuthenticator.retentionLowerBound(fact, carrier)
		val ownerLower = CellWalCarrierAuthenticator.retentionLowerBound(aggregate, ownerCarrier)
		val start = CellWalCarrierAuthenticator.coverageStartWallTime(fact, carrier)
		if (lower == null || ownerLower == null || start == null) { return null }
		return if (aggregateMatchesCarrier(fact, aggregate, carrier) &&
			CellWalCarrierAuthenticator.authenticatesAggregate(aggregate, ownerCarrier)) {
			AuthenticatedCellHistoryFact(fact, aggregate, lower, ownerLower, start)
		} else { null }
	}

	private fun aggregateMatchesCarrier(
		fact: CellCapturedFactRevisionEntity, aggregate: CellCapturedFactRevisionEntity,
		carrier: AuthenticatedCellWalCarrier,
	): Boolean = if (fact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) {
		CellWalCarrierAuthenticator.authenticatesAggregate(aggregate, carrier, fact)
	} else { CellWalCarrierAuthenticator.authenticatesAggregate(aggregate, carrier) }

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
			val recency = members.maxWithOrNull(
				compareBy(SessionSegment::startTimeMs, SessionSegment::id),
			) ?: return@mapNotNull null
			ComposedCellEntry(
				logicalTrackingId = logicalId,
				recencyStartTimeMs = recency.startTimeMs,
				recencySegmentId = recency.id,
				recencyTieIdentity = PortableCellOpaqueIdentity.derive(
					PortableCellIdentityKind.PHYSICAL_RUN,
					recency.serviceRunId?.takeIf(String::isNotBlank)
						?: "cell-source-segment:${recency.id}",
				),
				physicalSegmentIds = members.map(SessionSegment::id),
				capturesOnlyCell = hasExactCellOnlyCaptureIntent(logicalId, snapshot),
				entry = entry,
			)
		}.toList()

	internal fun hasExactCellOnlyCaptureIntent(
		logicalId: String,
		snapshot: CellHistorySnapshot,
	): Boolean {
		val segments = members(logicalId, snapshot)
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId)
		if (segments.isEmpty() || runIds.size != segments.size || runIds.distinct().size != runIds.size) {
			return false
		}
		val runs = runIds.mapNotNull(snapshot.runs::get)
		if (runs.size != runIds.size) {
			return false
		}
		val manifestsByRun = runs.associate { run ->
			run.serviceRunId to snapshot.manifestsByRun[run.serviceRunId].orEmpty()
				.sortedBy(SessionManifestVersionEntity::manifestRevision)
		}
		if (!hasValidManifestHistory(logicalId, runs, manifestsByRun, snapshot)) {
			return false
		}
		return manifestsByRun.values.flatten().all { manifest ->
			val membership = snapshot.sourcesByManifest[
				CellManifestKey(logicalId, manifest.manifestRevision)
			].orEmpty()
			val captured = membership.filter {
				it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
			}
			captured.size == 1 && captured.single().sourceKind == CELL_SOURCE &&
				captured.single().persistenceEligible
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun composeGroup(
		logicalId: String,
		snapshot: CellHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): CellHistoryEntry? {
		val segments = members(logicalId, snapshot)
		if (segments.isEmpty()) {
			return null
		}
		if (snapshot.overflow) {
			return failed(logicalId, segments, CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
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
			if (matches.size > 1) {
				return failed(logicalId, segments, CellHistoryCause.MANIFEST_INTEGRITY_FAILED)
			}
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
			return if (relatedRevisions.isEmpty()) {
				unavailable(logicalId, segments, zones,
				CellHistoryCause.SOURCE_NOT_CAPTURED)
			} else {
				failed(logicalId, segments,
				CellHistoryCause.FACT_INTEGRITY_FAILED)
			}
		}
		if (!hasValidCaptureAuthority(manifests, bindings, snapshot)) {
			return failed(logicalId, segments, CellHistoryCause.PLAN_INTEGRITY_FAILED)
		}

		val originalBinding = bindings.values.map(SessionManifestSourceEntity::writerBindingGeneration)
			.distinct().singleOrNull()
		val lane = snapshot.lanes.singleOrNull { isCellLane(it) && it.bindingGeneration == originalBinding }
			?: return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		val requiredModeMask = manifests.fold(0L) { mask, manifest -> mask or captureModeMask(manifest.sessionMode) }
		if (!qualifiedLane(lane, requiredModeMask, manifests, laneExecutionAuthority)) {
			return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		}

		val session = snapshot.sessions[logicalId]
			?: return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		val cellCompleteness = runs.flatMap { snapshot.completenessByRun[it.serviceRunId].orEmpty() }
			.filter { it.sourceKind == CELL_SOURCE }
		if (!hasValidCompleteness(cellCompleteness, logicalId, runIds)) {
			return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		if (runs.all { it.completedAtMs != null } && relatedRevisions.isNotEmpty() &&
			runIds.any { runId -> cellCompleteness.none { it.serviceRunId == runId } }
		) {
			return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val targetOrdinal = maxOfOrNull(
			relatedRevisions.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal),
			cellCompleteness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
		)
		val legacyTargetOrdinal = maxOfOrNull(
			relatedRevisions.filter { it.payloadVersion != CELL_WAL_PAYLOAD_VERSION }
				.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal),
			cellCompleteness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
		)
		if (!hasValidSessionSettlement(session, runs, manifestsByRun, legacyTargetOrdinal) ||
			targetOrdinal?.let { target -> target < lane.activationOrdinal ||
				lane.captureAdmissionCutoffOrdinal?.let { it < target } == true } == true ||
			snapshot.terminalFailures.isNotEmpty()
		) {
			return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		}

		val evidenceState = snapshot.evidenceState
			?: return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
		val admissionsByEventId = snapshot.admissions.associateBy(SourceEventWalEntity::eventId)
		val admissionsByOrdinal = snapshot.admissions.associateBy(SourceEventWalEntity::admissionOrdinal)
		val admissionsByDelivery = snapshot.admissions.associateBy(SourceEventWalEntity::deliveryIdentity)
		if (admissionsByEventId.size != snapshot.admissions.size ||
			admissionsByOrdinal.size != snapshot.admissions.size ||
			admissionsByDelivery.size != snapshot.admissions.size
		) {
			return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val current = mutableListOf<AuthenticatedCellHistoryFact>()
		val factOwnerByEvent = linkedMapOf<Pair<String, Long>, String>()
		for ((_, lineage) in relatedRevisions.groupBy(CellCapturedFactRevisionEntity::logicalFactId)) {
			val latest = validateLineage(lineage.sortedBy(CellCapturedFactRevisionEntity::semanticRevision), snapshot)
				?: return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
			val aggregate = resolveAggregate(latest, snapshot)
				?: return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
			val wal = admissionsByEventId[latest.sourceEventId]
				?: return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
			val aggregateWal = admissionsByEventId[aggregate.sourceEventId]
				?: return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
			if (!indexedWalMatches(wal, latest, admissionsByOrdinal, admissionsByDelivery) ||
				!indexedWalMatches(aggregateWal, aggregate, admissionsByOrdinal, admissionsByDelivery)) {
				return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			val authenticated = authenticateCarriedFact(latest, aggregate, wal, aggregateWal, snapshot)
				?: return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
			val retentionLowerBound = authenticated.retentionLowerBoundMs
			val aggregateRetentionLowerBound = authenticated.aggregateRetentionLowerBoundMs
			val priorFact = factOwnerByEvent.putIfAbsent(
				latest.sourceEventId to latest.manifestRevision, latest.logicalFactId)
			if (priorFact != null && priorFact != latest.logicalFactId) {
				return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			if (!factHasExactAuthority(
					latest,
					aggregate,
					retentionLowerBound,
					snapshot,
					manifestsByRun,
					bindings,
					evidenceState,
				)
				|| aggregate !== latest && !factHasExactAuthority(
					aggregate,
					aggregate,
					aggregateRetentionLowerBound,
					snapshot,
					manifestsByRun,
					bindings,
					evidenceState,
				)
			) {
				return failed(logicalId, segments, CellHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			current += authenticated
		}

		val deletedRuns = runIds.filter { runId ->
			val generation = snapshot.deletionGenerations[logicalId to runId]
			generation != null && !evidenceState.acceptsRetainedEpoch(generation.collectedDataEpoch)
		}.toSet()
		if (deletedRuns.isNotEmpty()) {
			return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED)
		}

		val currentEpoch = current.filter { authenticated ->
			authenticated.fact.hasCurrentPrivacyAuthority(logicalId, evidenceState, snapshot) &&
				authenticated.aggregate.hasCurrentPrivacyAuthority(logicalId, evidenceState, snapshot)
		}
		val epochLoss = currentEpoch.size != current.size
		val retained = runCatching {
			currentEpoch.filter { authenticated ->
				evidenceState.retainedFromMs?.let { floor ->
					authenticated.retentionLowerBoundMs >= floor &&
						authenticated.aggregateRetentionLowerBoundMs >= floor
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
			retained.sortedWith(compareBy<AuthenticatedCellHistoryFact>(
				{ it.fact.observedWallTimeMs },
				{ it.fact.sourceAdmissionOrdinal },
				{ it.fact.logicalFactId },
			)).map { authenticated ->
				toObservation(
					authenticated.fact,
					authenticated.aggregate,
					authenticated.coverageStartWallTimeMs,
					snapshot.childrenByRevision[
						authenticated.fact.logicalFactId to authenticated.fact.semanticRevision,
					].orEmpty(),
				)
			}
		}.getOrElse { return failed(logicalId, segments, CellHistoryCause.FACT_INTEGRITY_FAILED) }
		val causes = linkedSetOf<CellHistoryCause>()
		if (snapshot.savedSessionCutoffs[logicalId]?.clockUnknown == true) {
			causes += CellHistoryCause.ACQUISITION_INCOMPLETE
		}
		if (isActive(session, runs)) {
			causes += CellHistoryCause.SESSION_ACTIVE
		}
		if (lane.contiguousAdmissionOrdinal < (targetOrdinal ?: lane.activationOrdinal)) {
			causes += CellHistoryCause.MATERIALIZATION_BEHIND
		}
		if (epochLoss) {
			causes += CellHistoryCause.PRIVACY_EPOCH_MISMATCH
		}
		if (retentionLoss) {
			causes += CellHistoryCause.RETENTION_LIMIT
		}
		if (cellCompleteness.any { row ->
			row.unresolvedSequenceStart != null || row.unresolvedSequenceEnd != null ||
				row.providerCoverage != COMPLETE_PROVIDER_COVERAGE || row.stopStatus != COMPLETE_STOP_STATUS
		}) {
			causes += CellHistoryCause.ACQUISITION_INCOMPLETE
		}
		if (retained.any {
				it.fact.childCompleteness == CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_PARTIAL
			}
		) {
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
		val state = if (causes.isEmpty()) {
			CellHistoryProductState.READY
		} else {
			CellHistoryProductState.PARTIAL
		}
		return entry(logicalId, segments, zones, state, coverage, observations, causes)
	}

	private fun hasValidManifestHistory(
		logicalId: String,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		snapshot: CellHistorySnapshot,
		allowSubset: Boolean = false,
	): Boolean = (if (allowSubset) {
		SessionManifestIntegrity.hasValidLogicalManifestRevisionSliceUnion(
		manifestsByRun.values.map { it.map(SessionManifestVersionEntity::manifestRevision) },
	)
	} else {
		SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
		manifestsByRun.values.map { it.map(SessionManifestVersionEntity::manifestRevision) },
	)
	}) && runs.all { run ->
		val manifests = manifestsByRun[run.serviceRunId].orEmpty()
		SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests) && manifests.all { manifest ->
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
		hasValidCaptureBinding(manifest, binding, snapshot)
	}

	private fun hasValidCaptureBinding(
		manifest: SessionManifestVersionEntity,
		binding: SessionManifestSourceEntity,
		snapshot: CellHistorySnapshot,
	): Boolean {
		val policy = snapshot.policies[manifest.sourcePolicyRevision] ?: return false
		val consent = snapshot.consents[binding.consentEpoch] ?: return false
		return isExactCellWriter(binding) && hasValidCapturePlan(manifest, snapshot) &&
			policyMatchesCapture(policy, manifest, binding) && consentMatchesCapture(consent, policy, manifest, binding)
	}

	private fun hasValidCapturePlan(manifest: SessionManifestVersionEntity, snapshot: CellHistorySnapshot): Boolean {
		val header = snapshot.planHeaders[manifest.acquisitionPlanRevision] ?: return false
		val plan = snapshot.desiredPlans[manifest.acquisitionPlanRevision]?.let(CellHistoryPlanIntegrity::decode)
			?: return false
		return plan.enabled && plan.revision == manifest.acquisitionPlanRevision &&
			header.revision == manifest.acquisitionPlanRevision &&
			header.sourcePolicyRevision == manifest.sourcePolicyRevision
	}

	private fun policyMatchesCapture(
		policy: SourcePolicyEntity, manifest: SessionManifestVersionEntity, binding: SessionManifestSourceEntity,
	): Boolean = policy.sourceKind == CELL_SOURCE && policy.policyRevision == manifest.sourcePolicyRevision &&
		policy.enabled && policy.capturePersistenceEligible && policy.qosCode == binding.qosCode &&
		policy.captureConsentEpoch == binding.consentEpoch && policy.effectiveBootId == manifest.effectiveBootId &&
		policy.effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos

	private fun consentMatchesCapture(
		consent: SourceConsentEpochEntity, policy: SourcePolicyEntity,
		manifest: SessionManifestVersionEntity, binding: SessionManifestSourceEntity,
	): Boolean = consent.sourceKind == CELL_SOURCE && consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		consent.epoch == binding.consentEpoch && consent.policyRevision <= policy.policyRevision &&
		consent.eligible && consent.persistenceEligible && consent.effectiveBootId == manifest.effectiveBootId &&
		consent.effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos

	private fun validateLineage(
		ordered: List<CellCapturedFactRevisionEntity>,
		snapshot: CellHistorySnapshot,
	): CellCapturedFactRevisionEntity? {
		if (!CellCapturedFactRevisionIntegrity.hasValidRetainedLineage(ordered)) {
			return null
		}
		val latest = ordered.last()
		val cursor = snapshot.cursors.singleOrNull { it.logicalFactId == latest.logicalFactId }
		return latest.takeIf { candidate ->
			cursor?.let { CellCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(it, candidate) } == true
		}
	}

	private fun resolveAggregate(
		fact: CellCapturedFactRevisionEntity, snapshot: CellHistorySnapshot,
	): CellCapturedFactRevisionEntity? = when (fact.factKind) {
		CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> fact
		CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ->
			fact.aggregateOwnerLogicalFactId?.let { ownerId ->
				fact.aggregateOwnerSemanticRevision?.let { revision -> resolveAggregateOwner(fact, ownerId, revision,
                    snapshot) }
			}
		else -> null
	}

	private fun resolveAggregateOwner(
		fact: CellCapturedFactRevisionEntity, ownerId: String, revision: Long, snapshot: CellHistorySnapshot,
	): CellCapturedFactRevisionEntity? {
		val lineage = snapshot.revisions.filter { it.logicalFactId == ownerId }
		val owner = lineage.singleOrNull { it.semanticRevision == revision } ?: return null
		val current = validateLineage(lineage.sortedBy(CellCapturedFactRevisionEntity::semanticRevision), snapshot)
			?: return null
		val cursor = snapshot.cursors.singleOrNull { it.logicalFactId == ownerId } ?: return null
		return owner.takeIf { ownerIsCurrentAggregate(owner, current, lineage) &&
			CellCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(fact, owner, cursor) &&
			hasReusableAggregateAuthority(fact, owner, snapshot) }
	}

	private fun ownerIsCurrentAggregate(
		owner: CellCapturedFactRevisionEntity, current: CellCapturedFactRevisionEntity,
		lineage: List<CellCapturedFactRevisionEntity>,
	): Boolean = owner.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
		lineage.all { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE } && current == owner

	private fun hasReusableAggregateAuthority(
		fact: CellCapturedFactRevisionEntity, owner: CellCapturedFactRevisionEntity, snapshot: CellHistorySnapshot,
	): Boolean {
		val dependent = fact.aggregateReuseAuthority(snapshot) ?: return false
		val original = owner.aggregateReuseAuthority(snapshot) ?: return false
		return dependent.canReuseAggregateFrom(original)
	}

	private fun factHasExactAuthority(
		fact: CellCapturedFactRevisionEntity, aggregate: CellCapturedFactRevisionEntity,
		retentionLowerBoundMs: Long, snapshot: CellHistorySnapshot,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		bindings: Map<Long, SessionManifestSourceEntity>, evidenceState: SourceEvidenceState,
	): Boolean {
		val manifest = factManifestAuthority(fact, snapshot, manifestsByRun, bindings) ?: return false
		val provider = factProviderAuthority(fact, snapshot) ?: return false
		val purpose = if (fact.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			qualifiedCellPurposeWindow(fact, snapshot, provider.authentication.interval.startInclusiveNanos,
				provider.authentication.interval.endExclusiveNanos) ?: return false
		} else { null }
		return hasExactRetainedFactAuthority(fact, retentionLowerBoundMs, snapshot, manifest, provider, purpose) &&
			factProductMatches(fact, aggregate, evidenceState)
	}

	private fun hasExactRetainedFactAuthority(
		fact: CellCapturedFactRevisionEntity, retentionLowerBoundMs: Long, snapshot: CellHistorySnapshot,
		manifest: CellFactManifestAuthority, provider: CellFactProviderAuthority,
		purpose: CellPurposeItems.Qualified?,
	): Boolean = factPurposeMatches(fact, purpose, snapshot) &&
			factIdentityMatches(fact, manifest, retentionLowerBoundMs) &&
			factTimingMatches(fact, manifest, provider) && factPlanMatches(fact, manifest, purpose) &&
			factProviderMatches(fact, provider) && hasExactAuthorization(fact, provider.authorizations,
				provider.authentication.interval.endExclusiveNanos, snapshot.demands,
				fact.payloadVersion == CELL_WAL_PAYLOAD_VERSION, purpose)

	private fun factManifestAuthority(
		fact: CellCapturedFactRevisionEntity, snapshot: CellHistorySnapshot,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		bindings: Map<Long, SessionManifestSourceEntity>,
	): CellFactManifestAuthority? {
		val run = snapshot.runs[fact.serviceRunId] ?: return null
		val manifests = manifestsByRun[fact.serviceRunId].orEmpty()
		val index = manifests.indexOfFirst { it.manifestRevision == fact.manifestRevision }
		val manifest = manifests.getOrNull(index) ?: return null
		val binding = bindings[fact.manifestRevision] ?: return null
		val plan = snapshot.desiredPlans[fact.configurationRevision]?.let(CellHistoryPlanIntegrity::decode)
		val consent = snapshot.consents[fact.captureConsentEpoch]
		return if (plan == null || consent == null) { null } else {
			CellFactManifestAuthority(run, manifest, manifests.getOrNull(index + 1), binding, plan, consent)
		}
	}

	private fun factProviderAuthority(
		fact: CellCapturedFactRevisionEntity, snapshot: CellHistorySnapshot,
	): CellFactProviderAuthority? {
		val provider = snapshot.providerRegistrations[fact.registrationGeneration] ?: return null
		val session = snapshot.sessions[fact.logicalTrackingId] ?: return null
		val cutoff = snapshot.savedSessionCutoffs[fact.logicalTrackingId]
			?: SavedSessionCutoff(session.lifecycleBootId, session.cutoffElapsedNanos)
		val interval = ProviderRegistrationIntervalAuthenticator.authenticate(
			registration = provider, expectedSourceKind = CELL_SOURCE,
			expectedRegistrationGeneration = fact.registrationGeneration,
			expectedSourceInstanceId = fact.sourceInstanceId, expectedClockDomainId = fact.clockDomainId,
			expectedPhysicalConfigurationFingerprint = fact.physicalConfigurationFingerprint,
			expectedCollectedDataEpoch = fact.collectedDataEpoch,
			expectedProviderResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = fact.coverageIntervalStartNanos, observedEndNanos = fact.coverageIntervalEndNanos,
			observedWallTimeMs = fact.observedWallTimeMs, currentClockDomainId = snapshot.currentClockDomainId,
			currentProviderRegistrations = snapshot.currentProviderRegistrations,
		) as? ProviderRegistrationIntervalAuthentication.Authenticated ?: return null
		return CellFactProviderAuthority(provider, interval,
			snapshot.authorizationsByRegistration[fact.registrationGeneration].orEmpty(),
			cutoff.retainedEnd(fact.clockDomainId, fact.deletionEffectEndNanos))
	}

	private fun factPurposeMatches(
		fact: CellCapturedFactRevisionEntity, purpose: CellPurposeItems.Qualified?, snapshot: CellHistorySnapshot,
	): Boolean = purpose == null || (purpose.indexes.isNotEmpty() &&
		fact.authorizationEffectStartNanos == purpose.continuousFromNanos &&
		fact.coverageIntervalEndNanos < purpose.continuousUntilNanos &&
		snapshot.childrenByRevision[fact.logicalFactId to fact.semanticRevision].orEmpty()
			.none { it.itemIndex !in purpose.indexes })

	private fun factIdentityMatches(
		fact: CellCapturedFactRevisionEntity, authority: CellFactManifestAuthority, retentionLowerBoundMs: Long,
	): Boolean = factWriterMatches(fact, authority.binding) && factManifestMatches(fact, authority) &&
		fact.clockDomainId == authority.manifest.effectiveBootId && fact.storedZoneId == authority.manifest.zoneId &&
		fact.hasExactStoredDay(retentionLowerBoundMs)

	private fun factWriterMatches(fact: CellCapturedFactRevisionEntity, binding: SessionManifestSourceEntity): Boolean =
		fact.writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
		fact.writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
		fact.writerBindingGeneration == binding.writerBindingGeneration &&
		fact.writerOwnerGeneration == binding.writerOwnerGeneration &&
		fact.purpose == SourceBrokerPurpose.SESSION_CAPTURE && isExactCellWriter(binding)

	private fun factManifestMatches(fact: CellCapturedFactRevisionEntity,
        authority: CellFactManifestAuthority): Boolean =
		fact.sessionSegmentId == authority.run.sessionSegmentId &&
		fact.logicalTrackingId == authority.run.logicalTrackingId &&
		fact.manifestRevision == authority.manifest.manifestRevision &&
		fact.sourcePolicyRevision == authority.manifest.sourcePolicyRevision &&
		fact.captureConsentEpoch == authority.binding.consentEpoch &&
		SessionManifestIntegrity.hasRetainedLeaseGeneration(authority.run, fact.lifecycleLeaseGeneration)

	private fun factTimingMatches(
		fact: CellCapturedFactRevisionEntity, manifest: CellFactManifestAuthority, provider: CellFactProviderAuthority,
	): Boolean {
		val expectedManifestEnd = manifest.next?.effectiveElapsedRealtimeNanos ?:
			minOf(provider.sessionEnd, provider.authentication.interval.endExclusiveNanos,
                fact.authorizationEffectEndNanos)
		return factConsentAndManifestTimesMatch(fact, manifest, expectedManifestEnd) &&
			factSessionTimesMatch(fact, manifest, provider.sessionEnd)
	}

	private fun factConsentAndManifestTimesMatch(
		fact: CellCapturedFactRevisionEntity, authority: CellFactManifestAuthority, expectedEnd: Long,
	): Boolean = fact.consentEffectStartNanos == authority.consent.effectiveElapsedRealtimeNanos &&
		fact.consentEffectEndNanos == fact.authorizationEffectEndNanos &&
		fact.sessionRunEffectStartNanos == authority.manifest.effectiveElapsedRealtimeNanos &&
		(fact.sessionRunEffectEndNanos == expectedEnd || fact.sessionRunEffectEndNanos == Long.MAX_VALUE) &&
		fact.coverageIntervalEndNanos < expectedEnd

	private fun factSessionTimesMatch(
		fact: CellCapturedFactRevisionEntity, authority: CellFactManifestAuthority, sessionEnd: Long,
	): Boolean = fact.deletionEffectStartNanos == authority.run.startedElapsedNanos &&
		(fact.deletionEffectEndNanos == sessionEnd || fact.deletionEffectEndNanos == Long.MAX_VALUE) &&
		fact.coverageIntervalEndNanos < sessionEnd &&
		fact.coverageIntervalStartNanos >= authority.manifest.effectiveElapsedRealtimeNanos &&
		(authority.next == null || fact.coverageIntervalEndNanos < authority.next.effectiveElapsedRealtimeNanos)

	private fun factPlanMatches(
		fact: CellCapturedFactRevisionEntity, authority: CellFactManifestAuthority,
            purpose: CellPurposeItems.Qualified?,
	): Boolean {
		val maximumAge = runCatching { Math.multiplyExact(authority.plan.maximumAgeMs, NANOS_PER_MILLISECOND) }
			.getOrNull() ?: return false
		return authority.plan.revision == fact.configurationRevision &&
			authority.manifest.acquisitionPlanRevision == fact.configurationRevision &&
			authority.plan.physicalConfigurationFingerprint == fact.physicalConfigurationFingerprint &&
			fact.maximumObservationAgeNanos == minOf(maximumAge, purpose?.demandMaximumAgeNanos ?: maximumAge)
	}

	private fun factProviderMatches(fact: CellCapturedFactRevisionEntity,
        authority: CellFactProviderAuthority): Boolean =
		authority.provider.sourceKind == CELL_SOURCE &&
		authority.provider.registrationGeneration == fact.registrationGeneration &&
		authority.provider.sourceInstanceId == fact.sourceInstanceId &&
		authority.provider.clockDomainId == fact.clockDomainId &&
		authority.provider.ownerScope == "source-broker:$CELL_SOURCE" &&
		authority.provider.physicalConfigurationFingerprint == fact.physicalConfigurationFingerprint &&
		authority.provider.collectedDataEpoch == fact.collectedDataEpoch &&
		authority.provider.acceptedElapsedRealtimeNanos == fact.providerAcceptanceStartNanos &&
		authority.authentication.interval.endExclusiveNanos
			.settlesRecordedRadioEnd(fact.providerAcceptanceEndNanos)

	private fun factProductMatches(
		fact: CellCapturedFactRevisionEntity, aggregate: CellCapturedFactRevisionEntity,
            evidenceState: SourceEvidenceState,
	): Boolean = fact.availability == CellCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE &&
		fact.subscriptionCompleteness == CellCapturedFactRevisionEntity.SUBSCRIPTION_COMPLETENESS_UNKNOWN &&
		aggregate.aggregateShapeIsValid() && fact.collectedDataEpoch <= evidenceState.collectedDataEpoch

	private fun hasExactAuthorization(
		fact: CellCapturedFactRevisionEntity, rows: List<SourceAuthorizationEntity>, providerEnd: Long,
		demands: Map<String, SourceDemandEntity>, perReading: Boolean = false,
		purpose: CellPurposeItems.Qualified? = null,
	): Boolean {
		val exact = rows.filter { it.authorizationRevision == fact.authorizationRevision }
		if (exact.isEmpty() || exact.any { !authorizationHeaderMatches(it, fact, perReading) }) { return false }
		if (!authorizationDemandsMatch(exact, fact, demands)) { return false }
		val expectedEnd = if (perReading) { purpose?.continuousUntilNanos ?: return false }
			else { minOf(providerEnd, nextAuthorizationStart(fact, rows)) }
		return exact.any { captureMemberMatches(it, fact) } &&
			expectedEnd.settlesRecordedRadioEnd(fact.authorizationEffectEndNanos) &&
			fact.coverageIntervalEndNanos < expectedEnd
	}

	private fun authorizationHeaderMatches(
		row: SourceAuthorizationEntity, fact: CellCapturedFactRevisionEntity, perReading: Boolean,
	): Boolean = row.sourceKind == CELL_SOURCE && row.registrationGeneration == fact.registrationGeneration &&
		row.authorizationFingerprint == fact.authorizationFingerprint &&
		row.purposeEligibilityMask == fact.purposeEligibilityMask &&
		row.effectiveBootId == fact.clockDomainId &&
		(perReading || row.effectiveElapsedRealtimeNanos == fact.authorizationEffectStartNanos)

	private fun authorizationDemandsMatch(
		rows: List<SourceAuthorizationEntity>, fact: CellCapturedFactRevisionEntity,
		demands: Map<String, SourceDemandEntity>,
	): Boolean {
		val ids = rows.mapNotNull(SourceAuthorizationEntity::demandId)
		val exactDemands = ids.mapNotNull(demands::get)
		val first = rows.first()
		val recomputed = runCatching { SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE, registrationGeneration = fact.registrationGeneration,
			authorizationRevision = fact.authorizationRevision, demands = exactDemands,
			effectiveBootId = first.effectiveBootId, effectiveElapsedRealtimeNanos =
                first.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = first.effectiveWallTimeMs,
		) }.getOrNull()
		return ids.distinct().size == ids.size && exactDemands.size == ids.size &&
			recomputed?.sortedBy(SourceAuthorizationEntity::memberId) == rows.sortedBy(SourceAuthorizationEntity::memberId)
	}

	private fun captureMemberMatches(row: SourceAuthorizationEntity, fact: CellCapturedFactRevisionEntity): Boolean =
		!row.isDenyAll && row.purpose == SourceBrokerPurpose.SESSION_CAPTURE && row.persistenceEligible &&
		captureScopeMatches(row, fact) && capturePolicyMatches(row, fact)

	private fun captureScopeMatches(row: SourceAuthorizationEntity, fact: CellCapturedFactRevisionEntity): Boolean =
		row.logicalTrackingId == fact.logicalTrackingId && row.serviceRunId == fact.serviceRunId &&
		row.manifestRevision == fact.manifestRevision && row.lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration

	private fun capturePolicyMatches(row: SourceAuthorizationEntity, fact: CellCapturedFactRevisionEntity): Boolean =
		row.sourcePolicyRevision == fact.sourcePolicyRevision && row.consentEpoch == fact.captureConsentEpoch

	private fun nextAuthorizationStart(fact: CellCapturedFactRevisionEntity,
        rows: List<SourceAuthorizationEntity>): Long =
		rows.asSequence().filter { row -> nextAuthorizationMatches(row, fact) }
			.minWithOrNull(compareBy(SourceAuthorizationEntity::effectiveElapsedRealtimeNanos,
				SourceAuthorizationEntity::authorizationRevision))?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE

	private fun nextAuthorizationMatches(row: SourceAuthorizationEntity,
        fact: CellCapturedFactRevisionEntity): Boolean =
		row.effectiveBootId == fact.clockDomainId && if (fact.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			row.authorizationRevision > fact.authorizationRevision
		} else {
			row.effectiveElapsedRealtimeNanos > fact.authorizationEffectStartNanos ||
				row.effectiveElapsedRealtimeNanos == fact.authorizationEffectStartNanos &&
					row.authorizationRevision > fact.authorizationRevision
		}

	private fun authenticateFactCarrier(
		fact: CellCapturedFactRevisionEntity, wal: SourceEventWalEntity, snapshot: CellHistorySnapshot,
	): CellWalCarrierAuthentication.Authenticated? {
		val children = snapshot.childrenByRevision[fact.logicalFactId to fact.semanticRevision].orEmpty()
		val stored = CellWalCarrierAuthenticator.authenticate(fact, wal, children)
			as? CellWalCarrierAuthentication.Authenticated ?: return null
		return if (fact.payloadVersion != CELL_WAL_PAYLOAD_VERSION || hasExactPurposeCarrier(fact, wal, snapshot,
            stored)) {
			stored
		} else { null }
	}

	private fun hasExactPurposeCarrier(
		fact: CellCapturedFactRevisionEntity, wal: SourceEventWalEntity, snapshot: CellHistorySnapshot,
		stored: CellWalCarrierAuthentication.Authenticated,
	): Boolean {
		val purpose = qualifiedCellPurposeWindow(fact, snapshot,
			fact.providerAcceptanceStartNanos, fact.providerAcceptanceEndNanos) ?: return false
		val exact = CellWalCarrierAuthenticator.authenticate(fact, wal, purpose.indexes.toSet())
			as? CellWalCarrierAuthentication.Authenticated ?: return false
		return exact.carrier.childQualification == stored.carrier.childQualification
	}

	/** The same per-reading rule and uninterrupted demand window used by the Cell writer. */
	private fun qualifiedCellPurposeWindow(
		fact: CellCapturedFactRevisionEntity, snapshot: CellHistorySnapshot, providerStart: Long, providerEnd: Long,
	): CellPurposeItems.Qualified? {
		val wal = snapshot.admissions.singleOrNull { it.eventId == fact.sourceEventId } ?: return null
		val carrier = (CellWalCarrierAuthenticator.authenticate(wal)
			as? CellWalCarrierAuthentication.Authenticated)?.carrier ?: return null
		val timeline = cellPurposeTimeline(fact, wal, snapshot) ?: return null
		val interval = if (providerStart >= 0L && providerEnd > providerStart) {
			ProviderRegistrationObservationInterval(providerStart, providerEnd)
		} else { null }
		return interval?.let { qualifyCellTimelinePurpose(fact, wal, carrier.payload, timeline, snapshot, it) }
	}

	private fun cellPurposeTimeline(
		fact: CellCapturedFactRevisionEntity, wal: SourceEventWalEntity, snapshot: CellHistorySnapshot,
	): List<SourceAuthorizationSnapshot>? {
		val all = cellAuthorizationSnapshots(fact, snapshot) ?: return null
		val callback = all.singleOrNull { it.authorizationRevision == wal.authorizationRevision } ?: return null
		if (!callbackMatchesWal(callback, all, wal)) { return null }
		val first = all.lastOrNull {
			it.effectiveElapsedRealtimeNanos <= requireNotNull(wal.observedIntervalStartNanos)
		} ?: all.first()
		return all.filter { it.authorizationRevision in first.authorizationRevision..callback.authorizationRevision }
	}

	private fun cellAuthorizationSnapshots(
		fact: CellCapturedFactRevisionEntity, snapshot: CellHistorySnapshot,
	): List<SourceAuthorizationSnapshot>? =
		snapshot.authorizationsByRegistration[fact.registrationGeneration].orEmpty()
			.groupBy(SourceAuthorizationEntity::authorizationRevision).values.map { rows ->
				rows.toAuthorizationSnapshotOrNull() ?: return null
			}.sortedBy { it.authorizationRevision }

	private fun callbackMatchesWal(
		callback: SourceAuthorizationSnapshot,
		all: List<SourceAuthorizationSnapshot>, wal: SourceEventWalEntity,
	): Boolean = all.lastOrNull { it.effectiveBootId == wal.clockDomainId &&
		it.effectiveElapsedRealtimeNanos <= wal.receivedElapsedNanos } == callback &&
		callback.authorizationFingerprint == wal.authorizationFingerprint &&
		callback.purposeEligibilityMask == wal.authorizationPurposeEligibilityMask

	private fun qualifyCellTimelinePurpose(
		fact: CellCapturedFactRevisionEntity, wal: SourceEventWalEntity,
		payload: CellWalPayload,
		timeline: List<SourceAuthorizationSnapshot>,
		snapshot: CellHistorySnapshot, providerInterval: ProviderRegistrationObservationInterval,
	): CellPurposeItems.Qualified? {
		val ids = timeline.flatMap { it.authorizedMembers.mapNotNull(SourceAuthorizationEntity::demandId) }.distinct()
		val demands = ids.map { snapshot.demands[it] ?: return null }
		val qualified = RadioPurposeItemQualifier.cell(payload, RadioPurposeItemAuthority(
			CELL_SOURCE, fact.registrationGeneration, fact.clockDomainId, providerInterval.startInclusiveNanos,
			providerInterval.endExclusiveNanos, wal.receivedElapsedNanos, timeline, demands), wal.payloadVersion)
			as? RadioPurposeItemQualification.Qualified ?: return null
		val member = timeline.flatMap { it.authorizedMembers }.singleOrNull { purposeMemberMatches(it, fact) }
			?: return null
		val callback = timeline.single { it.authorizationRevision == wal.authorizationRevision }
		return RadioPurposeItems(callback, timeline, demands.associateBy(SourceDemandEntity::demandId),
			qualified.acceptedIndexesByDemandId).cellPurposeWindow(payload,
				requireNotNull(member.demandId), providerInterval.endExclusiveNanos) as? CellPurposeItems.Qualified
	}

	private fun purposeMemberMatches(row: SourceAuthorizationEntity, fact: CellCapturedFactRevisionEntity): Boolean =
		row.authorizationRevision == fact.authorizationRevision &&
		row.authorizationFingerprint == fact.authorizationFingerprint &&
		row.purposeEligibilityMask == fact.purposeEligibilityMask &&
		row.purpose == SourceBrokerPurpose.SESSION_CAPTURE && row.persistenceEligible &&
		captureScopeMatches(row, fact) && capturePolicyMatches(row, fact)

	private fun toObservation(
		fact: CellCapturedFactRevisionEntity,
		aggregate: CellCapturedFactRevisionEntity,
		coverageStartWallTimeMs: Long,
		children: List<CellCapturedChildEntity>,
	): CellHistoryObservation {
		val rejected = listOf(fact.staleChildCount, fact.futureTimeChildCount, fact.missingTimeChildCount,
			fact.clockUnverifiableChildCount, fact.authorityMismatchChildCount,
			fact.unsupportedTechnologyChildCount).fold(0, Math::addExact)
		val technology = linkedMapOf<CellHistoryTechnology, Int>()
		fun put(kind: CellHistoryTechnology, count: Int?) { if (requireNotNull(count) > 0) {
			technology[kind] = count
		} }
		put(CellHistoryTechnology.GSM, aggregate.gsmCount)
		put(CellHistoryTechnology.CDMA, aggregate.cdmaCount)
		put(CellHistoryTechnology.WCDMA, aggregate.wcdmaCount)
		put(CellHistoryTechnology.TDSCDMA, aggregate.tdscdmaCount)
		put(CellHistoryTechnology.LTE, aggregate.lteCount)
		put(CellHistoryTechnology.NR, aggregate.nrCount)
		return CellHistoryObservation(
			intervalStartTime = EpochMs(coverageStartWallTimeMs),
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
			localSourceEventId = fact.sourceEventId,
			towers = children.map { child -> CellObservedTower(
				observedTimeMs = child.observedWallTimeMs,
				wallTimeUncertaintyMs = child.wallTimeUncertaintyMs,
				identity = child.towerIdentity,
				technology = CellHistoryTechnology.valueOf(child.radioType),
				registered = child.registered,
				signalDbm = child.signalDbm,
			) },
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

	private fun CellCapturedFactRevisionEntity.aggregateReuseAuthority(
		snapshot: CellHistorySnapshot,
	): CellAggregateReuseAuthority? {
		val (capturedSources, controlSources) = snapshot.manifestAuthorityShape(
			logicalTrackingId,
			manifestRevision,
		) ?: return null
		return aggregateReuseAuthority(capturedSources, controlSources)
	}

	private fun CellCapturedFactRevisionEntity.hasCurrentPrivacyAuthority(
		logicalId: String,
		evidenceState: SourceEvidenceState,
		snapshot: CellHistorySnapshot,
	): Boolean = evidenceState.acceptsRetainedEpoch(collectedDataEpoch) &&
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

	private fun hasValidCompleteness(
		rows: List<SourceSessionCompletenessEntity>, logicalId: String, runIds: List<String>,
	): Boolean = rows.groupBy(SourceSessionCompletenessEntity::serviceRunId).all { (runId, runRows) ->
		runId in runIds && runRows.map(SourceSessionCompletenessEntity::registrationGeneration)
			.distinct().size == runRows.size && runRows.all { completenessRowMatches(it, logicalId) }
	}

	private fun completenessRowMatches(row: SourceSessionCompletenessEntity, logicalId: String): Boolean =
		row.logicalTrackingId == logicalId && row.sourceInstanceId.isNotBlank() &&
		row.registrationGeneration >= 0L && completenessTailMatches(row) &&
		row.providerCoverage in PROVIDER_COVERAGE_VALUES && row.stopStatus in STOP_STATUS_VALUES &&
		row.updatedAtMs >= 0L

	private fun completenessTailMatches(row: SourceSessionCompletenessEntity): Boolean =
		row.lastAdmissionOrdinal?.let { it > 0L } != false &&
		row.lastSourceSequence?.let { it >= 0L } != false &&
		(row.unresolvedSequenceStart == null) == (row.unresolvedSequenceEnd == null)

	private fun hasValidSessionSettlement(
		session: LogicalTrackingSessionEntity, runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>, targetOrdinal: Long?,
	): Boolean {
		if (!hasConsistentCompletion(session.state, session.completedAtMs)) { return false }
		val latestByRun = runs.associateWith { run ->
			manifestsByRun[run.serviceRunId].orEmpty()
				.maxByOrNull(SessionManifestVersionEntity::manifestRevision) ?: return false
		}
		if (!settlementPlansMatch(session, latestByRun)) { return false }
		val active = runs.filter { it.completedAtMs == null && it.state !in TERMINAL_STATES }
		return if (active.isNotEmpty()) {
			val run = active.singleOrNull()
			val manifest = run?.let(latestByRun::get)
			run != null && manifest != null && activeSettlementMatches(session, run, manifest)
		} else { terminalSettlementMatches(session, targetOrdinal) }
	}

	private fun settlementPlansMatch(
		session: LogicalTrackingSessionEntity,
		latestByRun: Map<SourceServiceRunEntity, SessionManifestVersionEntity>,
	): Boolean {
		if (latestByRun.any { (run, manifest) ->
			run.desiredPlanRevision != manifest.acquisitionPlanRevision ||
				run.rolloutRevision != manifest.rolloutRevision
		}) { return false }
		val latest = latestByRun.values.maxByOrNull(SessionManifestVersionEntity::manifestRevision)
			?: return false
		return session.desiredPlanRevision == latest.acquisitionPlanRevision &&
			session.rolloutRevision == latest.rolloutRevision && session.sessionMode == latest.sessionMode
	}

	private fun activeSettlementMatches(
		session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity,
	): Boolean = activeStatesMatch(session, run) && sessionCutoffMatches(session, run) &&
		session.currentServiceRunId == run.serviceRunId &&
		session.currentManifestRevision == manifest.manifestRevision &&
		session.lifecycleLeaseGeneration == run.leaseGeneration &&
		session.lifecycleBootId == run.bootId && session.finalAdmissionOrdinal == null

	private fun activeStatesMatch(session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity): Boolean {
		val normalAdmission = session.state in ADMISSION_SESSION_STATES && run.state in ADMISSION_RUN_STATES
		val suspendingRun = session.state == "ACTIVE" && run.state == "STOPPING"
		val stoppingSession = session.state == "STOPPING" && run.state == "STOPPING"
		return normalAdmission || suspendingRun || stoppingSession
	}

	private fun sessionCutoffMatches(session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity): Boolean =
		if (session.state == "STOPPING" && run.state == "STOPPING") {
			session.cutoffAtMs != null && session.cutoffElapsedNanos != null
		} else { session.cutoffAtMs == null && session.cutoffElapsedNanos == null }

	private fun terminalSettlementMatches(session: LogicalTrackingSessionEntity, targetOrdinal: Long?): Boolean =
		session.state in TERMINAL_STATES && session.cutoffAtMs != null &&
		session.cutoffElapsedNanos != null && session.currentServiceRunId == null &&
		session.finalAdmissionOrdinal?.let { targetOrdinal == null || targetOrdinal <= it } == true

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

	private fun CellCapturedFactRevisionEntity.hasExactStoredDay(
		retentionLowerBoundMs: Long,
	): Boolean = runCatching {
		val zone = ZoneId.of(storedZoneId)
		val earliest = retentionLowerBoundMs
		val latest = Math.addExact(observedWallTimeMs, wallTimeUncertaintyMs)
		val firstDay = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate().toEpochDay()
		val lastDay = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate().toEpochDay()
		val measuredDay = Instant.ofEpochMilli(observedWallTimeMs).atZone(zone).toLocalDate().toEpochDay()
		earliest >= 0L && if (payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			measuredDay == structuralEpochDay
		} else
			{
			firstDay == lastDay && firstDay == structuralEpochDay
		}
	}.getOrDefault(false)

	private fun isExactCellWriter(source: SessionManifestSourceEntity): Boolean =
		isCellCaptureMembership(source) && cellWriterDestinationMatches(source) && cellWriterGenerationMatches(source)

	private fun cellWriterDestinationMatches(source: SessionManifestSourceEntity): Boolean =
		source.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
		source.writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
		source.writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
		source.writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION

	private fun cellWriterGenerationMatches(source: SessionManifestSourceEntity): Boolean {
		val binding = source.writerBindingGeneration ?: return false
		return SourceWriterGenerationContract.captureModeMaskForKnownBinding(binding) != null &&
			source.writerOwnerGeneration == SourceWriterGenerationContract.canonicalOwnerGeneration(binding)
	}

	private fun isCellCaptureMembership(source: SessionManifestSourceEntity): Boolean =
		source.sourceKind == CELL_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible

	private fun isCellLane(lane: SourceProductProjectionLaneEntity): Boolean =
		lane.sourceKind == CELL_SOURCE &&
            SourceWriterGenerationContract.captureModeMaskForKnownBinding(lane.bindingGeneration) != null &&
			lane.projectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
			lane.projectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION

	private fun isValidLane(lane: SourceProductProjectionLaneEntity): Boolean =
		lane.captureModeMask == SourceWriterGenerationContract.captureModeMaskForKnownBinding(lane.bindingGeneration) &&
		lane.activationOrdinal > 0L && lane.contiguousAdmissionOrdinal >= lane.activationOrdinal - 1L &&
		when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE -> activeLaneMatches(lane)
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> retiredLaneMatches(lane)
			else -> false
		}

	private fun activeLaneMatches(lane: SourceProductProjectionLaneEntity): Boolean =
		lane.captureAdmissionCutoffOrdinal == null && lane.terminalDisposition == null &&
		lane.terminalAtMs == null && lane.retentionRequired

	private fun retiredLaneMatches(lane: SourceProductProjectionLaneEntity): Boolean =
		lane.captureAdmissionCutoffOrdinal != null &&
		lane.contiguousAdmissionOrdinal >= requireNotNull(lane.captureAdmissionCutoffOrdinal) &&
		lane.terminalDisposition == SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
		lane.terminalAtMs != null && !lane.retentionRequired

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
			zones, state, coverage, observations, causes,
			selection = localSelection(logicalId))
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
		selection = segment.logicalTrackingId?.takeIf(String::isNotBlank)?.let(::localSelection),
	)

	private fun localSelection(logicalId: String) = LocalCellHistorySelection(
		LocalCellHistoryIdentity(
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value,
		),
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
	private const val NANOS_PER_MILLISECOND = 1_000_000L
	private const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
	private const val COMPLETE_STOP_STATUS = "COMPLETE"
	private const val MANUAL_CAPTURE_MASK = 1L
	private const val AUTOMATIC_CAPTURE_MASK = 2L
}

/** Canonical v1 Cell desired-plan verifier kept local to the read model. */
internal object CellHistoryPlanIntegrity {
	fun decode(row: SourceDesiredPlanEntity): CellPlanEvidence? = runCatching {
		require(row.payloadVersion == 1 && row.payload.size <= MAX_PAYLOAD_BYTES &&
			sha256(row.payload) == row.payloadChecksum)
		DataInputStream(ByteArrayInputStream(row.payload)).use { input -> decodePayload(row, input) }
	}.getOrNull()

	private fun decodePayload(row: SourceDesiredPlanEntity, input: DataInputStream): CellPlanEvidence {
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
		require(validBackoff(initialBackoffMs, maximumBackoffMs, multiplier))
		require(mode in setOf("OFF", "OBSERVE_CHANGES", "OBSERVE_AND_SPARSE_REFRESH"))
		val fingerprint = physicalFingerprint(mode, subscriptions, minimumRefreshMs,
			initialBackoffMs, maximumBackoffMs, multiplier)
		return CellPlanEvidence(revision, mode != "OFF", fingerprint, maximumAgeMs)
	}

	private fun validBackoff(initialMs: Long, maximumMs: Long, multiplier: Double): Boolean =
		initialMs >= 0L && maximumMs >= initialMs && multiplier.isFinite() && multiplier >= 1.0

	private fun physicalFingerprint(
		mode: String, subscriptions: Set<Int>, minimumRefreshMs: Long,
		initialBackoffMs: Long, maximumBackoffMs: Long, multiplier: Double,
	): String {
		val canonical = when (mode) {
			"OFF" -> listOf("CELL", mode)
			"OBSERVE_CHANGES" -> listOf("CELL", "CHANGE_CALLBACK", subscriptions.sorted().joinToString(","))
			else -> listOf("CELL", "CHANGE_CALLBACK", "EXPLICIT_REFRESH", subscriptions.sorted().joinToString(","),
				minimumRefreshMs, initialBackoffMs, maximumBackoffMs, multiplier)
		}.joinToString("\u001f")
		return sha256(canonical.toByteArray(Charsets.UTF_8))
	}

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

private data class MeasuredCellCaptureAuthority(
	val run: SourceServiceRunEntity, val lane: SourceProductProjectionLaneEntity,
	val manifests: Map<String, List<SessionManifestVersionEntity>>,
	val bindings: Map<Long, SessionManifestSourceEntity>,
)

private data class CellFactManifestAuthority(
	val run: SourceServiceRunEntity, val manifest: SessionManifestVersionEntity,
	val next: SessionManifestVersionEntity?, val binding: SessionManifestSourceEntity,
	val plan: CellPlanEvidence, val consent: SourceConsentEpochEntity,
)

private data class CellFactProviderAuthority(
	val provider: ProviderRegistrationGenerationEntity,
	val authentication: ProviderRegistrationIntervalAuthentication.Authenticated,
	val authorizations: List<SourceAuthorizationEntity>, val sessionEnd: Long,
)

private data class AuthenticatedCellHistoryFact(
	val fact: CellCapturedFactRevisionEntity,
	val aggregate: CellCapturedFactRevisionEntity,
	val retentionLowerBoundMs: Long,
	val aggregateRetentionLowerBoundMs: Long,
	val coverageStartWallTimeMs: Long,
)

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
	val childrenByRevision: Map<Pair<String, Long>, List<CellCapturedChildEntity>> = emptyMap(),
	val cursors: List<CellCapturedFactCursorEntity>,
	val deletionGenerations: Map<Pair<String, String>, CellCaptureDeletionGenerationEntity>,
	val deletedScopes: Set<Pair<String, String>>,
	val planHeaders: Map<Long, AcquisitionPlanRevisionEntity>,
	val desiredPlans: Map<Long, SourceDesiredPlanEntity>,
	val providerRegistrations: Map<Long, ProviderRegistrationGenerationEntity>,
	val authorizationsByRegistration: Map<Long, List<SourceAuthorizationEntity>>,
	val demands: Map<String, SourceDemandEntity>,
	val admissions: List<SourceEventWalEntity>,
	val lanes: List<SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val evidenceState: SourceEvidenceState?,
	val overflow: Boolean,
	val currentProviderRegistrations: List<ProviderRegistrationGenerationEntity> = emptyList(),
	val currentClockDomainId: String? = null,
	val savedSessionCutoffs: Map<String, SavedSessionCutoff> = emptyMap(),
) {
	companion object {
		fun empty(expansion: CellMembershipExpansion) = CellHistorySnapshot(
			expansion = expansion,
			sessions = emptyMap(),
			runs = emptyMap(),
			manifestsByRun = emptyMap(),
			sourcesByManifest = emptyMap(),
			policies = emptyMap(),
			consents = emptyMap(),
			completenessByRun = emptyMap(),
			revisions = emptyList(),
			cursors = emptyList(),
			deletionGenerations = emptyMap(),
			deletedScopes = emptySet(),
			planHeaders = emptyMap(),
			desiredPlans = emptyMap(),
			providerRegistrations = emptyMap(),
			authorizationsByRegistration = emptyMap(),
			demands = emptyMap(),
			admissions = emptyList(),
			lanes = emptyList(),
			terminalFailures = emptyList(),
			evidenceState = null,
			overflow = false,
		)
	}
}

internal data class ComposedCellEntry(
	val logicalTrackingId: String,
	val recencyStartTimeMs: Long,
	val recencySegmentId: Long,
	val recencyTieIdentity: PortableCellOpaqueIdentity,
	val physicalSegmentIds: List<Long>,
	val capturesOnlyCell: Boolean,
	val entry: CellHistoryEntry,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(recencyStartTimeMs >= 0L && recencySegmentId > 0L)
		require(physicalSegmentIds.isNotEmpty() && physicalSegmentIds.distinct().size ==
			physicalSegmentIds.size)
		require(recencySegmentId in physicalSegmentIds)
	}
}

internal const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL

internal data class QualifiedMeasuredCellFact(val fact: CellCapturedFactRevisionEntity,
	val observation: CellHistoryObservation, val children: List<CellCapturedChildEntity>, val partial: Boolean)
