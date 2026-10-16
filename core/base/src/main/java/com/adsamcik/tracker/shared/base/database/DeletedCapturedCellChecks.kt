package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity

/** Complete value-free authentication shared by local detail and selected-deletion replay. */
suspend fun AppDatabase.authenticateDeletedCapturedCellSelectionInTransaction(
	logicalTrackingId: String,
	entryIdentity: PortableCellOpaqueIdentity,
): DeletedCapturedCellSelectionAuthentication {
	if (logicalTrackingId.isBlank() || entryIdentity != PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			logicalTrackingId,
		)
	) {
		return DeletedCapturedCellSelectionAuthentication.Unverifiable
	}
	val state = sourceEvidenceStateDao().get()?.takeIf(DeletedCapturedCellChecks::hasValidState)
		?: return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val receipt = cellCapturedFactDao().entryDeletionReceipt(logicalTrackingId)
		?: return DeletedCapturedCellSelectionAuthentication.Absent
	return if (DeletedCapturedCellChecks.authenticates(this, logicalTrackingId, entryIdentity, state, receipt)) {
		DeletedCapturedCellSelectionAuthentication.Exact(receipt)
	} else {
		DeletedCapturedCellSelectionAuthentication.Unverifiable
	}
}

/** Authenticate the retained deletion hierarchy before exposing its value-free receipt. */
private object DeletedCapturedCellChecks {
	fun hasValidState(state: SourceEvidenceState): Boolean =
		state.id == SourceEvidenceState.SINGLETON_ID && state.revision >= 0L &&
			state.collectedDataEpoch >= 0L && hasValidStateBounds(state)

	private fun hasValidStateBounds(state: SourceEvidenceState): Boolean =
		state.retainedFromMs?.let { it >= 0L } != false &&
			state.deletedSourceEventHighWaterOrdinal >= 0L && state.updatedAtMs >= 0L

	suspend fun authenticates(
		database: AppDatabase,
		logicalTrackingId: String,
		entryIdentity: PortableCellOpaqueIdentity,
		state: SourceEvidenceState,
		receipt: CellCapturedEntryDeletionReceiptEntity,
	): Boolean {
		if (!receiptMatches(receipt, entryIdentity, state)) {
			return false
		}
		val deletedRuns = database.cellCapturedFactDao().deletedRuns(logicalTrackingId, receipt.expectedRunCount + 1)
		if (!runFootprintsMatch(receipt, deletedRuns)) {
			return false
		}
		val serviceRuns = loadAuthenticatedRuns(database, logicalTrackingId, receipt, deletedRuns) ?: return false
		val runIds = deletedRuns.map(CellCapturedDeletedRunEntity::serviceRunId)
		return authenticatesManifests(database, logicalTrackingId, runIds, serviceRuns) &&
			authenticatesFences(database, logicalTrackingId, runIds, receipt, deletedRuns) &&
			hasNoRemainingPayload(database, logicalTrackingId, runIds) &&
			database.cellCapturedFactDao().selectedCellCaptureDemandsForDeletion(
				CELL_SOURCE,
				logicalTrackingId,
				runIds,
				1,
			).isEmpty()
	}

