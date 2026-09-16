package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellFactDao
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellDeletionMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AmbientCellRetentionCommand(
	val beforeMs: Long,
	val expectedCollectedDataEpoch: Long,
	val appliedAtMs: Long,
) {
	init {
		require(beforeMs >= 0L && expectedCollectedDataEpoch >= 0L)
		require(appliedAtMs >= beforeMs)
	}
}

sealed interface AmbientCellRetentionResult {
	data class Pruned(
		val localLineages: Int,
		val importedArchives: Int,
		val gaps: Int,
	) : AmbientCellRetentionResult

	data object NoChange : AmbientCellRetentionResult
	data class Unavailable(val reason: AmbientCellMaintenanceUnavailableReason) :
		AmbientCellRetentionResult
}

sealed interface AmbientCellDeletionResult {
	data class Deleted(
		val localRevisionCount: Long,
		val importedFactCount: Long,
		val importedGapCount: Long,
		val deletionGeneration: Long,
	) : AmbientCellDeletionResult

	data object AlreadyDeleted : AmbientCellDeletionResult
	data class Unavailable(val reason: AmbientCellMaintenanceUnavailableReason) :
		AmbientCellDeletionResult
}

enum class AmbientCellMaintenanceUnavailableReason {
	SOURCE_AUTHORITY_UNAVAILABLE,
	RETENTION_APPROVAL_MISMATCH,
	RETENTION_AUTHORITY_UNAVAILABLE,
	RETENTION_BOUNDARY_MISMATCH,
	COLLECTED_DATA_EPOCH_CHANGED,
	CONSENT_STILL_ACTIVE,
	AMBIENT_DEMAND_ACTIVE,
	AMBIENT_PROVIDER_ACTIVE,
	DEPENDENCY_OVERFLOW,
	DELETION_GENERATION_EXHAUSTED,
}

