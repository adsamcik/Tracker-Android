package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.SavedSessionCutoff
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthenticator
import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AuthenticatedWifiWalCarrier
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.isSupportedWifiWalPayloadVersion
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemAuthority
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualification
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualifier
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.stats.api.repository.WifiHistoryAvailability
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryObservation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryResultCompleteness
import com.adsamcik.tracker.stats.api.repository.WifiHistorySignalQuality
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.time.ZoneId

/** Pure fail-closed composition of one bounded, transactionally loaded Wi-Fi snapshot. */
@Suppress("LargeClass", "TooManyFunctions")
internal object WifiHistoryComposer {
	/** A measured page proves this selected parent, not an invented complete logical session. */
	internal fun qualifyMeasuredFact(factId: String, revision: Long, snapshot: WifiHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority): QualifiedMeasuredWifiFact? {
		if (snapshot.overflow) {
			throw MeasuredRadioParentFailure(com.adsamcik.tracker.stats.api.repository.HistoryProblemReason.DEPENDENCY_OVERFLOW)
		}
		val evidence = requireNotNull(snapshot.evidenceState)
		val lineage = snapshot.revisions.filter { it.logicalFactId == factId }.sortedBy { it.semanticRevision }
		val fact = requireNotNull(validateLineage(lineage, snapshot))
		check(fact.semanticRevision == revision)
		val aggregate = requireNotNull(resolveAggregate(fact, snapshot))
		val logicalId = fact.logicalTrackingId
		val run = requireMeasuredRun(fact, snapshot)
		val manifests = snapshot.manifestsByRun[run.serviceRunId].orEmpty().sortedBy { it.manifestRevision }
		val byRun = mapOf(run.serviceRunId to manifests)
		check(hasValidManifestHistory(logicalId, listOf(run), byRun, snapshot, allowSubset = true))
		val bindings = measuredBindings(fact, aggregate, manifests, snapshot)
		val manifest = requireNotNull(manifests.singleOrNull { it.manifestRevision == fact.manifestRevision })
		val lane = requireMeasuredLane(fact, manifest, snapshot, laneExecutionAuthority)
		requireMeasuredSession(fact, aggregate, run, byRun, evidence, snapshot)
		if (!fact.hasCurrentPrivacyAuthority(logicalId, evidence, snapshot) ||
			!aggregate.hasCurrentPrivacyAuthority(logicalId, evidence, snapshot)) {
			return null
		}
		return qualifyMeasuredObservation(fact, aggregate, run, lane, snapshot, byRun, bindings, evidence)
	}

	private fun requireMeasuredRun(
		fact: WifiCapturedFactRevisionEntity, snapshot: WifiHistorySnapshot,
	): SourceServiceRunEntity {
		val logicalId = fact.logicalTrackingId
		val run = requireNotNull(snapshot.runs[fact.serviceRunId])
		val segment = snapshot.expansion.segments.singleOrNull { it.id == fact.sessionSegmentId }
		check(segment != null && run.ownsExactSegment(logicalId, listOf(segment)) &&
			hasConsistentCompletion(run.state, run.completedAtMs))
		return run
	}

	private fun measuredBindings(
		fact: WifiCapturedFactRevisionEntity, aggregate: WifiCapturedFactRevisionEntity,
		manifests: List<SessionManifestVersionEntity>, snapshot: WifiHistorySnapshot,
	): Map<Long, SessionManifestSourceEntity> {
		val logicalId = fact.logicalTrackingId
		val needed = setOf(fact.manifestRevision, aggregate.manifestRevision)
		val bindings = needed.associateWith { manifestRevision ->
			requireNotNull(snapshot.sourcesByManifest[WifiManifestKey(logicalId, manifestRevision)]
				.orEmpty().singleOrNull(::isWifiCaptureMembership))
		}
		check(hasValidCaptureAuthority(manifests, bindings, snapshot))
		return bindings
	}

	private fun requireMeasuredLane(
		fact: WifiCapturedFactRevisionEntity, manifest: SessionManifestVersionEntity,
		snapshot: WifiHistorySnapshot, laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): SourceProductProjectionLaneEntity {
		val lane = requireNotNull(snapshot.lanes.singleOrNull { isWifiLane(it) &&
            it.bindingGeneration == fact.writerBindingGeneration })
		val mode = manifest.wifiCaptureModeMask()
		val captureCutoff = lane.captureAdmissionCutoffOrdinal
		check(laneExecutionAuthority.owns(lane) && isValidLane(lane) && mode != 0L)
		check(lane.captureModeMask and mode == mode &&
			lane.activatedRolloutRevision <= manifest.rolloutRevision)
		check(lane.activationOrdinal <= fact.sourceAdmissionOrdinal &&
			(captureCutoff == null || fact.sourceAdmissionOrdinal <= captureCutoff))
		return lane
	}

	private fun requireMeasuredSession(
		fact: WifiCapturedFactRevisionEntity, aggregate: WifiCapturedFactRevisionEntity,
		run: SourceServiceRunEntity, byRun: Map<String, List<SessionManifestVersionEntity>>,
		evidence: SourceEvidenceState, snapshot: WifiHistorySnapshot,
	) {
		val logicalId = fact.logicalTrackingId
		val session = requireNotNull(snapshot.sessions[logicalId])
		check(hasConsistentCompletion(session.state, session.completedAtMs) &&
			session.lifecycleLeaseGeneration >= fact.lifecycleLeaseGeneration)
		// A current operation must still agree exactly. Older completed runs keep their original proof.
		if (run.completedAtMs == null || session.currentServiceRunId == run.serviceRunId) {
			check(hasValidSessionSettlement(session, listOf(run), byRun, fact.sourceAdmissionOrdinal))
		}
		check(snapshot.revisions.all { it.logicalTrackingId == logicalId && it.serviceRunId == run.serviceRunId })
		check(snapshot.terminalFailures.none { it.admissionOrdinal == fact.sourceAdmissionOrdinal ||
            it.admissionOrdinal == aggregate.sourceAdmissionOrdinal })
		val generation = snapshot.deletionGenerations[logicalId to run.serviceRunId]
		check(generation == null || evidence.acceptsRetainedEpoch(generation.collectedDataEpoch))
	}

	private fun SessionManifestVersionEntity.wifiCaptureModeMask(): Long = when (sessionMode) {
		"MANUAL" -> MANUAL_CAPTURE_MASK
		"AUTOMATIC" -> AUTOMATIC_CAPTURE_MASK
		else -> 0L
	}

	private fun SourceEventWalEntity.isValidMeasuredCarrier(
		value: WifiCapturedFactRevisionEntity, logicalId: String, snapshot: WifiHistorySnapshot,
		byRun: Map<String, List<SessionManifestVersionEntity>>, evidence: SourceEvidenceState,
	): Boolean {
		val carrier = this
		if (carrier.payloadVersion != WIFI_WAL_PAYLOAD_VERSION) {
			return carrier.isValidHistoryCarrier(logicalId, snapshot, byRun, evidence)
		}
		val member = snapshot.authorizationsByRegistration[carrier.registrationGeneration].orEmpty().singleOrNull {
			it.matchesCaptureFact(value, logicalId)
		} ?: return false
		return carrier.isValidHistoryCarrier(logicalId, snapshot, byRun, evidence, member)
	}

	private fun SourceAuthorizationEntity.matchesCaptureFact(
		fact: WifiCapturedFactRevisionEntity, logicalId: String = fact.logicalTrackingId,
	): Boolean {
		if (purpose != SourceBrokerPurpose.SESSION_CAPTURE || !persistenceEligible ||
			authorizationRevision != fact.authorizationRevision) {
			return false
		}
		val sessionMatches = logicalTrackingId == logicalId && serviceRunId == fact.serviceRunId &&
			manifestRevision == fact.manifestRevision && lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration
		return sessionMatches && sourcePolicyRevision == fact.sourcePolicyRevision && consentEpoch == fact.captureConsentEpoch
	}

	// Keep the original selected rows explicit across this complete read-only qualification phase.
	@Suppress("LongParameterList")
	private fun qualifyMeasuredObservation(
		fact: WifiCapturedFactRevisionEntity, aggregate: WifiCapturedFactRevisionEntity,
		run: SourceServiceRunEntity, lane: SourceProductProjectionLaneEntity, snapshot: WifiHistorySnapshot,
		byRun: Map<String, List<SessionManifestVersionEntity>>, bindings: Map<Long, SessionManifestSourceEntity>,
		evidence: SourceEvidenceState,
	): QualifiedMeasuredWifiFact? {
		val logicalId = fact.logicalTrackingId
		val wal = requireNotNull(snapshot.admissions.singleOrNull { it.eventId == fact.sourceEventId })
		val ownerWal = requireNotNull(snapshot.admissions.singleOrNull { it.eventId == aggregate.sourceEventId })
		check(wal.admissionOrdinal == fact.sourceAdmissionOrdinal &&
            wal.deliveryIdentity == fact.sourceDeliveryIdentity &&
			ownerWal.admissionOrdinal == aggregate.sourceAdmissionOrdinal &&
                ownerWal.deliveryIdentity == aggregate.sourceDeliveryIdentity)
		val authenticated = requireNotNull(
			WifiWalCarrierAuthenticator.authenticate(
				fact, wal, snapshot.childrenByRevision[fact.logicalFactId to fact.semanticRevision].orEmpty(),
			) as? WifiWalCarrierAuthentication.Authenticated,
		)
		val ownerAuthenticated = requireNotNull(
			WifiWalCarrierAuthenticator.authenticate(
				aggregate, ownerWal,
				snapshot.childrenByRevision[aggregate.logicalFactId to aggregate.semanticRevision].orEmpty(),
			) as? WifiWalCarrierAuthentication.Authenticated,
		)
		if (!fact.isWithinRetention(aggregate, evidence)) {
			return null
		}
		check(wal.isValidMeasuredCarrier(fact, logicalId, snapshot, byRun, evidence) &&
			ownerWal.isValidMeasuredCarrier(aggregate, logicalId, snapshot, byRun, evidence))
		check(WifiWalCarrierAuthenticator.authenticatesAggregate(aggregate, ownerAuthenticated.carrier) &&
			WifiWalCarrierAuthenticator.authenticatesAggregate(aggregate, authenticated.carrier, fact) &&
			factHasExactAuthority(fact, aggregate, snapshot, byRun, bindings, evidence) &&
			factHasExactAuthority(aggregate, aggregate, snapshot, byRun, bindings, evidence))
		val observation = toObservation(fact, aggregate, snapshot)
		val children = snapshot.childrenByRevision[fact.logicalFactId to fact.semanticRevision].orEmpty()
		val partial = isMeasuredCoveragePartial(fact, run, lane, snapshot)
		return QualifiedMeasuredWifiFact(fact, observation, children, partial)
	}

	private fun WifiCapturedFactRevisionEntity.isWithinRetention(
		aggregate: WifiCapturedFactRevisionEntity,
		evidence: SourceEvidenceState,
	): Boolean = evidence.retainedFromMs?.let { floor ->
		earliestPossibleWallTimeMs(this) >= floor && earliestPossibleWallTimeMs(aggregate) >= floor
	} != false

	private fun isMeasuredCoveragePartial(
		fact: WifiCapturedFactRevisionEntity, run: SourceServiceRunEntity,
		lane: SourceProductProjectionLaneEntity, snapshot: WifiHistorySnapshot,
	): Boolean {
		val logicalId = fact.logicalTrackingId
		val completeness = snapshot.completenessByRun[run.serviceRunId].orEmpty()
			.filter { it.sourceKind == WIFI_SOURCE }
		if (fact.coverageCompleteness == WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL ||
			snapshot.savedSessionCutoffs[logicalId]?.clockUnknown == true || run.completedAtMs == null) {
			return true
		}
		if (lane.contiguousAdmissionOrdinal < fact.sourceAdmissionOrdinal || completeness.isEmpty()) {
			return true
		}
		if (!hasValidCompleteness(completeness, logicalId, listOf(run.serviceRunId))) {
			return true
		}
		return completeness.any { it.providerCoverage != COMPLETE_PROVIDER_COVERAGE ||
			it.stopStatus != COMPLETE_STOP_STATUS || it.unresolvedSequenceStart != null }
	}