	private suspend fun loadAuthenticatedRuns(
		database: AppDatabase,
		logicalTrackingId: String,
		receipt: CellCapturedEntryDeletionReceiptEntity,
		deletedRuns: List<CellCapturedDeletedRunEntity>,
	): List<SourceServiceRunEntity>? {
		val runIds = deletedRuns.map(CellCapturedDeletedRunEntity::serviceRunId)
		val serviceRuns = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
			listOf(logicalTrackingId),
			CellCapturedEntryDeletionReceiptEntity.MAX_RUNS + 1,
			null,
			null,
			null,
		)
		if (serviceRuns.size != receipt.expectedRunCount ||
			serviceRuns.mapTo(linkedSetOf(), SourceServiceRunEntity::serviceRunId) != runIds.toSet() ||
			serviceRuns.any { run ->
				!run.matchesDeletedCapturedCellRun(logicalTrackingId, deletedRuns)
			}
		) {
			return null
		}
		val session = database.sourceSessionDao().session(logicalTrackingId)
		if (session == null || session.state !in setOf("FINALIZED", "FAILED", "CLOSED") ||
			session.currentServiceRunId != null || session.completedAtMs == null
		) {
			return null
		}
		if (database.trackingHistoryReadDao().segments(
				deletedRuns.map(CellCapturedDeletedRunEntity::sessionSegmentId),
			).isNotEmpty()
		) {
			return null
		}
		return serviceRuns
	}

	private suspend fun authenticatesManifests(
		database: AppDatabase,
		logicalTrackingId: String,
		runIds: List<String>,
		serviceRuns: List<SourceServiceRunEntity>,
	): Boolean {
		val manifests = database.trackingHistoryReadDao().manifests(runIds, CellCapturedPortableFormatV1.MAX_MANIFESTS + 1)
		val sources = database.trackingHistoryReadDao().manifestSources(
			runIds,
			CellCapturedPortableFormatV1.MAX_MANIFEST_SOURCES + 1,
		)
		if (manifests.size > CellCapturedPortableFormatV1.MAX_MANIFESTS ||
			sources.size > CellCapturedPortableFormatV1.MAX_MANIFEST_SOURCES
		) {
			return false
		}
		val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
		val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
		return SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
			runIds.map { runId ->
				manifestsByRun[runId].orEmpty().map(SessionManifestVersionEntity::manifestRevision)
			},
		) && serviceRuns.all { run ->
			authenticatesRunManifests(run, manifestsByRun[run.serviceRunId].orEmpty(), sourcesByManifest, logicalTrackingId)
		}
	}

	private fun authenticatesRunManifests(
		run: SourceServiceRunEntity,
		owned: List<SessionManifestVersionEntity>,
		sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
		logicalTrackingId: String,
	): Boolean = owned.isNotEmpty() && SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, owned) &&
		owned.all { manifest ->
			val membership = sourcesByManifest[logicalTrackingId to manifest.manifestRevision].orEmpty()
			manifest.logicalTrackingId == logicalTrackingId && SessionManifestIntegrity.verify(manifest, membership) &&
				membership.filter { it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE }
					.singleOrNull()?.let { it.isExactCapturedCellWalBinding(it.consentEpoch, manifest.sessionMode) } == true
		}

	private suspend fun authenticatesFences(
		database: AppDatabase,
		logicalTrackingId: String,
		runIds: List<String>,
		receipt: CellCapturedEntryDeletionReceiptEntity,
		deletedRuns: List<CellCapturedDeletedRunEntity>,
	): Boolean {
		val generations = database.cellCapturedFactDao().portableDeletionGenerationClosure(
			logicalTrackingId,
			runIds,
			receipt.expectedRunCount + 1,
		)
		val fences = try {
			database.trackingHistoryReadDao().deletionFences(
				CELL_SOURCE,
				CAPTURE_PURPOSE,
				SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				deletedRuns.map(CellCapturedDeletedRunEntity::scopeIdentityDigest),
			)
		} catch (_: IllegalArgumentException) {
			// Room constructs validated fence entities before this method can check their fields.
			return false
		}
		if (generations.size != receipt.expectedRunCount || fences.size != receipt.expectedRunCount) {
			return false
		}
		val generationByRun = generations.associateBy(CellCaptureDeletionGenerationEntity::serviceRunId)
		val fenceByScope = fences.associateBy(SourceDeletionFenceEntity::scopeIdentityDigest)
		return generationByRun.size == generations.size && fenceByScope.size == fences.size &&
			deletedRuns.all { run ->
				authenticatesRunFence(
					generationByRun[run.serviceRunId],
					fenceByScope[run.scopeIdentityDigest],
					logicalTrackingId,
					receipt,
					run,
				)
			}
	}

	private fun authenticatesRunFence(
		generation: CellCaptureDeletionGenerationEntity?,
		fence: SourceDeletionFenceEntity?,
		logicalTrackingId: String,
		receipt: CellCapturedEntryDeletionReceiptEntity,
		run: CellCapturedDeletedRunEntity,
	): Boolean {
		if (generation == null || fence == null) {
			return false
		}
		return authenticatesGeneration(generation, logicalTrackingId, receipt, run) &&
			hasFenceIdentity(fence) && authenticatesFenceEffect(fence, receipt, run) &&
			run.deletedAtMs == receipt.deletedAtMs
	}

	private fun authenticatesGeneration(
		generation: CellCaptureDeletionGenerationEntity,
		logicalTrackingId: String,
		receipt: CellCapturedEntryDeletionReceiptEntity,
		run: CellCapturedDeletedRunEntity,
	): Boolean = generation.logicalTrackingId == logicalTrackingId &&
		generation.collectedDataEpoch == receipt.collectedDataEpoch && generation.generation == run.generation &&
		generation.updatedAtMs == receipt.deletedAtMs

	private fun authenticatesFenceEffect(
		fence: SourceDeletionFenceEntity,
		receipt: CellCapturedEntryDeletionReceiptEntity,
		run: CellCapturedDeletedRunEntity,
	): Boolean = fence.collectedDataEpoch == receipt.collectedDataEpoch &&
		fence.fenceGeneration == run.generation && fence.deletedAtMs == receipt.deletedAtMs

	private suspend fun hasNoRemainingPayload(
		database: AppDatabase,
		logicalTrackingId: String,
		runIds: List<String>,
	): Boolean = database.cellCapturedFactDao().historyCursorsForScopes(
		WRITER_ID,
		WRITER_VERSION,
		runIds,
		listOf(logicalTrackingId),
		1,
	).isEmpty() && database.cellCapturedFactDao().historyRevisionPage(
		runIds,
		listOf(logicalTrackingId),
		1,
		null,
		null,
		null,
		null,
	).isEmpty()
}

