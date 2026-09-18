package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiFactDao
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiDeletionMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AmbientWifiRetentionCommand(
	val beforeMs: Long,
	val expectedCollectedDataEpoch: Long,
	val appliedAtMs: Long,
) {
	init {
		require(beforeMs >= 0L && expectedCollectedDataEpoch >= 0L)
		require(appliedAtMs >= beforeMs)
	}
}

sealed interface AmbientWifiRetentionResult {
	data class Pruned(
		val localLineages: Int,
		val importedArchives: Int,
		val gaps: Int,
	) : AmbientWifiRetentionResult

	data object NoChange : AmbientWifiRetentionResult
	data class Unavailable(val reason: AmbientWifiMaintenanceUnavailableReason) :
		AmbientWifiRetentionResult
}

sealed interface AmbientWifiDeletionResult {
	data class Deleted(
		val localRevisionCount: Long,
		val importedFactCount: Long,
		val importedGapCount: Long,
		val deletionGeneration: Long,
	) : AmbientWifiDeletionResult

	data object AlreadyDeleted : AmbientWifiDeletionResult
	data class Unavailable(val reason: AmbientWifiMaintenanceUnavailableReason) :
		AmbientWifiDeletionResult
}

enum class AmbientWifiMaintenanceUnavailableReason {
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

/** Prunes only complete Ambient Wi-Fi lineages selected by durable source retention authority. */
suspend fun AppDatabase.pruneAmbientWifi(
	command: AmbientWifiRetentionCommand,
): AmbientWifiRetentionResult = withTransaction {
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != command.expectedCollectedDataEpoch) {
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	if (state.retainedFromMs != command.beforeMs) {
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.RETENTION_BOUNDARY_MISMATCH,
		)
	}
	val dao = ambientWifiFactDao()
	val liveRow = dao.latestRetentionAuthority(
		AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
	)
	val importRow = dao.latestRetentionAuthority(
		AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
	)
	if (liveRow?.let(AmbientWifiRetentionAuthorityIntegrity::isAuthentic) == false ||
		importRow?.let(AmbientWifiRetentionAuthorityIntegrity::isAuthentic) == false
	) {
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val liveRetention = liveRow?.takeIf {
		it.isActive &&
			it.collectedDataEpoch == command.expectedCollectedDataEpoch &&
			it.retainedFromMs == command.beforeMs
	}
	val importRetention = importRow?.takeIf {
		it.isActive &&
			it.collectedDataEpoch == command.expectedCollectedDataEpoch &&
			it.retainedFromMs == command.beforeMs
	}
	if (liveRetention == null && importRetention == null) {
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
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
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (localIds.isEmpty() && localGaps.isEmpty() &&
		importedIds.isEmpty() && importedGapIds.isEmpty()
	) return@withTransaction AmbientWifiRetentionResult.NoChange
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
		localLineages.any { AmbientWifiFactIntegrity.effectChecksum(it) != it.effectChecksum } ||
		localGaps.any { AmbientWifiFactIntegrity.gapChecksum(it) != it.effectChecksum } ||
		importedLineages.any { !AmbientWifiFactIntegrity.isAuthentic(it) } ||
		importedGaps.any { !AmbientWifiFactIntegrity.isAuthentic(it) } ||
		!localLineages.completeLocalWifiLineages() ||
		!importedLineages.completeImportedWifiLineages()
	) {
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val marker = dao.latestDeletionMarker(command.expectedCollectedDataEpoch)
	if (marker != null && !AmbientWifiFactIntegrity.isAuthentic(marker)) {
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
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
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	val candidateArchives = (
		importedLineages.map(ImportedAmbientWifiFactEntity::archiveId) +
			importedGaps.map(ImportedAmbientWifiGapEntity::archiveId)
	).distinct()
	val emptiedArchives = candidateArchives.filter { archiveId ->
		allImportedFacts.filter { it.archiveId == archiveId }
			.all { it.factId in selectedFactIds } &&
			allImportedGaps.filter { it.archiveId == archiveId }
				.all { it.gapId in selectedGapIds }
	}
	val footprints = wifiReplayFootprints(
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
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (!dao.installVerifiedWifiFootprints(footprints)) {
		return@withTransaction wifiRetentionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	localIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		dao.deleteLocalCursors(ids)
		dao.deleteLocalFacts(ids)
	}
	localGaps.map(AmbientWifiGapEntity::gapId).chunked(SQLITE_ID_CHUNK_SIZE).forEach {
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
				AmbientWifiFactIntegrity.createImportTombstone(
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
	AmbientWifiRetentionResult.Pruned(
		localIds.size,
		emptiedArchives.size,
		localGaps.size + importedGaps.size,
	)
}

/** Fences a revoked Ambient Wi-Fi consent epoch even when no payload rows exist. */
suspend fun AppDatabase.deleteAmbientWifiAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): AmbientWifiDeletionResult = withTransaction {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val dao = ambientWifiFactDao()
	val authority = dao.latestAuthority()
	if (authority == null || !AmbientWifiAuthorityIntegrity.isAuthentic(authority) ||
		authority.state != AmbientWifiAuthorityEntity.STATE_REVOKED ||
		authority.collectedDataEpoch != expectedCollectedDataEpoch ||
		authority.ambientConsentEpoch != expectedRevokedConsentEpoch
	) return@withTransaction wifiDeletionUnavailable(
		AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	val policyAuthority = sourcePolicyDao().authority()
	val policy = policyAuthority?.takeIf {
		it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
	}?.let {
		sourcePolicyDao().policyAtRevision(
			it.currentPolicyRevision,
			SourceDestinationOwnerEntity.SOURCE_WIFI,
		)
	}
	if (policy == null || policy.ambientPersistenceEligible || policy.ambientConsentEpoch != null) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.CONSENT_STILL_ACTIVE,
		)
	}
	val activeDemands = sourceBrokerDao().activeDemandsBounded(
		SourceDestinationOwnerEntity.SOURCE_WIFI,
		LIMIT + 1,
	)
	if (activeDemands.size > LIMIT) return@withTransaction wifiDeletionUnavailable(
		AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	if (activeDemands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.AMBIENT_DEMAND_ACTIVE,
		)
	}
	val registrations = sourceBrokerDao().currentPhysicalRegistrationsBounded(
		SourceDestinationOwnerEntity.SOURCE_WIFI,
		MAX_REGISTRATIONS + 1,
	)
	if (registrations.size > MAX_REGISTRATIONS) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	for (registration in registrations) {
		if (sourceBrokerDao().latestAuthorization(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				registration.registrationGeneration,
			).any {
				it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible
			}
		) return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.AMBIENT_PROVIDER_ACTIVE,
		)
	}
	val scope = dao.loadWholeWifiScope()
		?: return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	if (!scope.isAuthentic) return@withTransaction wifiDeletionUnavailable(
		AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	val previous = dao.latestDeletionMarker(expectedCollectedDataEpoch)
	if (previous != null && !AmbientWifiFactIntegrity.isAuthentic(previous)) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	if (scope.isEmpty &&
		previous?.throughConsentEpoch?.let { it >= expectedRevokedConsentEpoch } == true
	) return@withTransaction AmbientWifiDeletionResult.AlreadyDeleted
	val generation = previous.nextDeletionGeneration()
		?: return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	val footprints = scope.footprints(expectedCollectedDataEpoch, generation, deletedAtMs)
	if (footprints.size > LIMIT) return@withTransaction wifiDeletionUnavailable(
		AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	if (!dao.installVerifiedWifiFootprints(footprints)) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	dao.insertDeletionMarker(AmbientWifiFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		expectedRevokedConsentEpoch,
		"AMBIENT_WIFI_CONSENT_RESET",
		deletedAtMs,
	))
	dao.installWifiArchiveTombstones(scope.archiveIds, expectedCollectedDataEpoch, generation, deletedAtMs)
	dao.deleteWholeWifiPayload(scope.archiveIds)
	check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1)
	AmbientWifiDeletionResult.Deleted(
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
suspend fun AppDatabase.clearAmbientWifiProductPreservingReplayFootprints(
	expectedCollectedDataEpoch: Long,
	clearedAtMs: Long,
): AmbientWifiDeletionResult = withTransaction {
	require(expectedCollectedDataEpoch >= 0L && clearedAtMs >= 0L)
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val dao = ambientWifiFactDao()
	val scope = dao.loadWholeWifiScope()
		?: return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	if (!scope.isAuthentic) return@withTransaction wifiDeletionUnavailable(
		AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	val previous = dao.latestDeletionMarker(expectedCollectedDataEpoch)
	if (previous != null && !AmbientWifiFactIntegrity.isAuthentic(previous)) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val authority = dao.latestAuthority()
	if (authority != null && !AmbientWifiAuthorityIntegrity.isAuthentic(authority)) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val generation = previous.nextDeletionGeneration()
		?: return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	val footprints = scope.footprints(expectedCollectedDataEpoch, generation, clearedAtMs)
	if (footprints.size > LIMIT) return@withTransaction wifiDeletionUnavailable(
		AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	if (!dao.installVerifiedWifiFootprints(footprints)) {
		return@withTransaction wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	dao.insertDeletionMarker(AmbientWifiFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		authority?.ambientConsentEpoch ?: 0L,
		"AMBIENT_WIFI_FULL_CLEAR",
		clearedAtMs,
	))
	dao.installWifiArchiveTombstones(scope.archiveIds, expectedCollectedDataEpoch, generation, clearedAtMs)
	dao.deleteWholeWifiPayload(scope.archiveIds)
	dao.deleteAllAuthorities()
	dao.deleteAllRetentionAuthorities()
	check(sourceEvidenceStateDao().incrementRevision(clearedAtMs) == 1)
	AmbientWifiDeletionResult.Deleted(
		scope.localFacts.size.toLong(),
		scope.importedFacts.size.toLong(),
		scope.importedGaps.size.toLong(),
		generation,
	)
}

internal fun AppDatabase.clearAmbientWifiProductInCurrentFullClearTransaction(
	expectedCollectedDataEpoch: Long,
	clearedAtMs: Long,
): AmbientWifiDeletionResult {
	require(expectedCollectedDataEpoch >= 0L && clearedAtMs >= 0L)
	val storedEpoch = openHelper.writableDatabase.query(
		"SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1",
	).use { cursor ->
		check(cursor.moveToFirst()) { "Ambient Wi-Fi full clear requires evidence authority" }
		cursor.getLong(0)
	}
	if (storedEpoch != expectedCollectedDataEpoch) {
		return wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val dao = ambientWifiFactDao()
	val scope = dao.loadWholeWifiScopeForFullClear()
		?: return wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	if (!scope.isAuthentic) return wifiDeletionUnavailable(
		AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	val previous = dao.latestDeletionMarkerForFullClear(expectedCollectedDataEpoch)
	if (previous != null && !AmbientWifiFactIntegrity.isAuthentic(previous)) {
		return wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val authority = dao.latestAuthorityForFullClear()
	if (authority != null && !AmbientWifiAuthorityIntegrity.isAuthentic(authority)) {
		return wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val generation = previous.nextDeletionGeneration()
		?: return wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	val footprints = scope.footprints(expectedCollectedDataEpoch, generation, clearedAtMs)
	if (footprints.size > LIMIT) return wifiDeletionUnavailable(
		AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	if (!dao.installVerifiedWifiFootprintsForFullClear(footprints)) {
		return wifiDeletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	dao.insertDeletionMarkerForFullClear(AmbientWifiFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		authority?.ambientConsentEpoch ?: 0L,
		"AMBIENT_WIFI_FULL_CLEAR",
		clearedAtMs,
	))
	dao.installWifiArchiveTombstonesForFullClear(
		scope.archiveIds,
		expectedCollectedDataEpoch,
		generation,
		clearedAtMs,
	)
	dao.deleteWholeWifiPayloadForFullClear(scope.archiveIds)
	dao.deleteAllAuthoritiesForFullClear()
	dao.deleteAllRetentionAuthoritiesForFullClear()
	return AmbientWifiDeletionResult.Deleted(
		scope.localFacts.size.toLong(),
		scope.importedFacts.size.toLong(),
		scope.importedGaps.size.toLong(),
		generation,
	)
}

private data class WifiStoredScope(
	val localCursorCount: Long,
	val localFacts: List<AmbientWifiFactRevisionEntity>,
	val localGaps: List<AmbientWifiGapEntity>,
	val importedFacts: List<ImportedAmbientWifiFactEntity>,
	val importedGaps: List<ImportedAmbientWifiGapEntity>,
	val archiveIds: List<String>,
) {
	val isEmpty: Boolean
		get() = localCursorCount == 0L && localFacts.isEmpty() && localGaps.isEmpty() &&
			importedFacts.isEmpty() && importedGaps.isEmpty() && archiveIds.isEmpty()

	val isAuthentic: Boolean
		get() = localFacts.all { AmbientWifiFactIntegrity.effectChecksum(it) == it.effectChecksum } &&
			localGaps.all { AmbientWifiFactIntegrity.gapChecksum(it) == it.effectChecksum } &&
			importedFacts.all(AmbientWifiFactIntegrity::isAuthentic) &&
			importedGaps.all(AmbientWifiFactIntegrity::isAuthentic)

	fun footprints(epoch: Long, generation: Long, recordedAtMs: Long) =
		wifiReplayFootprints(
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

private suspend fun AmbientWifiFactDao.loadWholeWifiScope(): WifiStoredScope? {
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
	return WifiStoredScope(
		localCursorCount,
		localFacts,
		localGaps,
		importedFacts,
		importedGaps,
		archiveIds,
	)
}

private fun AmbientWifiFactDao.loadWholeWifiScopeForFullClear(): WifiStoredScope? {
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
	return WifiStoredScope(
		localCursorCount,
		localFacts,
		localGaps,
		importedFacts,
		importedGaps,
		archiveIds,
	)
}

private fun wifiReplayFootprints(
	localFacts: List<AmbientWifiFactRevisionEntity>,
	localGaps: List<AmbientWifiGapEntity>,
	importedFacts: List<ImportedAmbientWifiFactEntity>,
	importedGaps: List<ImportedAmbientWifiGapEntity>,
	archiveIds: List<String>,
	epoch: Long,
	generation: Long,
	recordedAtMs: Long,
): List<AmbientWifiReplayFootprintEntity> =
	localFacts.map { fact ->
		AmbientWifiFactIntegrity.createReplayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_LOCAL_FACT,
			fact.logicalFactId,
			fact.semanticRevision,
			epoch,
			generation,
			recordedAtMs,
		)
	} + localGaps.map { gap ->
		AmbientWifiFactIntegrity.createReplayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_LOCAL_GAP,
			gap.gapId,
			0L,
			epoch,
			generation,
			recordedAtMs,
		)
	} + importedFacts.flatMap { fact ->
		listOf(
			AmbientWifiFactIntegrity.createReplayFootprint(
				AmbientWifiReplayFootprintEntity.KIND_FACT_IDENTITY,
				fact.factId,
				fact.semanticRevision,
				epoch,
				generation,
				recordedAtMs,
			),
			AmbientWifiFactIntegrity.createReplayFootprint(
				AmbientWifiReplayFootprintEntity.KIND_FACT_EFFECT,
				fact.portableEffectChecksum,
				0L,
				epoch,
				generation,
				recordedAtMs,
			),
		)
	} + importedGaps.flatMap { gap ->
		listOf(
			AmbientWifiFactIntegrity.createReplayFootprint(
				AmbientWifiReplayFootprintEntity.KIND_GAP_IDENTITY,
				gap.gapId,
				0L,
				epoch,
				generation,
				recordedAtMs,
			),
			AmbientWifiFactIntegrity.createReplayFootprint(
				AmbientWifiReplayFootprintEntity.KIND_GAP_EFFECT,
				gap.portableEffectChecksum,
				0L,
				epoch,
				generation,
				recordedAtMs,
			),
		)
	} + archiveIds.map { archiveId ->
		AmbientWifiFactIntegrity.createReplayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_ARCHIVE_SCOPE,
			archiveId,
			0L,
			epoch,
			generation,
			recordedAtMs,
		)
	}

private suspend fun AmbientWifiFactDao.installWifiArchiveTombstones(
	archiveIds: List<String>,
	epoch: Long,
	generation: Long,
	deletedAtMs: Long,
) {
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		insertImportTombstones(ids.map { archiveId ->
			AmbientWifiFactIntegrity.createImportTombstone(
				archiveId,
				epoch,
				generation,
				deletedAtMs,
			)
		})
	}
}

private fun AmbientWifiFactDao.installWifiArchiveTombstonesForFullClear(
	archiveIds: List<String>,
	epoch: Long,
	generation: Long,
	deletedAtMs: Long,
) {
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		insertImportTombstonesForFullClear(ids.map { archiveId ->
			AmbientWifiFactIntegrity.createImportTombstone(
				archiveId,
				epoch,
				generation,
				deletedAtMs,
			)
		})
	}
}

private suspend fun AmbientWifiFactDao.deleteWholeWifiPayload(archiveIds: List<String>) {
	deleteAllLocalCursors()
	deleteAllLocalFacts()
	deleteAllGaps()
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		deleteImportReceipts(ids)
		deleteImportedGaps(ids)
		deleteImportedFacts(ids)
	}
}

private fun AmbientWifiFactDao.deleteWholeWifiPayloadForFullClear(archiveIds: List<String>) {
	deleteAllLocalCursorsForFullClear()
	deleteAllLocalFactsForFullClear()
	deleteAllGapsForFullClear()
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		deleteImportReceiptsForFullClear(ids)
		deleteImportedGapsForFullClear(ids)
		deleteImportedFactsForFullClear(ids)
	}
}

private suspend fun AmbientWifiFactDao.installVerifiedWifiFootprints(
	values: List<AmbientWifiReplayFootprintEntity>,
): Boolean {
	if (values.isEmpty()) return true
	val identities = values.map(AmbientWifiReplayFootprintEntity::identityDigest).distinct()
	val existing = buildList {
		identities.chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(replayFootprintsByIdentityDigests(chunk, LIMIT + 1 - size))
			if (size > LIMIT) return@buildList
		}
	}
	if (existing.size > LIMIT || existing.any { !AmbientWifiFactIntegrity.isAuthentic(it) }) {
		return false
	}
	val expectedByKey = values.associateBy(AmbientWifiReplayFootprintEntity::storageKey)
	val existingByKey = existing.associateBy(AmbientWifiReplayFootprintEntity::storageKey)
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
		storedRows.any { !AmbientWifiFactIntegrity.isAuthentic(it) }
	) return false
	val stored = storedRows.associateBy(AmbientWifiReplayFootprintEntity::storageKey)
	return expectedByKey.all { (key, expected) ->
		stored[key] == expected
	}
}

private fun AmbientWifiFactDao.installVerifiedWifiFootprintsForFullClear(
	values: List<AmbientWifiReplayFootprintEntity>,
): Boolean {
	if (values.isEmpty()) return true
	val identities = values.map(AmbientWifiReplayFootprintEntity::identityDigest).distinct()
	val existing = buildList {
		identities.chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(replayFootprintsByIdentityDigestsForFullClear(chunk, LIMIT + 1 - size))
			if (size > LIMIT) return@buildList
		}
	}
	if (existing.size > LIMIT || existing.any { !AmbientWifiFactIntegrity.isAuthentic(it) }) {
		return false
	}
	val expectedByKey = values.associateBy(AmbientWifiReplayFootprintEntity::storageKey)
	val existingByKey = existing.associateBy(AmbientWifiReplayFootprintEntity::storageKey)
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
		storedRows.any { !AmbientWifiFactIntegrity.isAuthentic(it) }
	) return false
	val stored = storedRows.associateBy(AmbientWifiReplayFootprintEntity::storageKey)
	return expectedByKey.all { (key, expected) -> stored[key] == expected }
}

private fun AmbientWifiReplayFootprintEntity.storageKey(): Triple<String, String, Long> =
	Triple(footprintKind, identityDigest, semanticRevision)

private fun List<AmbientWifiFactRevisionEntity>.completeLocalWifiLineages(): Boolean =
	groupBy(AmbientWifiFactRevisionEntity::logicalFactId).values.all { lineage ->
		lineage.sortedBy(AmbientWifiFactRevisionEntity::semanticRevision)
			.withIndex().all { (index, fact) -> fact.semanticRevision == index + 1L }
	}

private fun List<ImportedAmbientWifiFactEntity>.completeImportedWifiLineages(): Boolean =
	groupBy(ImportedAmbientWifiFactEntity::factId).values.all { lineage ->
		lineage.sortedBy(ImportedAmbientWifiFactEntity::semanticRevision)
			.withIndex().all { (index, fact) -> fact.semanticRevision == index + 1L }
	}

private fun AmbientWifiDeletionMarkerEntity?.nextDeletionGeneration(): Long? = when {
	this == null -> 1L
	deletionGeneration == Long.MAX_VALUE -> null
	else -> deletionGeneration + 1L
}

private fun wifiRetentionUnavailable(reason: AmbientWifiMaintenanceUnavailableReason) =
	AmbientWifiRetentionResult.Unavailable(reason)

private fun wifiDeletionUnavailable(reason: AmbientWifiMaintenanceUnavailableReason) =
	AmbientWifiDeletionResult.Unavailable(reason)

private const val LIMIT = 16_384
private const val MAX_REGISTRATIONS = 32
private const val SQLITE_ID_CHUNK_SIZE = 400
