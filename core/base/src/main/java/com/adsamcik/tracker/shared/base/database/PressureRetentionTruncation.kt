package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import java.time.DateTimeException
import java.time.ZoneId

private suspend fun AppDatabase.markPressureRetentionTruncation(
	logicalTrackingId: String,
	serviceRunId: String,
	collectedDataEpoch: Long,
	markedAtMs: Long,
): Boolean {
	val expected = PressureFactRevisionIntegrity.retentionTruncationFence(
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		collectedDataEpoch = collectedDataEpoch,
		markedAtMs = markedAtMs,
	)
	val dao = sourceDeletionFenceDao()
	if (dao.insertIfAbsent(expected) != INSERT_IGNORED) {
		return true
	}
	val retained = dao.get(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = PressureFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
		scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest = expected.scopeIdentityDigest,
	)
	check(retained != null && PressureFactRevisionIntegrity.isRetentionTruncationFence(
		fence = retained,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		collectedDataEpoch = collectedDataEpoch,
	)) { "Conflicting Pressure retention-truncation marker" }
	return false
}

/**
 * Marks and removes complete authenticated Pressure fact lineages affected by a retention floor.
 *
 * Pressure aggregates cannot be truthfully split at an uncertain wall-time boundary. Therefore any
 * revision whose earliest possible start precedes [beforeMs] selects its whole logical fact lineage,
 * including later corrections. The exact run marker and every selected revision are changed in one
 * Room transaction; any incomplete authority rolls the operation back without inferring retention
 * from absence.
 */
suspend fun AppDatabase.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
	beforeMs: Long,
	collectedDataEpoch: Long,
	markedAtMs: Long,
): Int = withTransaction {
	require(beforeMs >= 0L)
	require(collectedDataEpoch >= 0L)
	require(markedAtMs >= 0L)
	val evidence = requireNotNull(sourceEvidenceStateDao().get()) {
		"Pressure retention requires initialized source-evidence state"
	}
	check(evidence.collectedDataEpoch == collectedDataEpoch) {
		"Pressure retention epoch does not match current source-evidence state"
	}
	check(evidence.retainedFromMs == beforeMs) {
		"Pressure retention floor does not match current source-evidence authority"
	}

	val factDao = pressureFactRevisionDao()
	var afterServiceRunId: String? = null
	var deleted = 0
	while (true) {
		val candidateRunIds = factDao.retentionServiceRunIdPage(
			afterServiceRunId = afterServiceRunId,
			limit = RETENTION_RUN_PAGE_SIZE,
		)
		if (candidateRunIds.isEmpty()) break
		check(candidateRunIds.size == candidateRunIds.distinct().size &&
			candidateRunIds == candidateRunIds.sorted()
		) { "Pressure retention candidate page is not a unique ordered keyset" }
		val authorities = loadPressureRetentionAuthorities(candidateRunIds, collectedDataEpoch)
		check(!factDao.hasCrossScopeRevisionsForRuns(candidateRunIds)) {
			"Pressure retention encountered a correction lineage crossing run scope"
		}
		candidateRunIds.forEach { serviceRunId ->
			val authority = authorities.getValue(serviceRunId)
			val selected = authenticatePressureRetentionRun(
				authority = authority,
				beforeMs = beforeMs,
				collectedDataEpoch = collectedDataEpoch,
			)
			if (selected.revisionCountByLogicalFactId.isEmpty()) return@forEach
			markPressureRetentionTruncation(
				logicalTrackingId = authority.run.logicalTrackingId,
				serviceRunId = authority.run.serviceRunId,
				collectedDataEpoch = collectedDataEpoch,
				markedAtMs = markedAtMs,
			)
			selected.revisionCountByLogicalFactId.entries
				.chunked(RETENTION_FACT_DELETE_BATCH_SIZE)
				.forEach { batch ->
					val expectedDeleted = batch.fold(0) { total, entry ->
						Math.addExact(total, entry.value)
					}
					val actualDeleted = factDao.deleteExactLineages(
						logicalTrackingId = authority.run.logicalTrackingId,
						serviceRunId = authority.run.serviceRunId,
						writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
						writerProjectionVersion =
							SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
						logicalFactIds = batch.map { entry -> entry.key },
					)
					check(actualDeleted == expectedDeleted) {
						"Authenticated Pressure retention lineage changed during pruning"
					}
					deleted = Math.addExact(deleted, actualDeleted)
				}
		}
		afterServiceRunId = candidateRunIds.last()
		if (candidateRunIds.size < RETENTION_RUN_PAGE_SIZE) break
	}
	deleted
}

