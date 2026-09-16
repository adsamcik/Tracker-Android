package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsSourceFenceEntity

/**
 * Full-clear result for parent AppDatabase orchestration.
 *
 * The parent must advance SourceEvidenceState only after this helper has authenticated old-epoch
 * authority and installed new-epoch fences, and before unrelated source payloads are removed.
 */
data class ImportedAmbientStepsFullClearResult(
	val reepochedFenceCount: Int,
	val newlyFencedDayCount: Int,
	val protectedIdentityCount: Int,
)

/**
 * Synchronous, transaction-owned full-clear seam. It never starts a transaction or blocks a
 * coroutine. Exact call order is:
 *
 * 1. call this method with `oldCollectedDataEpoch` to authenticate and install new-epoch fences;
 * 2. parent advances the shared SourceEvidenceState to `newCollectedDataEpoch`;
 * 3. call [deleteFullClearPayloadInCurrentTransaction] with the new epoch.
 *
 * Both AppDatabase clear overloads can use these non-suspending methods inside their existing
 * transaction; no `runBlocking` bridge is required.
 */
fun ImportedAmbientStepsDao.prepareFullClearFencesInCurrentTransaction(
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	sourceEvidenceRevision: Long,
	clearedAtMs: Long,
): ImportedAmbientStepsFullClearResult = try {
	prepareFullClearFencesUnchecked(
		oldCollectedDataEpoch,
		newCollectedDataEpoch,
		sourceEvidenceRevision,
		clearedAtMs,
	)
} catch (failure: ImportedAmbientStepsLineageFailure) {
	throw failure
} catch (_: IllegalArgumentException) {
	corruptFullClear()
} catch (_: IllegalStateException) {
	corruptFullClear()
} catch (_: ArithmeticException) {
	corruptFullClear()
} catch (_: java.time.DateTimeException) {
	corruptFullClear()
}