	fun composeSelected(
		seed: SessionSegment,
		snapshot: WifiHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): WifiHistoryEntry? {
		val logicalId = seed.logicalTrackingId?.takeIf(String::isNotBlank) ?: return legacy(seed)
		if (seed.serviceRunId.isNullOrBlank()) {
			return failed(logicalId, listOf(seed), WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		return composeGroup(logicalId, snapshot, laneExecutionAuthority)
	}

	fun composeRecent(
		snapshot: WifiHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): List<ComposedWifiEntry> = snapshot.expansion.segments.asSequence()
		.mapNotNull(SessionSegment::logicalTrackingId).filter(String::isNotBlank).distinct()
		.mapNotNull { logicalId ->
			val members = members(logicalId, snapshot)
			val structuralRuns = mutableListOf<WifiHistoryStructuralRunProjection>()
			val entry = composeGroup(
				logicalId,
				snapshot,
				laneExecutionAuthority,
				structuralRuns,
			) ?: return@mapNotNull null
			ComposedWifiEntry(
				logicalTrackingId = logicalId,
				recencyStartTimeMs = members.maxOfOrNull(SessionSegment::startTimeMs) ?: 0L,
				recencySegmentId = members.maxWithOrNull(
					compareBy(SessionSegment::startTimeMs, SessionSegment::id),
				)?.id ?: 0L,
				physicalSegmentIds = members.map(SessionSegment::id),
				entry = entry,
				structuralRuns = structuralRuns,
			)
		}.toList()

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun composeGroup(
		logicalId: String,
		snapshot: WifiHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
		structuralOutput: MutableList<WifiHistoryStructuralRunProjection>? = null,
	): WifiHistoryEntry? {
		val segments = members(logicalId, snapshot)
		if (segments.isEmpty()) {
			return null
		}
		if (snapshot.overflow) {
			return failed(logicalId, segments, WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		snapshot.expansion.failures[logicalId]?.let { return failed(logicalId, segments, it) }

		val runIds = segments.mapNotNull(SessionSegment::serviceRunId)
		if (runIds.size != segments.size || runIds.distinct().size != runIds.size) {
			return failed(logicalId, segments, WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val runs = runIds.mapNotNull(snapshot.runs::get)
		if (runs.size != runIds.size || runs.any { !it.ownsExactSegment(logicalId, segments) }) {
			return failed(logicalId, segments, WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		if (runs.any { !hasConsistentCompletion(it.state, it.completedAtMs) }) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val manifestsByRun = runs.associate { run ->
			run.serviceRunId to snapshot.manifestsByRun[run.serviceRunId].orEmpty()
				.sortedBy(SessionManifestVersionEntity::manifestRevision)
		}
		if (!hasValidManifestHistory(logicalId, runs, manifestsByRun, snapshot)) {
			return failed(logicalId, segments, WifiHistoryCause.MANIFEST_INTEGRITY_FAILED)
		}
		val manifests = manifestsByRun.values.flatten()
		val capturesOnlyWifi = manifests.all { manifest ->
			snapshot.manifestAuthorityShape(logicalId, manifest.manifestRevision)?.first ==
				setOf(WIFI_SOURCE)
		}
		val zones = manifests.mapTo(linkedSetOf(), SessionManifestVersionEntity::zoneId)
		if (zones.any { runCatching { ZoneId.of(it) }.isFailure }) {
			return failed(logicalId, segments, WifiHistoryCause.STORED_ZONE_INVALID)
		}
		val structuralRunShells = runCatching {
			runs.map { run ->
				val segment = segments.single { it.id == run.sessionSegmentId }
				WifiHistoryStructuralRunProjection(
					startTimeMs = segment.startTimeMs,
					endTimeMs = segment.endTimeMs,
					storedZoneIds = manifestsByRun.getValue(run.serviceRunId)
						.mapTo(linkedSetOf(), SessionManifestVersionEntity::zoneId),
					observations = emptyList(),
				)
			}
		}.getOrElse {
			return failed(logicalId, segments, WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		fun publishStructuralRuns(values: List<WifiHistoryStructuralRunProjection>) {
			structuralOutput?.apply {
				clear()
				addAll(values)
			}
		}
		val bindings = linkedMapOf<Long, SessionManifestSourceEntity>()
		for (manifest in manifests) {
			val matches = snapshot.sourcesByManifest[WifiManifestKey(logicalId, manifest.manifestRevision)]
				.orEmpty().filter(::isWifiCaptureMembership)
			if (matches.size > 1) {
				return failed(logicalId, segments, WifiHistoryCause.MANIFEST_INTEGRITY_FAILED)
			}
			matches.singleOrNull()?.let { bindings[manifest.manifestRevision] = it }
		}

		val carriedFactIds = snapshot.cursors.asSequence()
			.filter { it.logicalTrackingId == logicalId && it.serviceRunId in runIds }
			.mapTo(linkedSetOf(), WifiCapturedFactCursorEntity::logicalFactId)
		val ownerFactIds = snapshot.revisions.asSequence().filter { it.logicalFactId in carriedFactIds }
			.mapNotNull(WifiCapturedFactRevisionEntity::aggregateOwnerLogicalFactId).toSet()
		val relatedIds = carriedFactIds + ownerFactIds
		val related = snapshot.revisions.filter { it.logicalFactId in relatedIds }
		if (carriedFactIds.any { id -> related.none { it.logicalFactId == id } } ||
			related.any { it.logicalTrackingId != logicalId || it.serviceRunId !in runIds }
		) {
			return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (bindings.isEmpty()) {
			if (related.isNotEmpty()) {
				return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
			}
			publishStructuralRuns(structuralRunShells)
			return unavailable(
				logicalId,
				segments,
				zones,
				WifiHistoryCause.SOURCE_NOT_CAPTURED,
			)
		}
		if (!hasValidCaptureAuthority(manifests, bindings, snapshot)) {
			return failed(logicalId, segments, WifiHistoryCause.PLAN_INTEGRITY_FAILED)
		}

		val originalBinding =
            bindings.values.map(SessionManifestSourceEntity::writerBindingGeneration).distinct().singleOrNull()
		val lane = snapshot.lanes.singleOrNull { isWifiLane(it) && it.bindingGeneration == originalBinding }
		val modeMask = manifests.fold(0L) { mask, manifest -> mask or manifest.wifiCaptureModeMask() }
		if (lane == null || !lane.acceptsHistoryCaptureMode(modeMask, manifests, laneExecutionAuthority)) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}

		val session = snapshot.sessions[logicalId]
			?: return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		val completeness = runs.flatMap { snapshot.completenessByRun[it.serviceRunId].orEmpty() }
			.filter { it.sourceKind == WIFI_SOURCE }
		if (!hasValidCompleteness(completeness, logicalId, runIds)) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		if (runs.all { it.completedAtMs != null } && related.isNotEmpty() &&
			runIds.any { runId -> completeness.none { it.serviceRunId == runId } }
		) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val evidence = snapshot.evidenceState
			?: return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
		// An entirely erased or expired entry has no values to authenticate for display.
		// Keep the strict carrier checks below for every entry that can still show data.
		if (related.isNotEmpty() && related.all { fact ->
				!evidence.acceptsRetainedEpoch(fact.collectedDataEpoch) ||
					fact.sourceAdmissionOrdinal <= evidence.deletedSourceEventHighWaterOrdinal
			}) {
			publishStructuralRuns(structuralRunShells)
			return unavailable(logicalId, segments, zones,
				WifiHistoryCause.PRIVACY_EPOCH_MISMATCH, capturesOnlyWifi)
		}
		val retainedFromMs = evidence.retainedFromMs
		if (related.isNotEmpty() && retainedFromMs != null &&
			related.all { fact -> runCatching { earliestPossibleWallTimeMs(fact) < retainedFromMs }
				.getOrDefault(false) }
		) {
			publishStructuralRuns(structuralRunShells)
			return unavailable(logicalId, segments, zones,
				WifiHistoryCause.RETENTION_LIMIT, capturesOnlyWifi)
		}
		val admissionFactIds = related.mapTo(mutableSetOf(), WifiCapturedFactRevisionEntity::sourceEventId)
		val admissions = snapshot.admissions.filter { it.serviceRunId in runIds || it.eventId in admissionFactIds }
		if (admissions.any { !it.isAuthenticatedHistoryAdmission(logicalId, runIds, snapshot, manifestsByRun, evidence) }) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val admissionsByEventId = admissions.associateBy(SourceEventWalEntity::eventId)
		val admissionsByOrdinal = admissions.associateBy(SourceEventWalEntity::admissionOrdinal)
		val admissionsByDelivery = admissions.associateBy(SourceEventWalEntity::deliveryIdentity)
		if (admissionsByEventId.size != admissions.size ||
			admissionsByOrdinal.size != admissions.size ||
			admissionsByDelivery.size != admissions.size
		) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val targetOrdinal = listOfNotNull(
			related.maxOfOrNull(WifiCapturedFactRevisionEntity::sourceAdmissionOrdinal),
			completeness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
			admissions.maxOfOrNull(SourceEventWalEntity::admissionOrdinal),
		).maxOrNull()
		val legacyTargetOrdinal = listOfNotNull(
			related.filter { it.payloadVersion != WIFI_WAL_PAYLOAD_VERSION }.maxOfOrNull { it.sourceAdmissionOrdinal },
			completeness.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull(),
			admissions.filter { it.payloadVersion != WIFI_WAL_PAYLOAD_VERSION }.maxOfOrNull { it.admissionOrdinal },
		).maxOrNull()
		if (!hasValidSessionSettlement(session, runs, manifestsByRun, legacyTargetOrdinal) ||
			targetOrdinal?.let { it < lane.activationOrdinal ||
				lane.captureAdmissionCutoffOrdinal?.let { cutoff -> cutoff < it } == true } == true ||
			snapshot.terminalFailures.isNotEmpty()
		) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}

		val current = mutableListOf<Pair<WifiCapturedFactRevisionEntity, WifiCapturedFactRevisionEntity>>()
		val factOwnerByEvent = linkedMapOf<Pair<String, Long>, String>()
		for ((_, lineage) in related.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)) {
			val latest = validateLineage(lineage.sortedBy(WifiCapturedFactRevisionEntity::semanticRevision), snapshot)
				?: return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
			val aggregate = resolveAggregate(latest, snapshot)
				?: return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
			val wal = admissionsByEventId[latest.sourceEventId]
				?: return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
			val walAuthentication = WifiWalCarrierAuthenticator.authenticate(
				latest, wal,
				snapshot.childrenByRevision[latest.logicalFactId to latest.semanticRevision].orEmpty(),
			)
				as? WifiWalCarrierAuthentication.Authenticated
			val aggregateWal = admissionsByEventId[aggregate.sourceEventId]
				?: return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
			val aggregateWalAuthentication = WifiWalCarrierAuthenticator.authenticate(
				aggregate, aggregateWal,
				snapshot.childrenByRevision[aggregate.logicalFactId to aggregate.semanticRevision].orEmpty(),
			) as? WifiWalCarrierAuthentication.Authenticated
			if (!wal.matchesHistoryIndexes(latest, admissionsByOrdinal, admissionsByDelivery) ||
				walAuthentication == null
			) {
				return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			if (!aggregateWal.matchesHistoryIndexes(aggregate, admissionsByOrdinal, admissionsByDelivery) ||
				aggregateWalAuthentication == null
			) {
				return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			if (!WifiWalCarrierAuthenticator.authenticatesAggregate(aggregate, aggregateWalAuthentication.carrier) ||
				!WifiWalCarrierAuthenticator.authenticatesAggregate(aggregate, walAuthentication.carrier, latest)
			) {
				return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			val priorFact = factOwnerByEvent.putIfAbsent(latest.sourceEventId to latest.manifestRevision,
                latest.logicalFactId)
			if (priorFact != null && priorFact != latest.logicalFactId) {
				return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			if (!factHasExactAuthority(latest, aggregate, snapshot, manifestsByRun, bindings, evidence) ||
				!factHasExactAuthority(aggregate, aggregate, snapshot, manifestsByRun, bindings, evidence)) {
				return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			if (latest.logicalFactId in carriedFactIds) {
				current += latest to aggregate
			}
		}
		if (!hasExactCompletenessTails(
				completeness,
				admissions,
				related.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
					.values.map { it.maxBy(WifiCapturedFactRevisionEntity::semanticRevision) },
				runs.filter { it.completedAtMs != null }.mapTo(hashSetOf(), SourceServiceRunEntity::serviceRunId),
			)
		) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}

		if (runIds.any { runId ->
			val row = snapshot.deletionGenerations[logicalId to runId]
			row != null && !evidence.acceptsRetainedEpoch(row.collectedDataEpoch)
		}) {
			return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
		}
		val currentEpoch = current.filter { (fact, aggregate) ->
			fact.hasCurrentPrivacyAuthority(logicalId, evidence, snapshot) &&
				aggregate.hasCurrentPrivacyAuthority(logicalId, evidence, snapshot)
		}
		val epochLoss = currentEpoch.size != current.size
		val retained = runCatching {
			currentEpoch.filter { (fact, aggregate) -> fact.isWithinRetention(aggregate, evidence) }
		}.getOrElse { return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED) }
		val retentionLoss = retained.size != currentEpoch.size
		if (retained.isEmpty()) {
			val emptyEntry = when {
			current.isNotEmpty() && epochLoss -> unavailable(logicalId, segments, zones,
				WifiHistoryCause.PRIVACY_EPOCH_MISMATCH, capturesOnlyWifi)
			currentEpoch.isNotEmpty() && retentionLoss -> unavailable(logicalId, segments, zones,
				WifiHistoryCause.RETENTION_LIMIT, capturesOnlyWifi)
			targetOrdinal != null && lane.contiguousAdmissionOrdinal < targetOrdinal ->
				materializing(logicalId, segments, zones, capturesOnlyWifi)
			isActive(session, runs) -> materializing(logicalId, segments, zones, capturesOnlyWifi)
			completeness.any { it.stopStatus in PROVIDER_UNAVAILABLE_STATUSES } ->
				unavailable(
					logicalId,
					segments,
					zones,
					WifiHistoryCause.PROVIDER_UNAVAILABLE,
					capturesOnlyWifi,
				)
			else -> missing(logicalId, segments, zones, capturesOnlyWifi)
			}
			publishStructuralRuns(structuralRunShells)
			return emptyEntry
		}

		val observations = runCatching { retained.sortedWith(compareBy(
			{ it.first.observedWallTimeMs }, { it.first.sourceAdmissionOrdinal }, { it.first.logicalFactId },
		)).map { (fact, aggregate) -> toObservation(fact, aggregate, snapshot) } }
			.getOrElse { return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED) }
		val causes = linkedSetOf<WifiHistoryCause>()
		if (snapshot.savedSessionCutoffs[logicalId]?.clockUnknown == true) {
			causes += WifiHistoryCause.ACQUISITION_INCOMPLETE
		}
		if (isActive(session, runs)) {
			causes += WifiHistoryCause.SESSION_ACTIVE
		}
		if (lane.contiguousAdmissionOrdinal < (targetOrdinal ?: lane.activationOrdinal)) {
			causes += WifiHistoryCause.MATERIALIZATION_BEHIND
		}
		if (epochLoss) {
			causes += WifiHistoryCause.PRIVACY_EPOCH_MISMATCH
		}
		if (retentionLoss) {
			causes += WifiHistoryCause.RETENTION_LIMIT
		}
		if (completeness.any { it.unresolvedSequenceStart != null ||
			it.providerCoverage != COMPLETE_PROVIDER_COVERAGE || it.stopStatus != COMPLETE_STOP_STATUS }) {
			causes += WifiHistoryCause.ACQUISITION_INCOMPLETE
		}
		if (retained.any { it.first.coverageCompleteness == WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL }) {
			causes += WifiHistoryCause.RESULT_SET_PARTIAL
		}
		val structuralObservations = runCatching {
			retained.groupBy { it.first.serviceRunId }.mapValues { (_, facts) ->
				facts.map { (fact, _) ->
					WifiHistoryStructuralObservationProjection(
						earliestPossibleTimeMs = earliestPossibleWallTimeMs(fact),
						latestPossibleTimeMs = Math.addExact(
							fact.observedWallTimeMs,
							fact.wallTimeUncertaintyMs,
						),
						storedZoneId = fact.storedZoneId,
					)
				}
			}
		}.getOrElse {
			return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
		}
		publishStructuralRuns(
			runs.zip(structuralRunShells).map { (run, shell) ->
				shell.copy(observations = structuralObservations[run.serviceRunId].orEmpty())
			},
		)
		return if (causes.isEmpty()) {
			entry(logicalId, segments, zones, WifiHistoryProductState.READY,
			WifiHistoryCoverage.COMPLETE, observations, emptySet(), capturesOnlyWifi)
		} else
			{
			entry(logicalId, segments, zones, WifiHistoryProductState.PARTIAL,
				WifiHistoryCoverage.PARTIAL, observations, causes, capturesOnlyWifi)
		}
	}

	private fun SourceProductProjectionLaneEntity.acceptsHistoryCaptureMode(
		modeMask: Long,
		manifests: List<SessionManifestVersionEntity>,
		executionAuthority: SourceProductLaneExecutionAuthority,
	): Boolean {
		if (!executionAuthority.owns(this) || !isValidLane(this) || modeMask == 0L) {
			return false
		}
		return captureModeMask and modeMask == modeMask && manifests.none { activatedRolloutRevision > it.rolloutRevision }
	}

	private fun SourceEventWalEntity.isAuthenticatedHistoryAdmission(
		logicalId: String,
		runIds: List<String>,
		snapshot: WifiHistorySnapshot,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		evidence: SourceEvidenceState,
	): Boolean {
		if (WifiWalCarrierAuthenticator.authenticate(this) !is WifiWalCarrierAuthentication.Authenticated) {
			return false
		}
		return if (payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
			val members = snapshot.authorizationsByRegistration[registrationGeneration].orEmpty()
				.filter { it.isCapturedHistoryMember(this, logicalId, runIds) }.distinctBy { it.demandId }
			members.any { member ->
				isValidHistoryCarrier(logicalId, snapshot, manifestsByRun, evidence,
					snapshot.authorizationsByRegistration[registrationGeneration].orEmpty().last { row ->
						row.demandId == member.demandId &&
							row.authorizationRevision <= requireNotNull(authorizationRevision)
					})
			}
		} else {
			isValidHistoryCarrier(logicalId, snapshot, manifestsByRun, evidence)
		}
	}

	private fun SourceAuthorizationEntity.isCapturedHistoryMember(
		wal: SourceEventWalEntity,
		logicalId: String,
		runIds: List<String>,
	): Boolean {
		if (authorizationRevision > requireNotNull(wal.authorizationRevision)) {
			return false
		}
		return purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible &&
			logicalTrackingId == logicalId && serviceRunId in runIds
	}

	private fun SourceEventWalEntity.matchesHistoryIndexes(
		fact: WifiCapturedFactRevisionEntity,
		byOrdinal: Map<Long, SourceEventWalEntity>,
		byDelivery: Map<String?, SourceEventWalEntity>,
	): Boolean = byOrdinal[fact.sourceAdmissionOrdinal] == this && byDelivery[fact.sourceDeliveryIdentity] == this

	private fun hasValidManifestHistory(
		logicalId: String,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		snapshot: WifiHistorySnapshot,
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
				SessionManifestIntegrity.verify(manifest,
					snapshot.sourcesByManifest[WifiManifestKey(logicalId, manifest.manifestRevision)].orEmpty())
		}
	}

	/** Resolves v6 membership on the already bounded history snapshot, without today's settings. */
	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private fun WifiHistorySnapshot.qualifyPurposeItems(
		wal: SourceEventWalEntity,
		providerStart: Long,
		providerEnd: Long,
		plan: WifiPlanEvidence,
		carrier: AuthenticatedWifiWalCarrier,
	): RadioPurposeItems? {
		val revision = wal.authorizationRevision ?: return null
		val observedStart = wal.observedIntervalStartNanos ?: return null
		val (callback, timeline) = historicalPurposeTimeline(wal, revision, observedStart) ?: return null
		val ids = timeline.flatMap { it.authorizedMembers.mapNotNull { member -> member.demandId } }.distinct()
		val historicalDemands = ids.mapNotNull(demands::get)
		val callbackDemands = callback.authorizedMembers.map { demands[it.demandId] ?: return null }
		if (timeline.lastOrNull() != callback || historicalDemands.size != ids.size ||
			callbackDemands.any { !it.hasCompatibleWifiPhysicalContract(plan, wal.clockDomainId) }
		) {
			return null
		}
		val result = RadioPurposeItemQualifier.wifi(carrier.payload, RadioPurposeItemAuthority(
			WIFI_SOURCE, wal.registrationGeneration, wal.clockDomainId, providerStart, providerEnd,
			wal.receivedElapsedNanos, timeline, historicalDemands,
		), wal.payloadVersion) as? RadioPurposeItemQualification.Qualified ?: return null
		return RadioPurposeItems(callback, timeline, historicalDemands.associateBy(SourceDemandEntity::demandId),
			result.acceptedIndexesByDemandId)
	}

	/** Reconstructs the callback and its recorded authorization timeline before demand qualification. */
	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private fun WifiHistorySnapshot.historicalPurposeTimeline(
		wal: SourceEventWalEntity,
		revision: Long,
		observedStart: Long,
	): Pair<SourceAuthorizationSnapshot, List<SourceAuthorizationSnapshot>>? {
		val all = authorizationsByRegistration[wal.registrationGeneration].orEmpty()
			.groupBy(SourceAuthorizationEntity::authorizationRevision).values.map { rows ->
				rows.toAuthorizationSnapshotOrNull() ?: return null
			}.sortedBy { it.authorizationRevision }
		val callback = all.singleOrNull { it.authorizationRevision == revision } ?: return null
		if (all.lastOrNull { it.effectiveBootId == wal.clockDomainId &&
			it.effectiveElapsedRealtimeNanos <= wal.receivedElapsedNanos } != callback ||
			callback.authorizationFingerprint != wal.authorizationFingerprint ||
			callback.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask
		) {
			return null
		}
		val first = all.lastOrNull { it.effectiveElapsedRealtimeNanos <= observedStart }
			?: all.firstOrNull() ?: return null
		val timeline = all.filter { it.authorizationRevision in first.authorizationRevision..revision }
		return callback to timeline
	}

	private fun RadioPurposeItems.captureIndexes(
		logicalId: String, runId: String, manifestRevision: Long, lease: Long, policy: Long, consent: Long,
		authorizationRevision: Long,
	): List<Int>? {
		val member = authorizationTimeline.flatMap { it.authorizedMembers }.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
				member.authorizationRevision == authorizationRevision &&
				member.logicalTrackingId == logicalId && member.serviceRunId == runId &&
				member.manifestRevision == manifestRevision && member.lifecycleLeaseGeneration == lease &&
				member.sourcePolicyRevision == policy && member.consentEpoch == consent
		} ?: return null
		return forDemand(requireNotNull(member.demandId)).takeIf { it.isNotEmpty() }
	}

	private fun SourceEventWalEntity.isValidHistoryCarrier(
		logicalId: String,
		snapshot: WifiHistorySnapshot,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		evidence: SourceEvidenceState,
		historicalMember: SourceAuthorizationEntity? = null,
	): Boolean {
		val manifest = resolveHistoryManifest(historicalMember, manifestsByRun) ?: return false
		val plan = historyCapturePlan(logicalId, manifest, snapshot) ?: return false
		return hasAuthenticatedHistoryCapture(plan, snapshot, evidence, historicalMember)
	}

	private fun SourceEventWalEntity.resolveHistoryManifest(
		member: SourceAuthorizationEntity?, manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	): SessionManifestVersionEntity? {
		val revision = member?.manifestRevision ?: sessionManifestRevision ?: return null
		if (!hasCaptureReferences(member)) {
			return null
		}
		val run = member?.serviceRunId ?: serviceRunId ?: return null
		return manifestsByRun[run].orEmpty().singleOrNull { it.manifestRevision == revision }
	}

	private fun SourceEventWalEntity.hasCaptureReferences(member: SourceAuthorizationEntity?): Boolean =
		(member?.sourcePolicyRevision != null || sourcePolicyRevision != null) &&
			(member?.consentEpoch != null || captureConsentEpoch != null) &&
			(member?.lifecycleLeaseGeneration != null || lifecycleLeaseGeneration != null)

	private fun historyCapturePlan(
		logicalId: String, manifest: SessionManifestVersionEntity, snapshot: WifiHistorySnapshot,
	): WifiPlanEvidence? {
		if (snapshot.historyCaptureBinding(logicalId, manifest.manifestRevision) == null) {
			return null
		}
		return snapshot.desiredPlans[manifest.acquisitionPlanRevision]?.let(WifiHistoryPlanIntegrity::decode)
	}

	private fun SourceEventWalEntity.hasAuthenticatedHistoryCapture(
		plan: WifiPlanEvidence, snapshot: WifiHistorySnapshot,
		evidence: SourceEvidenceState, member: SourceAuthorizationEntity?,
	): Boolean {
		val provider = snapshot.providerRegistrations[registrationGeneration] ?: return false
		val carrier = if (payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
			(WifiWalCarrierAuthenticator.authenticate(this)
				as? WifiWalCarrierAuthentication.Authenticated)?.carrier ?: return false
		} else {
			null
		}
		return authenticateHistoryProvider(provider, carrier, snapshot)?.let { authenticated ->
			authorizeHistoryObservationWindow(plan, snapshot, member, authenticated, carrier)?.let { observed ->
				matchesHistoryCarrierAuthority(plan, snapshot, evidence, member, authenticated, observed)
			} == true
		} == true
	}

	private fun SourceEventWalEntity.authorizeHistoryObservationWindow(
		plan: WifiPlanEvidence, snapshot: WifiHistorySnapshot, member: SourceAuthorizationEntity?,
		provider: ProviderRegistrationIntervalAuthentication.Authenticated, carrier: AuthenticatedWifiWalCarrier?,
	): LongRange? {
		val revision = member?.authorizationRevision ?: authorizationRevision ?: return null
		val authorization = snapshot.authorizationsByRegistration[registrationGeneration].orEmpty()
			.filter { it.authorizationRevision == revision }
		val providerEnd = provider.interval.endExclusiveNanos
		val purposeItems = if (payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
			snapshot.qualifyPurposeItems(this, provider.interval.startInclusiveNanos, providerEnd,
				plan, requireNotNull(carrier)) ?: return null
		} else {
			null
		}
		if (purposeItems == null &&
			!hasExactWalAuthorization(this, authorization, snapshot.demands, providerEnd, plan)) {
			return null
		}
		return qualifiedCarrierWindow(purposeItems, carrier, member, snapshot)
	}

	private fun SourceEventWalEntity.qualifiedCarrierWindow(
		purposeItems: RadioPurposeItems?, carrier: AuthenticatedWifiWalCarrier?,
		member: SourceAuthorizationEntity?, snapshot: WifiHistorySnapshot,
	): LongRange? {
		val indexes = purposeItems?.let { historyCaptureIndexes(it, member, snapshot) }
			?: if (purposeItems != null) { return null } else { null }
		val times = indexes?.map { requireNotNull(carrier).payload.observations[it].providerTimestampNanos
			?: return null }
		val first = times?.minOrNull() ?: observedIntervalStartNanos ?: return null
		val last = times?.maxOrNull() ?: observedElapsedNanos
		return (first..last).takeUnless(LongRange::isEmpty)
	}

	private fun SourceEventWalEntity.historyCaptureIndexes(
		purposeItems: RadioPurposeItems, member: SourceAuthorizationEntity?, snapshot: WifiHistorySnapshot,
	): List<Int>? {
		val manifest = historyCarrierManifest(snapshot, member)
		return purposeItems.captureIndexes(manifest.logicalTrackingId,
			requireNotNull(member?.serviceRunId ?: serviceRunId),
			requireNotNull(member?.manifestRevision ?: sessionManifestRevision),
			requireNotNull(member?.lifecycleLeaseGeneration ?: lifecycleLeaseGeneration),
			requireNotNull(member?.sourcePolicyRevision ?: sourcePolicyRevision),
			requireNotNull(member?.consentEpoch ?: captureConsentEpoch),
			requireNotNull(member?.authorizationRevision ?: authorizationRevision))
	}

	private fun SourceEventWalEntity.matchesHistoryCarrierAuthority(
		plan: WifiPlanEvidence, snapshot: WifiHistorySnapshot, evidence: SourceEvidenceState,
		member: SourceAuthorizationEntity?, provider: ProviderRegistrationIntervalAuthentication.Authenticated,
		observed: LongRange,
	): Boolean {
		val manifest = historyCarrierManifest(snapshot, member)
		val action = snapshot.historyStartAction(manifest.serviceRunId, manifest.manifestRevision) ?: return false
		val earliestWall = historyEarliestWallTime(payloadVersion == WIFI_WAL_PAYLOAD_VERSION, observed.first)
			?: return false
		return matchesHistoryCarrierBinding(plan, snapshot, member, manifest) &&
			matchesHistoryCarrierDelivery(evidence) && matchesHistoryCarrierProvider(provider.registration) &&
			matchesHistoryCarrierChronology(provider, action, member, observed, snapshot) &&
			isRetainedHistoryCarrier(manifest.logicalTrackingId, manifest.serviceRunId, snapshot, earliestWall, evidence)
	}

	private fun SourceEventWalEntity.matchesHistoryCarrierBinding(
		plan: WifiPlanEvidence, snapshot: WifiHistorySnapshot, member: SourceAuthorizationEntity?,
		manifest: SessionManifestVersionEntity,
	): Boolean {
		val run = requireNotNull(member?.serviceRunId ?: serviceRunId)
		val revision = requireNotNull(member?.authorizationRevision ?: authorizationRevision)
		val binding = requireNotNull(snapshot.historyCaptureBinding(manifest.logicalTrackingId, manifest.manifestRevision))
		return matchesHistoryCarrierIdentity(manifest.logicalTrackingId, member, run, manifest) &&
			matchesHistoryCarrierPlan(plan, revision, payloadVersion == WIFI_WAL_PAYLOAD_VERSION) &&
			matchesHistoryCarrierCapture(member, run, manifest, binding, snapshot)
	}

	private fun SourceEventWalEntity.matchesHistoryCarrierChronology(
		provider: ProviderRegistrationIntervalAuthentication.Authenticated, action: LifecycleDesiredActionEntity,
		member: SourceAuthorizationEntity?, observed: LongRange, snapshot: WifiHistorySnapshot,
	): Boolean {
		val manifest = historyCarrierManifest(snapshot, member)
		val revision = requireNotNull(member?.authorizationRevision ?: authorizationRevision)
		// Successful legacy authorization or per-reading capture selection already proves these rows nonempty.
		val callbackStart = snapshot.authorizationsByRegistration[registrationGeneration].orEmpty()
			.first { it.authorizationRevision == revision }.effectiveElapsedRealtimeNanos
		val perReading = payloadVersion == WIFI_WAL_PAYLOAD_VERSION
		val authorizationStart = if (perReading) { observed.first } else { callbackStart }
		val next = snapshot.authorizationsByRegistration[registrationGeneration].orEmpty()
			.nextHistoryAuthorizationStartNanos(clockDomainId, callbackStart, revision)
		val providerStart = provider.interval.startInclusiveNanos
		val authorizationEnd = minOf(provider.interval.endExclusiveNanos, next)
		val appliedAt = maxOf(providerStart, if (perReading) {
			manifest.effectiveElapsedRealtimeNanos
		} else {
			authorizationStart
		})
		return provider.registration.wasAppliedBeforeHistoryCapture(action, providerStart, observed.first) &&
			hasHistoryObservationWindow(provider.interval.endExclusiveNanos, authorizationStart, authorizationEnd,
				manifest.effectiveElapsedRealtimeNanos, observed.first, observed.last) &&
			action.authenticatesHistoryCarrier(this, manifest, member, appliedAt, observed.first)
	}

	// Every caller first verifies hasValidManifestHistory against this same immutable snapshot.
	// Its selected manifest therefore retains the requested logical ID and the selected physical run ID.
	private fun SourceEventWalEntity.historyCarrierManifest(
		snapshot: WifiHistorySnapshot, member: SourceAuthorizationEntity?,
	): SessionManifestVersionEntity {
		val run = requireNotNull(member?.serviceRunId ?: serviceRunId)
		val revision = requireNotNull(member?.manifestRevision ?: sessionManifestRevision)
		return snapshot.manifestsByRun.getValue(run).single { it.manifestRevision == revision }
	}

	private fun WifiHistorySnapshot.historyCaptureBinding(
		logicalId: String, revision: Long,
	): SessionManifestSourceEntity? =
		sourcesByManifest[WifiManifestKey(logicalId, revision)].orEmpty().singleOrNull(::isWifiCaptureMembership)

	private fun WifiHistorySnapshot.historyStartAction(runId: String, revision: Long): LifecycleDesiredActionEntity? =
		startActions.singleOrNull { it.serviceRunId == runId && it.manifestRevision == revision }

	private fun SourceEventWalEntity.matchesHistoryCarrierIdentity(
		logicalId: String, member: SourceAuthorizationEntity?, ownedRun: String, manifest: SessionManifestVersionEntity,
	): Boolean = sourceKind == WIFI_SOURCE && admissionOrdinal > 0L && sourceInstanceId.isNotBlank() &&
			registrationGeneration > 0L && sourceSequence >= 0L && (member?.logicalTrackingId ?:
                this.logicalTrackingId) == logicalId &&
			ownedRun == manifest.serviceRunId && (payloadVersion == WIFI_WAL_PAYLOAD_VERSION ||
                configRevision == manifest.acquisitionPlanRevision)

	private fun SourceEventWalEntity.matchesHistoryCarrierPlan(
		plan: WifiPlanEvidence, authorizationRevision: Long, perReading: Boolean,
	): Boolean = plan.enabled && plan.physicalFingerprint == physicalConfigurationFingerprint &&
			authorizationRevision > 0L &&
			(perReading ||
                authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) &&
			LOWERCASE_SHA_256.matches(authorizationFingerprint.orEmpty()) &&
                (payloadVersion == WIFI_WAL_PAYLOAD_VERSION || planAttribution == CAPTURED_PLAN_ORDINAL)

	private fun SourceEventWalEntity.matchesHistoryCarrierCapture(
		member: SourceAuthorizationEntity?, ownedRun: String, manifest: SessionManifestVersionEntity,
		binding: SessionManifestSourceEntity, snapshot: WifiHistorySnapshot,
	): Boolean {
		val policyRevision = requireNotNull(member?.sourcePolicyRevision ?: sourcePolicyRevision)
		val consentEpoch = requireNotNull(member?.consentEpoch ?: captureConsentEpoch)
		val lease = requireNotNull(member?.lifecycleLeaseGeneration ?: lifecycleLeaseGeneration)
		return clockDomainId == manifest.effectiveBootId && policyRevision == manifest.sourcePolicyRevision &&
			consentEpoch == binding.consentEpoch && snapshot.runs[ownedRun]?.let {
				SessionManifestIntegrity.hasRetainedLeaseGeneration(it, lease) } == true
	}

	private fun SourceEventWalEntity.matchesHistoryCarrierDelivery(evidence: SourceEvidenceState): Boolean =
		evidence.acceptsRetainedEpoch(capturedCollectedDataEpoch) &&
			admissionOrdinal > evidence.deletedSourceEventHighWaterOrdinal && deliveryUnitIndex == 0 &&
			deliveryUnitCount == 1 && LOWERCASE_SHA_256.matches(deliveryIdentity.orEmpty()) &&
			isSupportedWifiWalPayloadVersion(payloadVersion) && hasQualifiedIntegrity()

	private fun SourceEventWalEntity.matchesHistoryCarrierProvider(
		provider: ProviderRegistrationGenerationEntity,
	): Boolean =
		provider.sourceKind == WIFI_SOURCE && provider.sourceInstanceId == sourceInstanceId &&
			provider.ownerScope == "source-broker:$WIFI_SOURCE" &&
			provider.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
			provider.clockDomainId == clockDomainId && provider.collectedDataEpoch == capturedCollectedDataEpoch &&
			provider.physicalConfigurationFingerprint == physicalConfigurationFingerprint

	private fun ProviderRegistrationGenerationEntity.wasAppliedBeforeHistoryCapture(
		action: LifecycleDesiredActionEntity, providerStart: Long, observedStart: Long,
	): Boolean = acceptedAtMs?.let { accepted -> action.acknowledgedAtMs?.let { accepted <= it } } == true &&
			providerStart <= requireNotNull(action.acknowledgedElapsedRealtimeNanos) &&
			providerStart <= observedStart

	private fun SourceEventWalEntity.isRetainedHistoryCarrier(
		logicalId: String, ownedRun: String, snapshot: WifiHistorySnapshot, earliestWall: Long, evidence: SourceEvidenceState,
	): Boolean = (snapshot.deletionGenerations[logicalId to ownedRun]?.generation ?: 0L) == 0L &&
			(logicalId to ownedRun) !in snapshot.deletedScopes && earliestWall >= 0L &&
			evidence.retainedFromMs?.let { earliestWall >= it } != false

	private fun hasHistoryObservationWindow(
		providerEnd: Long, authorizationStart: Long, authorizationEnd: Long,
		manifestStart: Long, observedStart: Long, observedEnd: Long,
	): Boolean = observedEnd < providerEnd && authorizationStart <= observedStart &&
		observedEnd < authorizationEnd && manifestStart <= observedStart

	private fun LifecycleDesiredActionEntity.authenticatesHistoryCarrier(
		wal: SourceEventWalEntity, manifest: SessionManifestVersionEntity, member: SourceAuthorizationEntity?,
		appliedAt: Long, observedStart: Long,
	): Boolean = if (member != null) {
		authenticatesHistorical(wal, manifest, member, appliedAt, observedStart)
	} else {
		authenticates(wal, manifest, appliedAt, observedStart)
	}

	private fun SourceEventWalEntity.historyEarliestWallTime(perReading: Boolean, observedStart: Long): Long? {
		val wall = wallTimeMs ?: return null
		val uncertainty = wallTimeUncertaintyMs ?: return null
		return if (perReading) {
			CanonicalRetentionBounds.wifiCoverage(wall, uncertainty, observedStart, observedElapsedNanos)
		} else {
			runCatching { Math.subtractExact(Math.subtractExact(wall,
				Math.subtractExact(observedElapsedNanos, observedStart) / NANOS_PER_MILLISECOND), uncertainty) }
				.getOrNull()
		}
	}

	/** Authenticates the original provider interval using the carrier's measured observation bounds. */
	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private fun SourceEventWalEntity.authenticateHistoryProvider(
		provider: ProviderRegistrationGenerationEntity,
		carrier: AuthenticatedWifiWalCarrier?,
		snapshot: WifiHistorySnapshot,
	): ProviderRegistrationIntervalAuthentication.Authenticated? {
		val providerTimes = if (carrier != null) {
			carrier.historyProviderTimes(provider) ?: return null
		} else {
			null
		}
		val providerObservedStart = providerTimes?.minOrNull() ?: observedIntervalStartNanos ?: return null
		val providerObservedEnd = providerTimes?.maxOrNull() ?: observedElapsedNanos
		val providerObservedWall = (wallTimeMs ?: return null) -
			(observedElapsedNanos - providerObservedEnd) / NANOS_PER_MILLISECOND
		return ProviderRegistrationIntervalAuthenticator.authenticate(
			registration = provider,
			expectedSourceKind = WIFI_SOURCE,
			expectedRegistrationGeneration = registrationGeneration,
			expectedSourceInstanceId = sourceInstanceId,
			expectedClockDomainId = clockDomainId,
			expectedPhysicalConfigurationFingerprint =
				physicalConfigurationFingerprint ?: return null,
			expectedCollectedDataEpoch = capturedCollectedDataEpoch,
			expectedProviderResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = providerObservedStart,
			observedEndNanos = providerObservedEnd,
			observedWallTimeMs = providerObservedWall,
			currentClockDomainId = snapshot.currentClockDomainId,
			currentProviderRegistrations = snapshot.currentProviderRegistrations,
		) as? ProviderRegistrationIntervalAuthentication.Authenticated
	}

	private fun SourceAuthorizationEntity.matchesSavedAuthorization(
		expectedRegistration: Long,
		expectedFingerprint: String?,
		expectedMask: Long,
		expectedBoot: String,
	): Boolean {
		if (sourceKind != WIFI_SOURCE || registrationGeneration != expectedRegistration) {
			return false
		}
		return authorizationFingerprint == expectedFingerprint && purposeEligibilityMask == expectedMask &&
			effectiveBootId == expectedBoot
	}

	private fun SourceAuthorizationEntity.matchesCaptureWal(wal: SourceEventWalEntity): Boolean {
		if (isDenyAll || purpose != SourceBrokerPurpose.SESSION_CAPTURE || !persistenceEligible) {
			return false
		}
		val sessionMatches = logicalTrackingId == wal.logicalTrackingId && serviceRunId == wal.serviceRunId &&
			manifestRevision == wal.sessionManifestRevision && lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration
		return sessionMatches && sourcePolicyRevision == wal.sourcePolicyRevision && consentEpoch == wal.captureConsentEpoch
	}

	private fun AuthenticatedWifiWalCarrier.historyProviderTimes(
		provider: ProviderRegistrationGenerationEntity,
	): List<Long>? {
		val acceptedStart = provider.acceptedElapsedRealtimeNanos ?: return null
		val end = provider.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		return payload.observations.mapNotNull { it.providerTimestampNanos }
			.filter { it >= acceptedStart && it < end }.takeIf { it.isNotEmpty() }
	}

	private fun hasExactWalAuthorization(
		wal: SourceEventWalEntity,
		exact: List<SourceAuthorizationEntity>,
		demands: Map<String, SourceDemandEntity>,
		providerEnd: Long,
		plan: WifiPlanEvidence,
	): Boolean {
		if (exact.isEmpty() || exact.any {
				!it.matchesSavedAuthorization(wal.registrationGeneration, wal.authorizationFingerprint,
					wal.authorizationPurposeEligibilityMask, wal.clockDomainId)
			}) {
			return false
		}
		val exactDemands = matchingAuthorizationDemands(
			exact, wal.registrationGeneration, requireNotNull(wal.authorizationRevision), demands,
		) ?: return false
		val first = exact.first()
		val captureMemberCount = exact.count { it.matchesCaptureWal(wal) }
		val contractsMatch = exactDemands.all { demand ->
			demand.hasCompatibleWifiPhysicalContract(plan, wal.clockDomainId)
		}
		return providerEnd > first.effectiveElapsedRealtimeNanos && captureMemberCount == 1 && contractsMatch
	}

	// One saved action-to-WAL/manifest stamp, including its recorded scalar acknowledgement bounds.
	@Suppress("CyclomaticComplexMethod")
	private fun LifecycleDesiredActionEntity.authenticates(
		wal: SourceEventWalEntity,
		manifest: SessionManifestVersionEntity,
		appliedAt: Long,
		firstQualifiedNanos: Long = wal.observedIntervalStartNanos ?: Long.MIN_VALUE,
	): Boolean {
		val acknowledgedWall = acknowledgedAtMs ?: return false
		val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
		return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == WIFI_SOURCE &&
			desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
			failureCode == null && retryTrigger == null && logicalTrackingId == wal.logicalTrackingId &&
			serviceRunId == wal.serviceRunId && manifestRevision == wal.sessionManifestRevision &&
			desiredPlanRevision == wal.configRevision && sourcePolicyRevision == wal.sourcePolicyRevision &&
			consentEpoch == wal.captureConsentEpoch && startOrigin == manifest.startOrigin &&
			bootId == wal.clockDomainId && leaseGeneration == wal.lifecycleLeaseGeneration &&
			sourceInstanceId == wal.sourceInstanceId && registrationGeneration == wal.registrationGeneration &&
			requestedAtMs == manifest.effectiveWallTimeMs && acknowledgedWall >= requestedAtMs &&
			requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
			requestedElapsedRealtimeNanos <= appliedAt && appliedAt <= acknowledgedElapsed &&
			appliedAt <= firstQualifiedNanos
	}

	private fun hasValidCaptureAuthority(
		manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
		snapshot: WifiHistorySnapshot,
	): Boolean = bindings.all { (revision, binding) ->
		val manifest = manifests.singleOrNull { it.manifestRevision == revision } ?: return@all false
		val policy = snapshot.policies[manifest.sourcePolicyRevision]
		val consent = snapshot.consents[binding.consentEpoch]
		val header = snapshot.planHeaders[manifest.acquisitionPlanRevision]
		val plan = snapshot.desiredPlans[manifest.acquisitionPlanRevision]?.let(WifiHistoryPlanIntegrity::decode)
		if (!isExactWifiWriter(binding) || policy == null || consent == null) {
			return@all false
		}
		if (header == null || plan == null) {
			return@all false
		}
		plan.matchesCapturePlan(manifest, header) && policy.matchesCapturePolicy(manifest, binding) &&
			consent.matchesCaptureConsent(manifest, policy, binding)
	}

	private fun WifiPlanEvidence.matchesCapturePlan(
		manifest: SessionManifestVersionEntity,
		header: AcquisitionPlanRevisionEntity,
	): Boolean = enabled && revision == manifest.acquisitionPlanRevision &&
		header.revision == manifest.acquisitionPlanRevision && header.sourcePolicyRevision == manifest.sourcePolicyRevision

	private fun SourcePolicyEntity.matchesCapturePolicy(
		manifest: SessionManifestVersionEntity,
		binding: SessionManifestSourceEntity,
	): Boolean {
		if (sourceKind != WIFI_SOURCE || policyRevision != manifest.sourcePolicyRevision ||
			!enabled || !capturePersistenceEligible
		) {
			return false
		}
		return qosCode == binding.qosCode && captureConsentEpoch == binding.consentEpoch &&
			effectiveBootId == manifest.effectiveBootId &&
			effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos
	}

	private fun SourceConsentEpochEntity.matchesCaptureConsent(
		manifest: SessionManifestVersionEntity,
		policy: SourcePolicyEntity,
		binding: SessionManifestSourceEntity,
	): Boolean {
		if (sourceKind != WIFI_SOURCE || purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
			epoch != binding.consentEpoch || policyRevision > policy.policyRevision
		) {
			return false
		}
		return eligible && persistenceEligible && effectiveBootId == manifest.effectiveBootId &&
			effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos
	}

	private fun validateLineage(
		ordered: List<WifiCapturedFactRevisionEntity>,
		snapshot: WifiHistorySnapshot,
	): WifiCapturedFactRevisionEntity? {
		if (!WifiCapturedFactRevisionIntegrity.hasValidRetainedLineage(ordered)) {
			return null
		}
		val latest = ordered.last()
		val cursor = snapshot.cursors.singleOrNull { it.logicalFactId == latest.logicalFactId }
		return latest.takeIf { candidate ->
			cursor?.let { WifiCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(it, candidate) } == true
		}
	}

	private fun resolveAggregate(
		fact: WifiCapturedFactRevisionEntity,
		snapshot: WifiHistorySnapshot,
	): WifiCapturedFactRevisionEntity? = when (fact.factKind) {
		WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> fact.takeIf { it.aggregateShapeIsValid() }
		WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> resolveCoverageAggregate(fact, snapshot)
		else -> null
	}

	private fun resolveCoverageAggregate(
		fact: WifiCapturedFactRevisionEntity,
		snapshot: WifiHistorySnapshot,
	): WifiCapturedFactRevisionEntity? {
		val ownerId = fact.aggregateOwnerLogicalFactId ?: return null
		val ownerRevision = fact.aggregateOwnerSemanticRevision ?: return null
		val ownerLineage = snapshot.revisions.filter { it.logicalFactId == ownerId }
		val owner = ownerLineage.singleOrNull { it.semanticRevision == ownerRevision } ?: return null
		return qualifyCoverageAggregate(fact, owner, ownerLineage, snapshot)
	}

	private fun qualifyCoverageAggregate(
		fact: WifiCapturedFactRevisionEntity,
		owner: WifiCapturedFactRevisionEntity,
		lineage: List<WifiCapturedFactRevisionEntity>,
		snapshot: WifiHistorySnapshot,
	): WifiCapturedFactRevisionEntity? {
		val currentOwner = validateLineage(lineage.sortedBy(WifiCapturedFactRevisionEntity::semanticRevision), snapshot)
			?: return null
		val ownerCursor = snapshot.cursors.singleOrNull { cursor ->
			cursor.writerProjectionId == owner.writerProjectionId &&
				cursor.writerProjectionVersion == owner.writerProjectionVersion && cursor.logicalFactId == owner.logicalFactId
		} ?: return null
		return owner.takeIf { it.isCurrentCoverageAggregate(fact, lineage, currentOwner, ownerCursor, snapshot) }
	}

	private fun WifiCapturedFactRevisionEntity.isCurrentCoverageAggregate(
		fact: WifiCapturedFactRevisionEntity,
		lineage: List<WifiCapturedFactRevisionEntity>,
		currentOwner: WifiCapturedFactRevisionEntity,
		ownerCursor: WifiCapturedFactCursorEntity,
		snapshot: WifiHistorySnapshot,
	): Boolean {
		if (factKind != WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE || !aggregateShapeIsValid() ||
			lineage.any { it.factKind != WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }
		) {
			return false
		}
		return currentOwner.logicalFactId == fact.aggregateOwnerLogicalFactId &&
			fact.aggregateOwnerSemanticRevision == currentOwner.semanticRevision &&
			WifiCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(fact, this, ownerCursor) &&
			hasCompatibleAggregateAuthority(fact, snapshot)
	}

	private fun factHasExactAuthority(
		fact: WifiCapturedFactRevisionEntity,
		aggregate: WifiCapturedFactRevisionEntity,
		snapshot: WifiHistorySnapshot,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		bindings: Map<Long, SessionManifestSourceEntity>,
		evidence: SourceEvidenceState,
	): Boolean {
		val manifests = manifestsByRun[fact.serviceRunId].orEmpty()
		if (fact.historyManifest(snapshot, manifests, bindings) == null) {
			return false
		}
		val plan = snapshot.desiredPlans[fact.configurationRevision]?.let(WifiHistoryPlanIntegrity::decode)
			?: return false
		val wal = snapshot.admissions.singleOrNull { it.eventId == fact.sourceEventId } ?: return false
		return fact.hasRecordedHistoryAuthority(wal, snapshot, manifests, plan) &&
			fact.availability == WifiCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE &&
			aggregate.aggregateShapeIsValid() && fact.collectedDataEpoch <= evidence.collectedDataEpoch
	}

	private fun WifiCapturedFactRevisionEntity.historyManifest(
		snapshot: WifiHistorySnapshot, manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
	): SessionManifestVersionEntity? {
		if (snapshot.runs[serviceRunId] == null) {
			return null
		}
		val index = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		if (index < 0) {
			return null
		}
		if (bindings[manifestRevision] == null) {
			return null
		}
		return manifests[index]
	}

	private fun WifiCapturedFactRevisionEntity.hasRecordedHistoryAuthority(
		wal: SourceEventWalEntity, snapshot: WifiHistorySnapshot,
		manifests: List<SessionManifestVersionEntity>, plan: WifiPlanEvidence,
	): Boolean {
		val perReading = wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION
		val provider = savedHistoryProvider(snapshot, perReading) ?: return false
		val qualifiedStart = qualifyHistoryStart(wal, snapshot, provider, plan) ?: return false
		return matchesSavedHistoryAuthority(snapshot, manifests, provider, plan, qualifiedStart, perReading)
	}

	private fun WifiCapturedFactRevisionEntity.savedHistoryProvider(
		snapshot: WifiHistorySnapshot, perReading: Boolean,
	): ProviderRegistrationIntervalAuthentication.Authenticated? {
		if (snapshot.planHeaders[configurationRevision] == null) {
			return null
		}
		val provider = snapshot.providerRegistrations[registrationGeneration] ?: return null
		return historyProviderAuthentication(provider, snapshot, perReading)
	}

	private fun WifiCapturedFactRevisionEntity.qualifyHistoryStart(
		wal: SourceEventWalEntity, snapshot: WifiHistorySnapshot,
		provider: ProviderRegistrationIntervalAuthentication.Authenticated, plan: WifiPlanEvidence,
	): Long? {
		val perReading = wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION
		val carrier = if (perReading) {
			(WifiWalCarrierAuthenticator.authenticate(wal)
				as? WifiWalCarrierAuthentication.Authenticated)?.carrier ?: return null
		} else {
			null
		}
		val purposeItems = if (perReading) {
			snapshot.qualifyPurposeItems(wal, provider.interval.startInclusiveNanos, provider.interval.endExclusiveNanos,
				plan, requireNotNull(carrier)) ?: return null
		} else {
			null
		}
		return qualifiedHistoryStart(snapshot, purposeItems, carrier, perReading)
	}

	private fun WifiCapturedFactRevisionEntity.matchesSavedHistoryAuthority(
		snapshot: WifiHistorySnapshot, manifests: List<SessionManifestVersionEntity>,
		provider: ProviderRegistrationIntervalAuthentication.Authenticated, plan: WifiPlanEvidence,
		qualifiedStart: Long, perReading: Boolean,
	): Boolean {
		val sessionEnd = snapshot.historySessionEnd(this) ?: return false
		if (snapshot.consents[captureConsentEpoch] == null) {
			return false
		}
		val index = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		val manifest = manifests[index]
		val expectedManifestEnd = manifests.getOrNull(index + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, provider.interval.endExclusiveNanos, authorizationEffectEndNanos)
		if (!hasExactSavedHistoryObservationAge(plan)) {
			return false
		}
		return snapshot.manifestAuthorityShape(logicalTrackingId, manifestRevision)?.let { sources ->
			snapshot.historyStartAction(serviceRunId, manifestRevision)?.let { action ->
				matchesSavedHistoryBinding(snapshot, manifest, sources, expectedManifestEnd) &&
					matchesSavedHistoryCaptureWindow(snapshot, manifests, provider, qualifiedStart,
						perReading, expectedManifestEnd) &&
					matchesHistoryAcquisitionPlan(plan, manifest, requireNotNull(snapshot.planHeaders[configurationRevision])) &&
					hasSavedHistoryProviderAuthority(snapshot, provider, plan, qualifiedStart, perReading, action)
			} == true
		} == true
	}

	private fun WifiHistorySnapshot.historySessionEnd(fact: WifiCapturedFactRevisionEntity): Long? {
		val session = sessions[fact.logicalTrackingId] ?: return null
		val cutoff = savedSessionCutoffs[fact.logicalTrackingId]
			?: SavedSessionCutoff(session.lifecycleBootId, session.cutoffElapsedNanos)
		return cutoff.retainedEnd(fact.clockDomainId, fact.sessionRunEffectEndNanos)
	}

	private fun WifiCapturedFactRevisionEntity.hasExactSavedHistoryObservationAge(plan: WifiPlanEvidence): Boolean {
		val maximum = runCatching { Math.multiplyExact(plan.maximumAgeMs, NANOS_PER_MILLISECOND) }
			.getOrNull() ?: return false
		val oldest = runCatching { Math.subtractExact(receivedElapsedNanos, coverageIntervalStartNanos) }
			.getOrNull() ?: return false
		return matchesHistoryObservationAge(maximum, oldest)
	}

	// The entry guard has already resolved this same run and capture binding from the immutable snapshot.
	// measuredBindings and composeGroup construct their binding maps exclusively from these source rows.
	private fun WifiCapturedFactRevisionEntity.matchesSavedHistoryBinding(
		snapshot: WifiHistorySnapshot, manifest: SessionManifestVersionEntity,
		sources: Pair<Set<Int>, Set<Int>>, expectedManifestEnd: Long,
	): Boolean {
		val run = requireNotNull(snapshot.runs[serviceRunId])
		val binding = requireNotNull(snapshot.historyCaptureBinding(logicalTrackingId, manifestRevision))
		return matchesHistoryWriterBinding(binding) && matchesHistorySessionBinding(run, manifest, binding) &&
			matchesHistorySourceContext(manifest, sources) && matchesHistorySessionEnvelope(manifest, expectedManifestEnd)
	}

	private fun WifiCapturedFactRevisionEntity.matchesSavedHistoryCaptureWindow(
		snapshot: WifiHistorySnapshot, manifests: List<SessionManifestVersionEntity>,
		provider: ProviderRegistrationIntervalAuthentication.Authenticated, qualifiedStart: Long,
		perReading: Boolean, expectedManifestEnd: Long,
	): Boolean {
		val index = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		val manifest = manifests[index]
		val consent = requireNotNull(snapshot.consents[captureConsentEpoch])
		val capturedStart = maxOf(provider.interval.startInclusiveNanos, authorizationEffectStartNanos,
			manifest.effectiveElapsedRealtimeNanos)
		val capturedEnd = minOf(provider.interval.endExclusiveNanos, authorizationEffectEndNanos, expectedManifestEnd)
		return matchesHistoryQualifiedWindow(consent, qualifiedStart, manifest, manifests.getOrNull(index + 1), perReading) &&
			isInsideHistoryCaptureWindow(capturedStart, capturedEnd)
	}

	private fun WifiCapturedFactRevisionEntity.hasSavedHistoryProviderAuthority(
		snapshot: WifiHistorySnapshot, provider: ProviderRegistrationIntervalAuthentication.Authenticated,
		plan: WifiPlanEvidence, qualifiedStart: Long, perReading: Boolean, action: LifecycleDesiredActionEntity,
	): Boolean {
		val run = requireNotNull(snapshot.runs[serviceRunId])
		val manifest = snapshot.manifestsByRun.getValue(serviceRunId).single { it.manifestRevision == manifestRevision }
		val providerStart = provider.interval.startInclusiveNanos
		val providerEnd = provider.interval.endExclusiveNanos
		val appliedAt = maxOf(providerStart, if (perReading) {
			manifest.effectiveElapsedRealtimeNanos
		} else {
			authorizationEffectStartNanos
		})
		return matchesHistoryProviderApplication(provider.registration, action, providerStart, providerEnd,
			appliedAt, plan) &&
			hasExactAuthorization(this, snapshot.authorizationsByRegistration[registrationGeneration].orEmpty(),
				providerEnd, snapshot.demands, plan, perReading) &&
			action.authenticates(this, run, manifest, appliedAt, qualifiedStart)
	}

	private fun WifiCapturedFactRevisionEntity.matchesHistoryWriterBinding(
		binding: SessionManifestSourceEntity,
	): Boolean = writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
			writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
			writerBindingGeneration == binding.writerBindingGeneration &&
			writerOwnerGeneration == binding.writerOwnerGeneration &&
			purpose == SourceBrokerPurpose.SESSION_CAPTURE && isExactWifiWriter(binding)

	private fun WifiCapturedFactRevisionEntity.matchesHistorySessionBinding(
		run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity, binding: SessionManifestSourceEntity,
	): Boolean = sessionSegmentId == run.sessionSegmentId && logicalTrackingId == run.logicalTrackingId &&
			manifestRevision == manifest.manifestRevision &&
			sourcePolicyRevision == manifest.sourcePolicyRevision &&
			captureConsentEpoch == binding.consentEpoch && SessionManifestIntegrity.hasRetainedLeaseGeneration(run,
                lifecycleLeaseGeneration)

	private fun WifiCapturedFactRevisionEntity.matchesHistorySourceContext(
		manifest: SessionManifestVersionEntity, sources: Pair<Set<Int>, Set<Int>>,
	): Boolean = clockDomainId == manifest.effectiveBootId && storedZoneId == manifest.zoneId &&
			capturedSourceCodes == canonicalSourceCodes(sources.first) &&
			controlSourceCodes == canonicalSourceCodes(sources.second) && WIFI_SOURCE in sources.first &&
			WIFI_SOURCE !in sources.second

	private fun WifiCapturedFactRevisionEntity.matchesHistorySessionEnvelope(
		manifest: SessionManifestVersionEntity, expectedManifestEnd: Long,
	): Boolean = sessionRunEffectStartNanos == manifest.effectiveElapsedRealtimeNanos &&
			(sessionRunEffectEndNanos == expectedManifestEnd ||
				sessionRunEffectEndNanos == Long.MAX_VALUE) &&
			coverageIntervalEndNanos < expectedManifestEnd

	private fun WifiCapturedFactRevisionEntity.matchesHistoryQualifiedWindow(
		consent: SourceConsentEpochEntity, qualifiedStart: Long, manifest: SessionManifestVersionEntity,
		nextManifest: SessionManifestVersionEntity?, perReading: Boolean,
	): Boolean = consentEffectIsExact(consent, qualifiedStart) &&
			qualifiedStart >= manifest.effectiveElapsedRealtimeNanos &&
			(nextManifest == null || (if (perReading) {
				coverageIntervalEndNanos
			} else
				{
				observedElapsedNanos
			}) < nextManifest.effectiveElapsedRealtimeNanos)

	private fun WifiCapturedFactRevisionEntity.matchesHistoryAcquisitionPlan(
		plan: WifiPlanEvidence, manifest: SessionManifestVersionEntity,
		header: AcquisitionPlanRevisionEntity,
	): Boolean = plan.revision == configurationRevision &&
                manifest.acquisitionPlanRevision == configurationRevision &&
			header.revision == configurationRevision && header.sourcePolicyRevision == sourcePolicyRevision &&
			plan.payloadVersion == planPayloadVersion && plan.payloadChecksum == planPayloadChecksum &&
			plan.physicalFingerprint == physicalConfigurationFingerprint

	private fun WifiCapturedFactRevisionEntity.matchesHistoryObservationAge(
		maxAgeNanos: Long, oldestObservationAgeNanos: Long,
	): Boolean = maximumObservationAgeNanos == maxAgeNanos && resultContract == WIFI_RESULT_CONTRACT &&
			oldestObservationAgeNanos in 0L..maxAgeNanos

	private fun WifiCapturedFactRevisionEntity.isInsideHistoryCaptureWindow(
		capturedStart: Long, capturedEnd: Long,
	): Boolean = capturedEnd > capturedStart &&
			coverageIntervalStartNanos >= capturedStart && coverageIntervalStartNanos < capturedEnd &&
			coverageIntervalEndNanos >= capturedStart && coverageIntervalEndNanos < capturedEnd

	private fun WifiCapturedFactRevisionEntity.matchesHistoryProviderApplication(
		provider: ProviderRegistrationGenerationEntity, action: LifecycleDesiredActionEntity,
		providerStart: Long, providerEnd: Long, appliedAt: Long, plan: WifiPlanEvidence,
	): Boolean = registrationAppliedAtNanos == appliedAt &&
			provider.hasExactFactAuthority(this, plan.physicalFingerprint) &&
			provider.acceptedAtMs?.let { accepted -> action.acknowledgedAtMs?.let { accepted <= it } } == true &&
			providerStart <= requireNotNull(action.acknowledgedElapsedRealtimeNanos) &&
			providerStart == providerAcceptanceStartNanos &&
			providerEnd.settlesRecordedRadioEnd(providerAcceptanceEndNanos)

	private fun WifiCapturedFactRevisionEntity.historyProviderAuthentication(
		provider: ProviderRegistrationGenerationEntity, snapshot: WifiHistorySnapshot, perReading: Boolean,
	): ProviderRegistrationIntervalAuthentication.Authenticated? {
		return ProviderRegistrationIntervalAuthenticator.authenticate(
			registration = provider,
			expectedSourceKind = WIFI_SOURCE,
			expectedRegistrationGeneration = registrationGeneration,
			expectedSourceInstanceId = sourceInstanceId,
			expectedClockDomainId = clockDomainId,
			expectedPhysicalConfigurationFingerprint = physicalConfigurationFingerprint,
			expectedCollectedDataEpoch = collectedDataEpoch,
			expectedProviderResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = if (perReading) {
				coverageIntervalStartNanos
			} else {
				observedIntervalStartNanos
			},
			observedEndNanos = if (perReading) {
				coverageIntervalEndNanos
			} else {
				observedElapsedNanos
			},
			observedWallTimeMs = observedWallTimeMs,
			currentClockDomainId = snapshot.currentClockDomainId,
			currentProviderRegistrations = snapshot.currentProviderRegistrations,
		) as? ProviderRegistrationIntervalAuthentication.Authenticated
	}

	private fun WifiCapturedFactRevisionEntity.qualifiedHistoryStart(
		snapshot: WifiHistorySnapshot, purposeItems: RadioPurposeItems?,
		rawCarrier: AuthenticatedWifiWalCarrier?, perReading: Boolean,
	): Long? {
		val qualifiedIndexes = purposeItems?.captureIndexes(logicalTrackingId, serviceRunId,
            manifestRevision,
			lifecycleLeaseGeneration, sourcePolicyRevision, captureConsentEpoch,
                authorizationRevision) ?: if (perReading) {
				return null
			} else {
				null
			}
		val qualifiedStart = qualifiedIndexes?.minOf { index ->
			requireNotNull(requireNotNull(rawCarrier).payload.observations[index].providerTimestampNanos)
		} ?: observedIntervalStartNanos
		if (perReading && (authorizationEffectStartNanos != qualifiedStart ||
			snapshot.childrenByRevision[logicalFactId to semanticRevision].orEmpty()
				.any { it.itemIndex !in requireNotNull(qualifiedIndexes) })) {
			return null
		}
		return qualifiedStart
	}

	private fun WifiCapturedFactRevisionEntity.consentEffectIsExact(
		consent: SourceConsentEpochEntity,
		firstQualifiedNanos: Long = observedIntervalStartNanos,
	): Boolean = consent.sourceKind == WIFI_SOURCE && consent.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		consent.epoch == captureConsentEpoch && consent.effectiveBootId == clockDomainId &&
		consent.effectiveElapsedRealtimeNanos <= firstQualifiedNanos

	private fun ProviderRegistrationGenerationEntity.hasExactFactAuthority(
		fact: WifiCapturedFactRevisionEntity,
		expectedFingerprint: String,
	): Boolean {
		return sourceKind == WIFI_SOURCE && registrationGeneration == fact.registrationGeneration &&
			sourceInstanceId == fact.sourceInstanceId && ownerScope == "source-broker:$WIFI_SOURCE" &&
			providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
			clockDomainId == fact.clockDomainId && collectedDataEpoch == fact.collectedDataEpoch &&
			physicalConfigurationFingerprint == expectedFingerprint
	}

	private fun hasExactAuthorization(
		fact: WifiCapturedFactRevisionEntity,
		rows: List<SourceAuthorizationEntity>,
		providerEnd: Long,
		demands: Map<String, SourceDemandEntity>,
		plan: WifiPlanEvidence,
		perReading: Boolean = false,
	): Boolean {
		val exact = rows.filter { it.authorizationRevision == fact.authorizationRevision }
		if (exact.isEmpty() || exact.any { row ->
				!row.matchesSavedAuthorization(fact.registrationGeneration, fact.authorizationFingerprint,
					fact.purposeEligibilityMask, fact.clockDomainId) ||
					!perReading && row.effectiveElapsedRealtimeNanos != fact.authorizationEffectStartNanos
			}) {
			return false
		}
		val exactDemands = matchingAuthorizationDemands(
			exact, fact.registrationGeneration, fact.authorizationRevision, demands,
		) ?: return false
		val first = exact.first()
		val captureMemberCount = exact.count { row -> !row.isDenyAll && row.matchesCaptureFact(fact) }
		val next = rows.nextHistoryAuthorizationStartNanos(
			fact.clockDomainId, first.effectiveElapsedRealtimeNanos, fact.authorizationRevision,
		)
		val contractsMatch = exactDemands.all { demand ->
			demand.hasCompatibleWifiPhysicalContract(plan, fact.clockDomainId)
		}
		val expectedEnd = minOf(providerEnd, next)
		return captureMemberCount == 1 && contractsMatch &&
			expectedEnd.settlesRecordedRadioEnd(fact.authorizationEffectEndNanos) &&
			fact.coverageIntervalEndNanos < expectedEnd
	}

	/** Find the next saved authorization on this clock, preserving elapsed-time/revision tie ordering. */
	private fun List<SourceAuthorizationEntity>.nextHistoryAuthorizationStartNanos(
		clockDomainId: String,
		currentStartNanos: Long,
		currentRevision: Long,
	): Long = asSequence().filter { row -> row.effectiveBootId == clockDomainId &&
		(row.effectiveElapsedRealtimeNanos > currentStartNanos ||
			row.effectiveElapsedRealtimeNanos == currentStartNanos &&
				row.authorizationRevision > currentRevision) }
		.minWithOrNull(compareBy(SourceAuthorizationEntity::effectiveElapsedRealtimeNanos,
			SourceAuthorizationEntity::authorizationRevision))?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE

	/** One immutable-demand reconstruction for both WAL and retained fact authorization. */
	private fun matchingAuthorizationDemands(
		rows: List<SourceAuthorizationEntity>,
		registrationGeneration: Long,
		authorizationRevision: Long,
		demands: Map<String, SourceDemandEntity>,
	): List<SourceDemandEntity>? {
		val demandIds = rows.mapNotNull(SourceAuthorizationEntity::demandId)
		val exactDemands = demandIds.mapNotNull(demands::get)
		if (demandIds.distinct().size != demandIds.size || exactDemands.size != demandIds.size) {
			return null
		}
		val first = rows.first()
		val recomputed = runCatching {
			SourceBrokerAuthorization.rows(
				WIFI_SOURCE, registrationGeneration, authorizationRevision, exactDemands,
				first.effectiveBootId, first.effectiveElapsedRealtimeNanos, first.effectiveWallTimeMs,
			)
		}.getOrNull()
		return exactDemands.takeIf {
			recomputed?.sortedBy(SourceAuthorizationEntity::memberId) ==
				rows.sortedBy(SourceAuthorizationEntity::memberId)
		}
	}

	private fun SourceDemandEntity.hasCompatibleWifiPhysicalContract(
		plan: WifiPlanEvidence,
		clockDomainId: String,
	): Boolean = sourceKind == WIFI_SOURCE && requestedBootId == clockDomainId &&
		minimumAcquisitionSpec == WIFI_BROADCAST_FLOOR && requestedDeliveryLatencyMs == null &&
		plan.maximumAgeMs <= maximumAgeMs

	// One saved action-to-fact/run/manifest stamp, including its recorded scalar acknowledgement bounds.
	@Suppress("CyclomaticComplexMethod")
	private fun LifecycleDesiredActionEntity.authenticates(
		fact: WifiCapturedFactRevisionEntity,
		run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity,
		appliedAt: Long,
		firstQualifiedNanos: Long = fact.observedIntervalStartNanos,
	): Boolean {
		val acknowledgedWall = acknowledgedAtMs ?: return false
		val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
		return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == WIFI_SOURCE &&
			desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
			failureCode == null && retryTrigger == null && logicalTrackingId == fact.logicalTrackingId &&
			serviceRunId == fact.serviceRunId && manifestRevision == fact.manifestRevision &&
			desiredPlanRevision == fact.configurationRevision && sourcePolicyRevision == fact.sourcePolicyRevision &&
			consentEpoch == fact.captureConsentEpoch && startOrigin == manifest.startOrigin &&
			(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin) &&
                bootId == fact.clockDomainId &&
			leaseGeneration == fact.lifecycleLeaseGeneration && sourceInstanceId == fact.sourceInstanceId &&
			registrationGeneration == fact.registrationGeneration && requestedAtMs == manifest.effectiveWallTimeMs &&
			acknowledgedWall >= requestedAtMs &&
			requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
			requestedElapsedRealtimeNanos <= appliedAt && appliedAt <= acknowledgedElapsed &&
			appliedAt <= firstQualifiedNanos
	}

	// One historical action/member/WAL correspondence with the original recorded acknowledgement fields.
	@Suppress("CyclomaticComplexMethod")
	private fun LifecycleDesiredActionEntity.authenticatesHistorical(
		wal: SourceEventWalEntity, manifest: SessionManifestVersionEntity, member: SourceAuthorizationEntity,
		appliedAt: Long, firstQualified: Long,
	): Boolean {
		val acknowledgedWall = acknowledgedAtMs ?: return false
		val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
		return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == WIFI_SOURCE &&
			desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
			failureCode == null && retryTrigger == null && logicalTrackingId == member.logicalTrackingId &&
			serviceRunId == member.serviceRunId && manifestRevision == member.manifestRevision &&
			desiredPlanRevision == manifest.acquisitionPlanRevision &&
                sourcePolicyRevision == member.sourcePolicyRevision &&
			consentEpoch == member.consentEpoch && startOrigin == manifest.startOrigin && bootId == wal.clockDomainId &&
			leaseGeneration == member.lifecycleLeaseGeneration && sourceInstanceId == wal.sourceInstanceId &&
			registrationGeneration == wal.registrationGeneration && requestedAtMs == manifest.effectiveWallTimeMs &&
			requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
                acknowledgedWall >= requestedAtMs &&
			requestedElapsedRealtimeNanos <= appliedAt && appliedAt <= acknowledgedElapsed &&
                appliedAt <= firstQualified
	}

	private fun toObservation(
		fact: WifiCapturedFactRevisionEntity,
		aggregate: WifiCapturedFactRevisionEntity,
		snapshot: WifiHistorySnapshot,
	): WifiHistoryObservation {
		val spanMs = Math.subtractExact(fact.coverageIntervalEndNanos, fact.coverageIntervalStartNanos) /
			NANOS_PER_MILLISECOND
		val start = Math.subtractExact(fact.observedWallTimeMs, spanMs)
		val rejected = listOf(fact.staleResultCount, fact.clockUnverifiableResultCount,
			fact.malformedResultCount).fold(0, Math::addExact)
		val bands = linkedMapOf<WifiHistoryBand, Int>()
		fun add(band: WifiHistoryBand, value: Int?) { if (requireNotNull(value) > 0) {
			bands[band] = value
		} }
		add(WifiHistoryBand.TWO_POINT_FOUR_GHZ, aggregate.twoPointFourGhzCount)
		add(WifiHistoryBand.FIVE_GHZ, aggregate.fiveGhzCount)
		add(WifiHistoryBand.SIX_GHZ, aggregate.sixGhzCount)
		add(WifiHistoryBand.OTHER, aggregate.otherBandCount)
		val count = requireNotNull(aggregate.observationCount)
		val sum = requireNotNull(aggregate.signalSumDbm)
		return WifiHistoryObservation(
			EpochMs(start), EpochMs(fact.observedWallTimeMs), fact.wallTimeUncertaintyMs,
			WifiHistoryAvailability.AVAILABLE,
			WifiHistoryResultCompleteness.valueOf(fact.coverageCompleteness),
			fact.submittedResultCount, fact.acceptedResultCount, rejected, count, bands,
			WifiHistorySignalQuality(requireNotNull(aggregate.strongestSignalDbm),
				requireNotNull(aggregate.weakestSignalDbm), sum.toDouble() / count, count),
			fact.qualityFlags, fact.qualityConfidence, fact.storedZoneId,
			localSourceEventId = fact.sourceEventId,
			networks = snapshot.childrenByRevision[fact.logicalFactId to fact.semanticRevision]
				.orEmpty().map { child -> WifiObservedNetwork(
					child.observedWallTimeMs, child.wallTimeUncertaintyMs,
					child.bssid?.takeIf(String::isNotBlank),
					child.ssid?.takeIf(String::isNotEmpty),
					child.frequencyMhz, child.signalDbm,
				) },
		)
	}

	private fun WifiCapturedFactRevisionEntity.aggregateShapeIsValid(): Boolean =
		factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
			observationCount == acceptedResultCount &&
			listOf(twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount)
				.filterNotNull().fold(0, Math::addExact) == observationCount &&
			requireNotNull(strongestSignalDbm) >= requireNotNull(weakestSignalDbm) &&
			requireNotNull(signalSumDbm).toDouble() / requireNotNull(observationCount) in
				requireNotNull(weakestSignalDbm).toDouble()..requireNotNull(strongestSignalDbm).toDouble()

	private fun WifiCapturedFactRevisionEntity.hasCompatibleAggregateAuthority(
		dependent: WifiCapturedFactRevisionEntity,
		snapshot: WifiHistorySnapshot,
	): Boolean {
		val ownerSources = snapshot.manifestAuthorityShape(logicalTrackingId, manifestRevision) ?: return false
		val dependentSources = snapshot.manifestAuthorityShape(dependent.logicalTrackingId,
			dependent.manifestRevision) ?: return false
		return captureAuthorityKey() == dependent.captureAuthorityKey() && ownerSources == dependentSources
	}

	private fun WifiCapturedFactRevisionEntity.captureAuthorityKey(): List<Any?> = listOf(
		logicalTrackingId, serviceRunId, sessionSegmentId, capturedSourceCodes, controlSourceCodes,
		sourceInstanceId, registrationGeneration, configurationRevision, physicalConfigurationFingerprint,
		authorizationRevision, authorizationFingerprint, purposeEligibilityMask, sourcePolicyRevision,
		captureConsentEpoch, manifestRevision, lifecycleLeaseGeneration, collectedDataEpoch,
		scopeDeletionGeneration, clockDomainId, storedZoneId, planPayloadVersion, planPayloadChecksum,
		maximumObservationAgeNanos, resultContract, registrationAppliedAtNanos,
		providerAcceptanceStartNanos, providerAcceptanceEndNanos, authorizationEffectStartNanos,
		authorizationEffectEndNanos, sessionRunEffectStartNanos, sessionRunEffectEndNanos,
	)

	private fun WifiCapturedFactRevisionEntity.hasCurrentPrivacyAuthority(
		logicalId: String,
		evidence: SourceEvidenceState,
		snapshot: WifiHistorySnapshot,
	): Boolean = evidence.acceptsRetainedEpoch(collectedDataEpoch) &&
		sourceAdmissionOrdinal > evidence.deletedSourceEventHighWaterOrdinal &&
		scopeDeletionGeneration == (snapshot.deletionGenerations[logicalId to serviceRunId]?.generation ?: 0L) &&
		(logicalId to serviceRunId) !in snapshot.deletedScopes

	private fun WifiHistorySnapshot.manifestAuthorityShape(
		logicalId: String,
		manifestRevision: Long,
	): Pair<Set<Int>, Set<Int>>? {
		val sources = sourcesByManifest[WifiManifestKey(logicalId, manifestRevision)] ?: return null
		val captured = sources.filter { it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			it.persistenceEligible }.mapTo(linkedSetOf(), SessionManifestSourceEntity::sourceKind)
		val control = sources.filter { it.purpose == SessionManifestPurposeCode.CONTROL }
			.mapTo(linkedSetOf(), SessionManifestSourceEntity::sourceKind)
		return captured to control
	}

	private fun hasValidCompleteness(
		rows: List<SourceSessionCompletenessEntity>,
		logicalId: String,
		runIds: List<String>,
	): Boolean = rows.groupBy(SourceSessionCompletenessEntity::serviceRunId).all { (runId, runRows) ->
		runId in runIds && runRows.map(SourceSessionCompletenessEntity::registrationGeneration).distinct().size ==
			runRows.size && runRows.all { it.hasValidHistoryCompleteness(logicalId) }
	}

	private fun SourceSessionCompletenessEntity.hasValidHistoryCompleteness(logicalId: String): Boolean {
		if (logicalTrackingId != logicalId || sourceInstanceId.isBlank() || registrationGeneration < 0L) {
			return false
		}
		return hasValidHistoryCompletenessTail() && providerCoverage in PROVIDER_COVERAGE_VALUES &&
			stopStatus in STOP_STATUS_VALUES && updatedAtMs >= 0L
	}

	private fun SourceSessionCompletenessEntity.hasValidHistoryCompletenessTail(): Boolean =
		lastAdmissionOrdinal?.let { it > 0L } != false && lastSourceSequence?.let { it >= 0L } != false &&
			(unresolvedSequenceStart == null) == (unresolvedSequenceEnd == null)

	/** Running captures have no Stop receipt; every saved receipt still needs its exact tail. */
	private fun hasExactCompletenessTails(
		rows: List<SourceSessionCompletenessEntity>,
		admissions: List<SourceEventWalEntity>,
		currentFacts: List<WifiCapturedFactRevisionEntity>,
		settledRunIds: Set<String>,
	): Boolean {
		val rowsByOwner = rows.associateBy {
			Triple(it.serviceRunId, it.sourceInstanceId, it.registrationGeneration)
		}
		if (rowsByOwner.size != rows.size) {
			return false
		}
		if (currentFacts.any { fact ->
				fact.serviceRunId in settledRunIds && rowsByOwner[Triple(
					fact.serviceRunId,
					fact.sourceInstanceId,
					fact.registrationGeneration,
				)] == null
			}
		) {
			return false
		}
		return rows.all { it.hasExactWifiHistoryTail(admissions) }
	}

	/** Match one saved owner receipt against the final admission in that owner’s retained prefix. */
	private fun SourceSessionCompletenessEntity.hasExactWifiHistoryTail(
		admissions: List<SourceEventWalEntity>,
	): Boolean {
		val ownedAdmissions = admissions.filter {
			it.serviceRunId == serviceRunId &&
				it.sourceInstanceId == sourceInstanceId &&
				it.registrationGeneration == registrationGeneration &&
				(it.payloadVersion != WIFI_WAL_PAYLOAD_VERSION || lastAdmissionOrdinal?.let { end ->
                        it.admissionOrdinal <= end } == true)
		}
		return if (ownedAdmissions.isEmpty()) {
			lastAdmissionOrdinal == null && lastSourceSequence == null
		} else {
			val tail = ownedAdmissions.maxBy(SourceEventWalEntity::admissionOrdinal)
			lastAdmissionOrdinal == tail.admissionOrdinal &&
				lastSourceSequence == tail.sourceSequence
		}
	}

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private fun hasValidSessionSettlement(
		session: LogicalTrackingSessionEntity,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		targetOrdinal: Long?,
	): Boolean {
		if (!hasConsistentCompletion(session.state, session.completedAtMs)) {
			return false
		}
		val latestByRun = latestSettledWifiManifests(session, runs, manifestsByRun) ?: return false
		val active = runs.filter { it.completedAtMs == null && it.state !in TERMINAL_STATES }
		if (active.isNotEmpty()) {
			val run = active.singleOrNull() ?: return false
			val latest = latestByRun[run] ?: return false
			return session.hasExactActiveWifiRunSettlement(run, latest)
		}
		return session.hasCompleteTerminalSettlement(targetOrdinal)
	}

	private fun LogicalTrackingSessionEntity.hasCompleteTerminalSettlement(targetOrdinal: Long?): Boolean {
		if (state !in TERMINAL_STATES || cutoffAtMs == null || cutoffElapsedNanos == null) {
			return false
		}
		return currentServiceRunId == null &&
			finalAdmissionOrdinal?.let { targetOrdinal == null || targetOrdinal <= it } == true
	}

	/** Resolve and compare the latest saved manifest per run before selecting an active run. */
	@Suppress("ReturnCount") // Missing per-run/latest manifests and mismatched saved stamps retain their ordered exits.
	private fun latestSettledWifiManifests(
		session: LogicalTrackingSessionEntity,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	): Map<SourceServiceRunEntity, SessionManifestVersionEntity>? {
		val latestByRun = runs.associateWith { run -> manifestsByRun[run.serviceRunId].orEmpty()
			.maxByOrNull(SessionManifestVersionEntity::manifestRevision) ?: return null }
		if (latestByRun.any { (run, manifest) -> run.desiredPlanRevision != manifest.acquisitionPlanRevision ||
			run.rolloutRevision != manifest.rolloutRevision }) {
			return null
		}
		val latestLogical = latestByRun.values.maxByOrNull(SessionManifestVersionEntity::manifestRevision)
			?: return null
		if (session.desiredPlanRevision != latestLogical.acquisitionPlanRevision ||
			session.rolloutRevision != latestLogical.rolloutRevision || session.sessionMode != latestLogical.sessionMode
		) {
			return null
		}
		return latestByRun
	}

	// One saved session/run/latest-manifest stamp, including the finite state pair and cutoff shape.
	@Suppress("CyclomaticComplexMethod")
	private fun LogicalTrackingSessionEntity.hasExactActiveWifiRunSettlement(
		run: SourceServiceRunEntity,
		latest: SessionManifestVersionEntity,
	): Boolean {
		val pairValid = state in ADMISSION_SESSION_STATES && run.state in ADMISSION_RUN_STATES ||
			state == "ACTIVE" && run.state == "STOPPING" ||
			state == "STOPPING" && run.state == "STOPPING"
		val cutoffValid = if (state == "STOPPING" && run.state == "STOPPING")
			{
			cutoffAtMs != null && cutoffElapsedNanos != null
		}
		else {
			cutoffAtMs == null && cutoffElapsedNanos == null
		}
		return pairValid && cutoffValid && currentServiceRunId == run.serviceRunId &&
			currentManifestRevision == latest.manifestRevision &&
			lifecycleLeaseGeneration == run.leaseGeneration && lifecycleBootId == run.bootId &&
			finalAdmissionOrdinal == null
	}

	private fun hasConsistentCompletion(state: String, completedAtMs: Long?): Boolean =
		(state in TERMINAL_STATES) == (completedAtMs != null) && state in ALL_SESSION_STATES

	private fun isActive(session: LogicalTrackingSessionEntity, runs: List<SourceServiceRunEntity>): Boolean =
		session.completedAtMs == null && runs.any { it.completedAtMs == null }

	private fun SourceServiceRunEntity.ownsExactSegment(
		logicalId: String,
		segments: List<SessionSegment>,
	): Boolean {
		val segment = segments.singleOrNull { it.id == sessionSegmentId } ?: return false
		return logicalTrackingId == logicalId && segment.serviceRunId == serviceRunId &&
			segment.logicalTrackingId == logicalId &&
			presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
	}

	private fun earliestPossibleWallTimeMs(fact: WifiCapturedFactRevisionEntity): Long =
		requireNotNull(CanonicalRetentionBounds.wifiCoverage(
			coverageEndWallTimeMs = fact.observedWallTimeMs,
			wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
			coverageStartElapsedRealtimeNanos = fact.coverageIntervalStartNanos,
			coverageEndElapsedRealtimeNanos = fact.coverageIntervalEndNanos,
		))

	// Checks one immutable writer-binding tuple against the existing named storage contract.
	@Suppress("CyclomaticComplexMethod")
	private fun isExactWifiWriter(source: SessionManifestSourceEntity): Boolean =
		source.sourceKind == WIFI_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible &&
			source.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI &&
			source.writerOwner == SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS &&
			SourceWriterGenerationContract.captureModeMaskForKnownBinding(source.writerBindingGeneration ?:
                -1L) != null &&
			source.writerOwnerGeneration == source.writerBindingGeneration
				?.let(SourceWriterGenerationContract::canonicalOwnerGeneration) &&
			source.writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
			source.writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
			source.writerBindingGeneration != null

	private fun isWifiLane(lane: SourceProductProjectionLaneEntity): Boolean = lane.sourceKind == WIFI_SOURCE &&
		SourceWriterGenerationContract.captureModeMaskForKnownBinding(lane.bindingGeneration) != null &&
		lane.projectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
		lane.projectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION

	// One saved lane row and its finite ACTIVE/RETIRED field shape; no live authorization or traversal.
	@Suppress("CyclomaticComplexMethod")
	private fun isValidLane(lane: SourceProductProjectionLaneEntity): Boolean {
		@Suppress("ComplexCondition") // Complete scalar/null header of this single saved lane.
		if (lane.captureModeMask != SourceWriterGenerationContract.captureModeMaskForKnownBinding(lane.bindingGeneration) ||
			lane.productStage !in PRODUCT_STAGES || lane.activatedRolloutRevision <= 0L ||
			lane.activationOrdinal <= 0L || lane.contiguousAdmissionOrdinal < lane.activationOrdinal - 1L ||
			lane.installedAtMs < 0L || lane.updatedAtMs < lane.installedAtMs ||
			(lane.terminalDisposition == null) != (lane.terminalAtMs == null)
		) {
			return false
		}
		return when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE -> lane.captureAdmissionCutoffOrdinal == null &&
				lane.terminalDisposition == null && lane.retentionRequired
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> {
				val cutoff = lane.captureAdmissionCutoffOrdinal ?: return false
				val terminalAt = lane.terminalAtMs ?: return false
				cutoff >= lane.activationOrdinal - 1L && lane.contiguousAdmissionOrdinal == cutoff &&
					lane.terminalDisposition ==
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
					terminalAt >= lane.installedAtMs && lane.updatedAtMs >= terminalAt && !lane.retentionRequired
			}
			else -> false
		}
	}

	private fun members(logicalId: String, snapshot: WifiHistorySnapshot): List<SessionSegment> =
		snapshot.expansion.segments.filter { it.logicalTrackingId == logicalId }
			.sortedWith(compareBy(SessionSegment::startTimeMs, SessionSegment::id))

	private fun entry(
		logicalId: String,
		segments: List<SessionSegment>,
		zones: Set<String>,
		state: WifiHistoryProductState,
		coverage: WifiHistoryCoverage,
		observations: List<WifiHistoryObservation>,
		causes: Set<WifiHistoryCause>,
		capturesOnlyWifi: Boolean = false,
	): WifiHistoryEntry {
		val start = segments.minOf(SessionSegment::startTimeMs).coerceAtLeast(0L)
		val end = segments.maxOf(SessionSegment::endTimeMs).coerceAtLeast(start)
		val selection = runCatching {
			WifiLocalHistorySelectionKey(
				PortableWifiOpaqueIdentity.derive(
					PortableWifiIdentityKind.LOGICAL_ENTRY,
					logicalId,
				).value,
			)
		}.getOrNull().takeUnless { state == WifiHistoryProductState.FAILED }
		return WifiHistoryEntry(WifiHistoryEntryKey("wifi-logical:$logicalId"), EpochMs(start), EpochMs(end),
			zones, state, coverage, observations, causes, localSelection = selection,
			capturesOnlyWifi = capturesOnlyWifi)
	}

	private fun failed(logicalId: String, segments: List<SessionSegment>, cause: WifiHistoryCause) =
		entry(logicalId, segments, emptySet(), WifiHistoryProductState.FAILED, WifiHistoryCoverage.NONE,
			emptyList(), setOf(cause))

	private fun unavailable(
		logicalId: String,
		segments: List<SessionSegment>,
		zones: Set<String>,
		cause: WifiHistoryCause,
		capturesOnlyWifi: Boolean = false,
	) = entry(logicalId, segments, zones, WifiHistoryProductState.UNAVAILABLE, WifiHistoryCoverage.NONE,
		emptyList(), setOf(cause), capturesOnlyWifi)

	private fun materializing(
		logicalId: String,
		segments: List<SessionSegment>,
		zones: Set<String>,
		capturesOnlyWifi: Boolean = false,
	) =
		entry(logicalId, segments, zones, WifiHistoryProductState.MATERIALIZING, WifiHistoryCoverage.NONE,
			emptyList(), setOf(WifiHistoryCause.MATERIALIZATION_BEHIND), capturesOnlyWifi)

	private fun missing(
		logicalId: String,
		segments: List<SessionSegment>,
		zones: Set<String>,
		capturesOnlyWifi: Boolean = false,
	) =
		entry(logicalId, segments, zones, WifiHistoryProductState.MISSING, WifiHistoryCoverage.NONE,
			emptyList(), setOf(WifiHistoryCause.NO_QUALIFIED_FACTS), capturesOnlyWifi)

	private fun legacy(segment: SessionSegment) = WifiHistoryEntry(
		WifiHistoryEntryKey("wifi-legacy:${segment.id}"), EpochMs(segment.startTimeMs.coerceAtLeast(0L)),
		EpochMs(segment.endTimeMs.coerceAtLeast(segment.startTimeMs.coerceAtLeast(0L))), emptySet(),
		WifiHistoryProductState.UNAVAILABLE, WifiHistoryCoverage.NONE, emptyList(),
		setOf(WifiHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun canonicalSourceCodes(values: Set<Int>): String = values.sorted().joinToString(",")

	private val TERMINAL_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
	private val ALL_SESSION_STATES = TERMINAL_STATES + setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
	private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
	private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
	private val PROVIDER_UNAVAILABLE_STATUSES = setOf("PERMISSION_LOST", "PROVIDER_FAILED")
	private val PROVIDER_COVERAGE_VALUES = setOf("CALLBACKS_ENTERED_BEFORE_BARRIER",
		"PROVIDER_COMPLETENESS_UNOBSERVABLE")
	private val STOP_STATUS_VALUES = setOf("COMPLETE", "TIMED_OUT", "PERMISSION_LOST", "PROVIDER_FAILED",
		"PROCESS_RESTARTED")
	private const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
	private const val COMPLETE_STOP_STATUS = "COMPLETE"
	private const val WIFI_RESULT_CONTRACT = "ANDROID_SCAN_RESULTS_V1"
	private const val WIFI_BROADCAST_FLOOR = "wifi:v1:required=BROADCAST_CALLBACK"
	private const val CAPTURED_PLAN_ORDINAL = 0
	private const val MANUAL_CAPTURE_MASK = 1L
	private const val AUTOMATIC_CAPTURE_MASK = 2L
	private const val NANOS_PER_MILLISECOND = 1_000_000L
	private val PRODUCT_STAGES = setOf(
		SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
		SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
	)
	private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
}

/** Canonical v1 Wi-Fi desired-plan verifier kept local to the product read model. */
internal object WifiHistoryPlanIntegrity {
	fun decode(row: SourceDesiredPlanEntity): WifiPlanEvidence? = runCatching {
		if (row.payloadVersion != PAYLOAD_VERSION || row.payload.size > MAX_PAYLOAD_BYTES ||
			sha256(row.payload) != row.payloadChecksum
		) {
			return null
		}
		DataInputStream(ByteArrayInputStream(row.payload)).use { input -> input.readPlanEvidence(row) }
	}.getOrNull()

	private fun DataInputStream.readPlanEvidence(row: SourceDesiredPlanEntity): WifiPlanEvidence {
		require(readInt() == PAYLOAD_VERSION)
		require(readUTF() == "WIFI")
		val revision = readLong()
		val mode = readUTF()
		val minimumAttemptMs = readLong()
		val maximumAgeMs = readLong()
		val dedupeWindowMs = readLong()
		val initialBackoffMs = readLong()
		val maximumBackoffMs = readLong()
		val multiplier = readDouble()
		require(available() == 0)
		require(row.sourceKind == WIFI_SOURCE && revision == row.revision && revision >= 0L)
		require(mode in MODES && minimumAttemptMs >= 0L && maximumAgeMs >= 0L && dedupeWindowMs >= 0L)
		require(initialBackoffMs >= 0L && maximumBackoffMs >= initialBackoffMs &&
			multiplier.isFinite() && multiplier >= 1.0)
		return WifiPlanEvidence(
			revision, mode in ACCEPTED_CAPTURE_MODES,
			physicalFingerprint(mode, minimumAttemptMs, initialBackoffMs, maximumBackoffMs, multiplier),
			maximumAgeMs, PAYLOAD_VERSION, row.payloadChecksum,
		)
	}

	private fun physicalFingerprint(
		mode: String,
		minimumAttemptMs: Long,
		initialBackoffMs: Long,
		maximumBackoffMs: Long,
		multiplier: Double,
	): String {
		val canonical = when (mode) {
			"OFF" -> listOf("WIFI", mode)
			"CACHED_ONLY", "BROADCAST_DRIVEN" -> listOf("WIFI", "CALLBACK_REGISTRATION")
			else -> listOf("WIFI", "CALLBACK_REGISTRATION", "ACTIVE_PROBE", minimumAttemptMs,
				initialBackoffMs, maximumBackoffMs, multiplier)
		}.joinToString("\u001f")
		return sha256(canonical.toByteArray())
	}

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).toHexString()

	private const val PAYLOAD_VERSION = 1
	private const val MAX_PAYLOAD_BYTES = 1_024
	private val MODES = setOf("OFF", "CACHED_ONLY", "BROADCAST_DRIVEN", "ACTIVE_ATTEMPTS")
	private val ACCEPTED_CAPTURE_MODES = setOf("BROADCAST_DRIVEN", "ACTIVE_ATTEMPTS")
}

internal data class WifiPlanEvidence(
	val revision: Long,
	val enabled: Boolean,
	val physicalFingerprint: String,
	val maximumAgeMs: Long,
	val payloadVersion: Int,
	val payloadChecksum: String,
)

internal data class WifiMembershipExpansion(
	val segments: List<SessionSegment>,
	val failures: Map<String, WifiHistoryCause>,
	val overflow: Boolean = false,
)

internal data class WifiManifestKey(val logicalTrackingId: String, val manifestRevision: Long)

@Suppress("LongParameterList")
internal data class WifiHistorySnapshot(
	val expansion: WifiMembershipExpansion,
	val sessions: Map<String, LogicalTrackingSessionEntity>,
	val runs: Map<String, SourceServiceRunEntity>,
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByManifest: Map<WifiManifestKey, List<SessionManifestSourceEntity>>,
	val policies: Map<Long, SourcePolicyEntity>,
	val consents: Map<Long, SourceConsentEpochEntity>,
	val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
	val revisions: List<WifiCapturedFactRevisionEntity>,
	val childrenByRevision: Map<Pair<String, Long>, List<WifiCapturedChildEntity>> = emptyMap(),
	val cursors: List<WifiCapturedFactCursorEntity>,
	val deletionGenerations: Map<Pair<String, String>, WifiCaptureDeletionGenerationEntity>,
	val deletedScopes: Set<Pair<String, String>>,
	val planHeaders: Map<Long, AcquisitionPlanRevisionEntity>,
	val desiredPlans: Map<Long, SourceDesiredPlanEntity>,
	val providerRegistrations: Map<Long, ProviderRegistrationGenerationEntity>,
	val currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	val currentClockDomainId: String?,
	val authorizationsByRegistration: Map<Long, List<SourceAuthorizationEntity>>,
	val demands: Map<String, SourceDemandEntity>,
	val startActions: List<LifecycleDesiredActionEntity>,
	val admissions: List<SourceEventWalEntity>,
	val lanes: List<SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val evidenceState: SourceEvidenceState?,
	val overflow: Boolean,
	val savedSessionCutoffs: Map<String, SavedSessionCutoff> = emptyMap(),
) {
	companion object {
		fun empty(expansion: WifiMembershipExpansion) = WifiHistorySnapshot(
			expansion = expansion,
			sessions = emptyMap(),
			runs = emptyMap(),
			manifestsByRun = emptyMap(),
			sourcesByManifest = emptyMap(),
			policies = emptyMap(),
			consents = emptyMap(),
			completenessByRun = emptyMap(),
			revisions = emptyList(),
			childrenByRevision = emptyMap(),
			cursors = emptyList(),
			deletionGenerations = emptyMap(),
			deletedScopes = emptySet(),
			planHeaders = emptyMap(),
			desiredPlans = emptyMap(),
			providerRegistrations = emptyMap(),
			currentProviderRegistrations = emptyList(),
			currentClockDomainId = null,
			authorizationsByRegistration = emptyMap(),
			demands = emptyMap(),
			startActions = emptyList(),
			admissions = emptyList(),
			lanes = emptyList(),
			terminalFailures = emptyList(),
			evidenceState = null,
			overflow = false,
		)
	}
}

internal data class ComposedWifiEntry(
	val logicalTrackingId: String,
	val recencyStartTimeMs: Long,
	val recencySegmentId: Long,
	val physicalSegmentIds: List<Long>,
	val entry: WifiHistoryEntry,
	val structuralRuns: List<WifiHistoryStructuralRunProjection> = emptyList(),
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(recencyStartTimeMs >= 0L && recencySegmentId > 0L)
		require(physicalSegmentIds.isNotEmpty() && physicalSegmentIds.all { it > 0L })
		require(physicalSegmentIds.distinct().size == physicalSegmentIds.size)
		require(recencySegmentId in physicalSegmentIds)
		require(entry.origin == WifiHistoryOrigin.LOCAL)
		require(structuralRuns.all {
			it.startTimeMs >= entry.startTime.raw && it.endTimeMs <= entry.endTime.raw
		})
	}
}

internal sealed interface WifiComposedPage {
	data class Available(val entries: List<ComposedWifiEntry>) : WifiComposedPage
	data class Failed(val cause: WifiHistoryCause) : WifiComposedPage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

internal sealed interface WifiSourceRecentEntry {
	val entry: WifiHistoryEntry

	data class Local(val composed: ComposedWifiEntry) : WifiSourceRecentEntry {
		override val entry: WifiHistoryEntry = composed.entry
	}

	/** Imported rows deliberately expose no local logical or physical member identity. */
	data class Imported(
		override val entry: WifiHistoryEntry,
		val recencyStartTimeMs: Long,
		val recencyIdentity: String,
		val selection: WifiImportedHistorySelection = requireNotNull(entry.importedSelection),
	) : WifiSourceRecentEntry {
		init {
			require(entry.origin == WifiHistoryOrigin.IMPORTED)
			require(entry.localSelection == null)
			require(entry.importedSelection == selection)
			require(recencyStartTimeMs >= 0L && recencyIdentity.isNotBlank())
		}
	}
}

internal sealed interface WifiSourceRecentPage {
	data class Available(val entries: List<WifiSourceRecentEntry>) : WifiSourceRecentPage
	data class Failed(val cause: WifiHistoryCause) : WifiSourceRecentPage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

internal val wifiCompositionOrder =
	compareByDescending<ComposedWifiEntry> { it.recencyStartTimeMs }
		.thenByDescending { it.recencySegmentId }

internal const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI

internal data class QualifiedMeasuredWifiFact(val fact: WifiCapturedFactRevisionEntity,
	val observation: WifiHistoryObservation, val children: List<WifiCapturedChildEntity>, val partial: Boolean)