@Suppress("ComplexCondition", "LongMethod")
private suspend fun AppDatabase.loadPressureRetentionAuthorities(
	serviceRunIds: List<String>,
	collectedDataEpoch: Long,
): Map<String, PressureRetentionAuthority> {
	val historyDao = trackingHistoryReadDao()
	val runs = historyDao.serviceRuns(serviceRunIds)
	val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
	check(runs.size == runsById.size && runsById.keys == serviceRunIds.toSet()) {
		"Pressure retention candidate is missing its authoritative service run"
	}
	val segmentIds = runs.map { run -> requireNotNull(run.sessionSegmentId) {
		"Pressure retention requires exact run-to-segment ownership"
	} }
	val segments = historyDao.segments(segmentIds)
	val segmentsById = segments.associateBy { segment -> segment.id }
	check(segments.size == segmentsById.size && segmentsById.keys == segmentIds.toSet()) {
		"Pressure retention run is missing its exact presentation segment"
	}
	for (run in runs) {
		val segment = segmentsById.getValue(requireNotNull(run.sessionSegmentId))
		check(segment.logicalTrackingId == run.logicalTrackingId &&
			segment.serviceRunId == run.serviceRunId
		) { "Pressure retention run/segment reverse binding is invalid" }
	}

	val manifestLimit = Math.addExact(
		Math.multiplyExact(serviceRunIds.size, MAX_MANIFESTS_PER_RUN),
		1,
	)
	val manifests = historyDao.manifests(serviceRunIds, manifestLimit)
	check(manifests.size < manifestLimit) { "Pressure retention manifest set exceeds its bound" }
	val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
	val sourceLimit = Math.addExact(
		Math.multiplyExact(manifests.size, MAX_SOURCES_PER_MANIFEST),
		1,
	)
	val sources = historyDao.manifestSources(serviceRunIds, sourceLimit)
	check(sources.size < sourceLimit) { "Pressure retention manifest membership exceeds its bound" }
	val sourcesByManifest = sources.groupBy { source ->
		PressureRetentionManifestKey(source.logicalTrackingId, source.manifestRevision)
	}
	val policies = historyDao.policiesForServiceRuns(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		serviceRunIds = serviceRunIds,
	)
	val policiesByRevision = policies.associateBy(SourcePolicyEntity::policyRevision)
	check(policies.size == policiesByRevision.size) {
		"Pressure retention policy authority is ambiguous"
	}

	val pressureBindings = mutableMapOf<PressureRetentionManifestKey, SessionManifestSourceEntity>()
	for (run in runs) {
		val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
		check(runManifests.size <= MAX_MANIFESTS_PER_RUN &&
			SessionManifestIntegrity.hasValidServiceRunTimeline(run, runManifests)
		) { "Pressure retention service-run manifest timeline is invalid" }
		for (manifest in runManifests) {
			val key = PressureRetentionManifestKey(
				manifest.logicalTrackingId,
				manifest.manifestRevision,
			)
			val membership = sourcesByManifest[key].orEmpty()
			check(manifest.logicalTrackingId == run.logicalTrackingId &&
				manifest.serviceRunId == run.serviceRunId &&
				membership.size <= MAX_SOURCES_PER_MANIFEST &&
				SessionManifestIntegrity.verify(manifest, membership) &&
				hasValidZone(manifest.zoneId)
			) { "Pressure retention manifest attribution is invalid" }
			val capture = membership.singleOrNull { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
					source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
			}
			if (capture?.persistenceEligible == true) pressureBindings[key] = capture
		}
	}

	val consentEpochs = pressureBindings.values.map(SessionManifestSourceEntity::consentEpoch)
		.distinct()
	val consents = if (consentEpochs.isEmpty()) emptyList() else sourcePolicyDao().consentEpochs(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epochs = consentEpochs,
	)
	val consentsByEpoch = consents.associateBy(SourceConsentEpochEntity::epoch)
	check(consents.size == consentsByEpoch.size && consentsByEpoch.keys == consentEpochs.toSet()) {
		"Pressure retention consent authority is missing or ambiguous"
	}

	return runs.associate { run ->
		val runManifests = manifestsByRun.getValue(run.serviceRunId)
		val manifestsByRevision = runManifests.associateBy(SessionManifestVersionEntity::manifestRevision)
		check(manifestsByRevision.size == runManifests.size) {
			"Pressure retention manifest revisions are ambiguous"
		}
		val runBindings = pressureBindings.filterKeys { key ->
			key.logicalTrackingId == run.logicalTrackingId &&
				key.manifestRevision in manifestsByRevision
		}.mapKeys { entry -> entry.key.manifestRevision }
		for ((revision, binding) in runBindings) {
			val manifest = manifestsByRevision.getValue(revision)
			check(hasValidPressureCaptureAuthority(
				policy = policiesByRevision[manifest.sourcePolicyRevision],
				consent = consentsByEpoch[binding.consentEpoch],
				manifestPolicyRevision = manifest.sourcePolicyRevision,
				binding = binding,
			)) { "Pressure retention capture authority is invalid" }
		}
		run.serviceRunId to PressureRetentionAuthority(
			run = run,
			manifests = manifestsByRevision,
			bindings = runBindings,
			collectedDataEpoch = collectedDataEpoch,
		)
	}
}

