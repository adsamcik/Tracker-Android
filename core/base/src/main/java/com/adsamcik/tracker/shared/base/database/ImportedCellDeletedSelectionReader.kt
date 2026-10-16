package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveCompletenessOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveFactOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveRunOwner
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity

/**
 * Verifies the retained original Cell deletion receipt for the exact identity, revision and
 * checksum inside the caller's snapshot.
 */
suspend fun AppDatabase.authenticateDeletedImportedCellSelectionInTransaction(
	identity: PortableCellOpaqueIdentity,
	expectedImportRevision: Long,
	expectedContentChecksum: PortableCellDigest,
): DeletedImportedCellSelectionAuthentication {
	val state = sourceEvidenceStateDao().get()
	return if (state == null || !state.hasValidDeletedCellAuthorityShape()) {
		DeletedImportedCellSelectionAuthentication.Unverifiable
	} else {
		readDeletedCellSelectionHeader(identity, expectedImportRevision, expectedContentChecksum, state)
	}
}

private suspend fun AppDatabase.readDeletedCellSelectionHeader(
	identity: PortableCellOpaqueIdentity, expectedImportRevision: Long,
	expectedContentChecksum: PortableCellDigest, state: SourceEvidenceState,
): DeletedImportedCellSelectionAuthentication {
	val dao = importedCellDao()
	val deletion = dao.entryDeletion(identity.value)
	val receipt = dao.entryDeletionReceipt(identity.value)
	return when {
		deletion == null && receipt == null -> DeletedImportedCellSelectionAuthentication.Absent
		deletion == null || receipt == null -> DeletedImportedCellSelectionAuthentication.Unverifiable
		else -> readDeletedCellSelectionMarkers(identity, state, deletion, receipt,
			expectedImportRevision, expectedContentChecksum)
	}
}

private suspend fun AppDatabase.readDeletedCellSelectionMarkers(
	identity: PortableCellOpaqueIdentity, state: SourceEvidenceState,
	deletion: ImportedCellEntryDeletionEntity, receipt: ImportedCellEntryDeletionReceiptEntity,
	expectedImportRevision: Long, expectedContentChecksum: PortableCellDigest,
): DeletedImportedCellSelectionAuthentication {
	val dao = importedCellDao()
	if (!dao.hasExactDeletedCellReceiptHeader(identity.value, state, deletion, receipt)) {
		return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	val staleSelection = receipt.deletedImportRevision != expectedImportRevision ||
		receipt.deletedContentChecksum != expectedContentChecksum.value
	val (markers, markerByIdentity) = dao.readDeletedCellMarkerSet(identity.value, receipt)
		?: return DeletedImportedCellSelectionAuthentication.Unverifiable
	val runMarkers = dao.readDeletedCellRunMarkers(identity.value, receipt, markers)
		?: return DeletedImportedCellSelectionAuthentication.Unverifiable
	return when {
		!hasValidDeletedCellTopology(deletion, receipt, markers, runMarkers) ->
			DeletedImportedCellSelectionAuthentication.Unverifiable
		!dao.hasExclusiveDeletedCellIdentities(markerByIdentity, markers.size, state) ->
			DeletedImportedCellSelectionAuthentication.Unverifiable
		!dao.hasCompatibleDeletedCellLiveOwners(state, markerByIdentity, markers) ->
			DeletedImportedCellSelectionAuthentication.Unverifiable
		staleSelection -> DeletedImportedCellSelectionAuthentication.Stale
		else -> DeletedImportedCellSelectionAuthentication.Exact(receipt)
	}
}

private suspend fun ImportedCellDao.hasExactDeletedCellReceiptHeader(
	identity: String,
	state: SourceEvidenceState,
	deletion: ImportedCellEntryDeletionEntity,
	receipt: ImportedCellEntryDeletionReceiptEntity,
): Boolean = deletion.hasExactImportedCellDeletionReceipt(receipt) &&
	state.acceptsRetainedEpoch(deletion.collectedDataEpoch) &&
		receipt.retainedFromMs == state.retainedFromMs && hasNoDeletedCellPayload(identity)

private suspend fun ImportedCellDao.hasNoDeletedCellPayload(identity: String): Boolean =
	boundedEntryRevisions(identity).isEmpty() && receiptsForAdmission(identity).isEmpty() &&
	allRunsForAdmission(identity).isEmpty() && allObservationsForAdmission(identity).isEmpty()

private suspend fun ImportedCellDao.readDeletedCellMarkerSet(
	identity: String, receipt: ImportedCellEntryDeletionReceiptEntity,
): Pair<List<ImportedCellDeletedIdentityEntity>, Map<String, ImportedCellDeletedIdentityEntity>>? {
	val markers = deletedIdentitiesForEntry(identity, receipt.expectedProtectedIdentityCount + 1)
	if (markers.size != receipt.expectedProtectedIdentityCount ||
		ImportedCellEntryDeletionReceiptEntity.checksumProtectedIdentities(markers) !=
		receipt.protectedIdentitySetChecksum ||
		markers.filter { it.identityKind == ImportedCellDeletedIdentityEntity.ENTRY }
			.singleOrNull()?.protectedIdentity != identity
	) {
		return null
	}
	val markerByIdentity = markers.associateBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
	return if (markerByIdentity.size != markers.size) {
		null
	} else {
		markers to markerByIdentity
	}
}

private suspend fun ImportedCellDao.readDeletedCellRunMarkers(
	identity: String, receipt: ImportedCellEntryDeletionReceiptEntity,
	markers: List<ImportedCellDeletedIdentityEntity>,
): List<ImportedCellDeletionGenerationEntity>? {
	val runIdentities = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.RUN
	}.mapTo(linkedSetOf(), ImportedCellDeletedIdentityEntity::protectedIdentity)
	val runMarkers = deletionGenerationsForHistory(listOf(identity), runIdentities.size + 1)
	if (runMarkers.size != runIdentities.size ||
		runMarkers.mapTo(linkedSetOf(), ImportedCellDeletionGenerationEntity::runIdentity) != runIdentities
	) {
		return null
	}
	return runMarkers.takeUnless { rows -> rows.any {
		it.entryIdentity != identity || it.collectedDataEpoch != receipt.collectedDataEpoch || it.generation != 1L
	} }
}

