package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
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
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
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
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.stats.api.repository.WifiHistoryAvailability
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryObservation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryResultCompleteness
import com.adsamcik.tracker.stats.api.repository.WifiHistorySignalQuality
import com.adsamcik.tracker.stats.api.value.EpochMs
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.time.ZoneId

/** Pure fail-closed composition of one bounded, transactionally loaded Wi-Fi snapshot. */
@Suppress("LargeClass", "TooManyFunctions")
internal object WifiHistoryComposer {
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
			val entry = composeGroup(logicalId, snapshot, laneExecutionAuthority) ?: return@mapNotNull null
			ComposedWifiEntry(
				logicalId,
				members.maxOfOrNull(SessionSegment::startTimeMs) ?: 0L,
				members.maxWithOrNull(compareBy(SessionSegment::startTimeMs, SessionSegment::id))?.id ?: 0L,
				entry,
			)
		}.toList()

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun composeGroup(
		logicalId: String,
		snapshot: WifiHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): WifiHistoryEntry? {
		val segments = members(logicalId, snapshot)
		if (segments.isEmpty()) return null
		if (snapshot.overflow) return failed(logicalId, segments, WifiHistoryCause.READ_BUDGET_EXCEEDED)
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
		val zones = manifests.mapTo(linkedSetOf(), SessionManifestVersionEntity::zoneId)
		if (zones.any { runCatching { ZoneId.of(it) }.isFailure }) {
			return failed(logicalId, segments, WifiHistoryCause.STORED_ZONE_INVALID)
		}
		val bindings = linkedMapOf<Long, SessionManifestSourceEntity>()
		for (manifest in manifests) {
			val matches = snapshot.sourcesByManifest[WifiManifestKey(logicalId, manifest.manifestRevision)]
				.orEmpty().filter(::isWifiCaptureMembership)
			if (matches.size > 1) return failed(logicalId, segments, WifiHistoryCause.MANIFEST_INTEGRITY_FAILED)
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
		) return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
		if (bindings.isEmpty()) {
			return if (related.isEmpty()) unavailable(logicalId, segments, zones,
				WifiHistoryCause.SOURCE_NOT_CAPTURED) else failed(logicalId, segments,
				WifiHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (!hasValidCaptureAuthority(manifests, bindings, snapshot)) {
			return failed(logicalId, segments, WifiHistoryCause.PLAN_INTEGRITY_FAILED)
		}

		val lane = snapshot.lanes.singleOrNull(::isWifiLane)
		val modeMask = manifests.fold(0L) { mask, manifest -> mask or when (manifest.sessionMode) {
			"MANUAL" -> MANUAL_CAPTURE_MASK
			"AUTOMATIC" -> AUTOMATIC_CAPTURE_MASK
			else -> 0L
		} }
		if (lane == null || !laneExecutionAuthority.owns(lane) || !isValidLane(lane) || modeMask == 0L ||
			lane.captureModeMask and modeMask != modeMask ||
			manifests.any { lane.activatedRolloutRevision > it.rolloutRevision }
		) return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)

		val session = snapshot.sessions[logicalId]
			?: return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		val completeness = runs.flatMap { snapshot.completenessByRun[it.serviceRunId].orEmpty() }
			.filter { it.sourceKind == WIFI_SOURCE }
		if (!hasValidCompleteness(completeness, logicalId, runIds)) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		if (runs.all { it.completedAtMs != null } && related.isNotEmpty() &&
			runIds.any { runId -> completeness.none { it.serviceRunId == runId } }
		) return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		val evidence = snapshot.evidenceState
			?: return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
		val admissions = snapshot.admissions.filter { it.serviceRunId in runIds }
		if (admissions.any { !it.isValidHistoryCarrier(logicalId, snapshot, manifestsByRun, evidence) }) {
			return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val targetOrdinal = listOfNotNull(
			related.maxOfOrNull(WifiCapturedFactRevisionEntity::sourceAdmissionOrdinal),
			completeness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
			admissions.maxOfOrNull(SourceEventWalEntity::admissionOrdinal),
		).maxOrNull()
		if (!hasValidSessionSettlement(session, runs, manifestsByRun, targetOrdinal) ||
			targetOrdinal?.let { it < lane.activationOrdinal ||
				lane.captureAdmissionCutoffOrdinal?.let { cutoff -> cutoff < it } == true } == true ||
			snapshot.terminalFailures.isNotEmpty()
		) return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)

		val current = mutableListOf<Pair<WifiCapturedFactRevisionEntity, WifiCapturedFactRevisionEntity>>()
		for ((_, lineage) in related.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)) {
			val latest = validateLineage(lineage.sortedBy(WifiCapturedFactRevisionEntity::semanticRevision), snapshot)
				?: return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
			val aggregate = resolveAggregate(latest, snapshot)
				?: return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
			if (!factHasExactAuthority(latest, aggregate, snapshot, manifestsByRun, bindings, evidence)) {
				return failed(logicalId, segments, WifiHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			if (latest.logicalFactId in carriedFactIds) current += latest to aggregate
		}

		if (runIds.any { runId ->
			val row = snapshot.deletionGenerations[logicalId to runId]
			row != null && row.collectedDataEpoch != evidence.collectedDataEpoch
		}) return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED)
		val currentEpoch = current.filter { (fact, aggregate) ->
			fact.hasCurrentPrivacyAuthority(logicalId, evidence, snapshot) &&
				aggregate.hasCurrentPrivacyAuthority(logicalId, evidence, snapshot)
		}
		val epochLoss = currentEpoch.size != current.size
		val retained = runCatching {
			currentEpoch.filter { (fact, aggregate) -> evidence.retainedFromMs?.let { floor ->
				earliestPossibleWallTimeMs(fact) >= floor && earliestPossibleWallTimeMs(aggregate) >= floor
			} != false }
		}.getOrElse { return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED) }
		val retentionLoss = retained.size != currentEpoch.size
		if (retained.isEmpty()) return when {
			current.isNotEmpty() && epochLoss -> unavailable(logicalId, segments, zones,
				WifiHistoryCause.PRIVACY_EPOCH_MISMATCH)
			currentEpoch.isNotEmpty() && retentionLoss -> unavailable(logicalId, segments, zones,
				WifiHistoryCause.RETENTION_LIMIT)
			targetOrdinal != null && lane.contiguousAdmissionOrdinal < targetOrdinal ->
				materializing(logicalId, segments, zones)
			isActive(session, runs) -> materializing(logicalId, segments, zones)
			completeness.any { it.stopStatus in PROVIDER_UNAVAILABLE_STATUSES } ->
				unavailable(logicalId, segments, zones, WifiHistoryCause.PROVIDER_UNAVAILABLE)
			else -> missing(logicalId, segments, zones)
		}

		val observations = runCatching { retained.sortedWith(compareBy(
			{ it.first.observedWallTimeMs }, { it.first.sourceAdmissionOrdinal }, { it.first.logicalFactId },
		)).map { (fact, aggregate) -> toObservation(fact, aggregate) } }
			.getOrElse { return failed(logicalId, segments, WifiHistoryCause.FACT_INTEGRITY_FAILED) }
		val causes = linkedSetOf<WifiHistoryCause>()
		if (isActive(session, runs)) causes += WifiHistoryCause.SESSION_ACTIVE
		if (lane.contiguousAdmissionOrdinal < (targetOrdinal ?: lane.activationOrdinal)) {
			causes += WifiHistoryCause.MATERIALIZATION_BEHIND
		}
		if (epochLoss) causes += WifiHistoryCause.PRIVACY_EPOCH_MISMATCH
		if (retentionLoss) causes += WifiHistoryCause.RETENTION_LIMIT
		if (completeness.any { it.unresolvedSequenceStart != null ||
			it.providerCoverage != COMPLETE_PROVIDER_COVERAGE || it.stopStatus != COMPLETE_STOP_STATUS }) {
			causes += WifiHistoryCause.ACQUISITION_INCOMPLETE
		}
		if (retained.any { it.first.coverageCompleteness == WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL }) {
			causes += WifiHistoryCause.RESULT_SET_PARTIAL
		}
		val capturesOnlyWifi = manifests.all { manifest ->
			snapshot.manifestAuthorityShape(logicalId, manifest.manifestRevision)?.first == setOf(WIFI_SOURCE)
		}
		return if (causes.isEmpty()) entry(logicalId, segments, zones, WifiHistoryProductState.READY,
			WifiHistoryCoverage.COMPLETE, observations, emptySet(), capturesOnlyWifi) else
			entry(logicalId, segments, zones, WifiHistoryProductState.PARTIAL,
				WifiHistoryCoverage.PARTIAL, observations, causes, capturesOnlyWifi)
	}

	private fun hasValidManifestHistory(
		logicalId: String,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		snapshot: WifiHistorySnapshot,
	): Boolean = SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
		manifestsByRun.values.map { it.map(SessionManifestVersionEntity::manifestRevision) },
	) && runs.all { run ->
		val manifests = manifestsByRun[run.serviceRunId].orEmpty()
		SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) && manifests.all { manifest ->
			manifest.logicalTrackingId == logicalId && manifest.serviceRunId == run.serviceRunId &&
				SessionManifestIntegrity.verify(manifest,
					snapshot.sourcesByManifest[WifiManifestKey(logicalId, manifest.manifestRevision)].orEmpty())
		}
	}

	private fun SourceEventWalEntity.isValidHistoryCarrier(
		logicalId: String,
		snapshot: WifiHistorySnapshot,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		evidence: SourceEvidenceState,
	): Boolean {
		val manifestRevision = sessionManifestRevision ?: return false
		val policyRevision = sourcePolicyRevision ?: return false
		val consentEpoch = captureConsentEpoch ?: return false
		val lease = lifecycleLeaseGeneration ?: return false
		val manifest = manifestsByRun[serviceRunId].orEmpty()
			.singleOrNull { it.manifestRevision == manifestRevision } ?: return false
		val binding = snapshot.sourcesByManifest[WifiManifestKey(logicalId, manifestRevision)].orEmpty()
			.singleOrNull(::isWifiCaptureMembership) ?: return false
		val plan = configRevision?.let(snapshot.desiredPlans::get)?.let(WifiHistoryPlanIntegrity::decode)
			?: return false
		val provider = snapshot.providerRegistrations[registrationGeneration] ?: return false
		val providerStart = provider.acceptedElapsedRealtimeNanos ?: return false
		val providerEnd = provider.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		val authorizationRevision = authorizationRevision ?: return false
		val authorization = snapshot.authorizationsByRegistration[registrationGeneration].orEmpty()
			.filter { it.authorizationRevision == authorizationRevision }
		if (!hasExactWalAuthorization(this, authorization, snapshot.demands, providerEnd, plan)) return false
		val authorizationStart = authorization.first().effectiveElapsedRealtimeNanos
		val nextAuthorization = snapshot.authorizationsByRegistration[registrationGeneration].orEmpty()
			.asSequence().filter { it.effectiveBootId == clockDomainId &&
				(it.effectiveElapsedRealtimeNanos > authorizationStart ||
					it.effectiveElapsedRealtimeNanos == authorizationStart &&
						it.authorizationRevision > authorizationRevision) }
			.minWithOrNull(compareBy(SourceAuthorizationEntity::effectiveElapsedRealtimeNanos,
				SourceAuthorizationEntity::authorizationRevision))?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE
		val authorizationEnd = minOf(providerEnd, nextAuthorization)
		val action = snapshot.startActions.singleOrNull { it.serviceRunId == serviceRunId &&
			it.manifestRevision == manifestRevision } ?: return false
		val appliedAt = maxOf(providerStart, authorizationStart)
		val wall = wallTimeMs ?: return false
		val uncertainty = wallTimeUncertaintyMs ?: return false
		val observedStart = observedIntervalStartNanos ?: return false
		val earliestWall = runCatching { Math.subtractExact(Math.subtractExact(wall,
			Math.subtractExact(observedElapsedNanos, observedStart) / NANOS_PER_MILLISECOND), uncertainty) }
			.getOrNull() ?: return false
		return sourceKind == WIFI_SOURCE && admissionOrdinal > 0L && sourceInstanceId.isNotBlank() &&
			registrationGeneration > 0L && sourceSequence >= 0L && this.logicalTrackingId == logicalId &&
			serviceRunId == manifest.serviceRunId && configRevision == manifest.acquisitionPlanRevision &&
			plan.enabled && plan.physicalFingerprint == physicalConfigurationFingerprint &&
			authorizationRevision > 0L &&
			authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L &&
			LOWERCASE_SHA_256.matches(authorizationFingerprint.orEmpty()) && planAttribution == CAPTURED_PLAN_ORDINAL &&
			clockDomainId == manifest.effectiveBootId && policyRevision == manifest.sourcePolicyRevision &&
			consentEpoch == binding.consentEpoch && lease == snapshot.runs[serviceRunId]?.leaseGeneration &&
			capturedCollectedDataEpoch == evidence.collectedDataEpoch &&
			admissionOrdinal > evidence.deletedSourceEventHighWaterOrdinal && deliveryUnitIndex == 0 &&
			deliveryUnitCount == 1 && LOWERCASE_SHA_256.matches(deliveryIdentity.orEmpty()) &&
			payloadVersion == WIFI_PROVIDER_PAYLOAD_VERSION && hasQualifiedIntegrity() &&
			provider.sourceKind == WIFI_SOURCE && provider.sourceInstanceId == sourceInstanceId &&
			provider.ownerScope == "source-broker:$WIFI_SOURCE" &&
			provider.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
			provider.clockDomainId == clockDomainId && provider.collectedDataEpoch == capturedCollectedDataEpoch &&
			provider.physicalConfigurationFingerprint == physicalConfigurationFingerprint &&
			provider.hasValidHistoricalShape() &&
			provider.acceptedAtMs?.let { accepted -> action.acknowledgedAtMs?.let { accepted <= it } } == true &&
			providerStart <= requireNotNull(action.acknowledgedElapsedRealtimeNanos) &&
			providerStart <= observedStart &&
			observedElapsedNanos < providerEnd && authorizationStart <= observedStart &&
			observedElapsedNanos < authorizationEnd && manifest.effectiveElapsedRealtimeNanos <= observedStart &&
			action.authenticates(this, manifest, appliedAt) &&
			(snapshot.deletionGenerations[logicalId to serviceRunId]?.generation ?: 0L) == 0L &&
			(logicalId to serviceRunId) !in snapshot.deletedScopes && earliestWall >= 0L &&
			evidence.retainedFromMs?.let { earliestWall >= it } != false
	}

	private fun ProviderRegistrationGenerationEntity.hasValidHistoricalShape(): Boolean {
		val acceptedWall = acceptedAtMs ?: return false
		val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
		val statusShape = when (status) {
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE -> retiredAtMs == null &&
				retiredElapsedRealtimeNanos == null && failureCode == null
			ProviderRegistrationGenerationEntity.STATUS_RETIRING -> retiredAtMs != null &&
				retiredElapsedRealtimeNanos != null && !failureCode.isNullOrBlank()
			ProviderRegistrationGenerationEntity.STATUS_RETIRED -> retiredAtMs != null &&
				retiredElapsedRealtimeNanos != null && failureCode?.isNotBlank() != false
			else -> false
		}
		return statusShape && reservedAtMs <= acceptedWall && reservedElapsedRealtimeNanos <= acceptedElapsed &&
			retiredAtMs?.let { it >= acceptedWall } != false &&
			retiredElapsedRealtimeNanos?.let { it >= acceptedElapsed } != false
	}

	private fun hasExactWalAuthorization(
		wal: SourceEventWalEntity,
		exact: List<SourceAuthorizationEntity>,
		demands: Map<String, SourceDemandEntity>,
		providerEnd: Long,
		plan: WifiPlanEvidence,
	): Boolean {
		if (exact.isEmpty() || exact.any { it.sourceKind != WIFI_SOURCE ||
			it.registrationGeneration != wal.registrationGeneration ||
			it.authorizationFingerprint != wal.authorizationFingerprint ||
			it.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			it.effectiveBootId != wal.clockDomainId }) return false
		val demandIds = exact.mapNotNull(SourceAuthorizationEntity::demandId)
		val exactDemands = demandIds.mapNotNull(demands::get)
		val first = exact.first()
		val recomputed = runCatching { SourceBrokerAuthorization.rows(WIFI_SOURCE, wal.registrationGeneration,
			requireNotNull(wal.authorizationRevision), exactDemands, first.effectiveBootId,
			first.effectiveElapsedRealtimeNanos, first.effectiveWallTimeMs) }.getOrNull()
		val captureMemberCount = exact.count {
			!it.isDenyAll && it.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
			it.persistenceEligible && it.logicalTrackingId == wal.logicalTrackingId &&
			it.serviceRunId == wal.serviceRunId && it.manifestRevision == wal.sessionManifestRevision &&
			it.lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration &&
			it.sourcePolicyRevision == wal.sourcePolicyRevision && it.consentEpoch == wal.captureConsentEpoch
		}
		val contractsMatch = exactDemands.all { demand ->
			demand.hasCompatibleWifiPhysicalContract(plan, wal.clockDomainId)
		}
		return providerEnd > first.effectiveElapsedRealtimeNanos && captureMemberCount == 1 && contractsMatch &&
			demandIds.distinct().size == demandIds.size && exactDemands.size == demandIds.size &&
			recomputed?.sortedBy(SourceAuthorizationEntity::memberId) == exact.sortedBy(SourceAuthorizationEntity::memberId)
	}

	private fun LifecycleDesiredActionEntity.authenticates(
		wal: SourceEventWalEntity,
		manifest: SessionManifestVersionEntity,
		appliedAt: Long,
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
			appliedAt <= requireNotNull(wal.observedIntervalStartNanos)
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
		isExactWifiWriter(binding) && policy != null && consent != null && header != null && plan != null &&
			plan.enabled && plan.revision == manifest.acquisitionPlanRevision &&
			header.revision == manifest.acquisitionPlanRevision &&
			header.sourcePolicyRevision == manifest.sourcePolicyRevision &&
			policy.sourceKind == WIFI_SOURCE && policy.policyRevision == manifest.sourcePolicyRevision &&
			policy.enabled && policy.capturePersistenceEligible && policy.qosCode == binding.qosCode &&
			policy.captureConsentEpoch == binding.consentEpoch &&
			policy.effectiveBootId == manifest.effectiveBootId &&
			policy.effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos &&
			consent.sourceKind == WIFI_SOURCE && consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			consent.epoch == binding.consentEpoch && consent.policyRevision <= policy.policyRevision &&
			consent.eligible && consent.persistenceEligible && consent.effectiveBootId == manifest.effectiveBootId &&
			consent.effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos
	}

	private fun validateLineage(
		ordered: List<WifiCapturedFactRevisionEntity>,
		snapshot: WifiHistorySnapshot,
	): WifiCapturedFactRevisionEntity? {
		if (ordered.isEmpty() || ordered.map(WifiCapturedFactRevisionEntity::semanticRevision) !=
			(1L..ordered.size.toLong()).toList()
		) return null
		if (ordered.any { !WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(it) }) return null
		if (ordered.zipWithNext().any { (previous, next) -> !next.isExactSuccessorOf(previous) }) return null
		val latest = ordered.last()
		val cursor = snapshot.cursors.singleOrNull { it.logicalFactId == latest.logicalFactId }
		return latest.takeIf { cursor?.matches(it) == true }
	}

	private fun resolveAggregate(
		fact: WifiCapturedFactRevisionEntity,
		snapshot: WifiHistorySnapshot,
	): WifiCapturedFactRevisionEntity? = when (fact.factKind) {
		WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> fact.takeIf { it.aggregateShapeIsValid() }
		WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> {
			val ownerId = fact.aggregateOwnerLogicalFactId ?: return null
			val ownerRevision = fact.aggregateOwnerSemanticRevision ?: return null
			val ownerLineage = snapshot.revisions.filter { it.logicalFactId == ownerId }
			val owner = ownerLineage.singleOrNull { it.semanticRevision == ownerRevision } ?: return null
			val currentOwner = validateLineage(ownerLineage.sortedBy(WifiCapturedFactRevisionEntity::semanticRevision),
				snapshot) ?: return null
			owner.takeIf {
				it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE && it.aggregateShapeIsValid() &&
				ownerLineage.all { revision ->
					revision.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
				} && currentOwner.logicalFactId == ownerId &&
					fact.aggregateOwnerCursorRevision == currentOwner.semanticRevision &&
					it.hasCompatibleAggregateAuthority(fact, snapshot)
			}
		}
		else -> null
	}

	@Suppress("ComplexCondition", "LongMethod")
	private fun factHasExactAuthority(
		fact: WifiCapturedFactRevisionEntity,
		aggregate: WifiCapturedFactRevisionEntity,
		snapshot: WifiHistorySnapshot,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		bindings: Map<Long, SessionManifestSourceEntity>,
		evidence: SourceEvidenceState,
	): Boolean {
		val run = snapshot.runs[fact.serviceRunId] ?: return false
		val manifests = manifestsByRun[fact.serviceRunId].orEmpty()
		val index = manifests.indexOfFirst { it.manifestRevision == fact.manifestRevision }
		if (index < 0) return false
		val manifest = manifests[index]
		val nextManifest = manifests.getOrNull(index + 1)
		val binding = bindings[fact.manifestRevision] ?: return false
		val plan = snapshot.desiredPlans[fact.configurationRevision]?.let(WifiHistoryPlanIntegrity::decode)
			?: return false
		val header = snapshot.planHeaders[fact.configurationRevision] ?: return false
		val provider = snapshot.providerRegistrations[fact.registrationGeneration] ?: return false
		val providerStart = provider.acceptedElapsedRealtimeNanos ?: return false
		val providerEnd = provider.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		val authorizations = snapshot.authorizationsByRegistration[fact.registrationGeneration].orEmpty()
		val session = snapshot.sessions[fact.logicalTrackingId] ?: return false
		val sessionEnd = session.cutoffElapsedNanos.takeIf { session.lifecycleBootId == fact.clockDomainId }
			?: Long.MAX_VALUE
		val consent = snapshot.consents[fact.captureConsentEpoch] ?: return false
		val expectedManifestEnd = nextManifest?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, providerEnd, fact.authorizationEffectEndNanos)
		val maxAgeNanos = runCatching { Math.multiplyExact(plan.maximumAgeMs, NANOS_PER_MILLISECOND) }
			.getOrNull() ?: return false
		val oldestObservationAgeNanos = runCatching {
			Math.subtractExact(fact.receivedElapsedNanos, fact.coverageIntervalStartNanos)
		}.getOrNull() ?: return false
		val capturedStart = maxOf(providerStart, fact.authorizationEffectStartNanos,
			manifest.effectiveElapsedRealtimeNanos)
		val capturedEnd = minOf(providerEnd, fact.authorizationEffectEndNanos, expectedManifestEnd)
		val sources = snapshot.manifestAuthorityShape(fact.logicalTrackingId, fact.manifestRevision) ?: return false
		val action = snapshot.startActions.singleOrNull { it.serviceRunId == fact.serviceRunId &&
			it.manifestRevision == fact.manifestRevision } ?: return false
		val appliedAt = maxOf(providerStart, fact.authorizationEffectStartNanos)
		return fact.writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
			fact.writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
			fact.writerBindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION &&
			fact.writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
			fact.purpose == SourceBrokerPurpose.SESSION_CAPTURE && isExactWifiWriter(binding) &&
			fact.sessionSegmentId == run.sessionSegmentId && fact.logicalTrackingId == run.logicalTrackingId &&
			fact.manifestRevision == manifest.manifestRevision &&
			fact.sourcePolicyRevision == manifest.sourcePolicyRevision &&
			fact.captureConsentEpoch == binding.consentEpoch && fact.lifecycleLeaseGeneration == run.leaseGeneration &&
			fact.clockDomainId == run.bootId && fact.storedZoneId == manifest.zoneId &&
			fact.capturedSourceCodes == canonicalSourceCodes(sources.first) &&
			fact.controlSourceCodes == canonicalSourceCodes(sources.second) && WIFI_SOURCE in sources.first &&
			WIFI_SOURCE !in sources.second &&
			fact.sessionRunEffectStartNanos == manifest.effectiveElapsedRealtimeNanos &&
			fact.sessionRunEffectEndNanos == expectedManifestEnd &&
			fact.consentEffectIsExact(consent) &&
			fact.observedIntervalStartNanos >= manifest.effectiveElapsedRealtimeNanos &&
			(nextManifest == null || fact.observedElapsedNanos < nextManifest.effectiveElapsedRealtimeNanos) &&
			plan.revision == fact.configurationRevision && manifest.acquisitionPlanRevision == fact.configurationRevision &&
			header.revision == fact.configurationRevision && header.sourcePolicyRevision == fact.sourcePolicyRevision &&
			plan.payloadVersion == fact.planPayloadVersion && plan.payloadChecksum == fact.planPayloadChecksum &&
			plan.physicalFingerprint == fact.physicalConfigurationFingerprint &&
			fact.maximumObservationAgeNanos == maxAgeNanos && fact.resultContract == WIFI_RESULT_CONTRACT &&
			oldestObservationAgeNanos in 0L..maxAgeNanos && capturedEnd > capturedStart &&
			fact.coverageIntervalStartNanos >= capturedStart && fact.coverageIntervalStartNanos < capturedEnd &&
			fact.coverageIntervalEndNanos >= capturedStart && fact.coverageIntervalEndNanos < capturedEnd &&
			fact.registrationAppliedAtNanos == appliedAt &&
			provider.hasExactFactAuthority(fact, plan.physicalFingerprint) &&
			provider.acceptedAtMs?.let { accepted -> action.acknowledgedAtMs?.let { accepted <= it } } == true &&
			providerStart <= requireNotNull(action.acknowledgedElapsedRealtimeNanos) &&
			providerStart == fact.providerAcceptanceStartNanos && providerEnd == fact.providerAcceptanceEndNanos &&
			hasExactAuthorization(fact, authorizations, providerEnd, snapshot.demands, plan) &&
			action.authenticates(fact, run, manifest, appliedAt) &&
			fact.availability == WifiCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE &&
			aggregate.aggregateShapeIsValid() && fact.collectedDataEpoch <= evidence.collectedDataEpoch
	}

	private fun WifiCapturedFactRevisionEntity.consentEffectIsExact(
		consent: SourceConsentEpochEntity,
	): Boolean = consent.sourceKind == WIFI_SOURCE && consent.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		consent.epoch == captureConsentEpoch && consent.effectiveBootId == clockDomainId &&
		consent.effectiveElapsedRealtimeNanos <= observedIntervalStartNanos

	private fun ProviderRegistrationGenerationEntity.hasExactFactAuthority(
		fact: WifiCapturedFactRevisionEntity,
		expectedFingerprint: String,
	): Boolean {
		val acceptedWall = acceptedAtMs ?: return false
		val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
		val validShape = when (status) {
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE -> retiredAtMs == null &&
				retiredElapsedRealtimeNanos == null && failureCode == null
			ProviderRegistrationGenerationEntity.STATUS_RETIRING -> retiredAtMs != null &&
				retiredElapsedRealtimeNanos != null && !failureCode.isNullOrBlank()
			ProviderRegistrationGenerationEntity.STATUS_RETIRED -> retiredAtMs != null &&
				retiredElapsedRealtimeNanos != null && failureCode?.isNotBlank() != false
			else -> false
		}
		return validShape && sourceKind == WIFI_SOURCE && registrationGeneration == fact.registrationGeneration &&
			sourceInstanceId == fact.sourceInstanceId && ownerScope == "source-broker:$WIFI_SOURCE" &&
			providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
			clockDomainId == fact.clockDomainId && collectedDataEpoch == fact.collectedDataEpoch &&
			physicalConfigurationFingerprint == expectedFingerprint && reservedAtMs <= acceptedWall &&
			reservedElapsedRealtimeNanos <= acceptedElapsed &&
			retiredAtMs?.let { it >= acceptedWall } != false &&
			retiredElapsedRealtimeNanos?.let { it >= acceptedElapsed } != false
	}

	private fun hasExactAuthorization(
		fact: WifiCapturedFactRevisionEntity,
		rows: List<SourceAuthorizationEntity>,
		providerEnd: Long,
		demands: Map<String, SourceDemandEntity>,
		plan: WifiPlanEvidence,
	): Boolean {
		val exact = rows.filter { it.authorizationRevision == fact.authorizationRevision }
		if (exact.isEmpty() || exact.any { row ->
			row.sourceKind != WIFI_SOURCE || row.registrationGeneration != fact.registrationGeneration ||
				row.authorizationFingerprint != fact.authorizationFingerprint ||
				row.purposeEligibilityMask != fact.purposeEligibilityMask || row.effectiveBootId != fact.clockDomainId ||
				row.effectiveElapsedRealtimeNanos != fact.authorizationEffectStartNanos
		}) return false
		val demandIds = exact.mapNotNull(SourceAuthorizationEntity::demandId)
		val exactDemands = demandIds.mapNotNull(demands::get)
		val first = exact.first()
		val recomputed = runCatching { SourceBrokerAuthorization.rows(
			WIFI_SOURCE, fact.registrationGeneration, fact.authorizationRevision, exactDemands,
			first.effectiveBootId, first.effectiveElapsedRealtimeNanos, first.effectiveWallTimeMs,
		) }.getOrNull()
		if (demandIds.distinct().size != demandIds.size || exactDemands.size != demandIds.size ||
			recomputed?.sortedBy(SourceAuthorizationEntity::memberId) != exact.sortedBy(SourceAuthorizationEntity::memberId)
		) return false
		val captureMemberCount = exact.count { row -> !row.isDenyAll &&
			row.purpose == SourceBrokerPurpose.SESSION_CAPTURE && row.persistenceEligible &&
			row.logicalTrackingId == fact.logicalTrackingId && row.serviceRunId == fact.serviceRunId &&
			row.manifestRevision == fact.manifestRevision &&
			row.lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration &&
			row.sourcePolicyRevision == fact.sourcePolicyRevision && row.consentEpoch == fact.captureConsentEpoch }
		val next = rows.asSequence().filter { row -> row.effectiveBootId == fact.clockDomainId &&
			(row.effectiveElapsedRealtimeNanos > fact.authorizationEffectStartNanos ||
				row.effectiveElapsedRealtimeNanos == fact.authorizationEffectStartNanos &&
					row.authorizationRevision > fact.authorizationRevision) }
			.minWithOrNull(compareBy(SourceAuthorizationEntity::effectiveElapsedRealtimeNanos,
				SourceAuthorizationEntity::authorizationRevision))?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE
		val contractsMatch = exactDemands.all { demand ->
			demand.hasCompatibleWifiPhysicalContract(plan, fact.clockDomainId)
		}
		return captureMemberCount == 1 && contractsMatch &&
			minOf(providerEnd, next) == fact.authorizationEffectEndNanos
	}

	private fun SourceDemandEntity.hasCompatibleWifiPhysicalContract(
		plan: WifiPlanEvidence,
		clockDomainId: String,
	): Boolean = sourceKind == WIFI_SOURCE && requestedBootId == clockDomainId &&
		minimumAcquisitionSpec == WIFI_BROADCAST_FLOOR && requestedDeliveryLatencyMs == null &&
		plan.maximumAgeMs <= maximumAgeMs

	private fun LifecycleDesiredActionEntity.authenticates(
		fact: WifiCapturedFactRevisionEntity,
		run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity,
		appliedAt: Long,
	): Boolean {
		val acknowledgedWall = acknowledgedAtMs ?: return false
		val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
		return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == WIFI_SOURCE &&
			desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
			failureCode == null && retryTrigger == null && logicalTrackingId == fact.logicalTrackingId &&
			serviceRunId == fact.serviceRunId && manifestRevision == fact.manifestRevision &&
			desiredPlanRevision == fact.configurationRevision && sourcePolicyRevision == fact.sourcePolicyRevision &&
			consentEpoch == fact.captureConsentEpoch && startOrigin == manifest.startOrigin &&
			startOrigin == run.startOrigin && bootId == fact.clockDomainId &&
			leaseGeneration == fact.lifecycleLeaseGeneration && sourceInstanceId == fact.sourceInstanceId &&
			registrationGeneration == fact.registrationGeneration && requestedAtMs == manifest.effectiveWallTimeMs &&
			acknowledgedWall >= requestedAtMs &&
			requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
			requestedElapsedRealtimeNanos <= appliedAt && appliedAt <= acknowledgedElapsed &&
			appliedAt <= fact.observedIntervalStartNanos
	}

	private fun toObservation(
		fact: WifiCapturedFactRevisionEntity,
		aggregate: WifiCapturedFactRevisionEntity,
	): WifiHistoryObservation {
		val spanMs = Math.subtractExact(fact.observedElapsedNanos, fact.coverageIntervalStartNanos) /
			NANOS_PER_MILLISECOND
		val start = Math.subtractExact(fact.observedWallTimeMs, spanMs)
		val rejected = listOf(fact.staleResultCount, fact.clockUnverifiableResultCount,
			fact.malformedResultCount).fold(0, Math::addExact)
		val bands = linkedMapOf<WifiHistoryBand, Int>()
		fun add(band: WifiHistoryBand, value: Int?) { if (requireNotNull(value) > 0) bands[band] = value }
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
	): Boolean = collectedDataEpoch == evidence.collectedDataEpoch &&
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

	private fun WifiCapturedFactRevisionEntity.isExactSuccessorOf(
		previous: WifiCapturedFactRevisionEntity,
	): Boolean = fixedCorrectionAuthority() == previous.fixedCorrectionAuthority() &&
		providerAcceptanceEndNanos.settles(previous.providerAcceptanceEndNanos) &&
		authorizationEffectEndNanos.settles(previous.authorizationEffectEndNanos) &&
		sessionRunEffectEndNanos.settles(previous.sessionRunEffectEndNanos)

	@Suppress("LongMethod")
	private fun WifiCapturedFactRevisionEntity.fixedCorrectionAuthority(): List<Any?> = listOf(
		writerProjectionId, writerProjectionVersion, writerBindingGeneration, writerOwnerGeneration,
		logicalFactId, factKind, aggregateOwnerLogicalFactId, aggregateOwnerSemanticRevision,
		aggregateOwnerCursorRevision, logicalTrackingId, serviceRunId, sessionSegmentId, purpose,
		capturedSourceCodes, controlSourceCodes, sourceEventId, sourceAdmissionOrdinal, walIntegrityIdentity,
		payloadChecksum, sourceDeliveryIdentity, deliveryUnitIndex, deliveryUnitCount, sourceSequence,
		planAttribution, sourceInstanceId, registrationGeneration, configurationRevision,
		physicalConfigurationFingerprint, authorizationRevision, authorizationFingerprint,
		purposeEligibilityMask, sourcePolicyRevision, captureConsentEpoch, manifestRevision,
		lifecycleLeaseGeneration, collectedDataEpoch, scopeDeletionGeneration, clockDomainId, storedZoneId,
		planPayloadVersion, planPayloadChecksum, maximumObservationAgeNanos, resultContract,
		registrationAppliedAtNanos, providerAcceptanceStartNanos, authorizationEffectStartNanos,
		sessionRunEffectStartNanos, observedIntervalStartNanos, observedElapsedNanos, receivedElapsedNanos,
		coverageIntervalStartNanos, coverageIntervalEndNanos, observedWallTimeMs, wallTimeUncertaintyMs,
		acquiredAtMs, qualityFlags, qualityConfidence, availability, submittedResultCount,
		acceptedResultCount, staleResultCount, clockUnverifiableResultCount, malformedResultCount,
		coverageCompleteness, observationCount, twoPointFourGhzCount, fiveGhzCount, sixGhzCount,
		otherBandCount, strongestSignalDbm, weakestSignalDbm, signalSumDbm,
	)

	private fun Long.settles(previous: Long): Boolean =
		this == previous || previous == Long.MAX_VALUE && this < Long.MAX_VALUE

	private fun WifiCapturedFactCursorEntity.matches(fact: WifiCapturedFactRevisionEntity): Boolean =
		writerProjectionId == fact.writerProjectionId && writerProjectionVersion == fact.writerProjectionVersion &&
		logicalFactId == fact.logicalFactId && logicalTrackingId == fact.logicalTrackingId &&
		serviceRunId == fact.serviceRunId && sessionSegmentId == fact.sessionSegmentId &&
		writerOwnerGeneration == fact.writerOwnerGeneration && collectedDataEpoch == fact.collectedDataEpoch &&
		scopeDeletionGeneration == fact.scopeDeletionGeneration && latestSemanticRevision == fact.semanticRevision &&
		latestMutationId == fact.mutationId && latestEffectChecksum == fact.effectChecksum &&
		latestSourceAdmissionOrdinal == fact.sourceAdmissionOrdinal && cursorRevision == fact.semanticRevision &&
		updatedAtMs == fact.appliedAtMs

	private fun hasValidCompleteness(
		rows: List<SourceSessionCompletenessEntity>,
		logicalId: String,
		runIds: List<String>,
	): Boolean = rows.groupBy(SourceSessionCompletenessEntity::serviceRunId).all { (runId, runRows) ->
		runId in runIds && runRows.map(SourceSessionCompletenessEntity::registrationGeneration).distinct().size ==
			runRows.size && runRows.all { row -> row.logicalTrackingId == logicalId &&
			row.sourceInstanceId.isNotBlank() && row.registrationGeneration >= 0L &&
			row.lastAdmissionOrdinal?.let { it > 0L } != false && row.lastSourceSequence?.let { it >= 0L } != false &&
			(row.unresolvedSequenceStart == null) == (row.unresolvedSequenceEnd == null) &&
			row.providerCoverage in PROVIDER_COVERAGE_VALUES && row.stopStatus in STOP_STATUS_VALUES &&
			row.updatedAtMs >= 0L }
	}

	private fun hasValidSessionSettlement(
		session: LogicalTrackingSessionEntity,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		targetOrdinal: Long?,
	): Boolean {
		if (!hasConsistentCompletion(session.state, session.completedAtMs)) return false
		val latestByRun = runs.associateWith { run -> manifestsByRun[run.serviceRunId].orEmpty()
			.maxByOrNull(SessionManifestVersionEntity::manifestRevision) ?: return false }
		if (latestByRun.any { (run, manifest) -> run.desiredPlanRevision != manifest.acquisitionPlanRevision ||
			run.rolloutRevision != manifest.rolloutRevision }) return false
		val latestLogical = latestByRun.values.maxByOrNull(SessionManifestVersionEntity::manifestRevision)
			?: return false
		if (session.desiredPlanRevision != latestLogical.acquisitionPlanRevision ||
			session.rolloutRevision != latestLogical.rolloutRevision || session.sessionMode != latestLogical.sessionMode
		) return false
		val active = runs.filter { it.completedAtMs == null && it.state !in TERMINAL_STATES }
		if (active.isNotEmpty()) {
			val run = active.singleOrNull() ?: return false
			val latest = latestByRun[run] ?: return false
			val pairValid = session.state in ADMISSION_SESSION_STATES && run.state in ADMISSION_RUN_STATES ||
				session.state == "ACTIVE" && run.state == "STOPPING" ||
				session.state == "STOPPING" && run.state == "STOPPING"
			val cutoffValid = if (session.state == "STOPPING" && run.state == "STOPPING")
				session.cutoffAtMs != null && session.cutoffElapsedNanos != null
			else session.cutoffAtMs == null && session.cutoffElapsedNanos == null
			return pairValid && cutoffValid && session.currentServiceRunId == run.serviceRunId &&
				session.currentManifestRevision == latest.manifestRevision &&
				session.lifecycleLeaseGeneration == run.leaseGeneration && session.lifecycleBootId == run.bootId &&
				session.finalAdmissionOrdinal == null
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
		logicalId: String,
		segments: List<SessionSegment>,
	): Boolean {
		val segment = segments.singleOrNull { it.id == sessionSegmentId } ?: return false
		return logicalTrackingId == logicalId && segment.serviceRunId == serviceRunId &&
			segment.logicalTrackingId == logicalId &&
			presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
	}

	private fun earliestPossibleWallTimeMs(fact: WifiCapturedFactRevisionEntity): Long = Math.subtractExact(
		Math.subtractExact(fact.observedWallTimeMs,
			Math.subtractExact(fact.observedElapsedNanos, fact.coverageIntervalStartNanos) /
				NANOS_PER_MILLISECOND),
		fact.wallTimeUncertaintyMs,
	)

	private fun isExactWifiWriter(source: SessionManifestSourceEntity): Boolean =
		source.sourceKind == WIFI_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible &&
			source.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI &&
			source.writerOwner == SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS &&
			source.writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
			source.writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
			source.writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
			source.writerBindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION

	private fun isWifiLane(lane: SourceProductProjectionLaneEntity): Boolean = lane.sourceKind == WIFI_SOURCE &&
		lane.bindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION &&
		lane.projectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
		lane.projectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION

	private fun isValidLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.captureModeMask <= 0L || lane.captureModeMask and ALL_CAPTURE_MASK.inv() != 0L ||
			lane.productStage !in PRODUCT_STAGES || lane.activatedRolloutRevision <= 0L ||
			lane.activationOrdinal <= 0L || lane.contiguousAdmissionOrdinal < lane.activationOrdinal - 1L ||
			lane.installedAtMs < 0L || lane.updatedAtMs < lane.installedAtMs ||
			(lane.terminalDisposition == null) != (lane.terminalAtMs == null)
		) return false
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
		return WifiHistoryEntry(WifiHistoryEntryKey("wifi-logical:$logicalId"), EpochMs(start), EpochMs(end),
			zones, state, coverage, observations, causes, capturesOnlyWifi = capturesOnlyWifi)
	}

	private fun failed(logicalId: String, segments: List<SessionSegment>, cause: WifiHistoryCause) =
		entry(logicalId, segments, emptySet(), WifiHistoryProductState.FAILED, WifiHistoryCoverage.NONE,
			emptyList(), setOf(cause))

	private fun unavailable(
		logicalId: String, segments: List<SessionSegment>, zones: Set<String>, cause: WifiHistoryCause,
	) = entry(logicalId, segments, zones, WifiHistoryProductState.UNAVAILABLE, WifiHistoryCoverage.NONE,
		emptyList(), setOf(cause))

	private fun materializing(logicalId: String, segments: List<SessionSegment>, zones: Set<String>) =
		entry(logicalId, segments, zones, WifiHistoryProductState.MATERIALIZING, WifiHistoryCoverage.NONE,
			emptyList(), setOf(WifiHistoryCause.MATERIALIZATION_BEHIND))

	private fun missing(logicalId: String, segments: List<SessionSegment>, zones: Set<String>) =
		entry(logicalId, segments, zones, WifiHistoryProductState.MISSING, WifiHistoryCoverage.NONE,
			emptyList(), setOf(WifiHistoryCause.NO_QUALIFIED_FACTS))

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
	private const val WIFI_PROVIDER_PAYLOAD_VERSION = 2
	private const val MANUAL_CAPTURE_MASK = 1L
	private const val AUTOMATIC_CAPTURE_MASK = 2L
	private const val ALL_CAPTURE_MASK = MANUAL_CAPTURE_MASK or AUTOMATIC_CAPTURE_MASK
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
		) return null
		DataInputStream(ByteArrayInputStream(row.payload)).use { input ->
			require(input.readInt() == PAYLOAD_VERSION)
			require(input.readUTF() == "WIFI")
			val revision = input.readLong()
			val mode = input.readUTF()
			val minimumAttemptMs = input.readLong()
			val maximumAgeMs = input.readLong()
			val dedupeWindowMs = input.readLong()
			val initialBackoffMs = input.readLong()
			val maximumBackoffMs = input.readLong()
			val multiplier = input.readDouble()
			require(input.available() == 0)
			require(row.sourceKind == WIFI_SOURCE && revision == row.revision && revision >= 0L)
			require(mode in MODES && minimumAttemptMs >= 0L && maximumAgeMs >= 0L && dedupeWindowMs >= 0L)
			require(initialBackoffMs >= 0L && maximumBackoffMs >= initialBackoffMs &&
				multiplier.isFinite() && multiplier >= 1.0)
			val canonical = when (mode) {
				"OFF" -> listOf("WIFI", mode)
				"CACHED_ONLY", "BROADCAST_DRIVEN" -> listOf("WIFI", "CALLBACK_REGISTRATION")
				else -> listOf("WIFI", "CALLBACK_REGISTRATION", "ACTIVE_PROBE", minimumAttemptMs,
					initialBackoffMs, maximumBackoffMs, multiplier)
			}.joinToString("\u001f")
			WifiPlanEvidence(revision, mode in ACCEPTED_CAPTURE_MODES, sha256(canonical.toByteArray()),
				maximumAgeMs, PAYLOAD_VERSION, row.payloadChecksum)
		}
	}.getOrNull()

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }

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
	val cursors: List<WifiCapturedFactCursorEntity>,
	val deletionGenerations: Map<Pair<String, String>, WifiCaptureDeletionGenerationEntity>,
	val deletedScopes: Set<Pair<String, String>>,
	val planHeaders: Map<Long, AcquisitionPlanRevisionEntity>,
	val desiredPlans: Map<Long, SourceDesiredPlanEntity>,
	val providerRegistrations: Map<Long, ProviderRegistrationGenerationEntity>,
	val authorizationsByRegistration: Map<Long, List<SourceAuthorizationEntity>>,
	val demands: Map<String, SourceDemandEntity>,
	val startActions: List<LifecycleDesiredActionEntity>,
	val admissions: List<SourceEventWalEntity>,
	val lanes: List<SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val evidenceState: SourceEvidenceState?,
	val overflow: Boolean,
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
			cursors = emptyList(),
			deletionGenerations = emptyMap(),
			deletedScopes = emptySet(),
			planHeaders = emptyMap(),
			desiredPlans = emptyMap(),
			providerRegistrations = emptyMap(),
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
	val entry: WifiHistoryEntry,
)

internal const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