/** Prunes only complete Ambient Cell lineages selected by durable source retention authority. */
suspend fun AppDatabase.pruneAmbientCell(
	command: AmbientCellRetentionCommand,
): AmbientCellRetentionResult = withTransaction {
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != command.expectedCollectedDataEpoch) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	if (state.retainedFromMs != command.beforeMs) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.RETENTION_BOUNDARY_MISMATCH,
		)
	}
	val dao = ambientCellFactDao()
	val liveRow = dao.latestRetentionAuthority(
		AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
	)
	val importRow = dao.latestRetentionAuthority(
		AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
	)
	if (liveRow?.let(AmbientCellRetentionAuthorityIntegrity::isAuthentic) == false ||
		importRow?.let(AmbientCellRetentionAuthorityIntegrity::isAuthentic) == false
	) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val liveRetention = liveRow?.takeIf {
		it.isActive && it.collectedDataEpoch == command.expectedCollectedDataEpoch
	}
	val importRetention = importRow?.takeIf {
		it.isActive && it.collectedDataEpoch == command.expectedCollectedDataEpoch
	}
	if (liveRetention == null && importRetention == null) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)
	}
	currentCoroutineContext().ensureActive()
	val localIds = liveRetention?.let {
		dao.localFactIdsBefore(command.beforeMs, it.opaquePolicyId, LIMIT + 1)
	}.orEmpty()
	val localGaps = liveRetention?.let {
		dao.localGapsBefore(command.beforeMs, it.opaquePolicyId, LIMIT + 1)
	}.orEmpty()
	val importedIds = importRetention?.let {
		dao.importedFactIdsBefore(command.beforeMs, it.opaquePolicyId, LIMIT + 1)
	}.orEmpty()
	val importedGapIds = importRetention?.let {
		dao.importedGapIdsBefore(command.beforeMs, it.opaquePolicyId, LIMIT + 1)
	}.orEmpty()
	if (listOf(localIds, localGaps, importedIds, importedGapIds).any { it.size > LIMIT }) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (localIds.isEmpty() && localGaps.isEmpty() &&
		importedIds.isEmpty() && importedGapIds.isEmpty()
	) return@withTransaction AmbientCellRetentionResult.NoChange
	val localLineages = localIds.takeIf { it.isNotEmpty() }?.let {
		dao.localFactLineages(it, LIMIT + 1)
	}.orEmpty()
	val importedLineages = importedIds.takeIf { it.isNotEmpty() }?.let {
		dao.importedFactLineages(it, LIMIT + 1)
	}.orEmpty()
	val importedGaps = importedGapIds.takeIf { it.isNotEmpty() }?.let {
		dao.importedGapRows(it, LIMIT + 1)
	}.orEmpty()
	if (localLineages.size > LIMIT || importedLineages.size > LIMIT ||
		importedGaps.size > LIMIT ||
		localLineages.any { AmbientCellFactIntegrity.effectChecksum(it) != it.effectChecksum } ||
		localGaps.any { AmbientCellFactIntegrity.gapChecksum(it) != it.effectChecksum } ||
		importedLineages.any { !AmbientCellFactIntegrity.isAuthentic(it) } ||
		importedGaps.any { !AmbientCellFactIntegrity.isAuthentic(it) } ||
		!localLineages.completeLocalCellLineages() ||
		!importedLineages.completeImportedCellLineages()
	) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val marker = dao.latestDeletionMarker(command.expectedCollectedDataEpoch)
	if (marker != null && !AmbientCellFactIntegrity.isAuthentic(marker)) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val generation = maxOf(1L, marker?.deletionGeneration ?: 0L)
	val selectedFactIds = importedIds.toSet()
	val selectedGapIds = importedGapIds.toSet()
	val allImportedFacts = if (selectedFactIds.isEmpty() && selectedGapIds.isEmpty()) {
		emptyList()
	} else {
		dao.allImportedFacts(LIMIT + 1)
	}
	val allImportedGaps = if (selectedFactIds.isEmpty() && selectedGapIds.isEmpty()) {
		emptyList()
	} else {
		dao.allImportedGaps(LIMIT + 1)
	}
	if (allImportedFacts.size > LIMIT || allImportedGaps.size > LIMIT) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	val candidateArchives = (
		importedLineages.map(ImportedAmbientCellFactEntity::archiveId) +
			importedGaps.map(ImportedAmbientCellGapEntity::archiveId)
	).distinct()
	val emptiedArchives = candidateArchives.filter { archiveId ->
		allImportedFacts.filter { it.archiveId == archiveId }
			.all { it.factId in selectedFactIds } &&
			allImportedGaps.filter { it.archiveId == archiveId }
				.all { it.gapId in selectedGapIds }
	}
	val footprints = cellReplayFootprints(
		localLineages,
		localGaps,
		importedLineages,
		importedGaps,
		emptiedArchives,
		command.expectedCollectedDataEpoch,
		generation,
		command.appliedAtMs,
	)
	if (footprints.size > LIMIT) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (!dao.installVerifiedCellFootprints(footprints)) {
		return@withTransaction cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	localIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		dao.deleteLocalCursors(ids)
		dao.deleteLocalFacts(ids)
	}
	localGaps.map(AmbientCellGapEntity::gapId).chunked(SQLITE_ID_CHUNK_SIZE).forEach {
		dao.deleteLocalGapsByIdentity(it)
	}
	importedIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach {
		dao.deleteImportedFactsByIdentity(it)
	}
	importedGapIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach {
		dao.deleteImportedGapsByIdentity(it)
	}
	if (emptiedArchives.isNotEmpty()) {
		emptiedArchives.chunked(SQLITE_ID_CHUNK_SIZE).forEach { archiveIds ->
			dao.insertImportTombstones(archiveIds.map { archiveId ->
				AmbientCellFactIntegrity.createImportTombstone(
					archiveId,
					command.expectedCollectedDataEpoch,
					generation,
					command.appliedAtMs,
				)
			})
			dao.deleteImportReceipts(archiveIds)
		}
	}
	check(sourceEvidenceStateDao().incrementRevision(command.appliedAtMs) == 1)
	AmbientCellRetentionResult.Pruned(
		localIds.size,
		emptiedArchives.size,
		localGaps.size + importedGaps.size,
	)
}

