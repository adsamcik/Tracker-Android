package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AmbientWifiRetentionCommand(
	val retentionPolicyId: String,
	val retentionApprovalRevision: Long,
	val beforeMs: Long,
	val expectedCollectedDataEpoch: Long,
	val expectedAmbientConsentEpoch: Long,
	val appliedAtMs: Long,
) {
	init {
		require(retentionPolicyId.isNotBlank())
		require(retentionApprovalRevision > 0L)
		require(beforeMs >= 0L && expectedCollectedDataEpoch >= 0L)
		require(expectedAmbientConsentEpoch >= 0L && appliedAtMs >= beforeMs)
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
	RETENTION_BOUNDARY_MISMATCH,
	COLLECTED_DATA_EPOCH_CHANGED,
	CONSENT_STILL_ACTIVE,
	AMBIENT_DEMAND_ACTIVE,
	AMBIENT_PROVIDER_ACTIVE,
	DEPENDENCY_OVERFLOW,
	DELETION_GENERATION_EXHAUSTED,
}

/**
 * Source-specific Ambient Wi-Fi maintenance. It touches no captured Wi-Fi, CONTROL, or other-source
 * table. Callers provide an already-approved opaque cutoff; this code never invents a duration.
 */
suspend fun AppDatabase.pruneAmbientWifi(
	command: AmbientWifiRetentionCommand,
): AmbientWifiRetentionResult = withTransaction {
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction unavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != command.expectedCollectedDataEpoch) {
		return@withTransaction unavailable(
			AmbientWifiMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	if (state.retainedFromMs != command.beforeMs) {
		return@withTransaction unavailable(
			AmbientWifiMaintenanceUnavailableReason.RETENTION_BOUNDARY_MISMATCH,
		)
	}
	val dao = ambientWifiFactDao()
	val authority = dao.latestAuthority()
	if (authority?.isActive != true ||
		authority.collectedDataEpoch != command.expectedCollectedDataEpoch ||
		authority.ambientConsentEpoch != command.expectedAmbientConsentEpoch
	) {
		return@withTransaction unavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	if (authority.retentionPolicyId != command.retentionPolicyId ||
		authority.retentionApprovalRevision != command.retentionApprovalRevision
	) {
		return@withTransaction unavailable(
			AmbientWifiMaintenanceUnavailableReason.RETENTION_APPROVAL_MISMATCH,
		)
	}
	currentCoroutineContext().ensureActive()
	val localIds = dao.localFactIdsBefore(
		command.beforeMs,
		command.retentionPolicyId,
		MAINTENANCE_LIMIT + 1,
	)
	val archiveIds = dao.importedArchiveIdsBefore(
		command.beforeMs,
		command.retentionPolicyId,
		MAINTENANCE_LIMIT + 1,
	)
	if (localIds.size > MAINTENANCE_LIMIT || archiveIds.size > MAINTENANCE_LIMIT) {
		return@withTransaction unavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (localIds.isEmpty() && archiveIds.isEmpty()) {
		val gaps = dao.deleteGapsBefore(command.beforeMs)
		if (gaps == 0) return@withTransaction AmbientWifiRetentionResult.NoChange
		check(sourceEvidenceStateDao().incrementRevision(command.appliedAtMs) == 1)
		return@withTransaction AmbientWifiRetentionResult.Pruned(0, 0, gaps)
	}
	if (localIds.isNotEmpty()) {
		localIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
			dao.deleteLocalCursors(ids)
			dao.deleteLocalFacts(ids)
		}
	}
	if (archiveIds.isNotEmpty()) {
		archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
			dao.insertImportTombstones(ids.map { archiveId ->
				AmbientWifiFactIntegrity.createImportTombstone(
					archiveId,
					command.expectedCollectedDataEpoch,
					1L,
					command.appliedAtMs,
				)
			})
			dao.deleteImportReceipts(ids)
			dao.deleteImportedGaps(ids)
			dao.deleteImportedFacts(ids)
		}
	}
	val gaps = dao.deleteGapsBefore(command.beforeMs)
	check(sourceEvidenceStateDao().incrementRevision(command.appliedAtMs) == 1)
	AmbientWifiRetentionResult.Pruned(localIds.size, archiveIds.size, gaps)
}

/**
 * Installs source/import no-resurrection markers before cascading only Ambient Wi-Fi payload.
 */
suspend fun AppDatabase.deleteAmbientWifiAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): AmbientWifiDeletionResult = withTransaction {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
		return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val dao = ambientWifiFactDao()
	val authority = dao.latestAuthority()
	if (authority == null || authority.state != AmbientWifiAuthorityEntity.STATE_REVOKED ||
		authority.collectedDataEpoch != expectedCollectedDataEpoch ||
		authority.ambientConsentEpoch != expectedRevokedConsentEpoch
	) {
		return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
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
		return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.CONSENT_STILL_ACTIVE,
		)
	}
	val activeDemands = sourceBrokerDao().activeDemandsBounded(
		SourceDestinationOwnerEntity.SOURCE_WIFI,
		MAINTENANCE_LIMIT + 1,
	)
	if (activeDemands.size > MAINTENANCE_LIMIT) {
		return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (activeDemands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }) {
		return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.AMBIENT_DEMAND_ACTIVE,
		)
	}
	val registrations = sourceBrokerDao().currentPhysicalRegistrationsBounded(
		SourceDestinationOwnerEntity.SOURCE_WIFI,
		MAX_REGISTRATIONS + 1,
	)
	if (registrations.size > MAX_REGISTRATIONS) {
		return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	for (registration in registrations) {
		val members = sourceBrokerDao().latestAuthorization(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			registration.registrationGeneration,
		)
		if (members.any {
				it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible
			}
		) {
			return@withTransaction deletionUnavailable(
				AmbientWifiMaintenanceUnavailableReason.AMBIENT_PROVIDER_ACTIVE,
			)
		}
	}
	val localCount = dao.localFactCount()
	val importedCount = dao.importedFactCount()
	val importedGapCount = dao.importedGapCount()
	val archiveIds = (
		dao.allImportedArchiveIds(MAINTENANCE_LIMIT + 1) +
			dao.allImportReceiptArchiveIds(MAINTENANCE_LIMIT + 1)
	).distinct()
	if (archiveIds.size > MAINTENANCE_LIMIT) {
		return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	val previousGeneration = dao.latestDeletionMarker(expectedCollectedDataEpoch)
		?.deletionGeneration ?: 0L
	if (previousGeneration == Long.MAX_VALUE) {
		return@withTransaction deletionUnavailable(
			AmbientWifiMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	}
	if (localCount == 0L && importedCount == 0L && importedGapCount == 0L &&
		archiveIds.isEmpty()
	) {
		return@withTransaction AmbientWifiDeletionResult.AlreadyDeleted
	}
	val nextGeneration = previousGeneration + 1L
	dao.insertDeletionMarker(AmbientWifiFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		nextGeneration,
		expectedRevokedConsentEpoch,
		"AMBIENT_WIFI_CONSENT_RESET",
		deletedAtMs,
	))
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		dao.insertImportTombstones(ids.map { archiveId ->
			AmbientWifiFactIntegrity.createImportTombstone(
				archiveId,
				expectedCollectedDataEpoch,
				nextGeneration,
				deletedAtMs,
			)
		})
	}
	dao.deleteAllLocalCursors()
	dao.deleteAllLocalFacts()
	dao.deleteAllGaps()
	if (archiveIds.isNotEmpty()) {
		archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
			dao.deleteImportReceipts(ids)
			dao.deleteImportedGaps(ids)
			dao.deleteImportedFacts(ids)
		}
	}
	check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1)
	AmbientWifiDeletionResult.Deleted(
		localCount,
		importedCount,
		importedGapCount,
		nextGeneration,
	)
}

private fun unavailable(reason: AmbientWifiMaintenanceUnavailableReason) =
	AmbientWifiRetentionResult.Unavailable(reason)

private fun deletionUnavailable(reason: AmbientWifiMaintenanceUnavailableReason) =
	AmbientWifiDeletionResult.Unavailable(reason)

private const val MAINTENANCE_LIMIT = 16_384
private const val MAX_REGISTRATIONS = 32
private const val SQLITE_ID_CHUNK_SIZE = 400
