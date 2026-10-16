package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.PressureFactRetentionAuthorityDao
import com.adsamcik.tracker.shared.base.database.dao.PressureFactRetentionRevisionAuthorityDao
import com.adsamcik.tracker.shared.base.database.dao.PressureRetentionRunRevisionCardinality
import com.adsamcik.tracker.shared.base.database.data.MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionRevisionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity

/**
 * No retention witness, an invalid witness, or a verified fact/semantic-revision retention
 * binding.
 */
sealed interface PressureFactRetentionResolution {
	/**
	 * No original registration, owner or deletion/retention witness matches this exact source
	 * selection.
	 */
	data object Absent : PressureFactRetentionResolution
	/**
	 * A retention witness exists but its original revision/evidence binding cannot be verified.
	 */
	data object Invalid : PressureFactRetentionResolution
	/**
	 * Verified retained lineage/revision witness for the original Pressure fact.
	 */
	data class Authenticated(
		val lineage: PressureFactRetentionAuthorityEntity,
		val revision: PressureFactRetentionRevisionAuthorityEntity,
	) : PressureFactRetentionResolution
}

/**
 * Verifies the original Pressure correction lineage and saves durable retention bindings before
 * its expired payload is pruned.
 */
suspend fun AppDatabase.persistPressureFactRetentionAuthority(
	revisions: List<PressureFactRevisionEntity>,
	retainedFromMs: Long,
	retainedAtMs: Long,
): PressureFactRetentionAuthorityEntity = withTransaction {
	val expected = PressureFactRetentionAuthorityEntity.create(
		revisions = revisions,
		retainedFromMs = retainedFromMs,
		retainedAtMs = retainedAtMs,
	)
	val expectedRevisions = PressureFactRetentionRevisionAuthorityEntity.createAll(
		expected,
		revisions,
	)
	val parentDao = pressureFactRetentionAuthorityDao()
	val revisionDao = pressureFactRetentionRevisionAuthorityDao()
	val inserted = parentDao.insertIfAbsent(expected)
	val retained = parentDao.get(
		expected.writerProjectionId,
		expected.writerProjectionVersion,
		expected.logicalFactId,
	)
	check(retained == expected && retained.hasValidStoredIntegrity()) {
		"Conflicting Pressure fact-retention authority"
	}
	if (inserted == INSERT_IGNORED) {
		val retainedRevisions = revisionDao.forLineage(
			expected.writerProjectionId,
			expected.writerProjectionVersion,
			expected.logicalFactId,
			MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES + 1,
		)
		check(retainedRevisions == expectedRevisions &&
			expected.authenticatesRevisionAuthorities(retainedRevisions)
		) { "Conflicting Pressure fact-retention revision authority" }
		return@withTransaction expected
	}
	val revisionInsertions = revisionDao.insertIfAbsent(expectedRevisions)
	check(revisionInsertions.size == expectedRevisions.size &&
		revisionInsertions.none { result -> result == INSERT_IGNORED }
	) { "Conflicting Pressure fact-retention revision identity" }
	val retainedRevisions = revisionDao.forLineage(
		expected.writerProjectionId,
		expected.writerProjectionVersion,
		expected.logicalFactId,
		MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES + 1,
	)
	check(retainedRevisions == expectedRevisions &&
		expected.authenticatesRevisionAuthorities(retainedRevisions)
	) { "Incomplete Pressure fact-retention revision authority" }
	expected
}

/**
 * Resolves an original evidence/writer/run revision to its retained Pressure witness without
 * borrowing current recording permission.
 */