@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
private suspend fun AppDatabase.authenticatePressureRetentionRun(
	authority: PressureRetentionAuthority,
	beforeMs: Long,
	collectedDataEpoch: Long,
): AuthenticatedPressureRetentionSelection {
	val factDao = pressureFactRevisionDao()
	val run = authority.run
	check(!factDao.hasServiceRunScopeMismatch(run.logicalTrackingId, run.serviceRunId)) {
		"Pressure retention run contains mismatched fact ownership"
	}
	val captureFenceIdentity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		logicalTrackingId = run.logicalTrackingId,
		serviceRunId = run.serviceRunId,
	)
	check(sourceDeletionFenceDao().get(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest = captureFenceIdentity,
	) == null) { "Pressure retention found facts behind a selected-session deletion fence" }

	val orderedManifests = authority.manifests.values
		.sortedBy(SessionManifestVersionEntity::manifestRevision)
	val nextManifestByRevision = orderedManifests
		.mapIndexed { index, manifest ->
			manifest.manifestRevision to orderedManifests.getOrNull(index + 1)
		}.toMap()
	val revisionCountByLogicalFactId = linkedMapOf<String, Int>()
	val affectedLogicalFactIds = linkedSetOf<String>()
	var cursor: PressureRetentionFactCursor? = null
	var previousFact: PressureFactRevisionEntity? = null
	var factCount = 0
	while (true) {
		val page = cursor?.let { after ->
			factDao.exactServiceRunPageAfter(
				logicalTrackingId = run.logicalTrackingId,
				serviceRunId = run.serviceRunId,
				afterWriterId = after.writerProjectionId,
				afterWriterVersion = after.writerProjectionVersion,
				afterLogicalFactId = after.logicalFactId,
				afterSemanticRevision = after.semanticRevision,
				limit = RETENTION_FACT_PAGE_SIZE,
			)
		} ?: factDao.firstExactServiceRunPage(
			logicalTrackingId = run.logicalTrackingId,
			serviceRunId = run.serviceRunId,
			limit = RETENTION_FACT_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		for (fact in page) {
			val nextCursor = PressureRetentionFactCursor(fact)
			check(cursor == null || nextCursor > requireNotNull(cursor)) {
				"Pressure retention fact page did not advance"
			}
			cursor = nextCursor
			val manifest = authority.manifests[fact.manifestRevision]
			val binding = authority.bindings[fact.manifestRevision]
			val nextManifest = nextManifestByRevision[fact.manifestRevision]
			check(manifest != null && binding != null &&
				fact.logicalTrackingId == run.logicalTrackingId &&
				fact.serviceRunId == run.serviceRunId &&
				fact.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				fact.collectedDataEpoch == collectedDataEpoch &&
				fact.collectedDataEpoch == authority.collectedDataEpoch &&
				fact.sourcePolicyRevision == manifest.sourcePolicyRevision &&
				fact.captureConsentEpoch == binding.consentEpoch &&
				fact.clockDomainId == run.bootId && manifest.effectiveBootId == run.bootId &&
				fact.windowStartElapsedRealtimeNanos >= manifest.effectiveElapsedRealtimeNanos &&
				nextManifest?.let { next ->
					fact.windowEndElapsedRealtimeNanos <= next.effectiveElapsedRealtimeNanos
				} != false && hasValidPressureFactIdentity(fact, binding) &&
				continuesValidPressureCorrection(previousFact, fact, authority.manifests)
			) { "Pressure retention fact authority or correction lineage is invalid" }
			previousFact = fact
			factCount = Math.addExact(factCount, 1)
			revisionCountByLogicalFactId[fact.logicalFactId] = Math.addExact(
				revisionCountByLogicalFactId[fact.logicalFactId] ?: 0,
				1,
			)
			if (earliestPossibleWallTimeMs(fact.intervalStartTimeMs, fact.wallTimeUncertaintyMs) <
				beforeMs
			) {
				affectedLogicalFactIds += fact.logicalFactId
			}
		}
		if (page.size < RETENTION_FACT_PAGE_SIZE) break
	}
	check(factCount > 0) { "Pressure retention candidate run has no fact revisions" }
	return AuthenticatedPressureRetentionSelection(
		revisionCountByLogicalFactId = revisionCountByLogicalFactId
			.filterKeys(affectedLogicalFactIds::contains),
	)
}