/** Fences a revoked Ambient Cell consent epoch even when no payload rows exist. */
suspend fun AppDatabase.deleteAmbientCellAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): AmbientCellDeletionResult = withTransaction {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val dao = ambientCellFactDao()
	val authority = dao.latestAuthority()
	if (authority == null || !AmbientCellAuthorityIntegrity.isAuthentic(authority) ||
		authority.state != AmbientCellAuthorityEntity.STATE_REVOKED ||
		authority.collectedDataEpoch != expectedCollectedDataEpoch ||
		authority.ambientConsentEpoch != expectedRevokedConsentEpoch
	) return@withTransaction cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	val policyAuthority = sourcePolicyDao().authority()
	val policy = policyAuthority?.takeIf {
		it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
	}?.let {
		sourcePolicyDao().policyAtRevision(
			it.currentPolicyRevision,
			SourceDestinationOwnerEntity.SOURCE_CELL,
		)
	}
	if (policy == null || policy.ambientPersistenceEligible || policy.ambientConsentEpoch != null) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.CONSENT_STILL_ACTIVE,
		)
	}
	val activeDemands = sourceBrokerDao().activeDemandsBounded(
		SourceDestinationOwnerEntity.SOURCE_CELL,
		LIMIT + 1,
	)
	if (activeDemands.size > LIMIT) return@withTransaction cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	if (activeDemands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.AMBIENT_DEMAND_ACTIVE,
		)
	}
	val registrations = sourceBrokerDao().currentPhysicalRegistrationsBounded(
		SourceDestinationOwnerEntity.SOURCE_CELL,
		MAX_REGISTRATIONS + 1,
	)
	if (registrations.size > MAX_REGISTRATIONS) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	for (registration in registrations) {
		if (sourceBrokerDao().latestAuthorization(
				SourceDestinationOwnerEntity.SOURCE_CELL,
				registration.registrationGeneration,
			).any {
				it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible
			}
		) return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.AMBIENT_PROVIDER_ACTIVE,
		)
	}
	val scope = dao.loadWholeCellScope()
		?: return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	if (!scope.isAuthentic) return@withTransaction cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	val previous = dao.latestDeletionMarker(expectedCollectedDataEpoch)
	if (previous != null && !AmbientCellFactIntegrity.isAuthentic(previous)) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	if (scope.isEmpty &&
		previous?.throughConsentEpoch?.let { it >= expectedRevokedConsentEpoch } == true
	) return@withTransaction AmbientCellDeletionResult.AlreadyDeleted
	val generation = previous.nextCellDeletionGeneration()
		?: return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	val footprints = scope.footprints(expectedCollectedDataEpoch, generation, deletedAtMs)
	if (footprints.size > LIMIT) return@withTransaction cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	if (!dao.installVerifiedCellFootprints(footprints)) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	dao.insertDeletionMarker(AmbientCellFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		expectedRevokedConsentEpoch,
		"AMBIENT_CELL_CONSENT_RESET",
		deletedAtMs,
	))
	dao.installCellArchiveTombstones(scope.archiveIds, expectedCollectedDataEpoch, generation, deletedAtMs)
	dao.deleteWholeCellPayload(scope.archiveIds)
	check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1)
	AmbientCellDeletionResult.Deleted(
		scope.localFacts.size.toLong(),
		scope.importedFacts.size.toLong(),
		scope.importedGaps.size.toLong(),
		generation,
	)
}

/**
 * Source-preserving full clear. Parent integration must retain the deletion-marker, import
 * tombstone, and replay-footprint tables after invoking this hook.
 */
