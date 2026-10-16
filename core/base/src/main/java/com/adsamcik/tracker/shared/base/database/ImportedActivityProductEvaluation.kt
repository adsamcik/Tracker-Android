package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.IMPORTED_ACTIVITY_CANDIDATE_LIVE
import com.adsamcik.tracker.shared.base.database.dao.IMPORTED_ACTIVITY_CANDIDATE_RETAINED
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProductBatchPreflight
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun AppDatabase.evaluateImportedActivityProductBatch(
	candidates: List<ImportedActivityHistoryCandidate>,
	retentionLimits: ImportedActivityRetentionLimits?,
	scanBudget: ImportedActivityProductScanBudget? = null,
): List<ImportedActivityProductEvaluation> {
	if (candidates.isEmpty()) {
		return emptyList()
	}
	scanBudget?.consumeQuery()
	val state = sourceEvidenceStateDao().get()
		?: return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
	return evaluateImportedActivityProductState(candidates, state, retentionLimits, scanBudget)
}

private suspend fun AppDatabase.evaluateImportedActivityProductState(
	candidates: List<ImportedActivityHistoryCandidate>,
	state: SourceEvidenceState,
	retentionLimits: ImportedActivityRetentionLimits?,
	scanBudget: ImportedActivityProductScanBudget?,
): List<ImportedActivityProductEvaluation> {
	if (state.collectedDataEpoch < 0L || state.retainedFromMs?.let { it < 0L } == true) {
		return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	}
	val dao = importedActivityDao()
	val candidateIdentities = candidates.map(ImportedActivityHistoryCandidate::identity)
	val liveCandidates = candidates.filter {
		it.candidateState == IMPORTED_ACTIVITY_CANDIDATE_LIVE
	}
	val retainedCandidates = candidates.filter {
		it.candidateState == IMPORTED_ACTIVITY_CANDIDATE_RETAINED
	}
	val preflight = scanBudget?.let { budget ->
		budget.consumeQuery()
		val value = dao.productBatchPreflight(
			identities = candidateIdentities,
			activitySourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			sessionCapturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			logicalRunScopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		)
		val refusal = value.importedActivityPreflightFailure(liveCandidates.size, retainedCandidates.size, retentionLimits)
		if (refusal != null) {
			return candidates.unverifiableImportedActivityProduct(refusal)
		}
		budget.reserveProductBatch(value, candidates.size)
		value
	}
	val retentionReceipts = dao.readImportedActivityProductRetentionReceipts(candidateIdentities, preflight, scanBudget)
		?: return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	return evaluateImportedActivityRetainedRows(
		candidates, state, retentionLimits, scanBudget, preflight, retentionReceipts,
	)
}

private fun ImportedActivityProductBatchPreflight.importedActivityPreflightFailure(
	liveCount: Int,
	retainedCount: Int,
	retentionLimits: ImportedActivityRetentionLimits?,
): ImportedActivityProductFailure? = when {
	latestEntryCount != liveCount.toLong() || retentionReceiptCount != retainedCount.toLong() ->
		ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT
	exceedsBatchLimit(liveCount, retentionLimits) -> ImportedActivityProductFailure.DEPENDENCY_OVERFLOW
	else -> null
}

