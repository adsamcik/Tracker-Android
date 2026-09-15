package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
 * from absence. Explicit total and per-run traversal ceilings keep that transaction finite; an
 * overflow is a no-mutation failure, never permission to prune an unaudited suffix.
 */
suspend fun AppDatabase.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
	beforeMs: Long,
	collectedDataEpoch: Long,
	markedAtMs: Long,
): Int = pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
	beforeMs = beforeMs,
	collectedDataEpoch = collectedDataEpoch,
	markedAtMs = markedAtMs,
	limits = DEFAULT_PRESSURE_RETENTION_TRAVERSAL_LIMITS,
	checkpoint = { currentCoroutineContext().ensureActive() },
)

/** Internal deterministic seam for proving bounded rollback and cooperative cancellation. */
internal suspend fun AppDatabase.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
	beforeMs: Long,
	collectedDataEpoch: Long,
	markedAtMs: Long,
	limits: PressureRetentionTraversalLimits,
	checkpoint: suspend (PressureRetentionCheckpoint) -> Unit,
): Int = withTransaction {
	require(beforeMs >= 0L)
	require(collectedDataEpoch >= 0L)
	require(markedAtMs >= 0L)
	val budget = PressureRetentionTraversalBudget(limits)
	checkpoint(PressureRetentionCheckpoint.TRANSACTION_STARTED)
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
		budget.consumeRuns(candidateRunIds.size)
		checkpoint(PressureRetentionCheckpoint.RUN_PAGE_LOADED)
		val authorities = loadPressureRetentionAuthorities(
			candidateRunIds,
			collectedDataEpoch,
			budget,
		)
		check(!factDao.hasCrossScopeRevisionsForRuns(candidateRunIds)) {
			"Pressure retention encountered a correction lineage crossing run scope"
		}
		candidateRunIds.forEach { serviceRunId ->
			val authority = authorities.getValue(serviceRunId)
			val selected = authenticatePressureRetentionRun(
				authority = authority,
				beforeMs = beforeMs,
				collectedDataEpoch = collectedDataEpoch,
				budget = budget,
				checkpoint = checkpoint,
			)
			checkpoint(PressureRetentionCheckpoint.RUN_AUTHENTICATED)
			if (selected.revisionCountByLogicalFactId.isEmpty()) return@forEach
			markPressureRetentionTruncation(
				logicalTrackingId = authority.run.logicalTrackingId,
				serviceRunId = authority.run.serviceRunId,
				collectedDataEpoch = collectedDataEpoch,
				markedAtMs = markedAtMs,
			)
			checkpoint(PressureRetentionCheckpoint.RUN_MARKED)
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
					checkpoint(PressureRetentionCheckpoint.DELETE_BATCH_APPLIED)
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
	budget: PressureRetentionTraversalBudget,
): Map<String, PressureRetentionAuthority> {
	val historyDao = trackingHistoryReadDao()
	val runs = historyDao.serviceRuns(serviceRunIds)
	budget.consumeAuthorityRows(runs.size)
	val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
	check(runs.size == runsById.size && runsById.keys == serviceRunIds.toSet()) {
		"Pressure retention candidate is missing its authoritative service run"
	}
	val segmentIds = runs.map { run -> requireNotNull(run.sessionSegmentId) {
		"Pressure retention requires exact run-to-segment ownership"
	} }
	val segments = historyDao.segments(segmentIds)
	budget.consumeAuthorityRows(segments.size)
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
	budget.consumeAuthorityRows(manifests.size)
	check(manifests.size < manifestLimit) { "Pressure retention manifest set exceeds its bound" }
	val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
	val sourceLimit = Math.addExact(
		Math.multiplyExact(manifests.size, MAX_SOURCES_PER_MANIFEST),
		1,
	)
	val sources = historyDao.manifestSources(serviceRunIds, sourceLimit)
	budget.consumeAuthorityRows(sources.size)
	check(sources.size < sourceLimit) { "Pressure retention manifest membership exceeds its bound" }
	val sourcesByManifest = sources.groupBy { source ->
		PressureRetentionManifestKey(source.logicalTrackingId, source.manifestRevision)
	}
	val policies = historyDao.policiesForServiceRuns(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		serviceRunIds = serviceRunIds,
	)
	budget.consumeAuthorityRows(policies.size)
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
	budget.consumeAuthorityRows(consents.size)
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
	budget: PressureRetentionTraversalBudget,
	checkpoint: suspend (PressureRetentionCheckpoint) -> Unit,
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
	var lineageCount = 0
	var latestDurableTimeMs = 0L
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
		factCount = Math.addExact(factCount, page.size)
		check(factCount <= budget.limits.maximumFactRevisionsPerRun) {
			"Pressure retention run fact revision budget exceeded"
		}
		budget.consumeFactRevisions(page.size)
		checkpoint(PressureRetentionCheckpoint.FACT_PAGE_LOADED)
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
			val startsLineage = previousFact?.let { previous ->
				previous.logicalFactId != fact.logicalFactId ||
					previous.writerProjectionId != fact.writerProjectionId ||
					previous.writerProjectionVersion != fact.writerProjectionVersion
			} != false
			if (startsLineage) {
				lineageCount = Math.addExact(lineageCount, 1)
				check(lineageCount <= budget.limits.maximumFactLineagesPerRun) {
					"Pressure retention run fact lineage budget exceeded"
				}
				budget.consumeFactLineage()
			}
			previousFact = fact
			latestDurableTimeMs = maxOf(latestDurableTimeMs, fact.appliedAtMs)
			revisionCountByLogicalFactId[fact.logicalFactId] = Math.addExact(
				revisionCountByLogicalFactId[fact.logicalFactId] ?: 0,
				1,
			)
			if (beforeMs == Long.MAX_VALUE ||
				earliestPossibleWallTimeMs(
					fact.intervalStartTimeMs,
					fact.wallTimeUncertaintyMs,
				) < beforeMs
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
		latestDurableTimeMs = latestDurableTimeMs,
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

enum class PressureSourceEraseLocalFailureReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	CAPTURE_CONSENT_STILL_ELIGIBLE,
	DIRECT_DEMAND_NOT_QUIESCED,
	CAPTURE_PROVIDER_NOT_QUIESCED,
	DESTINATION_OWNER_CHANGED,
	DELETION_FENCE_CONFLICT,
	STALE_REQUEST,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
}

class PressureSourceEraseLocalFailure(
	val reason: PressureSourceEraseLocalFailureReason,
) : IllegalStateException(reason.name)

data class PressureSourceEraseLocalScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
	}
}