@Suppress("LongParameterList", "ReturnCount")
suspend fun AppDatabase.resolvePressureRetentionByEvidence(
	writerProjectionId: String,
	writerProjectionVersion: Int,
	sourceEventId: String,
	sourceAdmissionOrdinal: Long?,
	sourcePolicyRevision: Long,
	captureConsentEpoch: Long,
	collectedDataEpoch: Long,
	logicalTrackingId: String,
	serviceRunId: String,
	retainedFromMs: Long?,
): PressureFactRetentionResolution {
	if (!hasValidPressureRetentionEvidenceInput(
			writerProjectionId, writerProjectionVersion, sourceEventId, sourceAdmissionOrdinal, sourcePolicyRevision,
			captureConsentEpoch, collectedDataEpoch, logicalTrackingId, serviceRunId, retainedFromMs,
		)
	) {
		return PressureFactRetentionResolution.Invalid
	}
	val revisionDao = pressureFactRetentionRevisionAuthorityDao()
	val candidates = revisionDao.pressureRetentionEvidenceCandidates(
		writerProjectionId, writerProjectionVersion, sourceEventId, sourceAdmissionOrdinal,
	) ?: return PressureFactRetentionResolution.Invalid
	val revision = candidates.singleOrNull()
	if (!hasValidPressureRetentionRunAuthority(
			writerProjectionId = writerProjectionId,
			writerProjectionVersion = writerProjectionVersion,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			collectedDataEpoch = collectedDataEpoch,
			retainedFromMs = retainedFromMs,
		)
	) {
		return PressureFactRetentionResolution.Invalid
	}
	if (revision == null) {
		return PressureFactRetentionResolution.Absent
	}
	return authenticatePressureRetentionRevision(
		revision = revision,
		expectedLogicalFactId = null,
		expectedSemanticRevision = null,
		expectedSourceEventId = sourceEventId,
		expectedSourceAdmissionOrdinal = sourceAdmissionOrdinal,
		expectedSourcePolicyRevision = sourcePolicyRevision,
		expectedCaptureConsentEpoch = captureConsentEpoch,
		expectedCollectedDataEpoch = collectedDataEpoch,
		expectedLogicalTrackingId = logicalTrackingId,
		expectedServiceRunId = serviceRunId,
		expectedRetainedFromMs = retainedFromMs,
	)
}

// One complete immutable evidence selection shape; no database or current policy is consulted.
@Suppress("LongParameterList", "CyclomaticComplexMethod", "ComplexCondition")
private fun hasValidPressureRetentionEvidenceInput(
	writerProjectionId: String,
	writerProjectionVersion: Int,
	sourceEventId: String,
	sourceAdmissionOrdinal: Long?,
	sourcePolicyRevision: Long,
	captureConsentEpoch: Long,
	collectedDataEpoch: Long,
	logicalTrackingId: String,
	serviceRunId: String,
	retainedFromMs: Long?,
): Boolean = writerProjectionId.isNotBlank() && writerProjectionVersion > 0 && sourceEventId.isNotBlank() &&
	sourceAdmissionOrdinal?.let { it <= 0L } != true && sourcePolicyRevision > 0L && captureConsentEpoch >= 0L &&
	collectedDataEpoch >= 0L && logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank() &&
	retainedFromMs?.let { it < 0L } != true

// Reject ambiguous event, admission and exact selector results in their original query order.
@Suppress("ReturnCount")
private suspend fun PressureFactRetentionRevisionAuthorityDao.pressureRetentionEvidenceCandidates(
	writerProjectionId: String,
	writerProjectionVersion: Int,
	sourceEventId: String,
	sourceAdmissionOrdinal: Long?,
): List<PressureFactRetentionRevisionAuthorityEntity>? {
	val eventCandidates = byEventId(
		writerProjectionId,
		writerProjectionVersion,
		sourceEventId,
		2,
	)
	if (eventCandidates.size > 1) {
		return null
	}
	val admissionCandidates = sourceAdmissionOrdinal?.let { admissionOrdinal ->
		byAdmissionOrdinal(
			writerProjectionId,
			writerProjectionVersion,
			admissionOrdinal,
			2,
		)
	}.orEmpty()
	if (admissionCandidates.size > 1) {
		return null
	}
	val candidates = if (sourceAdmissionOrdinal == null) {
		eventCandidates
	} else {
		byEvidence(
			writerProjectionId,
			writerProjectionVersion,
			sourceEventId,
			sourceAdmissionOrdinal,
			2,
		)
	}
	if (candidates.size > 1) {
		return null
	}
	val revision = candidates.singleOrNull()
	if (!hasConsistentPressureEvidenceSelection(
			writerProjectionId, writerProjectionVersion, sourceAdmissionOrdinal,
			eventCandidates, admissionCandidates, revision,
		)
	) {
		return null
	}
	return candidates
}