private suspend fun AppDatabase.evaluateImportedActivityRetainedRows(
	candidates: List<ImportedActivityHistoryCandidate>,
	state: SourceEvidenceState,
	retentionLimits: ImportedActivityRetentionLimits?,
	scanBudget: ImportedActivityProductScanBudget?,
	preflight: ImportedActivityProductBatchPreflight?,
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
): List<ImportedActivityProductEvaluation> {
	if (retentionReceipts.distinctBy { it.entryIdentity }.size != retentionReceipts.size) {
		return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	}
	val retentionByIdentity = retentionReceipts.associateBy { it.entryIdentity }
	if (candidates.any { candidate ->
		(candidate.candidateState == IMPORTED_ACTIVITY_CANDIDATE_RETAINED) !=
			(candidate.identity in retentionByIdentity)
	}) {
		return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
	}
	val expectedRetainedMarkerCount = retentionReceipts.sumOf { it.protectedIdentityCount }
	val retainedMarkers = importedActivityDao().readImportedActivityProductRetainedMarkers(
		retentionReceipts, expectedRetainedMarkerCount, scanBudget,
	) ?: return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	return if (retainedMarkers.size != expectedRetainedMarkerCount ||
		preflight != null && retainedMarkers.size.toLong() != preflight.retainedIdentityCount
	) {
		candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	} else {
		evaluateImportedActivityRetainedProducts(
			candidates, state, retentionLimits, scanBudget, preflight, retentionReceipts, retainedMarkers,
		)
	}
}

// Preserve original captured source rows, preflight and budget rather than rereading authority or adding a holder.
@Suppress("LongParameterList")
private suspend fun AppDatabase.evaluateImportedActivityRetainedProducts(
	candidates: List<ImportedActivityHistoryCandidate>,
	state: SourceEvidenceState,
	retentionLimits: ImportedActivityRetentionLimits?,
	scanBudget: ImportedActivityProductScanBudget?,
	preflight: ImportedActivityProductBatchPreflight?,
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
	retainedMarkers: List<ImportedActivityRetainedIdentityEntity>,
): List<ImportedActivityProductEvaluation> {
	val retentionFailure = importedActivityProductRetentionFailure(state, retentionReceipts, scanBudget)
	retentionFailure?.toImportedActivityProductFailure()?.let { return candidates.unverifiableImportedActivityProduct(it) }
	val retainedEvaluations = retainedImportedActivityProducts(retentionReceipts, retainedMarkers)
	val liveCandidates = candidates.filter { it.candidateState == IMPORTED_ACTIVITY_CANDIDATE_LIVE }
	if (liveCandidates.isEmpty()) {
		return candidates.map { requireNotNull(retainedEvaluations[it.identity]) }
	}
	return evaluateImportedActivityLiveProducts(
		candidates, state, retentionLimits, scanBudget, preflight, retentionReceipts, retainedMarkers, retainedEvaluations,
	)
}

private fun ImportedActivityRetentionAuthorityFailure.toImportedActivityProductFailure():
	ImportedActivityProductFailure =
	when (this) {
		ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE ->
			ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE
		ImportedActivityRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT ->
			ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT
		ImportedActivityRetentionAuthorityFailure.DEPENDENCY_OVERFLOW -> ImportedActivityProductFailure.DEPENDENCY_OVERFLOW
	}

private fun retainedImportedActivityProducts(
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
	retainedMarkers: List<ImportedActivityRetainedIdentityEntity>,
): Map<String, ImportedActivityProductEvaluation.Retained> {
	val retainedMarkersByEntry = retainedMarkers.groupBy(ImportedActivityRetainedIdentityEntity::entryIdentity)
	return retentionReceipts.associate { receipt ->
		val temporalAuthorityAvailable = receipt.temporalAuthorityState ==
			ImportedActivityRetentionReceiptEntity.TEMPORAL_AUTHORITY_AVAILABLE
		receipt.entryIdentity to ImportedActivityProductEvaluation.Retained(
			candidate = receipt.toHistoryCandidate(),
			retainedFromMs = receipt.retainedFromMs,
			retainedAtMs = receipt.retainedAtMs,
			latestMemberStartTimeMs = receipt.latestMemberStartTimeMs,
			latestMemberIdentity = receipt.latestMemberIdentity?.let(
				::PortableActivityOpaqueIdentity,
			),
			structuralZoneRanges = receipt.structuralZoneRanges(),
			structuralZoneCoverageComplete = receipt.structuralZoneCoverageComplete,
			hasTemporalAuthority = temporalAuthorityAvailable,
			protectedIdentities = retainedMarkersByEntry[receipt.entryIdentity].orEmpty()
				.map(ImportedActivityRetainedIdentityEntity::toProductIdentity),
		)
	}

}

