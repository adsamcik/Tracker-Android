// One Cell maintenance owner keeps retention, consent reset and full clear on the same replay-fencing helpers.
@file:Suppress("TooManyFunctions")

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

/**
 * Original UTC millisecond retention cutoff and maintenance time bound to the expected
 * collected-data epoch.
 */
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

/**
 * Independent all-day local-lineage/imported-archive/gap pruning counts or a named unavailable
 * authority.
 */
sealed interface AmbientCellRetentionResult {
	/**
	 * Reports logical local/imported scope and revision/gap counts removed by the verified retention
	 * cutoff.
	 */
	data class Pruned(
		val localLineages: Int,
		val importedArchives: Int,
		val gaps: Int,
	) : AmbientCellRetentionResult

	/**
	 * No saved payload in this source scope required removal for the requested cutoff.
	 */
	data object NoChange : AmbientCellRetentionResult
	/**
	 * The requested original scope or retention authority is unavailable for the stated reason.
	 */
	data class Unavailable(val reason: AmbientCellMaintenanceUnavailableReason) :
		AmbientCellRetentionResult
}

/**
 * All-day native/imported payload removal with its replay-fence generation, prior deletion or
 * unavailable authority.
 */
sealed interface AmbientCellDeletionResult {
	/**
	 * The exact selected payload was fenced and removed; reports original removed scope counts.
	 */
	data class Deleted(
		val localRevisionCount: Long,
		val importedFactCount: Long,
		val importedGapCount: Long,
		val deletionGeneration: Long,
	) : AmbientCellDeletionResult

	/**
	 * The exact selected source scope was already deleted and remains represented by its original
	 * deletion authority.
	 */
	data object AlreadyDeleted : AmbientCellDeletionResult
	/**
	 * The requested original scope or retention authority is unavailable for the stated reason.
	 */
	data class Unavailable(val reason: AmbientCellMaintenanceUnavailableReason) :
		AmbientCellDeletionResult
}

/**
 * Changed epoch/floor/approval, active consent/provider/demand or exceeded dependencies preventing
 * all-day maintenance.
 */
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
	pruneCellAtApprovedBoundary(command)
}