// Original selector results and exact writer identity remain explicit; no new query state holder.
@Suppress("LongParameterList")
private suspend fun PressureFactRetentionRevisionAuthorityDao.hasConsistentPressureEvidenceSelection(
	writerProjectionId: String,
	writerProjectionVersion: Int,
	sourceAdmissionOrdinal: Long?,
	eventCandidates: List<PressureFactRetentionRevisionAuthorityEntity>,
	admissionCandidates: List<PressureFactRetentionRevisionAuthorityEntity>,
	revision: PressureFactRetentionRevisionAuthorityEntity?,
): Boolean {
	if (sourceAdmissionOrdinal != null) {
		if (revision == null) {
			if (eventCandidates.isNotEmpty() || admissionCandidates.isNotEmpty()) {
				return false
			}
		} else if (eventCandidates.singleOrNull() != revision ||
			admissionCandidates.singleOrNull() != revision
		) {
			return false
		}
	} else if (revision != null &&
		byAdmissionOrdinal(
			writerProjectionId,
			writerProjectionVersion,
			revision.sourceAdmissionOrdinal,
			2,
		).singleOrNull() != revision
	) {
		return false
	}
	return true
}

@Suppress("LongParameterList")
private suspend fun AppDatabase.hasValidPressureRetentionRunAuthority(
	writerProjectionId: String,
	writerProjectionVersion: Int,
	logicalTrackingId: String,
	serviceRunId: String,
	collectedDataEpoch: Long,
	retainedFromMs: Long?,
): Boolean {
	val parentDao = pressureFactRetentionAuthorityDao()
	val revisionDao = pressureFactRetentionRevisionAuthorityDao()
	val cardinality = revisionDao.runCardinality(logicalTrackingId, serviceRunId)
	if (!cardinality.hasValidPressureRunCounts() ||
		!revisionDao.hasUniquePressureRunEvidence(logicalTrackingId, serviceRunId) ||
		!parentDao.hasNoLivePressureRunCollisions(logicalTrackingId, serviceRunId) ||
		revisionDao.hasOrphanForWriter(writerProjectionId, writerProjectionVersion)
	) {
		return false
	}
	if (cardinality.parentCount == 0L) {
		return cardinality.expectedRevisionCount == 0L &&
			cardinality.storedRevisionCount == 0L
	}
	if (retainedFromMs == null) {
		return false
	}

	return hasValidPressureRetentionRunPages(
		parentDao, revisionDao, cardinality, writerProjectionId, writerProjectionVersion, logicalTrackingId,
		serviceRunId, collectedDataEpoch, retainedFromMs,
	)
}

private fun PressureRetentionRunRevisionCardinality.hasValidPressureRunCounts(): Boolean =
	parentCount >= 0L && expectedRevisionCount >= 0L && storedRevisionCount >= 0L &&
		expectedRevisionCount == storedRevisionCount

private suspend fun PressureFactRetentionRevisionAuthorityDao.hasUniquePressureRunEvidence(
	logicalTrackingId: String,
	serviceRunId: String,
): Boolean = !hasDuplicateEventForExactRun(logicalTrackingId, serviceRunId) &&
	!hasDuplicateAdmissionForExactRun(logicalTrackingId, serviceRunId)

private suspend fun PressureFactRetentionAuthorityDao.hasNoLivePressureRunCollisions(
	logicalTrackingId: String,
	serviceRunId: String,
): Boolean = !hasLiveFactCollisionForExactRun(logicalTrackingId, serviceRunId) &&
	!hasLiveEventCollisionForExactRun(logicalTrackingId, serviceRunId) &&
	!hasLiveAdmissionCollisionForExactRun(logicalTrackingId, serviceRunId)