@Suppress("ComplexCondition")
private fun hasValidPressureCaptureAuthority(
	policy: SourcePolicyEntity?,
	consent: SourceConsentEpochEntity?,
	manifestPolicyRevision: Long,
	binding: SessionManifestSourceEntity,
): Boolean = policy != null && policy.policyRevision == manifestPolicyRevision &&
	policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE && policy.enabled &&
	policy.capturePersistenceEligible && policy.captureConsentEpoch == binding.consentEpoch &&
	policy.qosCode == binding.qosCode && binding.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
	binding.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && binding.persistenceEligible &&
	binding.qosCode in MIN_CAPTURE_QOS..MAX_CAPTURE_QOS &&
	binding.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE &&
	binding.writerOwner == SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS &&
	binding.writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
	binding.writerProjectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
	binding.writerProjectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
	binding.writerBindingGeneration == SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION &&
	consent != null && consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
	consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && consent.epoch == binding.consentEpoch &&
	consent.eligible && consent.persistenceEligible && consent.policyRevision <= manifestPolicyRevision

private fun hasValidPressureFactIdentity(
	fact: PressureFactRevisionEntity,
	binding: SessionManifestSourceEntity,
): Boolean {
	val expectedLogicalFactId =
		"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:${fact.sourceEventId}"
	return fact.writerProjectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
		fact.writerProjectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
		fact.writerBindingGeneration == SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION &&
		fact.logicalFactId == expectedLogicalFactId &&
		fact.mutationId == "$expectedLogicalFactId:${fact.semanticRevision}" &&
		PressureFactRevisionIntegrity.hasValidEffectChecksum(fact, binding)
}