data class PressureSourceEraseLocalAudit(
	val factRevisionCount: Int,
	val walEventCount: Int,
	val scopes: List<PressureSourceEraseLocalScope>,
	val latestDurableTimeMs: Long,
) {
	init {
		listOf(factRevisionCount, walEventCount).forEach { require(it >= 0) }
		require(scopes.distinct().size == scopes.size)
		require(latestDurableTimeMs >= 0L)
	}

	val requiresHardwareAuthority: Boolean
		get() = factRevisionCount > 0 || walEventCount > 0
}

data class PressureSourceEraseLocalLimits(
	val maximumRuns: Int = 2_048,
	val maximumFactRevisions: Int = 131_072,
	val maximumWalEvents: Int = 65_536,
	val maximumDirectDemands: Int = 256,
	val maximumNonterminalRegistrations: Int = 64,
) {
	init {
		listOf(
			maximumRuns,
			maximumFactRevisions,
			maximumWalEvents,
			maximumDirectDemands,
			maximumNonterminalRegistrations,
		).forEach { require(it > 0) }
	}
}

/** Cheap payload-free preflight used to avoid inventing provider authority for import-only erase. */
suspend fun AppDatabase.pressureSourceEraseRequiresHardwareAuthority(): Boolean =
	pressureFactRevisionDao().count() > 0L ||
		pressureFactRevisionDao().sourceEraseWalCount() > 0L