// One bounded run scan retains the original DAO handles, immutable scope and expected counts explicitly.
@Suppress("LongParameterList")
private suspend fun hasValidPressureRetentionRunPages(
	parentDao: PressureFactRetentionAuthorityDao,
	revisionDao: PressureFactRetentionRevisionAuthorityDao,
	cardinality: PressureRetentionRunRevisionCardinality,
	writerProjectionId: String,
	writerProjectionVersion: Int,
	logicalTrackingId: String,
	serviceRunId: String,
	collectedDataEpoch: Long,
	retainedFromMs: Long,
): Boolean {
	var parentCount = 0L
	var revisionCount = 0L
	var cursor: PressureFactRetentionAuthorityEntity? = null
	while (true) {
		val page = parentDao.retainedPressureRunPage(logicalTrackingId, serviceRunId, cursor)
		if (page.isEmpty()) {
			break
		}
		for (parent in page) {
			if (!parent.matchesRetainedPressureRun(
					writerProjectionId, writerProjectionVersion, logicalTrackingId, serviceRunId,
					collectedDataEpoch, retainedFromMs,
				)
			) {
				return false
			}
			val revisions = revisionDao.forLineage(
				parent.writerProjectionId,
				parent.writerProjectionVersion,
				parent.logicalFactId,
				MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES + 1,
			)
			if (!parent.authenticatesRevisionAuthorities(revisions)) {
				return false
			}
			try {
				parentCount = Math.addExact(parentCount, 1L)
				revisionCount = Math.addExact(revisionCount, revisions.size.toLong())
			} catch (_: ArithmeticException) {
				return false
			}
		}
		cursor = page.last()
		if (page.size < PRESSURE_RETENTION_RUN_AUTHORITY_PAGE_SIZE) {
			break
		}
	}
	return cardinality.matchesRetainedPressureCounts(parentCount, revisionCount)
}

private suspend fun PressureFactRetentionAuthorityDao.retainedPressureRunPage(
	logicalTrackingId: String,
	serviceRunId: String,
	cursor: PressureFactRetentionAuthorityEntity?,
): List<PressureFactRetentionAuthorityEntity> =
	cursor?.let { previous ->
		pageForExactRunAfter(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			afterWriterProjectionId = previous.writerProjectionId,
			afterWriterProjectionVersion = previous.writerProjectionVersion,
			afterLogicalFactId = previous.logicalFactId,
			limit = PRESSURE_RETENTION_RUN_AUTHORITY_PAGE_SIZE,
		)
	} ?: forExactRun(
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		limit = PRESSURE_RETENTION_RUN_AUTHORITY_PAGE_SIZE,
	)

// The saved parent is compared with one complete supplied writer/run/epoch/floor tuple.
@Suppress("LongParameterList")
private fun PressureFactRetentionAuthorityEntity.matchesRetainedPressureRun(
	expectedWriterProjectionId: String,
	expectedWriterProjectionVersion: Int,
	expectedLogicalTrackingId: String,
	expectedServiceRunId: String,
	expectedCollectedDataEpoch: Long,
	expectedRetainedFromMs: Long,
): Boolean = hasValidStoredIntegrity() && writerProjectionId == expectedWriterProjectionId &&
	writerProjectionVersion == expectedWriterProjectionVersion && logicalTrackingId == expectedLogicalTrackingId &&
	serviceRunId == expectedServiceRunId && collectedDataEpoch == expectedCollectedDataEpoch &&
	retainedFromMs <= expectedRetainedFromMs

private fun PressureRetentionRunRevisionCardinality.matchesRetainedPressureCounts(
	actualParentCount: Long,
	actualRevisionCount: Long,
): Boolean = actualParentCount == parentCount && actualRevisionCount == expectedRevisionCount &&
	actualRevisionCount == storedRevisionCount

/**
 * Resolves an exact original fact/semantic revision and writer/run scope to its retained Pressure
 * witness.
 */