suspend fun AppDatabase.clearAmbientCellProductPreservingReplayFootprints(
	expectedCollectedDataEpoch: Long,
	clearedAtMs: Long,
): AmbientCellDeletionResult = withTransaction {
	require(expectedCollectedDataEpoch >= 0L && clearedAtMs >= 0L)
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val dao = ambientCellFactDao()
	val scope = dao.loadWholeCellScope()
		?: return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	if (!scope.isAuthentic) return@withTransaction cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	val previous = dao.latestDeletionMarker(expectedCollectedDataEpoch)
	if (previous != null && !AmbientCellFactIntegrity.isAuthentic(previous)) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val authority = dao.latestAuthority()
	if (authority != null && !AmbientCellAuthorityIntegrity.isAuthentic(authority)) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val generation = previous.nextCellDeletionGeneration()
		?: return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	val footprints = scope.footprints(expectedCollectedDataEpoch, generation, clearedAtMs)
	if (footprints.size > LIMIT) return@withTransaction cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	if (!dao.installVerifiedCellFootprints(footprints)) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	dao.insertDeletionMarker(AmbientCellFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		authority?.ambientConsentEpoch ?: 0L,
		"AMBIENT_CELL_FULL_CLEAR",
		clearedAtMs,
	))
	dao.installCellArchiveTombstones(scope.archiveIds, expectedCollectedDataEpoch, generation, clearedAtMs)
	dao.deleteWholeCellPayload(scope.archiveIds)
	dao.deleteAllAuthorities()
	dao.deleteAllRetentionAuthorities()
	check(sourceEvidenceStateDao().incrementRevision(clearedAtMs) == 1)
	AmbientCellDeletionResult.Deleted(
		scope.localFacts.size.toLong(),
		scope.importedFacts.size.toLong(),
		scope.importedGaps.size.toLong(),
		generation,
	)
}