private suspend fun ImportedCellDao.hasCompatibleDeletedCellLiveOwners(
	state: SourceEvidenceState,
	markerByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
	markers: List<ImportedCellDeletedIdentityEntity>,
): Boolean {
	val liveLimit = ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1
	val logicalIds = liveLogicalTrackingIds(liveLimit)
	val liveRuns = liveServiceRunOwners(liveLimit)
	val liveFacts = liveCellFactOwners(liveLimit)
	val liveCompleteness = liveCellCompletenessOwners(
		com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity.SOURCE_CELL,
		liveLimit,
	)
	val liveGenerations = liveCellDeletionGenerations(liveLimit)
	if (listOf(
			logicalIds.size,
			liveRuns.size,
			liveFacts.size,
			liveCompleteness.size,
			liveGenerations.size,
		).any {
			it > ImportedCellDao.MAX_LIVE_OWNER_ROWS
		}
	) {
		return false
	}
	if (!hasExactDeletedCellLogicalOwners(logicalIds, markerByIdentity) ||
		!hasExactDeletedCellLivePayloadOwners(liveRuns, liveCompleteness, liveFacts, markerByIdentity)
	) {
		return false
	}
	val scopeMarkers = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.DELETION_SCOPE
	}.associateBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
	val relevantLiveGenerations = matchingDeletedCellLiveGenerations(
		liveGenerations, state, markerByIdentity, scopeMarkers,
	)
		?: return false
	return hasExactDeletedCellSourceFences(scopeMarkers, relevantLiveGenerations, state)
}

private fun hasExactDeletedCellLivePayloadOwners(
	liveRuns: List<ImportedCellLiveRunOwner>,
	liveCompleteness: List<ImportedCellLiveCompletenessOwner>,
	liveFacts: List<ImportedCellLiveFactOwner>,
	markerByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
): Boolean {
	val liveRunById = liveRuns.associateBy { it.serviceRunId }
	if (liveRunById.size != liveRuns.size) {
		return false
	}
	if (!hasExactDeletedCellRunOwners(liveRuns, markerByIdentity)) {
		return false
	}
	return liveCompleteness.all { row ->
		val run = liveRunById[row.serviceRunId] ?: return@all false
		run.logicalTrackingId == row.logicalTrackingId
	} && hasExactDeletedCellFactOwners(liveFacts, liveRunById, markerByIdentity)
}