private fun receiptMatches(
	receipt: CellCapturedEntryDeletionReceiptEntity,
	entryIdentity: PortableCellOpaqueIdentity,
	state: SourceEvidenceState,
): Boolean = receipt.entryIdentity == entryIdentity.value &&
	state.acceptsRetainedEpoch(receipt.collectedDataEpoch) && receipt.retainedFromMs == state.retainedFromMs

private fun runFootprintsMatch(
	receipt: CellCapturedEntryDeletionReceiptEntity,
	deletedRuns: List<CellCapturedDeletedRunEntity>,
): Boolean = deletedRuns.size == receipt.expectedRunCount &&
	CellCapturedEntryDeletionReceiptEntity.checksumRunFootprints(deletedRuns) == receipt.runFootprintSetChecksum &&
	deletedRuns.minOf(CellCapturedDeletedRunEntity::startTimeMs) == receipt.startTimeMs &&
	deletedRuns.maxOf(CellCapturedDeletedRunEntity::endTimeMs) == receipt.endTimeMs

private fun hasFenceIdentity(fence: SourceDeletionFenceEntity): Boolean =
	fence.sourceKind == CELL_SOURCE && fence.purpose == CAPTURE_PURPOSE &&
		fence.scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN

private fun SourceServiceRunEntity.matchesDeletedCapturedCellRun(
	logicalId: String, deletedRuns: List<CellCapturedDeletedRunEntity>,
): Boolean = !(logicalTrackingId != logicalId ||
	deletedRuns.singleOrNull { it.serviceRunId == serviceRunId }?.sessionSegmentId != sessionSegmentId ||
	state !in setOf("FINALIZED", "FAILED", "CLOSED") || completedAtMs == null)