internal fun AppDatabase.clearAmbientCellProductInCurrentFullClearTransaction(
	expectedCollectedDataEpoch: Long,
	clearedAtMs: Long,
): AmbientCellDeletionResult {
	require(expectedCollectedDataEpoch >= 0L && clearedAtMs >= 0L)
	val storedEpoch = openHelper.writableDatabase.query(
		"SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1",
	).use { cursor ->
		check(cursor.moveToFirst()) { "Ambient Cell full clear requires evidence authority" }
		cursor.getLong(0)
	}
	if (storedEpoch != expectedCollectedDataEpoch) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val dao = ambientCellFactDao()
	val scope = dao.loadWholeCellScopeForFullClear()
		?: return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	if (!scope.isAuthentic) return cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	val previous = dao.latestDeletionMarkerForFullClear(expectedCollectedDataEpoch)
	if (previous != null && !AmbientCellFactIntegrity.isAuthentic(previous)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val authority = dao.latestAuthorityForFullClear()
	if (authority != null && !AmbientCellAuthorityIntegrity.isAuthentic(authority)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val generation = previous.nextCellDeletionGeneration()
		?: return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	val footprints = scope.footprints(expectedCollectedDataEpoch, generation, clearedAtMs)
	if (footprints.size > LIMIT) return cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	if (!dao.installVerifiedCellFootprintsForFullClear(footprints)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	dao.insertDeletionMarkerForFullClear(AmbientCellFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		authority?.ambientConsentEpoch ?: 0L,
		"AMBIENT_CELL_FULL_CLEAR",
		clearedAtMs,
	))
	dao.installCellArchiveTombstonesForFullClear(
		scope.archiveIds,
		expectedCollectedDataEpoch,
		generation,
		clearedAtMs,
	)
	dao.deleteWholeCellPayloadForFullClear(scope.archiveIds)
	dao.deleteAllAuthoritiesForFullClear()
	dao.deleteAllRetentionAuthoritiesForFullClear()
	return AmbientCellDeletionResult.Deleted(
		scope.localFacts.size.toLong(),
		scope.importedFacts.size.toLong(),
		scope.importedGaps.size.toLong(),
		generation,
	)
}

private data class CellStoredScope(
	val localCursorCount: Long,
	val localFacts: List<AmbientCellFactRevisionEntity>,
	val localGaps: List<AmbientCellGapEntity>,
	val importedFacts: List<ImportedAmbientCellFactEntity>,
	val importedGaps: List<ImportedAmbientCellGapEntity>,
	val archiveIds: List<String>,
) {
	val isEmpty: Boolean
		get() = localCursorCount == 0L && localFacts.isEmpty() && localGaps.isEmpty() &&
			importedFacts.isEmpty() && importedGaps.isEmpty() && archiveIds.isEmpty()

	val isAuthentic: Boolean
		get() = localFacts.all { AmbientCellFactIntegrity.effectChecksum(it) == it.effectChecksum } &&
			localGaps.all { AmbientCellFactIntegrity.gapChecksum(it) == it.effectChecksum } &&
			importedFacts.all(AmbientCellFactIntegrity::isAuthentic) &&
			importedGaps.all(AmbientCellFactIntegrity::isAuthentic)

	fun footprints(epoch: Long, generation: Long, recordedAtMs: Long) =
		cellReplayFootprints(
			localFacts,
			localGaps,
			importedFacts,
			importedGaps,
			archiveIds,
			epoch,
			generation,
			recordedAtMs,
		)
}

private suspend fun AmbientCellFactDao.loadWholeCellScope(): CellStoredScope? {
	val localCursorCount = localCursorCount()
	val localFacts = allLocalFactRevisions(LIMIT + 1)
	val localGaps = allLocalGaps(LIMIT + 1)
	val importedFacts = allImportedFacts(LIMIT + 1)
	val importedGaps = allImportedGaps(LIMIT + 1)
	val archiveIds = (
		allImportedArchiveIds(LIMIT + 1) + allImportReceiptArchiveIds(LIMIT + 1)
	).distinct()
	if (localCursorCount > LIMIT || listOf(
			localFacts,
			localGaps,
			importedFacts,
			importedGaps,
			archiveIds,
		).any { it.size > LIMIT }
	) return null
	return CellStoredScope(
		localCursorCount,
		localFacts,
		localGaps,
		importedFacts,
		importedGaps,
		archiveIds,
	)
}

private fun AmbientCellFactDao.loadWholeCellScopeForFullClear(): CellStoredScope? {
	val localCursorCount = localCursorCountForFullClear()
	val localFacts = allLocalFactRevisionsForFullClear(LIMIT + 1)
	val localGaps = allLocalGapsForFullClear(LIMIT + 1)
	val importedFacts = allImportedFactsForFullClear(LIMIT + 1)
	val importedGaps = allImportedGapsForFullClear(LIMIT + 1)
	val archiveIds = (
		allImportedArchiveIdsForFullClear(LIMIT + 1) +
			allImportReceiptArchiveIdsForFullClear(LIMIT + 1)
	).distinct()
	if (localCursorCount > LIMIT || listOf(
			localFacts,
			localGaps,
			importedFacts,
			importedGaps,
			archiveIds,
		).any { it.size > LIMIT }
	) return null
	return CellStoredScope(
		localCursorCount,
		localFacts,
		localGaps,
		importedFacts,
		importedGaps,
		archiveIds,
	)
}

private fun cellReplayFootprints(
	localFacts: List<AmbientCellFactRevisionEntity>,
	localGaps: List<AmbientCellGapEntity>,
	importedFacts: List<ImportedAmbientCellFactEntity>,
	importedGaps: List<ImportedAmbientCellGapEntity>,
	archiveIds: List<String>,
	epoch: Long,
	generation: Long,
	recordedAtMs: Long,
): List<AmbientCellReplayFootprintEntity> =
	localFacts.map { fact ->
		AmbientCellFactIntegrity.createReplayFootprint(
			AmbientCellReplayFootprintEntity.KIND_LOCAL_FACT,
			fact.logicalFactId,
			fact.semanticRevision,
			epoch,
			generation,
			recordedAtMs,
		)
	} + localGaps.map { gap ->
		AmbientCellFactIntegrity.createReplayFootprint(
			AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP,
			gap.gapId,
			0L,
			epoch,
			generation,
			recordedAtMs,
		)
	} + importedFacts.flatMap { fact ->
		listOf(
			AmbientCellFactIntegrity.createReplayFootprint(
				AmbientCellReplayFootprintEntity.KIND_FACT_IDENTITY,
				fact.factId,
				fact.semanticRevision,
				epoch,
				generation,
				recordedAtMs,
			),
			AmbientCellFactIntegrity.createReplayFootprint(
				AmbientCellReplayFootprintEntity.KIND_FACT_EFFECT,
				fact.portableEffectChecksum,
				0L,
				epoch,
				generation,
				recordedAtMs,
			),
		)
	} + importedGaps.flatMap { gap ->
		listOf(
			AmbientCellFactIntegrity.createReplayFootprint(
				AmbientCellReplayFootprintEntity.KIND_GAP_IDENTITY,
				gap.gapId,
				0L,
				epoch,
				generation,
				recordedAtMs,
			),
			AmbientCellFactIntegrity.createReplayFootprint(
				AmbientCellReplayFootprintEntity.KIND_GAP_EFFECT,
				gap.portableEffectChecksum,
				0L,
				epoch,
				generation,
				recordedAtMs,
			),
		)
	} + archiveIds.map { archiveId ->
		AmbientCellFactIntegrity.createReplayFootprint(
			AmbientCellReplayFootprintEntity.KIND_ARCHIVE_SCOPE,
			archiveId,
			0L,
			epoch,
			generation,
			recordedAtMs,
		)
	}

private suspend fun AmbientCellFactDao.installCellArchiveTombstones(
	archiveIds: List<String>,
	epoch: Long,
	generation: Long,
	deletedAtMs: Long,
) {
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		insertImportTombstones(ids.map { archiveId ->
			AmbientCellFactIntegrity.createImportTombstone(
				archiveId,
				epoch,
				generation,
				deletedAtMs,
			)
		})
	}
}

private fun AmbientCellFactDao.installCellArchiveTombstonesForFullClear(
	archiveIds: List<String>,
	epoch: Long,
	generation: Long,
	deletedAtMs: Long,
) {
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		insertImportTombstonesForFullClear(ids.map { archiveId ->
			AmbientCellFactIntegrity.createImportTombstone(
				archiveId,
				epoch,
				generation,
				deletedAtMs,
			)
		})
	}
}