private fun ImportedAmbientStepsDao.prepareFullClearFencesUnchecked(
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	sourceEvidenceRevision: Long,
	clearedAtMs: Long,
): ImportedAmbientStepsFullClearResult {
	require(oldCollectedDataEpoch >= 0L)
	require(newCollectedDataEpoch > oldCollectedDataEpoch)
	require(sourceEvidenceRevision >= 0L)
	require(clearedAtMs >= 0L)

	val storedFenceCount = fenceCountForFullClear()
	val storedProtectedIdentityCount = protectedIdentityCountForFullClear()
	if (storedFenceCount < 0L ||
		storedFenceCount > ImportedAmbientStepsDao.MAX_GLOBAL_FENCES ||
		storedProtectedIdentityCount < 0L ||
		storedProtectedIdentityCount >
		ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES
	) overflowFullClear()
	val structuralOwners = linkedMapOf<FullClearStructuralDayKey, String>()
	val fencedDayIds = linkedSetOf<String>()
	var reepochedFences = 0
	var protectedIdentityCount = 0L
	var afterFenceId: String? = null
	while (true) {
		val page = fencePageForFullClear(afterFenceId, FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		if (page.size > FULL_CLEAR_PAGE_SIZE ||
			page.zipWithNext().any { (left, right) -> left.dayIdentity >= right.dayIdentity } ||
			afterFenceId?.let { page.first().dayIdentity <= it } == true
		) corruptFullClear()
		page.forEach { fence ->
			if (fence.collectedDataEpoch != oldCollectedDataEpoch ||
				fence.fencedAtMs > clearedAtMs ||
				fence.sourceEvidenceRevision >= sourceEvidenceRevision
			) corruptFullClear()
			val markers = protectedIdentitiesForFullClear(
				fence.dayIdentity,
				ImportedAmbientStepsDao.MAX_PROTECTED_IDENTITIES_PER_DAY + 1,
			)
			if (markers.size != fence.protectedIdentityCount ||
				ImportedAmbientStepsIdentity.protectedIdentitySetChecksum(markers) !=
				fence.protectedIdentitySetChecksum
			) corruptFullClear()
			val key = fence.structuralKey()
			if (structuralOwners.putIfAbsent(key, fence.dayIdentity)?.let {
					it != fence.dayIdentity
				} == true
			) corruptFullClear()
			val replacement = ImportedAmbientStepsDayFenceEntity.reepoch(
				fence,
				newCollectedDataEpoch,
				sourceEvidenceRevision,
			)
			if (replaceFenceForFullClear(
					dayIdentity = fence.dayIdentity,
					expectedCollectedDataEpoch = oldCollectedDataEpoch,
					expectedEffectChecksum = fence.effectChecksum,
					collectedDataEpoch = replacement.collectedDataEpoch,
					sourceEvidenceRevision = replacement.sourceEvidenceRevision,
					effectChecksum = replacement.effectChecksum,
				) != 1
			) corruptFullClear()
			fencedDayIds += fence.dayIdentity
			reepochedFences = Math.addExact(reepochedFences, 1)
			if (reepochedFences.toLong() > ImportedAmbientStepsDao.MAX_GLOBAL_FENCES) {
				overflowFullClear()
			}
			protectedIdentityCount = Math.addExact(protectedIdentityCount, markers.size.toLong())
			if (protectedIdentityCount >
				ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES
			) overflowFullClear()
		}
		afterFenceId = page.last().dayIdentity
		if (page.size < FULL_CLEAR_PAGE_SIZE) break
	}
	if (reepochedFences.toLong() != storedFenceCount ||
		protectedIdentityCount != storedProtectedIdentityCount
	) corruptFullClear()

	sourceFenceForFullClear()?.let { sourceFence ->
		if (sourceFence.collectedDataEpoch != oldCollectedDataEpoch ||
			maxOf(
				sourceFence.deletedAtMs,
				sourceFence.completedAtMs ?: 0L,
				sourceFence.reopenedAtMs ?: 0L,
			) > clearedAtMs
		) corruptFullClear()
		val replacement = ImportedAmbientStepsSourceFenceEntity.reepoch(
			sourceFence,
			newCollectedDataEpoch,
		)
		if (replaceSourceFenceForFullClear(
				expectedCollectedDataEpoch = oldCollectedDataEpoch,
				expectedEffectChecksum = sourceFence.effectChecksum,
				collectedDataEpoch = replacement.collectedDataEpoch,
				revokedConsentEpoch = replacement.revokedConsentEpoch,
				deletedAtMs = replacement.deletedAtMs,
				deletionCompleted = replacement.deletionCompleted,
				completedAtMs = replacement.completedAtMs,
				reopenedConsentEpoch = replacement.reopenedConsentEpoch,
				reopenedAtMs = replacement.reopenedAtMs,
				effectChecksum = replacement.effectChecksum,
			) != 1
		) corruptFullClear()
	}

	var newlyFencedDays = 0
	var afterDayId: String? = null
	while (true) {
		val page = fullClearDayCandidatePage(afterDayId, 1)
		if (page.isEmpty()) break
		val candidate = page.single()
		if (candidate.dayIdentity in fencedDayIds) corruptFullClear()
		val lineage = authenticateFullClearLineage(candidate, oldCollectedDataEpoch)
		val latest = lineage.latest
		if (latest.header != candidate ||
			lineage.receipts.maxOfOrNull { it.receivedAtMs }?.let { it > clearedAtMs } == true
		) corruptFullClear()
		val key = latest.header.structuralKey()
		if (structuralOwners.putIfAbsent(key, latest.header.dayIdentity)?.let {
				it != latest.header.dayIdentity
			} == true
		) corruptFullClear()
		val markers = lineage.protectedIdentities()
		if (markers.size > ImportedAmbientStepsDao.MAX_PROTECTED_IDENTITIES_PER_DAY) {
			overflowFullClear()
		}
		if (protectedIdentitiesForFullClear(candidate.dayIdentity, 1).isNotEmpty()) {
			corruptFullClear()
		}
		markers.map { it.protectedIdentity }.chunked(FULL_CLEAR_ID_BATCH_SIZE).forEach { identities ->
			val existing = protectedIdentityKindsForFullClear(identities, identities.size + 1)
			if (existing.size > identities.size ||
				existing.any { owner ->
					markers.none {
						it.protectedIdentity == owner.protectedIdentity &&
							it.identityKind == owner.identityKind
					}
				}
			) corruptFullClear()
		}
		val fence = ImportedAmbientStepsDayFenceEntity.create(
			dayIdentity = latest.header.dayIdentity,
			deletionScopeIdentity = latest.header.deletionScopeIdentity,
			fenceKind = ImportedAmbientStepsDayFenceEntity.FENCE_FULL_CLEAR,
			collectedDataEpoch = newCollectedDataEpoch,
			sourceEvidenceRevision = sourceEvidenceRevision,
			fencedAtMs = clearedAtMs,
			retainedFromMs = null,
			latestImportRevision = latest.header.importRevision,
			latestContentChecksum = latest.header.dayContentChecksum,
			structuralEpochDay = latest.header.structuralEpochDay,
			storedZoneId = latest.header.storedZoneId,
			structuralDayStartTimeMs = latest.header.structuralDayStartTimeMs,
			structuralDayEndTimeMs = latest.header.structuralDayEndTimeMs,
			revisionCount = lineage.revisions.size,
			archiveCount = lineage.archiveDays
				.filter { it.dayIdentity == latest.header.dayIdentity }
				.map { it.archiveIdentity }
				.distinct()
				.size,
			factRowCount = lineage.facts.size,
			gapRowCount = lineage.gaps.size,
			protectedIdentities = markers,
			lineageChecksum = lineage.lineageChecksum,
		)
		insertFenceForFullClear(fence)
		insertProtectedIdentitiesForFullClear(markers)
		fencedDayIds += latest.header.dayIdentity
		newlyFencedDays = Math.addExact(newlyFencedDays, 1)
		if (newlyFencedDays.toLong() > ImportedAmbientStepsDao.MAX_GLOBAL_DAY_REVISIONS ||
			Math.addExact(reepochedFences, newlyFencedDays).toLong() >
			ImportedAmbientStepsDao.MAX_GLOBAL_FENCES
		) {
			overflowFullClear()
		}
		protectedIdentityCount = Math.addExact(protectedIdentityCount, markers.size.toLong())
		if (protectedIdentityCount >
			ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES
		) overflowFullClear()
		afterDayId = candidate.dayIdentity
	}

	return ImportedAmbientStepsFullClearResult(
		reepochedFences,
		newlyFencedDays,
		Math.toIntExact(protectedIdentityCount),
	)
}

/**
 * Removes imported payload only after the parent has advanced SourceEvidenceState and every live
 * day is protected by a new-epoch full-clear fence.
 */
fun ImportedAmbientStepsDao.deleteFullClearPayloadInCurrentTransaction(
	newCollectedDataEpoch: Long,
): Unit = try {
	deleteFullClearPayloadUnchecked(newCollectedDataEpoch)
} catch (failure: ImportedAmbientStepsLineageFailure) {
	throw failure
} catch (_: IllegalArgumentException) {
	corruptFullClear()
} catch (_: IllegalStateException) {
	corruptFullClear()
} catch (_: ArithmeticException) {
	corruptFullClear()
} catch (_: java.time.DateTimeException) {
	corruptFullClear()
}

private fun ImportedAmbientStepsDao.deleteFullClearPayloadUnchecked(
	newCollectedDataEpoch: Long,
) {
	require(newCollectedDataEpoch >= 0L)
	var afterDayId: String? = null
	while (true) {
		val page = fullClearDayCandidatePage(afterDayId, FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		page.forEach { day ->
			val fence = fenceForFullClear(day.dayIdentity) ?: corruptFullClear()
			if (fence.collectedDataEpoch != newCollectedDataEpoch ||
				fence.fenceKind != ImportedAmbientStepsDayFenceEntity.FENCE_FULL_CLEAR
			) corruptFullClear()
		}
		afterDayId = page.last().dayIdentity
		if (page.size < FULL_CLEAR_PAGE_SIZE) break
	}
	deleteAllDays()
	deleteAllReceipts()
	deleteAllArchives()
}

private fun ImportedAmbientStepsDao.authenticateFullClearLineage(
	candidate: ImportedAmbientStepsDayRevisionEntity,
	oldCollectedDataEpoch: Long,
): AuthenticatedImportedAmbientStepsLineage {
	val memberships = archiveDaysForFullClear(
		candidate.dayIdentity,
		ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
	)
	if (memberships.size > ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY) overflowFullClear()
	val archiveIds = memberships.map { it.archiveIdentity }.distinct()
	val archives = archivesForFullClear(archiveIds, archiveIds.size + 1)
	val archiveDays = allArchiveDaysForFullClear(
		archiveIds,
		ImportedAmbientStepsDao.MAX_ARCHIVE_MEMBERS_PER_LINEAGE + 1,
	)
	val receipts = receiptsForFullClear(
		archiveIds,
		ImportedAmbientStepsDao.MAX_RECEIPTS_PER_LINEAGE + 1,
	)
	if (archives.size != archiveIds.size ||
		archiveDays.size > ImportedAmbientStepsDao.MAX_ARCHIVE_MEMBERS_PER_LINEAGE ||
		receipts.size > ImportedAmbientStepsDao.MAX_RECEIPTS_PER_LINEAGE
	) overflowFullClear()
	return ImportedAmbientStepsLineageAuthenticator.authenticate(
		dayIdentity = candidate.dayIdentity,
		expectedCollectedDataEpoch = oldCollectedDataEpoch,
		headers = dayRevisionsForFullClear(
			candidate.dayIdentity,
			ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY + 1,
		),
		archives = archives,
		archiveDays = archiveDays,
		receipts = receipts,
		facts = factsForFullClear(
			candidate.dayIdentity,
			ImportedAmbientStepsDao.MAX_TOTAL_FACT_ROWS_PER_LINEAGE + 1,
		),
		gaps = gapsForFullClear(
			candidate.dayIdentity,
			ImportedAmbientStepsDao.MAX_TOTAL_GAP_ROWS_PER_LINEAGE + 1,
		),
	)
}

private data class FullClearStructuralDayKey(
	val epochDay: Long,
	val zoneId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
)

private fun ImportedAmbientStepsDayFenceEntity.structuralKey() = FullClearStructuralDayKey(
	structuralEpochDay,
	storedZoneId,
	structuralDayStartTimeMs,
	structuralDayEndTimeMs,
)

private fun ImportedAmbientStepsDayRevisionEntity.structuralKey() = FullClearStructuralDayKey(
	structuralEpochDay,
	storedZoneId,
	structuralDayStartTimeMs,
	structuralDayEndTimeMs,
)

private fun corruptFullClear(): Nothing = throw ImportedAmbientStepsLineageFailure(
	ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
)

private fun overflowFullClear(): Nothing = throw ImportedAmbientStepsLineageFailure(
	ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
)

private const val FULL_CLEAR_PAGE_SIZE = 256
private const val FULL_CLEAR_ID_BATCH_SIZE = 400