// Preserve missing parent, orphaned revisions and exact selected revision rejection before the final witness.
@Suppress("LongParameterList", "ReturnCount")
suspend fun AppDatabase.resolvePressureRetentionByFact(
	writerProjectionId: String,
	writerProjectionVersion: Int,
	logicalFactId: String,
	semanticRevision: Long?,
	sourceEventId: String,
	sourceAdmissionOrdinal: Long,
	sourcePolicyRevision: Long,
	captureConsentEpoch: Long,
	collectedDataEpoch: Long,
	logicalTrackingId: String,
	serviceRunId: String,
	retainedFromMs: Long?,
): PressureFactRetentionResolution {
	if (!hasValidPressureRetentionRunAuthority(
			writerProjectionId = writerProjectionId,
			writerProjectionVersion = writerProjectionVersion,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			collectedDataEpoch = collectedDataEpoch,
			retainedFromMs = retainedFromMs,
		)
	) {
		return PressureFactRetentionResolution.Invalid
	}
	val parent = pressureFactRetentionAuthorityDao().get(
		writerProjectionId,
		writerProjectionVersion,
		logicalFactId,
	)
	if (parent == null) {
		return if (pressureFactRetentionRevisionAuthorityDao().countForLineage(
				writerProjectionId,
				writerProjectionVersion,
				logicalFactId,
			) == 0L
		) {
			PressureFactRetentionResolution.Absent
		} else {
			PressureFactRetentionResolution.Invalid
		}
	}
	val revisions = pressureFactRetentionRevisionAuthorityDao().forLineage(
		writerProjectionId,
		writerProjectionVersion,
		logicalFactId,
		MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES + 1,
	)
	if (!parent.authenticatesRevisionAuthorities(revisions)) {
		return PressureFactRetentionResolution.Invalid
	}
	val revision = revisions.singleOrNull { candidate ->
		candidate.sourceEventId == sourceEventId &&
			candidate.sourceAdmissionOrdinal == sourceAdmissionOrdinal &&
			(semanticRevision == null || candidate.semanticRevision == semanticRevision)
	} ?: return PressureFactRetentionResolution.Invalid
	return authenticatePressureRetentionRevision(
		revision = revision,
		expectedLogicalFactId = logicalFactId,
		expectedSemanticRevision = semanticRevision,
		expectedSourceEventId = sourceEventId,
		expectedSourceAdmissionOrdinal = sourceAdmissionOrdinal,
		expectedSourcePolicyRevision = sourcePolicyRevision,
		expectedCaptureConsentEpoch = captureConsentEpoch,
		expectedCollectedDataEpoch = collectedDataEpoch,
		expectedLogicalTrackingId = logicalTrackingId,
		expectedServiceRunId = serviceRunId,
		expectedRetainedFromMs = retainedFromMs,
	)
}

// Compare the selected saved retention revision with its loaded parent and supplied evidence identity.
@Suppress("LongParameterList", "CyclomaticComplexMethod", "ComplexCondition")
private suspend fun AppDatabase.authenticatePressureRetentionRevision(
	revision: PressureFactRetentionRevisionAuthorityEntity,
	expectedLogicalFactId: String?,
	expectedSemanticRevision: Long?,
	expectedSourceEventId: String,
	expectedSourceAdmissionOrdinal: Long?,
	expectedSourcePolicyRevision: Long,
	expectedCaptureConsentEpoch: Long,
	expectedCollectedDataEpoch: Long,
	expectedLogicalTrackingId: String,
	expectedServiceRunId: String,
	expectedRetainedFromMs: Long?,
): PressureFactRetentionResolution {
	val parent = pressureFactRetentionAuthorityDao().get(
		revision.writerProjectionId,
		revision.writerProjectionVersion,
		revision.logicalFactId,
	) ?: return PressureFactRetentionResolution.Invalid
	val revisions = pressureFactRetentionRevisionAuthorityDao().forLineage(
		revision.writerProjectionId,
		revision.writerProjectionVersion,
		revision.logicalFactId,
		MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES + 1,
	)
	if (!parent.authenticatesRevisionAuthorities(revisions) ||
		expectedLogicalFactId?.let { revision.logicalFactId == it } == false ||
		parent.collectedDataEpoch != expectedCollectedDataEpoch ||
		parent.logicalTrackingId != expectedLogicalTrackingId ||
		parent.serviceRunId != expectedServiceRunId ||
		expectedRetainedFromMs == null ||
		parent.retainedFromMs > expectedRetainedFromMs ||
		!revision.authenticatesEvidence(
			parent = parent,
			expectedSourceEventId = expectedSourceEventId,
			expectedSourceAdmissionOrdinal = expectedSourceAdmissionOrdinal,
			expectedSemanticRevision = expectedSemanticRevision,
			expectedSourcePolicyRevision = expectedSourcePolicyRevision,
			expectedCaptureConsentEpoch = expectedCaptureConsentEpoch,
		)
	) {
		return PressureFactRetentionResolution.Invalid
	}
	return PressureFactRetentionResolution.Authenticated(parent, revision)
}

private const val INSERT_IGNORED = -1L
private const val PRESSURE_RETENTION_RUN_AUTHORITY_PAGE_SIZE = 128
