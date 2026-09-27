package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRetentionRevisionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity

sealed interface PressureFactRetentionResolution {
	data object Absent : PressureFactRetentionResolution
	data object Invalid : PressureFactRetentionResolution
	data class Authenticated(
		val lineage: PressureFactRetentionAuthorityEntity,
		val revision: PressureFactRetentionRevisionAuthorityEntity,
	) : PressureFactRetentionResolution
}

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

@Suppress("ComplexCondition", "LongMethod", "LongParameterList", "ReturnCount")
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
	if (writerProjectionId.isBlank() ||
		writerProjectionVersion <= 0 ||
		sourceEventId.isBlank() ||
		sourceAdmissionOrdinal?.let { it <= 0L } == true ||
		sourcePolicyRevision <= 0L ||
		captureConsentEpoch < 0L ||
		collectedDataEpoch < 0L ||
		logicalTrackingId.isBlank() ||
		serviceRunId.isBlank() ||
		retainedFromMs?.let { it < 0L } == true
	) {
		return PressureFactRetentionResolution.Invalid
	}
	val revisionDao = pressureFactRetentionRevisionAuthorityDao()
	val eventCandidates = revisionDao.byEventId(
		writerProjectionId,
		writerProjectionVersion,
		sourceEventId,
		2,
	)
	if (eventCandidates.size > 1) return PressureFactRetentionResolution.Invalid
	val admissionCandidates = sourceAdmissionOrdinal?.let { admissionOrdinal ->
		revisionDao.byAdmissionOrdinal(
			writerProjectionId,
			writerProjectionVersion,
			admissionOrdinal,
			2,
		)
	}.orEmpty()
	if (admissionCandidates.size > 1) return PressureFactRetentionResolution.Invalid
	val candidates = if (sourceAdmissionOrdinal == null) {
		eventCandidates
	} else {
		revisionDao.byEvidence(
			writerProjectionId,
			writerProjectionVersion,
			sourceEventId,
			sourceAdmissionOrdinal,
			2,
		)
	}
	if (candidates.size > 1) return PressureFactRetentionResolution.Invalid
	val revision = candidates.singleOrNull()
	if (sourceAdmissionOrdinal != null) {
		if (revision == null) {
			if (eventCandidates.isNotEmpty() || admissionCandidates.isNotEmpty()) {
				return PressureFactRetentionResolution.Invalid
			}
		} else if (eventCandidates.singleOrNull() != revision ||
			admissionCandidates.singleOrNull() != revision
		) {
			return PressureFactRetentionResolution.Invalid
		}
	} else if (revision != null &&
		revisionDao.byAdmissionOrdinal(
			writerProjectionId,
			writerProjectionVersion,
			revision.sourceAdmissionOrdinal,
			2,
		).singleOrNull() != revision
	) {
		return PressureFactRetentionResolution.Invalid
	}
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
	if (revision == null) return PressureFactRetentionResolution.Absent
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

@Suppress("ComplexCondition", "LongMethod", "LongParameterList", "ReturnCount")
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
	if (cardinality.parentCount < 0L ||
		cardinality.expectedRevisionCount < 0L ||
		cardinality.storedRevisionCount < 0L ||
		cardinality.expectedRevisionCount != cardinality.storedRevisionCount ||
		revisionDao.hasDuplicateEventForExactRun(logicalTrackingId, serviceRunId) ||
		revisionDao.hasDuplicateAdmissionForExactRun(logicalTrackingId, serviceRunId) ||
		parentDao.hasLiveFactCollisionForExactRun(logicalTrackingId, serviceRunId) ||
		parentDao.hasLiveEventCollisionForExactRun(logicalTrackingId, serviceRunId) ||
		parentDao.hasLiveAdmissionCollisionForExactRun(logicalTrackingId, serviceRunId) ||
		revisionDao.hasOrphanForWriter(writerProjectionId, writerProjectionVersion)
	) {
		return false
	}
	if (cardinality.parentCount == 0L) {
		return cardinality.expectedRevisionCount == 0L &&
			cardinality.storedRevisionCount == 0L
	}
	if (retainedFromMs == null) return false

	var parentCount = 0L
	var revisionCount = 0L
	var cursor: PressureFactRetentionAuthorityEntity? = null
	while (true) {
		val page = cursor?.let { previous ->
			parentDao.pageForExactRunAfter(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				afterWriterProjectionId = previous.writerProjectionId,
				afterWriterProjectionVersion = previous.writerProjectionVersion,
				afterLogicalFactId = previous.logicalFactId,
				limit = PRESSURE_RETENTION_RUN_AUTHORITY_PAGE_SIZE,
			)
		} ?: parentDao.forExactRun(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			limit = PRESSURE_RETENTION_RUN_AUTHORITY_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		for (parent in page) {
			if (!parent.hasValidStoredIntegrity() ||
				parent.writerProjectionId != writerProjectionId ||
				parent.writerProjectionVersion != writerProjectionVersion ||
				parent.logicalTrackingId != logicalTrackingId ||
				parent.serviceRunId != serviceRunId ||
				parent.collectedDataEpoch != collectedDataEpoch ||
				parent.retainedFromMs > retainedFromMs
			) {
				return false
			}
			val revisions = revisionDao.forLineage(
				parent.writerProjectionId,
				parent.writerProjectionVersion,
				parent.logicalFactId,
				MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES + 1,
			)
			if (!parent.authenticatesRevisionAuthorities(revisions)) return false
			parentCount = try {
				Math.addExact(parentCount, 1L)
			} catch (_: ArithmeticException) {
				return false
			}
			revisionCount = try {
				Math.addExact(revisionCount, revisions.size.toLong())
			} catch (_: ArithmeticException) {
				return false
			}
		}
		cursor = page.last()
		if (page.size < PRESSURE_RETENTION_RUN_AUTHORITY_PAGE_SIZE) break
	}
	return parentCount == cardinality.parentCount &&
		revisionCount == cardinality.expectedRevisionCount &&
		revisionCount == cardinality.storedRevisionCount
}

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
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