// Keep the already captured rows and product map with their original limits/budget through the live read.
@Suppress("LongParameterList")
private suspend fun AppDatabase.evaluateImportedActivityLiveProducts(
	candidates: List<ImportedActivityHistoryCandidate>,
	state: SourceEvidenceState,
	retentionLimits: ImportedActivityRetentionLimits?,
	scanBudget: ImportedActivityProductScanBudget?,
	preflight: ImportedActivityProductBatchPreflight?,
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
	retainedMarkers: List<ImportedActivityRetainedIdentityEntity>,
	retainedEvaluations: Map<String, ImportedActivityProductEvaluation.Retained>,
): List<ImportedActivityProductEvaluation> {
	val identities = candidates.filter { it.candidateState == IMPORTED_ACTIVITY_CANDIDATE_LIVE }
		.map(ImportedActivityHistoryCandidate::identity)
	val loaded = readLiveImportedActivityProductBatch(
		importedActivityDao(), identities, preflight, retentionLimits, scanBudget,
	)
		?: return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	if (loaded.exceedsBatchLimit(identities.size, retentionLimits)) {
		return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
	}
	if (!loaded.belongsOnlyTo(identities)) {
		return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
	}
	return evaluateImportedActivityDeletionRows(
		candidates, state, scanBudget, preflight, retentionReceipts, retainedMarkers, retainedEvaluations, loaded,
	)
}

// The same live batch, retained rows and captured source state remain explicit across deletion reads.
@Suppress("LongParameterList")
private suspend fun AppDatabase.evaluateImportedActivityDeletionRows(
	candidates: List<ImportedActivityHistoryCandidate>,
	state: SourceEvidenceState,
	scanBudget: ImportedActivityProductScanBudget?,
	preflight: ImportedActivityProductBatchPreflight?,
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
	retainedMarkers: List<ImportedActivityRetainedIdentityEntity>,
	retainedEvaluations: Map<String, ImportedActivityProductEvaluation.Retained>,
	loaded: ImportedActivityProductBatch,
): List<ImportedActivityProductEvaluation> {
	val dao = importedActivityDao()
	val candidateIdentities = candidates.map(ImportedActivityHistoryCandidate::identity)
	val runIdentities = retainedMarkers.withLiveImportedActivityIdentities(
		ImportedActivityRetainedIdentityEntity.RUN, loaded.runs.map(ImportedActivityRunEntity::identity),
	)
	val deletionScopes = retainedMarkers.withLiveImportedActivityIdentities(
		ImportedActivityRetainedIdentityEntity.DELETION_SCOPE,
		loaded.runs.map(ImportedActivityRunEntity::deletionScopeDigest),
	)
	val entryDeletions: List<ImportedActivityEntryDeletionEntity>
	val entryDeletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>
	val runDeletions: List<ImportedActivityDeletionGenerationEntity>
	val sourceDeletions: List<SourceDeletionFenceEntity>
	try {
		entryDeletions = loadOptionalImportedActivityRows(preflight?.entryDeletionCount, scanBudget,
			{ dao.entryDeletionsForHistory(candidateIdentities) },
			{ dao.entryDeletionsForBoundedHistory(candidateIdentities, it) })
		entryDeletionReceipts = loadOptionalImportedActivityRows(preflight?.entryDeletionReceiptCount, scanBudget,
			{ dao.entryDeletionReceipts(candidateIdentities) },
			{ dao.entryDeletionReceiptsForBoundedHistory(candidateIdentities, it) })
		runDeletions = loadOptionalChunkedImportedActivityRows(runIdentities, preflight?.runDeletionCount, scanBudget,
			{ dao.deletionGenerationsForHistory(runIdentities) },
			{ batch, limit -> dao.boundedDeletionGenerations(batch, limit) })
		sourceDeletions = loadOptionalChunkedImportedActivityRows(deletionScopes, preflight?.sourceFenceCount, scanBudget,
			{ deletionScopes.chunked(IMPORTED_ACTIVITY_SQLITE_BIND_BATCH).flatMap { scopes ->
				trackingHistoryReadDao().deletionFences(SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
					SessionManifestPurposeCode.SESSION_CAPTURE, SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, scopes)
			} },
			{ batch, limit -> dao.boundedActivitySourceFences(batch, SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				SessionManifestPurposeCode.SESSION_CAPTURE, SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, limit) })
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: ImportedActivityProductScanBudgetExceeded) {
		throw failure
	} catch (_: RuntimeException) {
		return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	}
	val refusal = importedActivityDeletionAuthorityFailure(state, loaded, retentionReceipts, retainedMarkers,
		entryDeletions, entryDeletionReceipts, runDeletions, sourceDeletions, scanBudget)
	return if (refusal != null) {
		candidates.unverifiableImportedActivityProduct(refusal)
	} else {
		val liveCandidates = candidates.filter { it.candidateState == IMPORTED_ACTIVITY_CANDIDATE_LIVE }
		val liveEvaluations = loaded.evaluateLiveImportedActivityProducts(
			liveCandidates, state, entryDeletions, runDeletions, sourceDeletions,
		)
		candidates.map { retainedEvaluations[it.identity] ?: requireNotNull(liveEvaluations[it.identity]) }
	}
}