private suspend fun ImportedCellDao.hasExclusiveDeletedCellIdentities(
	markerByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
	markerCount: Int,
	state: SourceEvidenceState,
): Boolean {
	for (identities in markerByIdentity.keys.chunked(ImportedCellDao.MAX_IDENTITY_QUERY_CHUNK)) {
		val limit = identities.size + 1
		if (!hasNoConflictingDeletedCellPayloadOwners(identities, limit, markerCount, markerByIdentity)) {
			return false
		}
		val sourceFences = sourceFenceOwners(identities, limit)
		if (sourceFences.size >= limit || sourceFences.any { fence ->
			val marker = markerByIdentity[fence.scopeIdentityDigest]
			marker?.identityKind != ImportedCellDeletedIdentityEntity.DELETION_SCOPE ||
				!fence.hasDeletedCellScopeShape(state)
		}) {
			return false
		}
	}
	return true
}

private fun hasExactDeletedCellLogicalOwners(
	logicalIds: List<String>,
	markerByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
): Boolean {
	val logicalByEntry = logicalIds.associateBy { logicalId ->
		PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			logicalId,
		).value
	}
	for ((entryIdentity, _) in logicalByEntry) {
		val marker = markerByIdentity[entryIdentity] ?: continue
		if (marker.identityKind != ImportedCellDeletedIdentityEntity.ENTRY ||
			marker.entryIdentity != entryIdentity
		) {
			return false
		}
	}
	return true
}

private fun hasExactDeletedCellRunOwners(
	liveRuns: List<ImportedCellLiveRunOwner>,
	markerByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
): Boolean {
	for (run in liveRuns) {
		val entryIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			run.logicalTrackingId,
		).value
		val runIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.PHYSICAL_RUN,
			run.serviceRunId,
		).value
		val scope = PortableCellDeletionScopeDigest.derive(
			run.logicalTrackingId,
			run.serviceRunId,
		).value
		val intersections = listOf(entryIdentity, runIdentity, scope)
			.mapNotNull(markerByIdentity::get)
		if (intersections.any { !it.matchesDeletedCellRunOwner(entryIdentity, runIdentity, scope) }) {
			return false
		}
	}
	return true
}

private fun ImportedCellDeletedIdentityEntity.matchesDeletedCellRunOwner(
	entryIdentity: String,
	runIdentity: String,
	scope: String,
): Boolean = when (identityKind) {
	ImportedCellDeletedIdentityEntity.ENTRY -> protectedIdentity == entryIdentity
	ImportedCellDeletedIdentityEntity.RUN -> protectedIdentity == runIdentity &&
		this.entryIdentity == entryIdentity && this.runIdentity == runIdentity
	ImportedCellDeletedIdentityEntity.DELETION_SCOPE -> protectedIdentity == scope &&
		this.entryIdentity == entryIdentity && this.runIdentity == runIdentity
	else -> false
}

private fun hasExactDeletedCellFactOwners(
	liveFacts: List<ImportedCellLiveFactOwner>,
	liveRunById: Map<String, ImportedCellLiveRunOwner>,
	markerByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
): Boolean {
	for (fact in liveFacts) {
		val run = liveRunById[fact.serviceRunId]
			?: return false
		if (run.logicalTrackingId != fact.logicalTrackingId) {
			return false
		}
		val entryIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			fact.logicalTrackingId,
		).value
		val runIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.PHYSICAL_RUN,
			fact.serviceRunId,
		).value
		val observationIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.OBSERVATION,
			fact.logicalFactId,
		).value
		val ownerIdentity = fact.aggregateOwnerLogicalFactId?.let { owner ->
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.OBSERVATION,
				owner,
			).value
		}
		val direct = markerByIdentity[observationIdentity]
		val owner = ownerIdentity?.let(markerByIdentity::get)
		// Present direct and aggregate-owner markers must match their fixed observation identities.
		if (!direct.matchesDeletedCellObservationOwner(entryIdentity, runIdentity, ownerIdentity) ||
			!owner.matchesDeletedCellObservationOwner(entryIdentity, runIdentity, null)
		) {
			return false
		}
	}
	return true
}