private suspend fun AmbientCellFactDao.deleteWholeCellPayload(archiveIds: List<String>) {
	deleteAllLocalCursors()
	deleteAllLocalFacts()
	deleteAllGaps()
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		deleteImportReceipts(ids)
		deleteImportedGaps(ids)
		deleteImportedFacts(ids)
	}
}

private fun AmbientCellFactDao.deleteWholeCellPayloadForFullClear(archiveIds: List<String>) {
	deleteAllLocalCursorsForFullClear()
	deleteAllLocalFactsForFullClear()
	deleteAllGapsForFullClear()
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		deleteImportReceiptsForFullClear(ids)
		deleteImportedGapsForFullClear(ids)
		deleteImportedFactsForFullClear(ids)
	}
}

private suspend fun AmbientCellFactDao.installVerifiedCellFootprints(
	values: List<AmbientCellReplayFootprintEntity>,
): Boolean {
	if (values.isEmpty()) return true
	val identities = values.map(AmbientCellReplayFootprintEntity::identityDigest).distinct()
	val existing = buildList {
		identities.chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(replayFootprintsByIdentityDigests(chunk, LIMIT + 1 - size))
			if (size > LIMIT) return@buildList
		}
	}
	if (existing.size > LIMIT || existing.any { !AmbientCellFactIntegrity.isAuthentic(it) }) {
		return false
	}
	val expectedByKey = values.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	val existingByKey = existing.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	if (expectedByKey.any { (key, expected) ->
			existingByKey[key]?.let { it != expected } == true
		}
	) return false
	val missing = expectedByKey.filterKeys { it !in existingByKey }.values.toList()
	if (missing.isNotEmpty()) insertReplayFootprints(missing)
	val storedRows = buildList {
		identities.chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(replayFootprintsByIdentityDigests(chunk, LIMIT + 1 - size))
			if (size > LIMIT) return@buildList
		}
	}
	if (storedRows.size > LIMIT ||
		storedRows.any { !AmbientCellFactIntegrity.isAuthentic(it) }
	) return false
	val stored = storedRows.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	return expectedByKey.all { (key, expected) ->
		stored[key] == expected
	}
}