@Suppress("ComplexCondition")
private fun continuesValidPressureCorrection(
	previous: PressureFactRevisionEntity?,
	current: PressureFactRevisionEntity,
	manifestsByRevision: Map<Long, SessionManifestVersionEntity>,
): Boolean {
	val sameLineage = previous != null && previous.logicalFactId == current.logicalFactId &&
		previous.writerProjectionId == current.writerProjectionId &&
		previous.writerProjectionVersion == current.writerProjectionVersion
	if (!sameLineage) return current.semanticRevision == FIRST_SEMANTIC_REVISION
	val prior = requireNotNull(previous)
	val expectedRevision = try {
		Math.addExact(prior.semanticRevision, 1L)
	} catch (_: ArithmeticException) {
		return false
	}
	val priorManifest = manifestsByRevision[prior.manifestRevision] ?: return false
	val currentManifest = manifestsByRevision[current.manifestRevision] ?: return false
	return current.semanticRevision == expectedRevision &&
		current.sourceAdmissionOrdinal > prior.sourceAdmissionOrdinal &&
		current.sourceEventId == prior.sourceEventId &&
		current.writerBindingGeneration == prior.writerBindingGeneration &&
		current.logicalTrackingId == prior.logicalTrackingId &&
		current.serviceRunId == prior.serviceRunId && current.purpose == prior.purpose &&
		current.collectedDataEpoch == prior.collectedDataEpoch &&
		current.manifestRevision == prior.manifestRevision &&
		current.sourcePolicyRevision == prior.sourcePolicyRevision &&
		current.captureConsentEpoch == prior.captureConsentEpoch &&
		current.clockDomainId == prior.clockDomainId &&
		current.wallTimeUncertaintyMs == prior.wallTimeUncertaintyMs &&
		currentManifest.effectiveBootId == priorManifest.effectiveBootId &&
		currentManifest.zoneId == priorManifest.zoneId
}

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

internal fun earliestPossiblePressureWallTimeMs(
	wallTimeMs: Long,
	uncertaintyMs: Long,
): Long {
	require(wallTimeMs >= 0L)
	require(uncertaintyMs >= 0L)
	return if (uncertaintyMs > wallTimeMs) 0L else wallTimeMs - uncertaintyMs
}

private fun earliestPossibleWallTimeMs(wallTimeMs: Long, uncertaintyMs: Long): Long =
	earliestPossiblePressureWallTimeMs(wallTimeMs, uncertaintyMs)

private data class PressureRetentionManifestKey(
	val logicalTrackingId: String,
	val manifestRevision: Long,
)

private data class PressureRetentionAuthority(
	val run: SourceServiceRunEntity,
	val manifests: Map<Long, SessionManifestVersionEntity>,
	val bindings: Map<Long, SessionManifestSourceEntity>,
	val collectedDataEpoch: Long,
)

private data class AuthenticatedPressureRetentionSelection(
	val revisionCountByLogicalFactId: Map<String, Int>,
)

private data class PressureRetentionFactCursor(
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
	val logicalFactId: String,
	val semanticRevision: Long,
) : Comparable<PressureRetentionFactCursor> {
	constructor(fact: PressureFactRevisionEntity) : this(
		fact.writerProjectionId,
		fact.writerProjectionVersion,
		fact.logicalFactId,
		fact.semanticRevision,
	)

	override fun compareTo(other: PressureRetentionFactCursor): Int =
		compareValuesBy(
			this,
			other,
			PressureRetentionFactCursor::writerProjectionId,
			PressureRetentionFactCursor::writerProjectionVersion,
			PressureRetentionFactCursor::logicalFactId,
			PressureRetentionFactCursor::semanticRevision,
		)
}

private const val INSERT_IGNORED = -1L
private const val RETENTION_RUN_PAGE_SIZE = 8
private const val RETENTION_FACT_PAGE_SIZE = 256
private const val RETENTION_FACT_DELETE_BATCH_SIZE = 128
private const val MAX_MANIFESTS_PER_RUN = 256
private const val MAX_SOURCES_PER_MANIFEST = 12
private const val FIRST_SEMANTIC_REVISION = 1L
private const val MIN_CAPTURE_QOS = 1
private const val MAX_CAPTURE_QOS = 3