private fun ImportedCellDeletedIdentityEntity?.matchesDeletedCellObservationOwner(
	expectedEntryIdentity: String,
	expectedRunIdentity: String,
	expectedAggregateOwnerIdentity: String?,
): Boolean = this == null || (identityKind == ImportedCellDeletedIdentityEntity.OBSERVATION &&
	entryIdentity == expectedEntryIdentity && runIdentity == expectedRunIdentity &&
		aggregateOwnerIdentity == expectedAggregateOwnerIdentity)

private fun matchingDeletedCellLiveGenerations(
	liveGenerations: List<CellCaptureDeletionGenerationEntity>,
	state: SourceEvidenceState,
	markerByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
	scopeMarkers: Map<String, ImportedCellDeletedIdentityEntity>,
): Map<String, Long>? {
	val relevantLiveGenerations = linkedMapOf<String, Long>()
	for (generation in liveGenerations) {
		if (!state.acceptsRetainedEpoch(generation.collectedDataEpoch) ||
			generation.logicalTrackingId.isBlank() || generation.serviceRunId.isBlank() ||
			generation.generation <= 0L
		) {
			return null
		}
		val entryIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			generation.logicalTrackingId,
		).value
		val runIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.PHYSICAL_RUN,
			generation.serviceRunId,
		).value
		val scope = PortableCellDeletionScopeDigest.derive(
			generation.logicalTrackingId,
			generation.serviceRunId,
		).value
		if (listOf(entryIdentity, runIdentity, scope).none(markerByIdentity::containsKey)) {
			continue
		}
		val runMarker = markerByIdentity[runIdentity]
		val scopeMarker = scopeMarkers[scope]
		if (!hasExactDeletedCellGenerationMarkers(runMarker, scopeMarker, entryIdentity, runIdentity, scope)
		) {
			return null
		}
		if (relevantLiveGenerations.put(scope, generation.generation) != null) {
			return null
		}
	}
	return relevantLiveGenerations
}

private suspend fun ImportedCellDao.hasExactDeletedCellSourceFences(
	scopeMarkers: Map<String, ImportedCellDeletedIdentityEntity>,
	relevantLiveGenerations: Map<String, Long>,
	state: SourceEvidenceState,
): Boolean {
	val selectedScopes = scopeMarkers.keys.toList()
	val sourceFences = selectedScopes.chunked(ImportedCellDao.MAX_IDENTITY_QUERY_CHUNK)
		.flatMap { scopes -> sourceFenceOwners(scopes, scopes.size + 1) }
	if (sourceFences.distinctBy { it.scopeIdentityDigest }.size != sourceFences.size ||
		sourceFences.any { fence ->
			fence.scopeIdentityDigest !in scopeMarkers ||
				!fence.hasDeletedCellScopeShape(state) || fence.fenceGeneration <= 0L
		}
	) {
		return false
	}
	val fenceGenerations = sourceFences.associate {
		it.scopeIdentityDigest to it.fenceGeneration
	}
	if (fenceGenerations != relevantLiveGenerations) {
		return false
	}
	return true
}

private fun SourceEvidenceState.hasValidDeletedCellAuthorityShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L


private suspend fun ImportedCellDao.hasNoConflictingDeletedCellPayloadOwners(
	identities: List<String>, limit: Int, markerCount: Int,
	markerByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
): Boolean = existingEntryIdentities(identities, limit).isEmpty() &&
	existingRunIdentityOwners(identities, limit).isEmpty() && existingRunScopeOwners(identities, limit).isEmpty() &&
	existingObservationIdentityOwners(identities, markerCount + 1).isEmpty() &&
	deletedIdentityOwners(identities, markerCount + 1).none { markerByIdentity[it.protectedIdentity] != it }

private fun SourceDeletionFenceEntity.hasDeletedCellScopeShape(state: SourceEvidenceState): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL && purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
	scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN && state.acceptsRetainedEpoch(collectedDataEpoch)

private fun hasExactDeletedCellGenerationMarkers(
	runMarker: ImportedCellDeletedIdentityEntity?, scopeMarker: ImportedCellDeletedIdentityEntity?,
	entryIdentity: String, runIdentity: String, scope: String,
): Boolean = !(runMarker == null || scopeMarker == null ||
	runMarker.identityKind != ImportedCellDeletedIdentityEntity.RUN || runMarker.entryIdentity != entryIdentity ||
	runMarker.deletionScopeDigest != scope || scopeMarker.entryIdentity != entryIdentity ||
	scopeMarker.runIdentity != runIdentity)