private fun List<ImportedActivityRetainedIdentityEntity>.withLiveImportedActivityIdentities(
	kind: String,
	live: List<String>,
): List<String> = (live + filter { it.identityKind == kind }
	.map(ImportedActivityRetainedIdentityEntity::protectedIdentity)).distinct()

// Compare the exact captured retained/deletion rows together; no authority is reread or wrapped in a new model.
@Suppress("LongParameterList")
private suspend fun AppDatabase.importedActivityDeletionAuthorityFailure(
	state: SourceEvidenceState,
	loaded: ImportedActivityProductBatch,
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
	retainedMarkers: List<ImportedActivityRetainedIdentityEntity>,
	entryDeletions: List<ImportedActivityEntryDeletionEntity>,
	entryDeletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>,
	runDeletions: List<ImportedActivityDeletionGenerationEntity>,
	sourceDeletions: List<SourceDeletionFenceEntity>,
	scanBudget: ImportedActivityProductScanBudget?,
): ImportedActivityProductFailure? {
	if (!hasValidImportedActivityDeletionRows(
			state, entryDeletions, entryDeletionReceipts, runDeletions, sourceDeletions,
		)
	) {
		return ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE
	}
	val hasExactOwnership = try {
		val expected = linkedMapOf<ImportedActivityOwnerAuditKey, Long>()
		expected.includeImportedActivityEntryOwners(loaded)
		expected.includeImportedActivityRetainedOwners(retentionReceipts, retainedMarkers)
		expected.includeImportedActivityChildOwners(loaded)
		expected.includeImportedActivityDeletionOwners(entryDeletions, entryDeletionReceipts, runDeletions, sourceDeletions)
		hasExactImportedActivityOwnerCounts(expected, scanBudget)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: ImportedActivityProductScanBudgetExceeded) {
		throw failure
	} catch (_: RuntimeException) {
		return ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE
	}
	return ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT.takeUnless { hasExactOwnership }
}