private fun AmbientCellFactDao.installVerifiedCellFootprintsForFullClear(
	values: List<AmbientCellReplayFootprintEntity>,
): Boolean {
	if (values.isEmpty()) return true
	val identities = values.map(AmbientCellReplayFootprintEntity::identityDigest).distinct()
	val existing = buildList {
		identities.chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(replayFootprintsByIdentityDigestsForFullClear(chunk, LIMIT + 1 - size))
			if (size > LIMIT) return@buildList
		}
	}
	if (existing.size > LIMIT || existing.any { !AmbientCellFactIntegrity.isAuthentic(it) }) {
		return false
	}
	val expectedByKey = values.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	val existingByKey = existing.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	if (expectedByKey.any { (key, expected) ->
			existingByKey[key]?.let { it != expected } == true
		}
	) return false
	val missing = expectedByKey.filterKeys { it !in existingByKey }.values.toList()
	if (missing.isNotEmpty()) insertReplayFootprintsForFullClear(missing)
	val storedRows = buildList {
		identities.chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(replayFootprintsByIdentityDigestsForFullClear(chunk, LIMIT + 1 - size))
			if (size > LIMIT) return@buildList
		}
	}
	if (storedRows.size > LIMIT ||
		storedRows.any { !AmbientCellFactIntegrity.isAuthentic(it) }
	) return false
	val stored = storedRows.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	return expectedByKey.all { (key, expected) -> stored[key] == expected }
}

private fun AmbientCellReplayFootprintEntity.storageKey(): Triple<String, String, Long> =
	Triple(footprintKind, identityDigest, semanticRevision)

private fun List<AmbientCellFactRevisionEntity>.completeLocalCellLineages(): Boolean =
	groupBy(AmbientCellFactRevisionEntity::logicalFactId).values.all { lineage ->
		lineage.sortedBy(AmbientCellFactRevisionEntity::semanticRevision)
			.withIndex().all { (index, fact) -> fact.semanticRevision == index + 1L }
	}

private fun List<ImportedAmbientCellFactEntity>.completeImportedCellLineages(): Boolean =
	groupBy(ImportedAmbientCellFactEntity::factId).values.all { lineage ->
		lineage.sortedBy(ImportedAmbientCellFactEntity::semanticRevision)
			.withIndex().all { (index, fact) -> fact.semanticRevision == index + 1L }
	}

private fun AmbientCellDeletionMarkerEntity?.nextCellDeletionGeneration(): Long? = when {
	this == null -> 1L
	deletionGeneration == Long.MAX_VALUE -> null
	else -> deletionGeneration + 1L
}

private fun cellRetentionUnavailable(reason: AmbientCellMaintenanceUnavailableReason) =
	AmbientCellRetentionResult.Unavailable(reason)

private fun cellDeletionUnavailable(reason: AmbientCellMaintenanceUnavailableReason) =
	AmbientCellDeletionResult.Unavailable(reason)

private const val LIMIT = 16_384
private const val MAX_REGISTRATIONS = 32
private const val SQLITE_ID_CHUNK_SIZE = 400