private suspend fun AppDatabase.pruneCellAtApprovedBoundary(
	command: AmbientCellRetentionCommand,
): AmbientCellRetentionResult {
	val dao = ambientCellFactDao()
	currentCoroutineContext().ensureActive()
	val localIds = dao.localFactIdsBefore(command.beforeMs, LIMIT + 1)
	val localGaps = dao.localGapsBefore(command.beforeMs, LIMIT + 1)
	val importedIds = dao.importedFactIdsBefore(command.beforeMs, LIMIT + 1)
	val importedGapIds = dao.importedGapIdsBefore(command.beforeMs, LIMIT + 1)
	if (listOf(localIds, localGaps, importedIds, importedGapIds).any { it.size > LIMIT }) {
		return cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	val hasLocalCandidates = localIds.isNotEmpty() || localGaps.isNotEmpty()
	val hasImportedCandidates = importedIds.isNotEmpty() || importedGapIds.isNotEmpty()
	val liveAuthorities = if (hasLocalCandidates) {
		dao.retentionAuthorities(
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			LIMIT + 1,
		)
	} else {
		emptyList()
	}
	val importAuthorities = if (hasImportedCandidates) {
		dao.retentionAuthorities(
			AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
			LIMIT + 1,
		)
	} else {
		emptyList()
	}
	cellRetentionAuthorityFailure(command, hasLocalCandidates, hasImportedCandidates,
		liveAuthorities, importAuthorities)?.let { return it }
	if (!hasLocalCandidates && !hasImportedCandidates) {
		return AmbientCellRetentionResult.NoChange
	}
	return pruneSelectedCellLineages(dao, command, localIds, localGaps, importedIds, importedGapIds,
		liveAuthorities, importAuthorities)
}

// Keep the selected native/imported rows and their two saved authority sets explicit; no extra snapshot carrier.
@Suppress("LongParameterList")
private suspend fun AppDatabase.pruneSelectedCellLineages(
	dao: AmbientCellFactDao,
	command: AmbientCellRetentionCommand,
	localIds: List<String>,
	localGaps: List<AmbientCellGapEntity>,
	importedIds: List<String>,
	importedGapIds: List<String>,
	liveAuthorities: List<AmbientCellRetentionAuthorityEntity>,
	importAuthorities: List<AmbientCellRetentionAuthorityEntity>,
): AmbientCellRetentionResult {
	val localLineages = localIds.takeIf { it.isNotEmpty() }?.let {
		dao.localFactLineages(it, LIMIT + 1)
	}.orEmpty()
	val importedLineages = importedIds.takeIf { it.isNotEmpty() }?.let {
		dao.importedFactLineages(it, LIMIT + 1)
	}.orEmpty()
	val importedGaps = importedGapIds.takeIf { it.isNotEmpty() }?.let {
		dao.importedGapRows(it, LIMIT + 1)
	}.orEmpty()
	if (!hasAuthenticCellRetentionRows(localLineages, localGaps, importedLineages, importedGaps) ||
		!hasAuthorizedCellRetentionRows(localLineages, localGaps, importedLineages, importedGaps,
			liveAuthorities, importAuthorities) ||
		!localLineages.completeLocalCellLineages() || !importedLineages.completeImportedCellLineages()
	) {
		return cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	return pruneVerifiedCellLineages(dao, command, localIds, localGaps, importedIds, importedGapIds,
		localLineages, importedLineages, importedGaps)
}

// Keep qualified rows explicit and ordered marker/archive/footprint rejection before any payload deletion.
@Suppress("LongParameterList", "ReturnCount")
private suspend fun AppDatabase.pruneVerifiedCellLineages(
	dao: AmbientCellFactDao,
	command: AmbientCellRetentionCommand,
	localIds: List<String>,
	localGaps: List<AmbientCellGapEntity>,
	importedIds: List<String>,
	importedGapIds: List<String>,
	localLineages: List<AmbientCellFactRevisionEntity>,
	importedLineages: List<ImportedAmbientCellFactEntity>,
	importedGaps: List<ImportedAmbientCellGapEntity>,
): AmbientCellRetentionResult {
	val marker = dao.latestDeletionMarker(command.expectedCollectedDataEpoch)
	if (marker != null && !AmbientCellFactIntegrity.isAuthentic(marker)) {
		return cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val generation = maxOf(1L, marker?.deletionGeneration ?: 0L)
	val emptiedArchives = dao.emptiedCellArchives(
		importedIds, importedGapIds, importedLineages, importedGaps,
	) ?: return cellRetentionUnavailable(
		AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
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
		return cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (!dao.installVerifiedCellFootprints(footprints)) {
		return cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	deleteSelectedCellLocalPayload(dao, localIds, localGaps)
	dao.deleteSelectedCellImportedPayload(
		importedIds, importedGapIds, emptiedArchives, command, generation,
	)
	check(sourceEvidenceStateDao().incrementRevision(command.appliedAtMs) == 1)
	return AmbientCellRetentionResult.Pruned(
		localIds.size,
		emptiedArchives.size,
		localGaps.size + importedGaps.size,
	)
}

private fun cellRetentionAuthorityFailure(
	command: AmbientCellRetentionCommand,
	hasLocalCandidates: Boolean,
	hasImportedCandidates: Boolean,
	liveAuthorities: List<AmbientCellRetentionAuthorityEntity>,
	importAuthorities: List<AmbientCellRetentionAuthorityEntity>,
): AmbientCellRetentionResult.Unavailable? {
	if (liveAuthorities.size > LIMIT || importAuthorities.size > LIMIT) {
		return cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (
		(hasLocalCandidates && !liveAuthorities.hasCurrentCellSettlementAuthority(command)) ||
		(hasImportedCandidates && !importAuthorities.hasCurrentCellSettlementAuthority(command))
	) {
		return cellRetentionUnavailable(
			AmbientCellMaintenanceUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)
	}
	return null
}

private fun hasAuthenticCellRetentionRows(
	localLineages: List<AmbientCellFactRevisionEntity>,
	localGaps: List<AmbientCellGapEntity>,
	importedLineages: List<ImportedAmbientCellFactEntity>,
	importedGaps: List<ImportedAmbientCellGapEntity>,
): Boolean {
	if (localLineages.size > LIMIT || importedLineages.size > LIMIT || importedGaps.size > LIMIT) {
		return false
	}
	return localLineages.none { AmbientCellFactIntegrity.effectChecksum(it) != it.effectChecksum } &&
		localGaps.none { AmbientCellFactIntegrity.gapChecksum(it) != it.effectChecksum } &&
		importedLineages.none { !AmbientCellFactIntegrity.isAuthentic(it) } &&
		importedGaps.none { !AmbientCellFactIntegrity.isAuthentic(it) }
}

private fun hasAuthorizedCellRetentionRows(
	localLineages: List<AmbientCellFactRevisionEntity>,
	localGaps: List<AmbientCellGapEntity>,
	importedLineages: List<ImportedAmbientCellFactEntity>,
	importedGaps: List<ImportedAmbientCellGapEntity>,
	liveAuthorities: List<AmbientCellRetentionAuthorityEntity>,
	importAuthorities: List<AmbientCellRetentionAuthorityEntity>,
): Boolean = localLineages.none { !liveAuthorities.authenticatesCellLocal(it) } &&
	localGaps.none { !liveAuthorities.authenticatesCellLocal(it) } &&
	importedLineages.none { !importAuthorities.authenticatesCellImported(it) } &&
	importedGaps.none { !importAuthorities.authenticatesCellImported(it) }

// An archive receipt can be retired only when every retained fact and gap is selected.
private suspend fun AmbientCellFactDao.emptiedCellArchives(
	importedIds: List<String>,
	importedGapIds: List<String>,
	importedLineages: List<ImportedAmbientCellFactEntity>,
	importedGaps: List<ImportedAmbientCellGapEntity>,
): List<String>? {
	val selectedFactIds = importedIds.toSet()
	val selectedGapIds = importedGapIds.toSet()
	val allImportedFacts = if (selectedFactIds.isEmpty() && selectedGapIds.isEmpty()) {
		emptyList()
	} else {
		allImportedFacts(LIMIT + 1)
	}
	val allImportedGaps = if (selectedFactIds.isEmpty() && selectedGapIds.isEmpty()) {
		emptyList()
	} else {
		allImportedGaps(LIMIT + 1)
	}
	if (allImportedFacts.size > LIMIT || allImportedGaps.size > LIMIT) {
		return null
	}
	val candidateArchives = (
		importedLineages.map(ImportedAmbientCellFactEntity::archiveId) +
			importedGaps.map(ImportedAmbientCellGapEntity::archiveId)
	).distinct()
	return candidateArchives.filter { archiveId ->
		allImportedFacts.filter { it.archiveId == archiveId }
			.all { it.factId in selectedFactIds } &&
			allImportedGaps.filter { it.archiveId == archiveId }
				.all { it.gapId in selectedGapIds }
	}
}

private suspend fun AppDatabase.deleteSelectedCellLocalPayload(
	dao: AmbientCellFactDao,
	localIds: List<String>,
	localGaps: List<AmbientCellGapEntity>,
) {
	localIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		ids.forEach { id -> radioObservationChildDao().deleteCellBackgroundFact(
			AmbientCellFactRevisionEntity.WRITER_ID,
			AmbientCellFactRevisionEntity.WRITER_VERSION, id,
		) }
		dao.deleteLocalCursors(ids)
		dao.deleteLocalFacts(ids)
	}
	localGaps.map(AmbientCellGapEntity::gapId).chunked(SQLITE_ID_CHUNK_SIZE).forEach {
		dao.deleteLocalGapsByIdentity(it)
	}
}

private suspend fun AmbientCellFactDao.deleteSelectedCellImportedPayload(
	importedIds: List<String>,
	importedGapIds: List<String>,
	emptiedArchives: List<String>,
	command: AmbientCellRetentionCommand,
	generation: Long,
) {
	importedIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach {
		deleteImportedFactsByIdentity(it)
	}
	importedGapIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach {
		deleteImportedGapsByIdentity(it)
	}
	if (emptiedArchives.isNotEmpty()) {
		emptiedArchives.chunked(SQLITE_ID_CHUNK_SIZE).forEach { archiveIds ->
			insertImportTombstones(archiveIds.map { archiveId ->
				AmbientCellFactIntegrity.createImportTombstone(
					archiveId,
					command.expectedCollectedDataEpoch,
					generation,
					command.appliedAtMs,
				)
			})
			deleteImportReceipts(archiveIds)
		}
	}
}

// Both source clear and consent reset must preserve replay authority before removing payload.
private suspend fun AmbientCellFactDao.installCellDeletionFootprints(
	scope: CellStoredScope,
	expectedCollectedDataEpoch: Long,
	generation: Long,
	deletedAtMs: Long,
): AmbientCellDeletionResult.Unavailable? {
	val footprints = scope.footprints(expectedCollectedDataEpoch, generation, deletedAtMs)
	if (footprints.size > LIMIT) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (!installVerifiedCellFootprints(footprints)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	return null
}

private suspend fun AppDatabase.deleteFencedCellScope(
	dao: AmbientCellFactDao,
	scope: CellStoredScope,
	marker: AmbientCellDeletionMarkerEntity,
) {
	dao.insertDeletionMarker(marker)
	dao.installCellArchiveTombstones(
		scope.archiveIds, marker.collectedDataEpoch, marker.deletionGeneration, marker.deletedAtMs,
	)
	radioObservationChildDao().deleteAllCellBackground()
	dao.deleteWholeCellPayload(scope.archiveIds)
}

private fun List<AmbientCellRetentionAuthorityEntity>.hasCurrentCellSettlementAuthority(
	command: AmbientCellRetentionCommand,
): Boolean {
	val current = maxByOrNull(AmbientCellRetentionAuthorityEntity::approvalRevision) ?: return false
	return AmbientCellRetentionAuthorityIntegrity.isAuthentic(current) &&
		all(AmbientCellRetentionAuthorityIntegrity::isAuthentic) &&
		current.collectedDataEpoch == command.expectedCollectedDataEpoch &&
		(
			current.state == AmbientCellRetentionAuthorityEntity.STATE_REVOKED ||
				current.retainedFromMs == command.beforeMs
			)
}

private fun List<AmbientCellRetentionAuthorityEntity>.authenticatesCellLocal(
	row: AmbientCellFactRevisionEntity,
): Boolean = any {
	it.scope == AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT &&
		it.opaquePolicyId == row.retentionPolicyId &&
		it.approvalRevision == row.retentionApprovalRevision &&
		it.sourcePolicyRevision == row.sourcePolicyRevision &&
		it.ambientConsentEpoch == row.ambientConsentEpoch &&
		it.collectedDataEpoch == row.collectedDataEpoch
}

private fun List<AmbientCellRetentionAuthorityEntity>.authenticatesCellLocal(
	row: AmbientCellGapEntity,
): Boolean = any {
	it.scope == AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT &&
		it.opaquePolicyId == row.retentionPolicyId &&
		it.approvalRevision == row.retentionApprovalRevision &&
		it.sourcePolicyRevision == row.sourcePolicyRevision &&
		it.ambientConsentEpoch == row.ambientConsentEpoch &&
		it.collectedDataEpoch == row.collectedDataEpoch
}

private fun List<AmbientCellRetentionAuthorityEntity>.authenticatesCellImported(
	row: ImportedAmbientCellFactEntity,
): Boolean = any {
	it.scope == AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT &&
		it.opaquePolicyId == row.retentionPolicyId &&
		it.approvalRevision == row.retentionApprovalRevision &&
		it.collectedDataEpoch == row.collectedDataEpoch
}

private fun List<AmbientCellRetentionAuthorityEntity>.authenticatesCellImported(
	row: ImportedAmbientCellGapEntity,
): Boolean = any {
	it.scope == AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT &&
		it.opaquePolicyId == row.retentionPolicyId &&
		it.approvalRevision == row.retentionApprovalRevision &&
		it.collectedDataEpoch == row.collectedDataEpoch
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
	cellConsentResetFailure(expectedCollectedDataEpoch, expectedRevokedConsentEpoch)?.let { reason ->
		return@withTransaction cellDeletionUnavailable(reason)
	}
	val dao = ambientCellFactDao()
	val scope = dao.loadWholeCellScope()
		?: return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	if (!scope.isAuthentic) {
		return@withTransaction cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	}
	deleteVerifiedRevokedCellScope(dao, scope, expectedCollectedDataEpoch, expectedRevokedConsentEpoch, deletedAtMs)
}

// Marker, repeat-deletion, generation and replay-footprint exits must precede payload mutation.
@Suppress("ReturnCount")
private suspend fun AppDatabase.deleteVerifiedRevokedCellScope(
	dao: AmbientCellFactDao,
	scope: CellStoredScope,
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): AmbientCellDeletionResult {
	val previous = dao.latestDeletionMarker(expectedCollectedDataEpoch)
	if (previous != null && !AmbientCellFactIntegrity.isAuthentic(previous)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	if (scope.isEmpty &&
		previous?.throughConsentEpoch?.let { it >= expectedRevokedConsentEpoch } == true
	) {
		return AmbientCellDeletionResult.AlreadyDeleted
	}
	val generation = previous.nextCellDeletionGeneration()
		?: return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	dao.installCellDeletionFootprints(scope, expectedCollectedDataEpoch, generation, deletedAtMs)?.let {
		return it
	}
	deleteFencedCellScope(dao, scope, AmbientCellFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		expectedRevokedConsentEpoch,
		"AMBIENT_CELL_CONSENT_RESET",
		deletedAtMs,
	))
	check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1)
	return AmbientCellDeletionResult.Deleted(
		scope.localFacts.size.toLong(),
		scope.importedFacts.size.toLong(),
		scope.importedGaps.size.toLong(),
		generation,
	)
}

private suspend fun AppDatabase.cellConsentResetFailure(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
): AmbientCellMaintenanceUnavailableReason? {
	val state = sourceEvidenceStateDao().get()
		?: return AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE
	if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
		return AmbientCellMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
	}
	val authority = ambientCellFactDao().latestAuthority()
		?: return AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE
	return if (!AmbientCellAuthorityIntegrity.isAuthentic(authority) ||
		authority.state != AmbientCellAuthorityEntity.STATE_REVOKED ||
		authority.collectedDataEpoch != expectedCollectedDataEpoch ||
		authority.ambientConsentEpoch != expectedRevokedConsentEpoch
	) {
		AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE
	} else {
		cellConsentPolicyFailure() ?: cellAmbientProviderFailure()
	}
}

private suspend fun AppDatabase.cellConsentPolicyFailure(): AmbientCellMaintenanceUnavailableReason? {
	val policyAuthority = sourcePolicyDao().authority()
	val policy = policyAuthority?.takeIf {
		it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
	}?.let {
		sourcePolicyDao().policyAtRevision(
			it.currentPolicyRevision,
			SourceDestinationOwnerEntity.SOURCE_CELL,
		)
	}
	return if (policy == null || policy.ambientPersistenceEligible || policy.ambientConsentEpoch != null) {
		AmbientCellMaintenanceUnavailableReason.CONSENT_STILL_ACTIVE
	} else {
		null
	}
}

private suspend fun AppDatabase.cellAmbientProviderFailure(): AmbientCellMaintenanceUnavailableReason? {
	val activeDemands = sourceBrokerDao().activeDemandsBounded(
		SourceDestinationOwnerEntity.SOURCE_CELL,
		LIMIT + 1,
	)
	if (activeDemands.size > LIMIT) {
		return AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW
	}
	if (activeDemands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }) {
		return AmbientCellMaintenanceUnavailableReason.AMBIENT_DEMAND_ACTIVE
	}
	val registrations = sourceBrokerDao().currentPhysicalRegistrationsBounded(
		SourceDestinationOwnerEntity.SOURCE_CELL,
		MAX_REGISTRATIONS + 1,
	)
	if (registrations.size > MAX_REGISTRATIONS) {
		return AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW
	}
	return if (registrations.any { registration ->
		sourceBrokerDao().latestAuthorization(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			registration.registrationGeneration,
		).any {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible
		}
	}) {
		AmbientCellMaintenanceUnavailableReason.AMBIENT_PROVIDER_ACTIVE
	} else {
		null
	}
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
	if (!scope.isAuthentic) {
		return@withTransaction cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	}
	clearVerifiedCellScope(dao, scope, expectedCollectedDataEpoch, clearedAtMs)
}

// Reject missing or conflicting saved authority before replay fencing and payload deletion.
@Suppress("ReturnCount")
private suspend fun AppDatabase.clearVerifiedCellScope(
	dao: AmbientCellFactDao,
	scope: CellStoredScope,
	expectedCollectedDataEpoch: Long,
	clearedAtMs: Long,
): AmbientCellDeletionResult {
	val previous = dao.latestDeletionMarker(expectedCollectedDataEpoch)
	if (previous != null && !AmbientCellFactIntegrity.isAuthentic(previous)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val authority = dao.latestAuthority()
	if (authority != null && !AmbientCellAuthorityIntegrity.isAuthentic(authority)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val generation = previous.nextCellDeletionGeneration()
		?: return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	dao.installCellDeletionFootprints(scope, expectedCollectedDataEpoch, generation, clearedAtMs)?.let {
		return it
	}
	deleteFencedCellScope(dao, scope, AmbientCellFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		authority?.ambientConsentEpoch ?: 0L,
		"AMBIENT_CELL_FULL_CLEAR",
		clearedAtMs,
	))
	dao.deleteAllAuthorities()
	dao.deleteAllRetentionAuthorities()
	check(sourceEvidenceStateDao().incrementRevision(clearedAtMs) == 1)
	return AmbientCellDeletionResult.Deleted(
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
	if (!scope.isAuthentic) {
		return cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
	)
	}
	return dao.clearVerifiedCellScopeForFullClear(scope, expectedCollectedDataEpoch, clearedAtMs)
}

// Ordered authority failures precede any full-clear marker or payload mutation.
@Suppress("ReturnCount")
private fun AmbientCellFactDao.clearVerifiedCellScopeForFullClear(
	scope: CellStoredScope,
	expectedCollectedDataEpoch: Long,
	clearedAtMs: Long,
): AmbientCellDeletionResult {
	val previous = latestDeletionMarkerForFullClear(expectedCollectedDataEpoch)
	if (previous != null && !AmbientCellFactIntegrity.isAuthentic(previous)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val authority = latestAuthorityForFullClear()
	if (authority != null && !AmbientCellAuthorityIntegrity.isAuthentic(authority)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val generation = previous.nextCellDeletionGeneration()
		?: return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	installCellDeletionFootprintsForFullClear(scope, expectedCollectedDataEpoch, generation, clearedAtMs)?.let {
		return it
	}
	insertDeletionMarkerForFullClear(AmbientCellFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		generation,
		authority?.ambientConsentEpoch ?: 0L,
		"AMBIENT_CELL_FULL_CLEAR",
		clearedAtMs,
	))
	installCellArchiveTombstonesForFullClear(
		scope.archiveIds,
		expectedCollectedDataEpoch,
		generation,
		clearedAtMs,
	)
	deleteWholeCellPayloadForFullClear(scope.archiveIds)
	deleteAllAuthoritiesForFullClear()
	deleteAllRetentionAuthoritiesForFullClear()
	return AmbientCellDeletionResult.Deleted(
		scope.localFacts.size.toLong(),
		scope.importedFacts.size.toLong(),
		scope.importedGaps.size.toLong(),
		generation,
	)
}

private fun AmbientCellFactDao.installCellDeletionFootprintsForFullClear(
	scope: CellStoredScope,
	expectedCollectedDataEpoch: Long,
	generation: Long,
	clearedAtMs: Long,
): AmbientCellDeletionResult.Unavailable? {
	val footprints = scope.footprints(expectedCollectedDataEpoch, generation, clearedAtMs)
	if (footprints.size > LIMIT) {
		return cellDeletionUnavailable(
		AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
	)
	}
	if (!installVerifiedCellFootprintsForFullClear(footprints)) {
		return cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	return null
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
	) {
		return null
	}
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
	) {
		return null
	}
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
): List<AmbientCellReplayFootprintEntity> {
	fun footprint(kind: String, identity: String, revision: Long) =
		AmbientCellFactIntegrity.createReplayFootprint(
			kind,
			identity,
			revision,
			epoch,
			generation,
			recordedAtMs,
		)

	return localFacts.map { fact ->
		footprint(AmbientCellReplayFootprintEntity.KIND_LOCAL_FACT, fact.logicalFactId, fact.semanticRevision)
	} + localGaps.map { gap ->
		footprint(AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP, gap.gapId, 0L)
	} + importedFacts.flatMap { fact ->
		listOf(
			footprint(AmbientCellReplayFootprintEntity.KIND_FACT_IDENTITY, fact.factId, fact.semanticRevision),
			footprint(AmbientCellReplayFootprintEntity.KIND_FACT_EFFECT, fact.portableEffectChecksum, 0L),
		)
	} + importedGaps.flatMap { gap ->
		listOf(
			footprint(AmbientCellReplayFootprintEntity.KIND_GAP_IDENTITY, gap.gapId, 0L),
			footprint(AmbientCellReplayFootprintEntity.KIND_GAP_EFFECT, gap.portableEffectChecksum, 0L),
		)
	} + archiveIds.map { archiveId ->
		footprint(AmbientCellReplayFootprintEntity.KIND_ARCHIVE_SCOPE, archiveId, 0L)
	}
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
	if (values.isEmpty()) {
		return true
	}
	val identities = values.map(AmbientCellReplayFootprintEntity::identityDigest).distinct()
	val existing = loadCellReplayFootprints(identities)
	if (existing.size > LIMIT || existing.any { !AmbientCellFactIntegrity.isAuthentic(it) }) {
		return false
	}
	val expectedByKey = values.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	val existingByKey = existing.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	if (expectedByKey.any { (key, expected) ->
			existingByKey[key]?.let { it != expected } == true
		}
	) {
		return false
	}
	val missing = expectedByKey.filterKeys { it !in existingByKey }.values.toList()
	if (missing.isNotEmpty()) {
		insertReplayFootprints(missing)
	}
	val storedRows = loadCellReplayFootprints(identities)
	return if (storedRows.size > LIMIT ||
		storedRows.any { !AmbientCellFactIntegrity.isAuthentic(it) }
	) {
		false
	} else {
		val stored = storedRows.associateBy(AmbientCellReplayFootprintEntity::storageKey)
		expectedByKey.all { (key, expected) ->
			stored[key] == expected
		}
	}
}

private fun AmbientCellFactDao.installVerifiedCellFootprintsForFullClear(
	values: List<AmbientCellReplayFootprintEntity>,
): Boolean {
	if (values.isEmpty()) {
		return true
	}
	val identities = values.map(AmbientCellReplayFootprintEntity::identityDigest).distinct()
	val existing = loadCellReplayFootprintsForFullClear(identities)
	if (existing.size > LIMIT || existing.any { !AmbientCellFactIntegrity.isAuthentic(it) }) {
		return false
	}
	val expectedByKey = values.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	val existingByKey = existing.associateBy(AmbientCellReplayFootprintEntity::storageKey)
	if (expectedByKey.any { (key, expected) ->
			existingByKey[key]?.let { it != expected } == true
		}
	) {
		return false
	}
	val missing = expectedByKey.filterKeys { it !in existingByKey }.values.toList()
	if (missing.isNotEmpty()) {
		insertReplayFootprintsForFullClear(missing)
	}
	val storedRows = loadCellReplayFootprintsForFullClear(identities)
	return if (storedRows.size > LIMIT ||
		storedRows.any { !AmbientCellFactIntegrity.isAuthentic(it) }
	) {
		false
	} else {
		val stored = storedRows.associateBy(AmbientCellReplayFootprintEntity::storageKey)
		expectedByKey.all { (key, expected) -> stored[key] == expected }
	}
}

private suspend fun AmbientCellFactDao.loadCellReplayFootprints(
	identities: List<String>,
): List<AmbientCellReplayFootprintEntity> = buildList {
	identities.chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
		addAll(replayFootprintsByIdentityDigests(chunk, LIMIT + 1 - size))
		if (size > LIMIT) {
			return@buildList
		}
	}
}

private fun AmbientCellFactDao.loadCellReplayFootprintsForFullClear(
	identities: List<String>,
): List<AmbientCellReplayFootprintEntity> = buildList {
	identities.chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
		addAll(replayFootprintsByIdentityDigestsForFullClear(chunk, LIMIT + 1 - size))
		if (size > LIMIT) {
			return@buildList
		}
	}
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