private fun hasValidImportedActivityDeletionRows(
	state: SourceEvidenceState,
	entryDeletions: List<ImportedActivityEntryDeletionEntity>,
	entryDeletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>,
	runDeletions: List<ImportedActivityDeletionGenerationEntity>,
	sourceDeletions: List<SourceDeletionFenceEntity>,
): Boolean {
	if (entryDeletions.distinctBy(ImportedActivityEntryDeletionEntity::entryIdentity).size != entryDeletions.size ||
		entryDeletionReceipts.distinctBy { it.entryIdentity }.size != entryDeletionReceipts.size ||
		runDeletions.distinctBy(ImportedActivityDeletionGenerationEntity::runIdentity).size != runDeletions.size
	) {
		return false
	}
	return entryDeletions.all { state.acceptsRetainedEpoch(it.collectedDataEpoch) } &&
		hasExactImportedActivityDeletionReceipts(entryDeletions, entryDeletionReceipts) &&
		runDeletions.all { state.acceptsRetainedEpoch(it.collectedDataEpoch) } &&
		sourceDeletions.all { state.acceptsRetainedEpoch(it.collectedDataEpoch) }
}

private fun hasExactImportedActivityDeletionReceipts(
	entryDeletions: List<ImportedActivityEntryDeletionEntity>,
	entryDeletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>,
): Boolean = entryDeletionReceipts.all { receipt ->
	val deletion = entryDeletions.singleOrNull { it.entryIdentity == receipt.entryIdentity }
	deletion != null && receipt.collectedDataEpoch == deletion.collectedDataEpoch &&
		receipt.deletedImportRevision == deletion.deletedImportRevision && receipt.deletedAtMs == deletion.deletedAtMs
}

private suspend fun ImportedActivityProductBatch.evaluateLiveImportedActivityProducts(
	liveCandidates: List<ImportedActivityHistoryCandidate>,
	state: SourceEvidenceState,
	entryDeletions: List<ImportedActivityEntryDeletionEntity>,
	runDeletions: List<ImportedActivityDeletionGenerationEntity>,
	sourceDeletions: List<SourceDeletionFenceEntity>,
): Map<String, ImportedActivityProductEvaluation> {
	val headersByIdentity = headers.groupBy(ImportedActivityEntryRevisionEntity::identity)
	val receiptsByIdentity = receipts.groupBy(ImportedActivityReceiptEntity::entryIdentity)
	val runsByIdentity = runs.groupBy(ImportedActivityRunEntity::entryIdentity)
	val zonesByIdentity = zoneEpochs.groupBy(ImportedActivityZoneEpochEntity::entryIdentity)
	val windowsByIdentity = windows.groupBy(ImportedActivityWindowEntity::entryIdentity)
	val fragmentsByIdentity = fragments.groupBy(ImportedActivityFragmentEntity::entryIdentity)
	val entryDeletionsByIdentity = entryDeletions.associateBy(ImportedActivityEntryDeletionEntity::entryIdentity)
	val deletedRunIdentities = runDeletions.mapTo(hashSetOf(), ImportedActivityDeletionGenerationEntity::runIdentity)
	val deletedSourceScopes = sourceDeletions.mapTo(hashSetOf(), SourceDeletionFenceEntity::scopeIdentityDigest)
	return liveCandidates.map { candidate ->
		currentCoroutineContext().ensureActive()
		val batch = ImportedActivityProductBatch(
			headersByIdentity[candidate.identity].orEmpty(), receiptsByIdentity[candidate.identity].orEmpty(),
			runsByIdentity[candidate.identity].orEmpty(), zonesByIdentity[candidate.identity].orEmpty(),
			windowsByIdentity[candidate.identity].orEmpty(), fragmentsByIdentity[candidate.identity].orEmpty(),
		)
		candidate.evaluateImportedActivityProduct(
			state, batch, entryDeletionsByIdentity[candidate.identity], deletedRunIdentities, deletedSourceScopes,
		)
	}.associateBy { it.candidate.identity }
}