/**
 * Authenticates every local Pressure retention dependency inside the caller's Room transaction.
 *
 * Call this before deleting Pressure facts, raw Pressure WAL, legacy Pressure samples, or shared
 * session rows. The returned scopes are the exact capture fences that must precede those cascades.
 */
@Suppress("LongMethod", "ComplexCondition", "CyclomaticComplexMethod")
suspend fun AppDatabase.auditPressureSourceEraseLocalAuthorityInTransaction(
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	expectedCurrentPolicyRevision: Long,
	expectedRevokedConsentEpoch: Long,
	erasedAtMs: Long,
	limits: PressureSourceEraseLocalLimits = PressureSourceEraseLocalLimits(),
): PressureSourceEraseLocalAudit {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
	require(expectedCurrentPolicyRevision > 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(erasedAtMs >= 0L)
	val evidence = sourceEvidenceStateDao().get()
	if (evidence == null || evidence.collectedDataEpoch != expectedCollectedDataEpoch ||
		evidence.deletedSourceEventHighWaterOrdinal != expectedDeletedSourceEventHighWaterOrdinal
	) failPressureErase(PressureSourceEraseLocalFailureReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)

	val factDao = pressureFactRevisionDao()
	val factCount = boundedInt(factDao.count(), limits.maximumFactRevisions)
	val walCount = boundedInt(factDao.sourceEraseWalCount(), limits.maximumWalEvents)
	if (factCount == 0 && walCount == 0) {
		return PressureSourceEraseLocalAudit(0, 0, emptyList(), evidence.updatedAtMs)
	}

	val policyDao = sourcePolicyDao()
	val policyAuthority = policyDao.authority()?.takeIf {
		it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
			it.currentPolicyRevision == expectedCurrentPolicyRevision
	} ?: failPressureErase(PressureSourceEraseLocalFailureReason.POLICY_AUTHORITY_UNAVAILABLE)
	val policy = policyDao.policyAtRevision(
		expectedCurrentPolicyRevision,
		SourceDestinationOwnerEntity.SOURCE_PRESSURE,
	)
	val revokedConsent = policyDao.latestConsentEpoch(
		SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		SessionManifestPurposeCode.SESSION_CAPTURE,
	)
	if (policy == null || revokedConsent == null ||
		revokedConsent.epoch != expectedRevokedConsentEpoch
	) failPressureErase(PressureSourceEraseLocalFailureReason.POLICY_AUTHORITY_UNAVAILABLE)
	if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
		revokedConsent.eligible || revokedConsent.persistenceEligible ||
		revokedConsent.policyRevision != policy.policyRevision ||
		revokedConsent.effectiveBootId != policy.effectiveBootId ||
		revokedConsent.effectiveElapsedRealtimeNanos != policy.effectiveElapsedRealtimeNanos ||
		revokedConsent.effectiveWallTimeMs != policy.effectiveWallTimeMs
	) failPressureErase(PressureSourceEraseLocalFailureReason.CAPTURE_CONSENT_STILL_ELIGIBLE)

	val demands = sourceBrokerDao().activeDemandsBounded(
		SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		limits.maximumDirectDemands + 1,
	)
	if (demands.size > limits.maximumDirectDemands) {
		failPressureErase(PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED)
	}
	if (demands.any {
		it.purpose == SourceBrokerPurpose.SESSION_CAPTURE || it.persistenceEligible
	}) failPressureErase(PressureSourceEraseLocalFailureReason.DIRECT_DEMAND_NOT_QUIESCED)
	val currentRegistrations = sourceBrokerDao().currentPhysicalRegistrationsBounded(
		SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		limits.maximumNonterminalRegistrations + 1,
	)
	val retiringRegistrations = sourceBrokerDao().pendingProviderRemovalsBounded(
		SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		limits.maximumNonterminalRegistrations + 1,
	)
	if (currentRegistrations.size > limits.maximumNonterminalRegistrations ||
		retiringRegistrations.size > limits.maximumNonterminalRegistrations
	) failPressureErase(PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED)
	if (currentRegistrations.isNotEmpty() || retiringRegistrations.isNotEmpty()) {
		failPressureErase(PressureSourceEraseLocalFailureReason.CAPTURE_PROVIDER_NOT_QUIESCED)
	}

	if (factCount > 0 || walCount > 0) {
		val owner = sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)
		if (owner == null || owner.owner != SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS ||
			owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		) failPressureErase(PressureSourceEraseLocalFailureReason.DESTINATION_OWNER_CHANGED)
	}

	val budget = PressureRetentionTraversalBudget(DEFAULT_PRESSURE_RETENTION_TRAVERSAL_LIMITS)
	val scopes = linkedSetOf<PressureSourceEraseLocalScope>()
	var authenticatedFactCount = 0
	var latestDurableTimeMs = maxOf(
		evidence.updatedAtMs,
		policyAuthority.updatedAtMs,
		policy.effectiveWallTimeMs,
		revokedConsent.effectiveWallTimeMs,
	)
	var afterServiceRunId: String? = null
	while (true) {
		val runIds = factDao.retentionServiceRunIdPage(afterServiceRunId, RETENTION_RUN_PAGE_SIZE)
		if (runIds.isEmpty()) break
		if (runIds != runIds.distinct().sorted() ||
			scopes.size + runIds.size > limits.maximumRuns
		) failPressureErase(PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED)
		budget.consumeRuns(runIds.size)
		val authorities = try {
			loadPressureRetentionAuthorities(runIds, expectedCollectedDataEpoch, budget)
		} catch (_: RuntimeException) {
			failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (factDao.hasCrossScopeRevisionsForRuns(runIds)) {
			failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		runIds.forEach { runId ->
			val authority = authorities.getValue(runId)
			val selection = try {
				authenticatePressureRetentionRun(
					authority,
					Long.MAX_VALUE,
					expectedCollectedDataEpoch,
					budget,
				) {}
			} catch (_: RuntimeException) {
				failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			authenticatedFactCount = try {
				Math.addExact(
					authenticatedFactCount,
					selection.revisionCountByLogicalFactId.values.fold(0) { total, count ->
						Math.addExact(total, count)
					},
				)
			} catch (_: ArithmeticException) {
				failPressureErase(PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED)
			}
			latestDurableTimeMs = maxOf(latestDurableTimeMs, selection.latestDurableTimeMs)
			scopes += PressureSourceEraseLocalScope(
				authority.run.logicalTrackingId,
				authority.run.serviceRunId,
			)
		}
		afterServiceRunId = runIds.last()
		if (runIds.size < RETENTION_RUN_PAGE_SIZE) break
	}
	if (authenticatedFactCount != factCount) {
		failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	var afterWalOrdinal = 0L
	var authenticatedWalCount = 0
	while (true) {
		val page = factDao.sourceEraseWalPage(
			afterWalOrdinal,
			MAX_PRESSURE_ERASE_WAL_PAYLOAD_BYTES,
			PRESSURE_ERASE_WAL_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		if (page.any { it.admissionOrdinal <= afterWalOrdinal } ||
			page != page.sortedBy { it.admissionOrdinal }
		) failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
		page.forEach { wal ->
			if (!wal.hasQualifiedIntegrity() ||
				wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_PRESSURE ||
				wal.capturedCollectedDataEpoch != expectedCollectedDataEpoch ||
				wal.payloadVersion != PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION ||
				wal.authorizationPurposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
				wal.logicalTrackingId.isNullOrBlank() || wal.serviceRunId.isNullOrBlank() ||
				wal.sessionManifestRevision == null || wal.sourcePolicyRevision == null ||
				wal.captureConsentEpoch == null || wal.observedIntervalStartNanos == null ||
				wal.wallTimeMs == null || wal.wallTimeUncertaintyMs == null
			) failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
			scopes += PressureSourceEraseLocalScope(
				requireNotNull(wal.logicalTrackingId),
				requireNotNull(wal.serviceRunId),
			)
			latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs, wal.acquiredAtMs)
		}
		authenticatePressureEraseWalScopes(page, expectedCollectedDataEpoch, budget)
		authenticatedWalCount = try {
			Math.addExact(authenticatedWalCount, page.size)
		} catch (_: ArithmeticException) {
			failPressureErase(PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED)
		}
		if (authenticatedWalCount > limits.maximumWalEvents || scopes.size > limits.maximumRuns) {
			failPressureErase(PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED)
		}
		afterWalOrdinal = page.last().admissionOrdinal
		if (page.size < PRESSURE_ERASE_WAL_PAGE_SIZE) break
	}
	if (authenticatedWalCount != walCount) {
		failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (erasedAtMs < latestDurableTimeMs) {
		failPressureErase(PressureSourceEraseLocalFailureReason.STALE_REQUEST)
	}
	return PressureSourceEraseLocalAudit(
		factRevisionCount = factCount,
		walEventCount = walCount,
		scopes = scopes.toList(),
		latestDurableTimeMs = latestDurableTimeMs,
	)
}

/** Installs every exact run fence before removing only Pressure-owned local payload. */
suspend fun AppDatabase.applyPressureSourceEraseLocalMutationInTransaction(
	audit: PressureSourceEraseLocalAudit,
	expectedCollectedDataEpoch: Long,
	erasedAtMs: Long,
) {
	audit.scopes.forEach { scope ->
		val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			logicalTrackingId = scope.logicalTrackingId,
			serviceRunId = scope.serviceRunId,
			fenceGeneration = 1L,
			collectedDataEpoch = expectedCollectedDataEpoch,
			deletedAtMs = erasedAtMs,
		)
		if (sourceDeletionFenceDao().insertIfAbsent(fence) == INSERT_IGNORED) {
			val retained = sourceDeletionFenceDao().get(
				fence.sourceKind,
				fence.purpose,
				fence.scopeKind,
				fence.scopeIdentityDigest,
			)
			if (retained == null || retained.collectedDataEpoch != expectedCollectedDataEpoch ||
				retained.fenceGeneration != 1L
			) failPressureErase(PressureSourceEraseLocalFailureReason.DELETION_FENCE_CONFLICT)
		}
	}
	val factDao = pressureFactRevisionDao()
	if (factDao.deleteFactsForSourceErase() != audit.factRevisionCount ||
		factDao.deleteSourceEraseWal() != audit.walEventCount ||
		factDao.count() != 0L || factDao.sourceEraseWalCount() != 0L
	) failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
}

private suspend fun AppDatabase.authenticatePressureEraseWalScopes(
	walRows: List<SourceEventWalEntity>,
	collectedDataEpoch: Long,
	budget: PressureRetentionTraversalBudget,
) {
	if (walRows.isEmpty()) return
	val runIds = walRows.map { requireNotNull(it.serviceRunId) }.distinct()
	val authorities = try {
		loadPressureRetentionAuthorities(runIds, collectedDataEpoch, budget)
	} catch (_: RuntimeException) {
		failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	walRows.forEach { wal ->
		val authority = authorities[wal.serviceRunId]
			?: failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
		val manifest = authority.manifests[wal.sessionManifestRevision]
			?: failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
		val binding = authority.bindings[manifest.manifestRevision]
			?: failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
		val nextManifest = authority.manifests.values
			.filter { it.manifestRevision > manifest.manifestRevision }
			.minByOrNull { it.manifestRevision }
		if (wal.logicalTrackingId != authority.run.logicalTrackingId ||
			wal.serviceRunId != authority.run.serviceRunId ||
			wal.clockDomainId != authority.run.bootId ||
			wal.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			wal.captureConsentEpoch != binding.consentEpoch ||
			requireNotNull(wal.observedIntervalStartNanos) < manifest.effectiveElapsedRealtimeNanos ||
			nextManifest?.let {
				wal.observedElapsedNanos > it.effectiveElapsedRealtimeNanos
			} == true
		) failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun boundedInt(value: Long, maximum: Int): Int {
	if (value < 0L || value > maximum.toLong()) {
		failPressureErase(PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED)
	}
	return value.toInt()
}

private fun failPressureErase(reason: PressureSourceEraseLocalFailureReason): Nothing =
	throw PressureSourceEraseLocalFailure(reason)

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
	val latestDurableTimeMs: Long,
)

internal enum class PressureRetentionCheckpoint {
	TRANSACTION_STARTED,
	RUN_PAGE_LOADED,
	FACT_PAGE_LOADED,
	RUN_AUTHENTICATED,
	RUN_MARKED,
	DELETE_BATCH_APPLIED,
}

internal data class PressureRetentionTraversalLimits(
	val maximumRuns: Long,
	val maximumTraversalRows: Long,
	val maximumFactRevisions: Long,
	val maximumFactLineages: Long,
	val maximumFactRevisionsPerRun: Int,
	val maximumFactLineagesPerRun: Int,
) {
	init {
		require(maximumRuns > 0L)
		require(maximumTraversalRows > 0L)
		require(maximumFactRevisions > 0L)
		require(maximumFactLineages > 0L)
		require(maximumFactRevisionsPerRun > 0)
		require(maximumFactLineagesPerRun > 0)
		require(maximumFactLineages <= maximumFactRevisions)
		require(maximumFactRevisionsPerRun.toLong() <= maximumFactRevisions)
		require(maximumFactLineagesPerRun.toLong() <= maximumFactLineages)
		require(maximumFactLineagesPerRun <= maximumFactRevisionsPerRun)
	}
}

private class PressureRetentionTraversalBudget(
	val limits: PressureRetentionTraversalLimits,
) {
	private var runCount = 0L
	private var traversalRowCount = 0L
	private var factRevisionCount = 0L
	private var factLineageCount = 0L

	fun consumeRuns(count: Int) {
		require(count >= 0)
		runCount = Math.addExact(runCount, count.toLong())
		check(runCount <= limits.maximumRuns) {
			"Pressure retention transaction run budget exceeded"
		}
		consumeTraversalRows(count)
	}

	fun consumeAuthorityRows(count: Int) = consumeTraversalRows(count)

	fun consumeFactRevisions(count: Int) {
		require(count >= 0)
		factRevisionCount = Math.addExact(factRevisionCount, count.toLong())
		check(factRevisionCount <= limits.maximumFactRevisions) {
			"Pressure retention transaction fact revision budget exceeded"
		}
		consumeTraversalRows(count)
	}

	fun consumeFactLineage() {
		factLineageCount = Math.addExact(factLineageCount, 1L)
		check(factLineageCount <= limits.maximumFactLineages) {
			"Pressure retention transaction fact lineage budget exceeded"
		}
	}

	private fun consumeTraversalRows(count: Int) {
		require(count >= 0)
		traversalRowCount = Math.addExact(traversalRowCount, count.toLong())
		check(traversalRowCount <= limits.maximumTraversalRows) {
			"Pressure retention transaction traversal row budget exceeded"
		}
	}
}

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
private const val PRESSURE_ERASE_WAL_PAGE_SIZE = 256
private const val MAX_PRESSURE_ERASE_WAL_PAYLOAD_BYTES = 64 * 1_024
private const val MAX_MANIFESTS_PER_RUN = 256
private const val MAX_SOURCES_PER_MANIFEST = 12
private const val FIRST_SEMANTIC_REVISION = 1L
private const val MIN_CAPTURE_QOS = 1
private const val MAX_CAPTURE_QOS = 3

private val DEFAULT_PRESSURE_RETENTION_TRAVERSAL_LIMITS = PressureRetentionTraversalLimits(
	maximumRuns = 2_048L,
	maximumTraversalRows = 262_144L,
	maximumFactRevisions = 131_072L,
	maximumFactLineages = 65_536L,
	maximumFactRevisionsPerRun = 65_536,
	maximumFactLineagesPerRun = 32_768,
)