private fun ImportedActivityHistoryCandidate.evaluateImportedActivityProduct(
	state: SourceEvidenceState,
	batch: ImportedActivityProductBatch,
	entryDeletion: ImportedActivityEntryDeletionEntity?,
	deletedRunIdentities: Set<String>,
	deletedSourceScopes: Set<String>,
): ImportedActivityProductEvaluation {
	val headers = batch.headers
	if (headers.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) }) {
		return unverifiableImportedActivityProduct(ImportedActivityProductFailure.STALE_COLLECTED_DATA_EPOCH)
	}
	return try {
		val lineage = ImportedActivityLineageAuthenticator.authenticate(
			identity = identity,
			expectedCollectedDataEpoch = state.collectedDataEpoch,
			retainedState = state,
			headers = headers,
			receipts = batch.receipts,
			runs = batch.runs,
			zoneEpochs = batch.zoneEpochs,
			windows = batch.windows,
			fragments = batch.fragments,
		)
		val latest = lineage.revisions.lastOrNull()
			?: return unverifiableImportedActivityProduct(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		if (!matchesLatestImportedActivityProduct(latest, entryDeletion)) {
			return unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val latestRuns = batch.runs
			.filter { it.entryImportRevision == latest.header.importRevision }
		val selectedDeletedRunIdentities = latestRuns.filterTo(linkedSetOf()) { run ->
			run.identity in deletedRunIdentities || run.deletionScopeDigest in deletedSourceScopes
		}.mapTo(linkedSetOf(), ImportedActivityRunEntity::identity)
		ImportedActivityProductEvaluation.Readable(
			candidate = this,
			entry = latest.entry,
			entryDeleted = entryDeletion != null,
			deletedRunIdentities = selectedDeletedRunIdentities,
			retainedFromMs = state.retainedFromMs,
			retentionLimited = state.retainedFromMs?.let { retainedFromMs ->
				lineage.revisions.any { it.entry.crossesRetentionBoundary(retainedFromMs) }
			} == true,
		)
	} catch (failure: ImportedActivityLineageFailure) {
		unverifiableImportedActivityProduct(failure.reason.toImportedActivityProductFailure())
	} catch (_: IllegalArgumentException) {
		unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	} catch (_: ArithmeticException) {
		unverifiableImportedActivityProduct(ImportedActivityProductFailure.VALUE_OVERFLOW)
	}
}

private fun ImportedActivityHistoryCandidate.matchesLatestImportedActivityProduct(
	latest: AuthenticatedImportedActivityRevision,
	entryDeletion: ImportedActivityEntryDeletionEntity?,
): Boolean {
	if (identity != latest.header.identity || importRevision != latest.header.importRevision ||
		contentChecksum != latest.header.contentChecksum
	) {
		return false
	}
	if (startTimeMs != latest.header.startTimeMs || endTimeMs != latest.header.endTimeMs ||
		receivedAtMs != latest.header.receivedAtMs
	) {
		return false
	}
	return entryDeletion == null || entryDeletion.deletedImportRevision == latest.header.importRevision
}

private fun ImportedActivityRetentionReceiptEntity.toHistoryCandidate() =
	ImportedActivityHistoryCandidate(
		identity = entryIdentity,
		importRevision = latestImportRevision,
		contentChecksum = latestContentChecksum,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		receivedAtMs = receivedAtMs,
		candidateState = IMPORTED_ACTIVITY_CANDIDATE_RETAINED,
	)

private fun ImportedActivityRetainedIdentityEntity.toProductIdentity(): RetainedImportedActivityIdentity =
	when (identityKind) {
		ImportedActivityRetainedIdentityEntity.ENTRY -> RetainedImportedActivityIdentity.Entry(
			PortableActivityOpaqueIdentity(protectedIdentity),
		)
		ImportedActivityRetainedIdentityEntity.RUN -> RetainedImportedActivityIdentity.Run(
			PortableActivityOpaqueIdentity(protectedIdentity),
		)
		ImportedActivityRetainedIdentityEntity.WINDOW -> RetainedImportedActivityIdentity.Window(
			PortableActivityOpaqueIdentity(protectedIdentity),
		)
		ImportedActivityRetainedIdentityEntity.DELETION_SCOPE ->
			RetainedImportedActivityIdentity.DeletionScope(
				PortableActivityDeletionScopeDigest(protectedIdentity),
			)
		else -> error("Unknown imported Activity retained identity kind")
	}

private fun ImportedActivityLineageAuthenticator.Reason.toImportedActivityProductFailure():
	ImportedActivityProductFailure =
	when (this) {
		ImportedActivityLineageAuthenticator.Reason.DEPENDENCY_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.RUN_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.WINDOW_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.FRAGMENT_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.ZONE_EPOCH_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.REVISION_OVERFLOW,
		-> ImportedActivityProductFailure.DEPENDENCY_OVERFLOW
		ImportedActivityLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE ->
			ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE
	}

internal data class ImportedActivityProductBatch(
	val headers: List<ImportedActivityEntryRevisionEntity>,
	val receipts: List<ImportedActivityReceiptEntity>,
	val runs: List<ImportedActivityRunEntity>,
	val zoneEpochs: List<ImportedActivityZoneEpochEntity>,
	val windows: List<ImportedActivityWindowEntity>,
	val fragments: List<ImportedActivityFragmentEntity>,
) {
	// Keep the fixed row-count limits together; this reads sizes without traversing or loading rows.
	@Suppress("CyclomaticComplexMethod")
	fun exceedsBatchLimit(
		identityCount: Int,
		retentionLimits: ImportedActivityRetentionLimits?,
	): Boolean =
		headers.size > identityCount * ImportedActivityDao.MAX_REVISIONS_PER_ENTRY ||
			receipts.size > identityCount * ImportedActivityDao.MAX_RECEIPTS_PER_ENTRY ||
			runs.size > identityCount * ImportedActivityDao.MAX_TOTAL_RUNS_PER_LINEAGE ||
			zoneEpochs.size > identityCount * ImportedActivityDao.MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE ||
			windows.size > identityCount * ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_LINEAGE ||
			fragments.size > identityCount * ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_LINEAGE ||
			retentionLimits?.let { limits ->
				headers.size > limits.maximumRevisions ||
					runs.size > limits.maximumRunRows ||
					zoneEpochs.size > limits.maximumZoneRows ||
					windows.size > limits.maximumWindowRows ||
					fragments.size > limits.maximumFragmentRows
			} == true

	fun belongsOnlyTo(identities: List<String>): Boolean {
		val expected = identities.toHashSet()
		return headers.all { it.identity in expected } && receipts.all { it.entryIdentity in expected } &&
			runs.all { it.entryIdentity in expected } && zoneEpochs.all { it.entryIdentity in expected } &&
			windows.all { it.entryIdentity in expected } && fragments.all { it.entryIdentity in expected }
	}
}

// Keep the fixed row-count limits together; this reads sizes without traversing or loading rows.
@Suppress("CyclomaticComplexMethod")
private fun ImportedActivityProductBatchPreflight.exceedsBatchLimit(
	identityCount: Int,
	retentionLimits: ImportedActivityRetentionLimits?,
): Boolean =
	entryRevisionCount > identityCount.toLong() * ImportedActivityDao.MAX_REVISIONS_PER_ENTRY ||
		importReceiptCount > identityCount.toLong() * ImportedActivityDao.MAX_RECEIPTS_PER_ENTRY ||
		runCount > identityCount.toLong() * ImportedActivityDao.MAX_TOTAL_RUNS_PER_LINEAGE ||
		zoneEpochCount > identityCount.toLong() *
		ImportedActivityDao.MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE ||
		windowCount > identityCount.toLong() * ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_LINEAGE ||
		fragmentCount > identityCount.toLong() *
		ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_LINEAGE ||
		retentionLimits?.let { limits ->
			entryRevisionCount > limits.maximumRevisions ||
				runCount > limits.maximumRunRows ||
				zoneEpochCount > limits.maximumZoneRows ||
				windowCount > limits.maximumWindowRows ||
				fragmentCount > limits.maximumFragmentRows
		} == true
