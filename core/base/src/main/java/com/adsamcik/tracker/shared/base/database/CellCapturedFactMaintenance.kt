package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CellWalIdentityFreeAggregate
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.RetainedCaptureEventIdentity
import com.adsamcik.tracker.shared.base.database.data.RetainedCaptureScopeIdentity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class CellCapturedMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	REVISION_PAGE_LOADED,
	LINEAGE_AUTHENTICATED,
	RETENTION_PROOF_PERSISTED,
	DELETION_FENCES_INSTALLED,
	PAYLOAD_REMOVED,
}

internal data class CellCapturedMaintenanceLimits(
	val revisionPageSize: Int = 256,
	val maximumRevisions: Int = 65_536,
	val maximumLogicalFacts: Int = 16_384,
	val maximumCursors: Int = 16_384,
	val maximumDeletionGenerations: Int = 16_384,
	val maximumManifestsPerRun: Int = 256,
	val maximumSourcesPerRun: Int = 4_096,
	val maximumSourcesPerManifest: Int = 32,
	val maximumSourcesPerPlan: Int = 12,
	val maximumPlanPayloadBytes: Int = 64 * 1_024,
	val maximumWalPayloadBytes: Int = 1_024 * 1_024,
	val maximumWalEvents: Int = 65_536,
	val maximumAuthorizationMembers: Int = 64,
	val maximumDirectDemands: Int = 256,
	val maximumNonterminalRegistrations: Int = 64,
) {
	init {
		require(revisionPageSize in 1..1_024)
		listOf(
			maximumRevisions,
			maximumLogicalFacts,
			maximumCursors,
			maximumDeletionGenerations,
			maximumManifestsPerRun,
			maximumSourcesPerRun,
			maximumSourcesPerManifest,
			maximumSourcesPerPlan,
			maximumPlanPayloadBytes,
			maximumWalPayloadBytes,
			maximumWalEvents,
			maximumAuthorizationMembers,
			maximumDirectDemands,
			maximumNonterminalRegistrations,
		).forEach { limit -> require(limit in 1 until Int.MAX_VALUE) }
		require(maximumLogicalFacts <= maximumRevisions)
	}
}

enum class CellCapturedRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	STALE_REQUEST,
}

sealed interface CellCapturedRetentionResult {
	data class Pruned(
		val logicalFactCount: Int,
		val revisionCount: Int,
	) : CellCapturedRetentionResult

	data object NoChange : CellCapturedRetentionResult

	data class Blocked(
		val reason: CellCapturedRetentionBlockedReason,
	) : CellCapturedRetentionResult
}

enum class CellCapturedSourceDeletionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	CAPTURE_CONSENT_STILL_ELIGIBLE,
	DIRECT_DEMAND_NOT_QUIESCED,
	CAPTURE_PROVIDER_NOT_QUIESCED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	DELETION_FENCE_CONFLICT,
	STALE_REQUEST,
}

sealed interface CellCapturedSourceDeletionResult {
	data class Deleted(
		val logicalFactCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : CellCapturedSourceDeletionResult

	data object AlreadyDeleted : CellCapturedSourceDeletionResult

	data class Blocked(
		val reason: CellCapturedSourceDeletionBlockedReason,
	) : CellCapturedSourceDeletionResult
}

enum class CellCapturedSelectedDeletionBlockedReason {
	STALE_COLLECTED_DATA_EPOCH,
	STALE_REQUEST,
	DELETION_FENCE_CONFLICT,
	FACT_AUTHORITY_UNVERIFIABLE,
	MAINTENANCE_BOUND_EXCEEDED,
}

sealed interface CellCapturedSelectedDeletionResult {
	data class Deleted(
		val logicalFactCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : CellCapturedSelectedDeletionResult

	data object AlreadyDeleted : CellCapturedSelectedDeletionResult
	data class Blocked(val reason: CellCapturedSelectedDeletionBlockedReason) :
		CellCapturedSelectedDeletionResult
}

enum class CellCapturedSelectedDeletionCheckpoint {
	TRANSACTION_STARTED,
	AUTHORITY_AUTHENTICATED,
	DELETION_FENCES_INSTALLED,
	PAYLOAD_REMOVED,
}

/**
 * Low-level exact selected Cell fact deletion. The caller authenticates replacement membership,
 * manifests, terminal lifecycle, active-demand absence and day repair before entering this call.
 */
suspend fun AppDatabase.deleteSelectedCapturedCellFactsInTransaction(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
	checkpoint: suspend (CellCapturedSelectedDeletionCheckpoint) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
): CellCapturedSelectedDeletionResult {
	require(logicalTrackingId.isNotBlank())
	require(serviceRunIds.isNotEmpty() && serviceRunIds.distinct().size == serviceRunIds.size)
	require(expectedCollectedDataEpoch >= 0L && deletedAtMs >= 0L)
	check(inTransaction()) { "Selected captured Cell deletion requires one caller-owned transaction" }
	val limits = DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS
	return try {
		checkpoint(CellCapturedSelectedDeletionCheckpoint.TRANSACTION_STARTED)
		val state = sourceEvidenceStateDao().get()
		if (state == null || state.collectedDataEpoch != expectedCollectedDataEpoch) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.STALE_COLLECTED_DATA_EPOCH)
		}
		val revisions = mutableListOf<CellCapturedFactRevisionEntity>()
		var cursor: CellSelectedRevisionCursor? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = limits.maximumRevisions - revisions.size
			val page = cellCapturedFactDao().historyRevisionPage(
				serviceRunIds,
				listOf(logicalTrackingId),
				minOf(limits.revisionPageSize, remaining + 1),
				cursor?.projectionId,
				cursor?.projectionVersion,
				cursor?.logicalFactId,
				cursor?.semanticRevision,
			)
			if (page.size > remaining) throw CellCapturedMaintenanceLimitExceeded()
			if (page.any {
				it.logicalTrackingId != logicalTrackingId || it.serviceRunId !in serviceRunIds
			}) selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			if (page.isEmpty()) break
			val next = CellSelectedRevisionCursor(page.last())
			if (cursor != null && next <= cursor) {
				selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			revisions += page
			cursor = next
			if (page.size < minOf(limits.revisionPageSize, remaining + 1)) break
		}
		val lineages = revisions.groupBy(CellCapturedFactRevisionEntity::logicalFactId)
			.values.map(::authenticateCellCapturedLineage)
		if (lineages.size > limits.maximumLogicalFacts) throw CellCapturedMaintenanceLimitExceeded()
		val byId = lineages.associateBy(CellCapturedLineage::logicalFactId)
		if (byId.size != lineages.size) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val cursorRows = cellCapturedFactDao().historyCursorsForScopes(
			WRITER_ID,
			WRITER_VERSION,
			serviceRunIds,
			listOf(logicalTrackingId),
			limits.maximumCursors + 1,
		)
		if (cursorRows.size > limits.maximumCursors) throw CellCapturedMaintenanceLimitExceeded()
		val cursors = cursorRows.associateBy(CellCapturedFactCursorEntity::logicalFactId)
		if (cursors.size != cursorRows.size || cursors.keys != byId.keys ||
			cursorRows.any {
				it.logicalTrackingId != logicalTrackingId || it.serviceRunId !in serviceRunIds
			}
		) selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val owner = sourceDestinationOwnerDao().get(
			CELL_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
		)
		if (lineages.isNotEmpty() && (owner == null ||
			owner.owner != SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
			!owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration())
		) selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val reuseAuthority = mutableMapOf<String, CellAggregateReuseAuthority>()
		val aggregates = mutableMapOf<String, CellHistoricalIdentityFreeAggregate>()
		var latestDurableTimeMs = maxOf(state.updatedAtMs, owner?.updatedAtMs ?: 0L)
		for (lineage in lineages) {
			currentCoroutineContext().ensureActive()
			val latest = lineage.revisions.last()
			if (cursors[lineage.logicalFactId]?.matchesCurrent(latest) != true ||
				latest.collectedDataEpoch != expectedCollectedDataEpoch ||
				latest.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
			) selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			val authority = authenticateCellCapturedAuthority(latest, limits)
			reuseAuthority[lineage.logicalFactId] = authority.reuseAuthority
			aggregates[lineage.logicalFactId] = authority.aggregate
			latestDurableTimeMs = maxOf(
				latestDurableTimeMs,
				authority.walCreatedAtMs,
				lineage.revisions.maxOf(CellCapturedFactRevisionEntity::appliedAtMs),
				requireNotNull(cursors[lineage.logicalFactId]).updatedAtMs,
			)
		}
		authenticateAggregateDependencies(lineages, byId, reuseAuthority, aggregates)
		val generations = cellCapturedFactDao().portableDeletionGenerationClosure(
			logicalTrackingId,
			serviceRunIds,
			limits.maximumDeletionGenerations + 1,
		)
		if (generations.size > limits.maximumDeletionGenerations) {
			throw CellCapturedMaintenanceLimitExceeded()
		}
		val generationsByRun = generations.associateBy(CellCaptureDeletionGenerationEntity::serviceRunId)
		if (generationsByRun.size != generations.size || generations.any {
			it.logicalTrackingId != logicalTrackingId ||
				it.serviceRunId !in serviceRunIds ||
				it.collectedDataEpoch != expectedCollectedDataEpoch ||
				it.generation != 1L
		}) selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val scopeByRun = serviceRunIds.associateWith { runId ->
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				CELL_SOURCE,
				CAPTURE_PURPOSE,
				logicalTrackingId,
				runId,
			)
		}
		val fences = trackingHistoryReadDao().deletionFences(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeByRun.values.toList(),
		)
		val fenceByScope = fences.associateBy(SourceDeletionFenceEntity::scopeIdentityDigest)
		if (fenceByScope.size != fences.size || fences.any {
			it.scopeIdentityDigest !in scopeByRun.values ||
				it.collectedDataEpoch != expectedCollectedDataEpoch || it.fenceGeneration != 1L
		}) selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		if (serviceRunIds.any { runId ->
			(runId in generationsByRun) != (scopeByRun.getValue(runId) in fenceByScope)
		}) selectedBlock(CellCapturedSelectedDeletionBlockedReason.DELETION_FENCE_CONFLICT)
		latestDurableTimeMs = maxOf(
			latestDurableTimeMs,
			generations.maxOfOrNull(CellCaptureDeletionGenerationEntity::updatedAtMs) ?: 0L,
			fences.maxOfOrNull(SourceDeletionFenceEntity::deletedAtMs) ?: 0L,
		)
		if (deletedAtMs < latestDurableTimeMs) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.STALE_REQUEST)
		}
		if (lineages.isEmpty() && serviceRunIds.all(generationsByRun::containsKey)) {
			return CellCapturedSelectedDeletionResult.AlreadyDeleted
		}
		checkpoint(CellCapturedSelectedDeletionCheckpoint.AUTHORITY_AUTHENTICATED)
		for (runId in serviceRunIds.filterNot(generationsByRun::containsKey)) {
			val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
				CELL_SOURCE,
				CAPTURE_PURPOSE,
				logicalTrackingId,
				runId,
				1L,
				expectedCollectedDataEpoch,
				deletedAtMs,
			)
			if (sourceDeletionFenceDao().insertIfAbsent(fence) == INSERT_IGNORED &&
				sourceDeletionFenceDao().get(
					fence.sourceKind,
					fence.purpose,
					fence.scopeKind,
					fence.scopeIdentityDigest,
				) != fence
			) selectedBlock(CellCapturedSelectedDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			cellCapturedFactDao().insertDeletionGeneration(
				CellCaptureDeletionGenerationEntity(
					logicalTrackingId,
					runId,
					expectedCollectedDataEpoch,
					1L,
					deletedAtMs,
				),
			)
		}
		checkpoint(CellCapturedSelectedDeletionCheckpoint.DELETION_FENCES_INSTALLED)
		var deletedRevisionCount = 0
		lineages.chunked(DELETE_BATCH_SIZE).forEach { batch ->
			val ids = batch.map(CellCapturedLineage::logicalFactId)
			ids.forEach { radioObservationChildDao().deleteCellSessionFact(WRITER_ID, WRITER_VERSION, it) }
			if (cellCapturedFactDao().deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != ids.size) {
				selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			val expected = batch.sumOf { it.revisions.size }
			val deleted = cellCapturedFactDao().deleteExactRevisionLineages(
				WRITER_ID,
				WRITER_VERSION,
				ids,
			)
			if (deleted != expected) {
				selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			deletedRevisionCount = Math.addExact(deletedRevisionCount, deleted)
		}
		if (sourceEvidenceStateDao().incrementRevision(deletedAtMs) != 1) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		checkpoint(CellCapturedSelectedDeletionCheckpoint.PAYLOAD_REMOVED)
		CellCapturedSelectedDeletionResult.Deleted(
			lineages.size,
			deletedRevisionCount,
			serviceRunIds.count { it !in generationsByRun },
		)
	} catch (blocked: CellCapturedSelectedDeletionBlockedException) {
		CellCapturedSelectedDeletionResult.Blocked(blocked.reason)
	} catch (_: CellCapturedRetentionBlockedException) {
		CellCapturedSelectedDeletionResult.Blocked(
			CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	} catch (_: CellCapturedMaintenanceLimitExceeded) {
		CellCapturedSelectedDeletionResult.Blocked(
			CellCapturedSelectedDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (_: IllegalArgumentException) {
		CellCapturedSelectedDeletionResult.Blocked(
			CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

private data class CellSelectedRevisionCursor(
	val projectionId: String,
	val projectionVersion: Int,
	val logicalFactId: String,
	val semanticRevision: Long,
) : Comparable<CellSelectedRevisionCursor> {
	constructor(value: CellCapturedFactRevisionEntity) : this(
		value.writerProjectionId,
		value.writerProjectionVersion,
		value.logicalFactId,
		value.semanticRevision,
	)

	override fun compareTo(other: CellSelectedRevisionCursor): Int = compareValuesBy(
		this,
		other,
		CellSelectedRevisionCursor::projectionId,
		CellSelectedRevisionCursor::projectionVersion,
		CellSelectedRevisionCursor::logicalFactId,
		CellSelectedRevisionCursor::semanticRevision,
	)
}

private fun selectedBlock(reason: CellCapturedSelectedDeletionBlockedReason): Nothing =
	throw CellCapturedSelectedDeletionBlockedException(reason)

private class CellCapturedSelectedDeletionBlockedException(
	val reason: CellCapturedSelectedDeletionBlockedReason,
) : RuntimeException(null, null, false, false)

sealed interface DeletedCapturedCellSelectionAuthentication {
	data object Absent : DeletedCapturedCellSelectionAuthentication
	data class Exact(
		val receipt: CellCapturedEntryDeletionReceiptEntity,
	) : DeletedCapturedCellSelectionAuthentication
	data object Unverifiable : DeletedCapturedCellSelectionAuthentication
}

/** Complete value-free authentication shared by local detail and selected-deletion replay. */
suspend fun AppDatabase.authenticateDeletedCapturedCellSelectionInTransaction(
	logicalTrackingId: String,
	entryIdentity: PortableCellOpaqueIdentity,
): DeletedCapturedCellSelectionAuthentication {
	if (logicalTrackingId.isBlank() || entryIdentity != PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			logicalTrackingId,
		)
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val state = sourceEvidenceStateDao().get()
		?: return DeletedCapturedCellSelectionAuthentication.Unverifiable
	if (state.id != SourceEvidenceState.SINGLETON_ID || state.revision < 0L ||
		state.collectedDataEpoch < 0L || state.retainedFromMs?.let { it < 0L } == true ||
		state.deletedSourceEventHighWaterOrdinal < 0L || state.updatedAtMs < 0L
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val receipt = cellCapturedFactDao().entryDeletionReceipt(logicalTrackingId)
		?: return DeletedCapturedCellSelectionAuthentication.Absent
	if (receipt.entryIdentity != entryIdentity.value ||
		receipt.collectedDataEpoch != state.collectedDataEpoch ||
		receipt.retainedFromMs != state.retainedFromMs
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val deletedRuns = cellCapturedFactDao().deletedRuns(
		logicalTrackingId,
		receipt.expectedRunCount + 1,
	)
	if (deletedRuns.size != receipt.expectedRunCount ||
		CellCapturedEntryDeletionReceiptEntity.checksumRunFootprints(deletedRuns) !=
		receipt.runFootprintSetChecksum ||
		deletedRuns.minOf(CellCapturedDeletedRunEntity::startTimeMs) != receipt.startTimeMs ||
		deletedRuns.maxOf(CellCapturedDeletedRunEntity::endTimeMs) != receipt.endTimeMs
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val runIds = deletedRuns.map(CellCapturedDeletedRunEntity::serviceRunId)
	val serviceRuns = trackingHistoryReadDao().logicalEntryServiceRunPage(
		listOf(logicalTrackingId),
		CellCapturedEntryDeletionReceiptEntity.MAX_RUNS + 1,
		null,
		null,
		null,
	)
	if (serviceRuns.size != receipt.expectedRunCount ||
		serviceRuns.mapTo(linkedSetOf(), SourceServiceRunEntity::serviceRunId) != runIds.toSet() ||
		serviceRuns.any { run ->
			run.logicalTrackingId != logicalTrackingId ||
				deletedRuns.singleOrNull { it.serviceRunId == run.serviceRunId }
					?.sessionSegmentId != run.sessionSegmentId ||
				run.state !in setOf("FINALIZED", "FAILED", "CLOSED") || run.completedAtMs == null
		}
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val session = sourceSessionDao().session(logicalTrackingId)
	if (session == null || session.state !in setOf("FINALIZED", "FAILED", "CLOSED") ||
		session.currentServiceRunId != null || session.completedAtMs == null
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	if (trackingHistoryReadDao().segments(
			deletedRuns.map(CellCapturedDeletedRunEntity::sessionSegmentId),
		).isNotEmpty()
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val manifests = trackingHistoryReadDao().manifests(
		runIds,
		CellCapturedPortableFormatV1.MAX_MANIFESTS + 1,
	)
	val sources = trackingHistoryReadDao().manifestSources(
		runIds,
		CellCapturedPortableFormatV1.MAX_MANIFEST_SOURCES + 1,
	)
	if (manifests.size > CellCapturedPortableFormatV1.MAX_MANIFESTS ||
		sources.size > CellCapturedPortableFormatV1.MAX_MANIFEST_SOURCES
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
	val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
	if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
			runIds.map { runId ->
				manifestsByRun[runId].orEmpty().map(SessionManifestVersionEntity::manifestRevision)
			},
		) || serviceRuns.any { run ->
			val owned = manifestsByRun[run.serviceRunId].orEmpty()
			owned.isEmpty() || !SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, owned) ||
				owned.any { manifest ->
					val membership = sourcesByManifest[
						logicalTrackingId to manifest.manifestRevision
					].orEmpty()
					manifest.logicalTrackingId != logicalTrackingId ||
						!SessionManifestIntegrity.verify(manifest, membership) ||
						membership.filter {
							it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
						}.singleOrNull()?.let {
							it.isExactCapturedCellWalBinding(it.consentEpoch, manifest.sessionMode)
						} != true
				}
		}
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val generations = cellCapturedFactDao().portableDeletionGenerationClosure(
		logicalTrackingId,
		runIds,
		receipt.expectedRunCount + 1,
	)
	val fences = try {
		trackingHistoryReadDao().deletionFences(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			deletedRuns.map(CellCapturedDeletedRunEntity::scopeIdentityDigest),
		)
	} catch (_: IllegalArgumentException) {
		// Room constructs validated fence entities before this method can check their fields.
		return DeletedCapturedCellSelectionAuthentication.Unverifiable
	}
	if (generations.size != receipt.expectedRunCount || fences.size != receipt.expectedRunCount) {
		return DeletedCapturedCellSelectionAuthentication.Unverifiable
	}
	val generationByRun = generations.associateBy(CellCaptureDeletionGenerationEntity::serviceRunId)
	val fenceByScope = fences.associateBy(SourceDeletionFenceEntity::scopeIdentityDigest)
	if (generationByRun.size != generations.size || fenceByScope.size != fences.size ||
		deletedRuns.any { run ->
			val generation = generationByRun[run.serviceRunId]
			val fence = fenceByScope[run.scopeIdentityDigest]
			generation == null || fence == null ||
				generation.logicalTrackingId != logicalTrackingId ||
				generation.collectedDataEpoch != receipt.collectedDataEpoch ||
				generation.generation != run.generation ||
				generation.updatedAtMs != receipt.deletedAtMs ||
				fence.sourceKind != CELL_SOURCE || fence.purpose != CAPTURE_PURPOSE ||
				fence.scopeKind != SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN ||
				fence.collectedDataEpoch != receipt.collectedDataEpoch ||
				fence.fenceGeneration != run.generation ||
				fence.deletedAtMs != receipt.deletedAtMs ||
				run.deletedAtMs != receipt.deletedAtMs
		}
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	if (cellCapturedFactDao().historyCursorsForScopes(
			WRITER_ID,
			WRITER_VERSION,
			runIds,
			listOf(logicalTrackingId),
			1,
		).isNotEmpty() || cellCapturedFactDao().historyRevisionPage(
			runIds,
			listOf(logicalTrackingId),
			1,
			null,
			null,
			null,
			null,
		).isNotEmpty()
	) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	val demands = cellCapturedFactDao().selectedCellCaptureDemandsForDeletion(
		CELL_SOURCE,
		logicalTrackingId,
		runIds,
		1,
	)
	if (demands.isNotEmpty()) return DeletedCapturedCellSelectionAuthentication.Unverifiable
	return DeletedCapturedCellSelectionAuthentication.Exact(receipt)
}

/**
 * Removes complete authenticated Cell correction/dependency closures affected by the global floor.
 *
 * A Cell fact covers an elapsed-time interval anchored by an uncertain wall time. If the oldest
 * possible covered wall time crosses [beforeMs], the complete correction and aggregate dependency
 * component is selected. Retention therefore never splits an owner from any coverage-only
 * dependent. The global retained floor remains the durable replay fence; WAL and provider/control
 * state are untouched.
 */
suspend fun AppDatabase.pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	markedAtMs: Long,
): CellCapturedRetentionResult = pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs,
	expectedCollectedDataEpoch,
	expectedDeletedSourceEventHighWaterOrdinal,
	markedAtMs,
	DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	markedAtMs: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedRetentionResult {
	require(beforeMs >= 0L)
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
	require(markedAtMs >= 0L)
	return try {
		withTransaction {
			checkpoint(CellCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
			val state = sourceEvidenceStateDao().get()
			if (state == null || state.collectedDataEpoch != expectedCollectedDataEpoch ||
				state.deletedSourceEventHighWaterOrdinal != expectedDeletedSourceEventHighWaterOrdinal ||
				state.retainedFromMs != beforeMs
			) block(CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
			val audit = auditCapturedCellFacts(state, limits, checkpoint)
			if (markedAtMs < audit.latestDurableTimeMs) {
				block(CellCapturedRetentionBlockedReason.STALE_REQUEST)
			}
			val selectedIds = affectedDependencyClosure(
				audit.lineages,
				audit.canonicalRetentionLowerBoundById,
				beforeMs,
			)
			if (selectedIds.isEmpty()) return@withTransaction CellCapturedRetentionResult.NoChange
			val directlyExpiredIds = audit.canonicalRetentionLowerBoundById
				.filterValues { lowerBound -> lowerBound < beforeMs }
				.keys
			val selected = selectedIds.map { logicalFactId ->
				audit.lineagesById.getValue(logicalFactId)
			}
			val retained = audit.lineages.filterNot { lineage -> lineage.logicalFactId in selectedIds }
			if (retained.any { lineage ->
				lineage.aggregateOwnerLogicalFactId in selectedIds
			}) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

			val dao = cellCapturedFactDao()
			val retentionProofs = selected.map { lineage ->
				val fact = lineage.revisions.last()
				val owner = lineage.aggregateOwnerLogicalFactId?.let { ownerId ->
					audit.lineagesById[ownerId]?.revisions?.last()
				}
				val ownerCursor = owner?.let { audit.cursorsById[it.logicalFactId] }
				CapturedRadioFactRetentionProofEntity.createCell(
					fact = fact,
					cursor = audit.cursorsById.getValue(fact.logicalFactId),
					aggregateOwner = owner,
					aggregateOwnerCursor = ownerCursor,
					retainedFromMs = beforeMs,
					retainedAtMs = markedAtMs,
					reason = if (fact.logicalFactId in directlyExpiredIds) {
						CapturedRadioFactRetentionProofEntity.REASON_DIRECT_FLOOR
					} else {
						CapturedRadioFactRetentionProofEntity.REASON_DEPENDENCY_CLOSURE
					},
				)
			}
			try {
				persistCapturedRadioFactRetentionProofs(retentionProofs)
			} catch (_: IllegalStateException) {
				block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			checkpoint(CellCapturedMaintenanceCheckpoint.RETENTION_PROOF_PERSISTED)
			var removedRevisions = 0
			selected.chunked(DELETE_BATCH_SIZE).forEach { batch ->
				currentCoroutineContext().ensureActive()
				val ids = batch.map(CellCapturedLineage::logicalFactId)
				ids.forEach { radioObservationChildDao().deleteCellSessionFact(WRITER_ID, WRITER_VERSION, it) }
				if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size) {
					block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				val expectedRevisions = batch.sumOf { lineage -> lineage.revisions.size }
				val deletedRevisions = dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids)
				if (deletedRevisions != expectedRevisions) {
					block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				removedRevisions = Math.addExact(removedRevisions, deletedRevisions)
				checkpoint(CellCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
			}
			check(sourceEvidenceStateDao().incrementRevision(maxOf(state.updatedAtMs, markedAtMs)) == 1) {
				"Unable to publish captured Cell retention"
			}
			CellCapturedRetentionResult.Pruned(selected.size, removedRevisions)
		}
	} catch (blocked: CellCapturedRetentionBlockedException) {
		CellCapturedRetentionResult.Blocked(blocked.reason)
	} catch (@Suppress("SwallowedException") _: CellCapturedMaintenanceLimitExceeded) {
		CellCapturedRetentionResult.Blocked(CellCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		CellCapturedRetentionResult.Blocked(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

/**
 * Deletes only the identity-free captured-Cell product store after exact capture-consent revoke.
 *
 * Durable source WAL, authorization history, registrations, and demands are audit inputs and are
 * never removed. Each retained physical run receives both the payload-free global scope fence and
 * the matching Cell-local monotonic generation before any fact or cursor is removed.
 */
suspend fun AppDatabase.deleteCapturedCellFactsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): CellCapturedSourceDeletionResult = deleteCapturedCellFactsAfterConsentReset(
	expectedCollectedDataEpoch,
	expectedDeletedSourceEventHighWaterOrdinal,
	expectedRevokedConsentEpoch,
	deletedAtMs,
	DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.deleteCapturedCellFactsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedSourceDeletionResult {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	return try {
		withTransaction {
			checkpoint(CellCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
			val evidenceState = sourceEvidenceStateDao().get()
			if (evidenceState == null || evidenceState.collectedDataEpoch != expectedCollectedDataEpoch ||
				evidenceState.deletedSourceEventHighWaterOrdinal !=
					expectedDeletedSourceEventHighWaterOrdinal
			) block(CellCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)

			val policyAuthority = sourcePolicyDao().authority()?.takeIf { authority ->
				authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
			} ?: block(CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
			val policy = sourcePolicyDao().policyAtRevision(
				policyAuthority.currentPolicyRevision,
				CELL_SOURCE,
			)
			val revokedConsent = sourcePolicyDao().latestConsentEpoch(CELL_SOURCE, CAPTURE_PURPOSE)
			if (policy == null || revokedConsent == null ||
				revokedConsent.epoch != expectedRevokedConsentEpoch
			) block(CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
			if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
				revokedConsent.eligible || revokedConsent.persistenceEligible ||
				revokedConsent.policyRevision > policy.policyRevision
			) block(CellCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
			// Exact epoch/high-water and capture clocks fence this erase; sibling WAL cannot veto it.
			if (deletedAtMs < maxOf(
				policyAuthority.updatedAtMs,
				policy.effectiveWallTimeMs,
				revokedConsent.effectiveWallTimeMs,
			)) block(CellCapturedSourceDeletionBlockedReason.STALE_REQUEST)

			val dao = cellCapturedFactDao()
			val demands = dao.directCellCaptureDemandsForDeletion(
				CELL_SOURCE,
				limits.maximumDirectDemands + 1,
			)
			if (demands.size > limits.maximumDirectDemands) throw CellCapturedMaintenanceLimitExceeded()
			if (demands.any { demand -> demand.status == SourceDemandEntity.STATUS_ACTIVE }) {
				block(CellCapturedSourceDeletionBlockedReason.DIRECT_DEMAND_NOT_QUIESCED)
			}
			authenticateRetiringCellCaptureDemands(
				demands = demands,
				revokedConsent = revokedConsent,
			)
			val registrations = dao.cellRegistrationsForDeletion(
				CELL_SOURCE,
				limits.maximumNonterminalRegistrations + 1,
			)
			if (registrations.size > limits.maximumNonterminalRegistrations) {
				throw CellCapturedMaintenanceLimitExceeded()
			}
			registrations.forEach { registration ->
				authenticateCellCaptureDeletionBarrier(
					registration = registration,
					expectedCollectedDataEpoch = expectedCollectedDataEpoch,
					currentPolicy = policy,
					limits = limits,
				)
			}
			if (demands.isNotEmpty() && dao.retireCellCaptureDemandsForDeletion(
					sourceKind = CELL_SOURCE,
					bootId = revokedConsent.effectiveBootId,
					elapsedRealtimeNanos = revokedConsent.effectiveElapsedRealtimeNanos,
					wallTimeMs = revokedConsent.effectiveWallTimeMs,
				) != demands.size
			) {
				block(CellCapturedSourceDeletionBlockedReason.DIRECT_DEMAND_NOT_QUIESCED)
			}

			val audit = auditCapturedCellFacts(evidenceState, limits, checkpoint)
			val walAudit = loadCapturedCellWalScopesForDeletion(evidenceState, limits)
			if (walAudit.scopes.isNotEmpty()) {
				val owner = sourceDestinationOwnerDao().get(
					CELL_SOURCE,
					SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
				)
				if (owner == null || owner.owner !=
					SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
					!owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration()
				) block(CellCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED)
			}
			if (deletedAtMs < maxOf(audit.latestDurableTimeMs, walAudit.latestDurableTimeMs)) {
				block(CellCapturedSourceDeletionBlockedReason.STALE_REQUEST)
			}
			val retainedProofs = loadCellCapturedRetentionProofsForSourceErase(limits)
			val retainedEventProofs = loadCellSourceEventRetentionProofsForSourceErase(limits)
			val retainedScopeProofs = loadCellSourceEventRetentionScopeProofsForSourceErase(limits)
			val scopeAuthorities = linkedMapOf<CellCapturedRunScope, RetainedCaptureScopeIdentity>()
			val eventAuthorities = mutableMapOf<CellCapturedEventAuthorityKey, RetainedCaptureEventIdentity>()
			val rawEventIdentities = mutableMapOf<String, Pair<Long, String>>()
			fun registerScope(
				identity: RetainedCaptureScopeIdentity,
				eventIdentity: RetainedCaptureEventIdentity? = null,
			) {
				if (!identity.isCanonicalFor(CELL_SOURCE, expectedCollectedDataEpoch)) {
					block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				val scope = CellCapturedRunScope(
					identity.logicalTrackingId,
					identity.serviceRunId,
				)
				val retainedScope = scopeAuthorities.putIfAbsent(scope, identity)
				if (retainedScope != null && retainedScope != identity) {
					block(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
				}
				if (retainedScope == null &&
					scopeAuthorities.size > limits.maximumDeletionGenerations
				) throw CellCapturedMaintenanceLimitExceeded()
				if (eventIdentity != null &&
					eventIdentity.sourceAdmissionOrdinal <=
					expectedDeletedSourceEventHighWaterOrdinal
				) block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				if (eventIdentity != null) {
					val rawIdentity = eventIdentity.sourceAdmissionOrdinal to eventIdentity.walIntegrityIdentity
					val original = rawEventIdentities.putIfAbsent(eventIdentity.eventId, rawIdentity)
					if (original != null && original != rawIdentity) {
						block(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
					}
					val retained = eventAuthorities.putIfAbsent(
						CellCapturedEventAuthorityKey(
							eventIdentity.eventId,
							eventIdentity.scope.logicalRunScopeDigest,
							eventIdentity.sourcePolicyRevision,
							eventIdentity.captureConsentEpoch,
						),
						eventIdentity,
					)
					if (retained != null && retained != eventIdentity) {
						block(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
					}
				}
			}
			for (scope in audit.lineages.map(CellCapturedLineage::scope) + walAudit.scopes) {
				registerScope(scope.retainedIdentity(expectedCollectedDataEpoch))
			}
			for (carrier in walAudit.carriers) {
				val scope = carrier.scope.retainedIdentity(expectedCollectedDataEpoch)
				registerScope(
					scope,
					RetainedCaptureEventIdentity(
						eventId = carrier.eventId,
						sourceAdmissionOrdinal = carrier.admissionOrdinal,
						sourcePolicyRevision = carrier.sourcePolicyRevision,
						captureConsentEpoch = carrier.captureConsentEpoch,
						walIntegrityIdentity = carrier.walIntegrityIdentity,
						scope = scope,
					),
				)
			}
			for (proof in retainedProofs) {
				val eventIdentity = proof.authenticatedCaptureEvent(
					expectedSourceKind = CELL_SOURCE,
					expectedCollectedDataEpoch = expectedCollectedDataEpoch,
					currentRetainedFromMs = evidenceState.retainedFromMs,
					deletedAtMs = deletedAtMs,
				) ?: block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				registerScope(eventIdentity.scope, eventIdentity)
			}
			val scopeProofsByDigest =
				retainedScopeProofs.associateBy(
					SourceEventRetentionScopeProofEntity::logicalRunScopeDigest,
				)
			if (scopeProofsByDigest.size != retainedScopeProofs.size) {
				block(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			}
			for (proof in retainedScopeProofs) {
				val identity = proof.authenticatedCaptureScope(
					expectedSourceKind = CELL_SOURCE,
					expectedCollectedDataEpoch = expectedCollectedDataEpoch,
					expectedDeletedSourceEventHighWaterOrdinal =
						expectedDeletedSourceEventHighWaterOrdinal,
				) ?: block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				registerScope(identity)
			}
			for (proof in retainedEventProofs) {
				val eventIdentity = proof.authenticatedCaptureEvent(
					expectedSourceKind = CELL_SOURCE,
					expectedCollectedDataEpoch = expectedCollectedDataEpoch,
				) ?: block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				val scopeProof = proof.logicalRunScopeDigest?.let(scopeProofsByDigest::get)
				if (scopeProof?.authenticatesRetainedEvent(proof) != true) {
					block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				registerScope(eventIdentity.scope, eventIdentity)
			}
			val scopesNeedingAuthority = mutableListOf<RetainedCaptureScopeIdentity>()
			for (identity in scopeAuthorities.values) {
				currentCoroutineContext().ensureActive()
				val scope = CellCapturedRunScope(
					identity.logicalTrackingId,
					identity.serviceRunId,
				)
				val generation = audit.generationByScope[scope]
				val fence = sourceDeletionFenceDao().get(
					CELL_SOURCE,
					CAPTURE_PURPOSE,
					SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					identity.logicalRunScopeDigest,
				)
				if (generation == null && fence == null) {
					scopesNeedingAuthority += identity
					continue
				}
				if (generation == null || fence == null ||
					generation.logicalTrackingId != identity.logicalTrackingId ||
					generation.serviceRunId != identity.serviceRunId ||
					generation.collectedDataEpoch != expectedCollectedDataEpoch ||
					generation.generation != FIRST_DELETION_GENERATION ||
					fence != SourceDeletionFenceEntity.createLogicalServiceRun(
						CELL_SOURCE,
						CAPTURE_PURPOSE,
						identity.logicalTrackingId,
						identity.serviceRunId,
						FIRST_DELETION_GENERATION,
						expectedCollectedDataEpoch,
						generation.updatedAtMs,
					)
				) block(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			}
			if (audit.lineages.isEmpty() && scopesNeedingAuthority.isEmpty() &&
				retainedProofs.isEmpty() && retainedEventProofs.isEmpty() &&
				retainedScopeProofs.isEmpty()
			) return@withTransaction CellCapturedSourceDeletionResult.AlreadyDeleted
			for (identity in scopesNeedingAuthority) {
				currentCoroutineContext().ensureActive()
				val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
					sourceKind = CELL_SOURCE,
					purpose = CAPTURE_PURPOSE,
					logicalTrackingId = identity.logicalTrackingId,
					serviceRunId = identity.serviceRunId,
					fenceGeneration = FIRST_DELETION_GENERATION,
					collectedDataEpoch = expectedCollectedDataEpoch,
					deletedAtMs = deletedAtMs,
				)
				if (sourceDeletionFenceDao().insertIfAbsent(fence) == INSERT_IGNORED &&
					sourceDeletionFenceDao().get(
						fence.sourceKind,
						fence.purpose,
						fence.scopeKind,
						fence.scopeIdentityDigest,
					) != fence
				) block(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
				val generation = CellCaptureDeletionGenerationEntity(
					logicalTrackingId = identity.logicalTrackingId,
					serviceRunId = identity.serviceRunId,
					collectedDataEpoch = expectedCollectedDataEpoch,
					generation = FIRST_DELETION_GENERATION,
					updatedAtMs = deletedAtMs,
				)
				dao.insertDeletionGeneration(generation)
				if (dao.deletionGeneration(
						identity.logicalTrackingId,
						identity.serviceRunId,
					) != generation ||
					sourceDeletionFenceDao().get(
						fence.sourceKind,
						fence.purpose,
						fence.scopeKind,
						fence.scopeIdentityDigest,
					) != fence
				) block(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			}
			checkpoint(CellCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED)

			val revisionCount = audit.lineages.sumOf { lineage -> lineage.revisions.size }
			audit.lineages.chunked(DELETE_BATCH_SIZE).forEach { batch ->
				currentCoroutineContext().ensureActive()
				val ids = batch.map(CellCapturedLineage::logicalFactId)
				ids.forEach { radioObservationChildDao().deleteCellSessionFact(WRITER_ID, WRITER_VERSION, it) }
				if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size ||
					dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids) !=
						batch.sumOf { lineage -> lineage.revisions.size }
				) block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			if (dao.revisionCount() != 0L || dao.cursorCount() != 0L) {
				block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			capturedRadioFactRetentionProofDao().deleteSource(CELL_SOURCE)
			if (capturedRadioFactRetentionProofDao().allForSource(CELL_SOURCE).isNotEmpty()) {
				block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			if (retainedEventProofs.isNotEmpty()) {
				val eventIds = retainedEventProofs.map(SourceEventRetentionProofEntity::eventId)
				val deletedProofs = eventIds.chunked(DELETE_BATCH_SIZE).sumOf { batch ->
					sourceEventRetentionProofDao().deleteExactSourceEvents(CELL_SOURCE, batch)
				}
				if (deletedProofs != eventIds.size) {
					block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
			}
			if (sourceEventRetentionProofDao().captureProofCount(
					CELL_SOURCE,
					SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				) != 0L
			) block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			if (retainedScopeProofs.isNotEmpty()) {
				val scopeDigests =
					retainedScopeProofs.map(SourceEventRetentionScopeProofEntity::logicalRunScopeDigest)
				val deletedScopes = scopeDigests.chunked(DELETE_BATCH_SIZE).sumOf { batch ->
					sourceEventRetentionScopeProofDao().deleteExactSourceScopes(
						CELL_SOURCE,
						batch,
					)
				}
				if (deletedScopes != scopeDigests.size) {
					block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
			}
			if (sourceEventRetentionScopeProofDao().countForSource(CELL_SOURCE) != 0L) {
				block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			check(sourceEvidenceStateDao().incrementRevision(maxOf(evidenceState.updatedAtMs, deletedAtMs)) == 1) {
				"Unable to publish captured Cell source deletion"
			}
			checkpoint(CellCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
			CellCapturedSourceDeletionResult.Deleted(
				logicalFactCount = audit.lineages.size,
				revisionCount = revisionCount,
				fencedServiceRunCount = scopesNeedingAuthority.size,
			)
		}
	} catch (blocked: CellCapturedSourceDeletionBlockedException) {
		CellCapturedSourceDeletionResult.Blocked(blocked.reason)
	} catch (blocked: CellCapturedRetentionBlockedException) {
		CellCapturedSourceDeletionResult.Blocked(
			when (blocked.reason) {
				CellCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED ->
					CellCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED
				CellCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT ->
					CellCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT
				CellCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
					CellCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED
				else -> CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE
			},
		)
	} catch (@Suppress("SwallowedException") _: CellCapturedMaintenanceLimitExceeded) {
		CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

internal suspend fun AppDatabase.auditCapturedCellFacts(
	evidenceState: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedFactAudit {
	val dao = cellCapturedFactDao()
	if (dao.unsupportedRevisionCount(WRITER_ID, WRITER_VERSION) != 0L ||
		dao.unsupportedCursorCount(WRITER_ID, WRITER_VERSION) != 0L
	) block(CellCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)

	val lineages = mutableListOf<CellCapturedLineage>()
	var afterLogicalFactId: String? = null
	var afterSemanticRevision: Long? = null
	var revisionCount = 0
	var current = mutableListOf<CellCapturedFactRevisionEntity>()
	while (true) {
		currentCoroutineContext().ensureActive()
		val page = dao.maintenanceRevisionPage(
			WRITER_ID,
			WRITER_VERSION,
			afterLogicalFactId,
			afterSemanticRevision,
			limits.revisionPageSize,
		)
		if (page.isEmpty()) break
		revisionCount = Math.addExact(revisionCount, page.size)
		if (revisionCount > limits.maximumRevisions) throw CellCapturedMaintenanceLimitExceeded()
		checkpoint(CellCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)
		for (revision in page) {
			if (current.isNotEmpty() && current.last().logicalFactId != revision.logicalFactId) {
				lineages += authenticateCellCapturedLineage(current)
				current = mutableListOf()
				if (lineages.size > limits.maximumLogicalFacts) {
					throw CellCapturedMaintenanceLimitExceeded()
				}
			}
			current += revision
		}
		afterLogicalFactId = page.last().logicalFactId
		afterSemanticRevision = page.last().semanticRevision
		if (page.size < limits.revisionPageSize) break
	}
	if (current.isNotEmpty()) lineages += authenticateCellCapturedLineage(current)
	if (lineages.size > limits.maximumLogicalFacts) throw CellCapturedMaintenanceLimitExceeded()

	val owner = sourceDestinationOwnerDao().get(
		CELL_SOURCE,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
	)
	if (lineages.isNotEmpty() && (owner == null ||
		owner.owner != SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
		!owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration())
	) block(CellCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)

	val cursors = loadCellCapturedCursors(limits)
	val generations = loadCellDeletionGenerations(limits)
	if (dao.revisionCount() != revisionCount.toLong() ||
		dao.cursorCount() != cursors.size.toLong() ||
		dao.deletionGenerationCount() != generations.size.toLong()
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (cursors.size != lineages.size || cursors.keys != lineages.mapTo(mutableSetOf()) {
			lineage -> lineage.logicalFactId
		}
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val byId = lineages.associateBy(CellCapturedLineage::logicalFactId)
	if (byId.size != lineages.size) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val reuseAuthorityById = mutableMapOf<String, CellAggregateReuseAuthority>()
	val aggregateById = mutableMapOf<String, CellHistoricalIdentityFreeAggregate>()
	val canonicalRetentionLowerBoundById = mutableMapOf<String, Long>()
	var latestWalTimeMs = 0L
	for (lineage in lineages) {
		currentCoroutineContext().ensureActive()
		val latest = lineage.revisions.last()
		val cursor = cursors[lineage.logicalFactId]
		if (cursor == null || !cursor.matchesCurrent(latest)) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (latest.collectedDataEpoch != evidenceState.collectedDataEpoch ||
			latest.sourceAdmissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) block(CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		val authenticated = authenticateCellCapturedAuthority(latest, limits)
		reuseAuthorityById[lineage.logicalFactId] = authenticated.reuseAuthority
		aggregateById[lineage.logicalFactId] = authenticated.aggregate
		canonicalRetentionLowerBoundById[lineage.logicalFactId] =
			authenticated.retentionLowerBoundMs
		latestWalTimeMs = maxOf(latestWalTimeMs, authenticated.walCreatedAtMs)
		checkpoint(CellCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
	}
	authenticateAggregateDependencies(lineages, byId, reuseAuthorityById, aggregateById)
	authenticateCellDeletionGenerations(lineages, generations, evidenceState.collectedDataEpoch)

	return CellCapturedFactAudit(
		lineages = lineages,
		lineagesById = byId,
		cursorsById = cursors,
		canonicalRetentionLowerBoundById = canonicalRetentionLowerBoundById,
		aggregateById = aggregateById,
		generationByScope = generations.associateBy { generation ->
			CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		},
		latestDurableTimeMs = maxOf(
			owner?.updatedAtMs ?: 0L,
			latestWalTimeMs,
			lineages.maxOfOrNull { lineage -> lineage.revisions.maxOf { it.appliedAtMs } } ?: 0L,
			cursors.values.maxOfOrNull(CellCapturedFactCursorEntity::updatedAtMs) ?: 0L,
			generations.maxOfOrNull(CellCaptureDeletionGenerationEntity::updatedAtMs) ?: 0L,
		),
	)
}

/**
 * Audits the complete retained Cell fact closure that can affect one portable logical entry.
 * Rows are selected from both sides of the logical-entry/service-run identity so a one-sided
 * corruption cannot escape the audit, while unrelated retained history cannot exhaust its bounds.
 */
@Suppress("ComplexCondition", "LongMethod")
internal suspend fun AppDatabase.auditPortableCapturedCellFacts(
	evidenceState: SourceEvidenceState,
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	capturedServiceRunIds: List<String>,
	activationFloorOrdinal: Long,
	admissionCeilingOrdinal: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedFactAudit {
	require(logicalTrackingId.isNotBlank())
	require(serviceRunIds.isNotEmpty() && serviceRunIds.distinct().size == serviceRunIds.size)
	require(capturedServiceRunIds.isNotEmpty() && capturedServiceRunIds.all(serviceRunIds::contains))
	require(capturedServiceRunIds.distinct().size == capturedServiceRunIds.size)
	require(activationFloorOrdinal >= 0L)
	require(admissionCeilingOrdinal >= activationFloorOrdinal)
	val dao = cellCapturedFactDao()
	val revisions = dao.portableRevisionClosure(
		CELL_SOURCE,
		logicalTrackingId,
		serviceRunIds,
		capturedServiceRunIds,
		activationFloorOrdinal,
		admissionCeilingOrdinal,
		limits.maximumRevisions + 1,
	)
	if (revisions.size > limits.maximumRevisions) throw CellCapturedMaintenanceLimitExceeded()
	if (revisions.isNotEmpty()) checkpoint(CellCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)

	val lineages = revisions.groupBy(CellCapturedFactRevisionEntity::logicalFactId)
		.values
		.map(::authenticateCellCapturedLineage)
	if (lineages.size > limits.maximumLogicalFacts) throw CellCapturedMaintenanceLimitExceeded()
	val byId = lineages.associateBy(CellCapturedLineage::logicalFactId)
	if (byId.size != lineages.size) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val owner = sourceDestinationOwnerDao().get(
		CELL_SOURCE,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
	)
	if (lineages.isNotEmpty() && (owner == null ||
		owner.owner != SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
		!owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration())
	) block(CellCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)

	val cursorRows = dao.portableCursorClosure(
		CELL_SOURCE,
		logicalTrackingId,
		serviceRunIds,
		capturedServiceRunIds,
		activationFloorOrdinal,
		admissionCeilingOrdinal,
		limits.maximumCursors + 1,
	)
	if (cursorRows.size > limits.maximumCursors) throw CellCapturedMaintenanceLimitExceeded()
	val cursors = cursorRows.associateBy(CellCapturedFactCursorEntity::logicalFactId)
	if (cursors.size != cursorRows.size || cursors.keys != byId.keys) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val generations = dao.portableDeletionGenerationClosure(
		logicalTrackingId,
		serviceRunIds,
		limits.maximumDeletionGenerations + 1,
	)
	if (generations.size > limits.maximumDeletionGenerations) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val generationByScope = generations.associateBy { generation ->
		CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val reuseAuthorityById = mutableMapOf<String, CellAggregateReuseAuthority>()
	val aggregateById = mutableMapOf<String, CellHistoricalIdentityFreeAggregate>()
	val canonicalRetentionLowerBoundById = mutableMapOf<String, Long>()
	var latestWalTimeMs = 0L
	for (lineage in lineages) {
		currentCoroutineContext().ensureActive()
		val latest = lineage.revisions.last()
		val cursor = cursors[lineage.logicalFactId]
		if (cursor == null || !cursor.matchesCurrent(latest)) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (latest.collectedDataEpoch != evidenceState.collectedDataEpoch ||
			latest.sourceAdmissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) block(CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		val authenticated = authenticateCellCapturedAuthority(latest, limits)
		reuseAuthorityById[lineage.logicalFactId] = authenticated.reuseAuthority
		aggregateById[lineage.logicalFactId] = authenticated.aggregate
		canonicalRetentionLowerBoundById[lineage.logicalFactId] =
			authenticated.retentionLowerBoundMs
		latestWalTimeMs = maxOf(latestWalTimeMs, authenticated.walCreatedAtMs)
		checkpoint(CellCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
	}
	// Aggregate owners share the selected capture scope and are therefore included by the closure
	// query even when only their dependent correction is a retained export observation.
	authenticateAggregateDependencies(lineages, byId, reuseAuthorityById, aggregateById)
	authenticateCellDeletionGenerations(lineages, generations, evidenceState.collectedDataEpoch)

	return CellCapturedFactAudit(
		lineages = lineages,
		lineagesById = byId,
		cursorsById = cursors,
		canonicalRetentionLowerBoundById = canonicalRetentionLowerBoundById,
		aggregateById = aggregateById,
		generationByScope = generationByScope,
		latestDurableTimeMs = maxOf(
			evidenceState.updatedAtMs,
			owner?.updatedAtMs ?: 0L,
			latestWalTimeMs,
			lineages.maxOfOrNull { lineage -> lineage.revisions.maxOf { it.appliedAtMs } } ?: 0L,
			cursors.values.maxOfOrNull(CellCapturedFactCursorEntity::updatedAtMs) ?: 0L,
			generations.maxOfOrNull(CellCaptureDeletionGenerationEntity::updatedAtMs) ?: 0L,
		),
	)
}

private fun authenticateCellCapturedLineage(
	revisions: List<CellCapturedFactRevisionEntity>,
): CellCapturedLineage {
	if (revisions.isEmpty()) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val first = revisions.first()
	for ((index, revision) in revisions.withIndex()) {
		val semanticRevision = index + 1L
		val previous = revisions.getOrNull(index - 1)
		if (revision.semanticRevision != semanticRevision ||
			revision.supersedesSemanticRevision != semanticRevision.takeIf { it > 1L }?.minus(1L) ||
			revision.logicalFactId != CellCapturedFactRevisionIntegrity.logicalFactId(
				revision.sourceDeliveryIdentity,
				revision.logicalTrackingId,
				revision.serviceRunId,
				revision.sessionSegmentId,
				revision.manifestRevision,
				revision.collectedDataEpoch,
				revision.scopeDeletionGeneration,
			) || revision.mutationId != CellCapturedFactRevisionIntegrity.mutationId(
				revision.logicalFactId,
				revision.semanticRevision,
			) || !CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(revision) ||
			!revision.hasSameCorrectionEffect(first) ||
			(previous != null && !revision.isExactSettlementSuccessorOf(previous))
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return CellCapturedLineage(
		logicalFactId = first.logicalFactId,
		revisions = revisions,
		aggregateOwnerLogicalFactId = first.aggregateOwnerLogicalFactId,
		aggregateOwnerSemanticRevision = first.aggregateOwnerSemanticRevision,
	)
}

private suspend fun AppDatabase.authenticateCellCapturedAuthority(
	revision: CellCapturedFactRevisionEntity,
	limits: CellCapturedMaintenanceLimits,
): AuthenticatedCellFactAuthority {
	val walPayloadBytes = cellCapturedFactDao().maintenanceWalPayloadByteCount(revision.sourceEventId)
	if (walPayloadBytes == null || walPayloadBytes > limits.maximumWalPayloadBytes) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val wal = sourceEventWalDao().getByEventId(revision.sourceEventId)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val rawCarrier = CellWalCarrierAuthenticator.authenticate(wal) as? CellWalCarrierAuthentication.Authenticated
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val selectedPurpose = if (revision.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
		qualifyCellCapturePurposeItems(wal, rawCarrier.carrier.payload, revision) as? CellPurposeItems.Qualified
			?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	} else null
	val walAuthentication = (
		CellWalCarrierAuthenticator.authenticate(revision, wal, selectedPurpose?.indexes?.toSet())
			as? CellWalCarrierAuthentication.Authenticated
		) ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val authenticatedAggregate = walAuthentication.carrier.childQualification?.aggregate
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val sessionDao = sourceSessionDao()
	val run = sessionDao.serviceRun(revision.serviceRunId)
	val session = sessionDao.session(revision.logicalTrackingId)
	val segment = sessionSegmentDao().getById(revision.sessionSegmentId)
	if (run == null || session == null || segment == null ||
		!session.hasValidHistoricalCellShape() ||
		!run.hasValidHistoricalCellShape(session) ||
		run.logicalTrackingId != revision.logicalTrackingId ||
		run.sessionSegmentId != revision.sessionSegmentId ||
		!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, revision.lifecycleLeaseGeneration) ||
		run.startedElapsedNanos != revision.deletionEffectStartNanos ||
		segment.logicalTrackingId != revision.logicalTrackingId ||
		segment.serviceRunId != revision.serviceRunId ||
		(revision.payloadVersion != CELL_WAL_PAYLOAD_VERSION && session.finalAdmissionOrdinal != null &&
			revision.sourceAdmissionOrdinal > session.finalAdmissionOrdinal) ||

		session.lifecycleLeaseGeneration < revision.lifecycleLeaseGeneration ||
		(session.cutoffAtMs == null) != (session.cutoffElapsedNanos == null) ||
		(session.completedAtMs != null && session.cutoffElapsedNanos == null)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val sessionEnd = savedSessionCutoff(session).retainedEnd(revision.clockDomainId, revision.deletionEffectEndNanos)
	if (!sessionEnd.isExactSettlementOf(revision.deletionEffectEndNanos)) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val manifests = sessionDao.manifestsForServiceRun(
		revision.serviceRunId,
		limits.maximumManifestsPerRun + 1,
	)
	val sources = trackingHistoryReadDao().manifestSources(
		listOf(revision.serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	if (manifests.size > limits.maximumManifestsPerRun ||
		sources.size > limits.maximumSourcesPerRun ||
		!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests) ||
		manifests.any { manifest ->
			!SessionManifestIntegrity.verify(
				manifest,
				sources.filter { source ->
					source.logicalTrackingId == manifest.logicalTrackingId &&
						source.manifestRevision == manifest.manifestRevision
				},
			)
		}
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifestIndex = manifests.indexOfFirst { manifest ->
		manifest.logicalTrackingId == revision.logicalTrackingId &&
			manifest.manifestRevision == revision.manifestRevision
	}
	if (manifestIndex < 0) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifest = manifests[manifestIndex]
	val membership = sources.filter { source ->
		source.logicalTrackingId == revision.logicalTrackingId &&
			source.manifestRevision == revision.manifestRevision
	}
	if (membership.size > limits.maximumSourcesPerManifest) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val binding = membership.singleOrNull { source ->
		source.sourceKind == CELL_SOURCE && source.purpose == CAPTURE_PURPOSE
	}
	val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
		?: minOf(
			sessionEnd,
			revision.providerAcceptanceEndNanos,
			revision.authorizationEffectEndNanos,
		)
	if (binding == null || !binding.isExactCapturedCellBinding(revision, manifest.sessionMode) ||
		manifest.serviceRunId != revision.serviceRunId ||
		manifest.sourcePolicyRevision != revision.sourcePolicyRevision ||
		manifest.acquisitionPlanRevision != revision.configurationRevision ||
		manifest.effectiveBootId != revision.clockDomainId ||
		manifest.effectiveElapsedRealtimeNanos != revision.sessionRunEffectStartNanos ||
		!manifestEnd.isExactSettlementOf(revision.sessionRunEffectEndNanos) ||
		manifest.zoneId != revision.storedZoneId || !hasValidZone(revision.storedZoneId)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val plan = authenticateHistoricalCellPlan(
		revision.configurationRevision,
		revision.sourcePolicyRevision,
		revision.physicalConfigurationFingerprint,
		limits,
	)
	val planMaximumAgeNanos = plan.maximumObservationAgeNanos()
	if (revision.payloadVersion != CELL_WAL_PAYLOAD_VERSION &&
		revision.maximumObservationAgeNanos != planMaximumAgeNanos) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val policy = sourcePolicyDao().policyAtRevision(revision.sourcePolicyRevision, CELL_SOURCE)
	val consent = sourcePolicyDao().consentEpoch(
		CELL_SOURCE,
		CAPTURE_PURPOSE,
		revision.captureConsentEpoch,
	)
	if (policy == null || consent == null || !policy.enabled || !policy.capturePersistenceEligible ||
		policy.captureConsentEpoch != revision.captureConsentEpoch || policy.qosCode != binding.qosCode ||
		policy.effectiveBootId != revision.clockDomainId ||
		policy.effectiveElapsedRealtimeNanos > revision.coverageIntervalStartNanos ||
		!consent.eligible || !consent.persistenceEligible ||
		consent.policyRevision > revision.sourcePolicyRevision ||
		consent.effectiveBootId != revision.clockDomainId ||
		consent.effectiveElapsedRealtimeNanos != revision.consentEffectStartNanos
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val brokerDao = sourceBrokerDao()
	val registration = brokerDao.registration(CELL_SOURCE, revision.registrationGeneration)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val registrationInterval = authenticateCellRegistration(
		registration = registration,
		expectedSourceInstanceId = revision.sourceInstanceId,
		expectedCollectedDataEpoch = revision.collectedDataEpoch,
		expectedClockDomainId = revision.clockDomainId,
		expectedPhysicalFingerprint = revision.physicalConfigurationFingerprint,
		expectedAuthorizationRevision = revision.authorizationRevision,
	)
	val registrationStart = registrationInterval.first
	val registrationEnd = registrationInterval.last
	if (registrationStart != revision.providerAcceptanceStartNanos ||
		!registrationEnd.isExactSettlementOf(revision.providerAcceptanceEndNanos)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val authorizationRows = boundedAuthorizationMembers(
		revision.registrationGeneration,
		revision.authorizationRevision,
		limits,
	)
	val authorization = authorizationRows.toAuthorizationSnapshotOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val startAuthorization = if (revision.payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
		boundedAuthorizationAt(
			revision.registrationGeneration, revision.clockDomainId,
			revision.coverageIntervalStartNanos, limits,
		)
	} else null
	val endAuthorization = if (revision.payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
		boundedAuthorizationAt(
			revision.registrationGeneration, revision.clockDomainId,
			revision.coverageIntervalEndNanos, limits,
		)
	} else null
	val nextRows = cellCapturedFactDao().maintenanceNextAuthorizationMembers(
		CELL_SOURCE,
		revision.registrationGeneration,
		revision.authorizationRevision,
		limits.maximumAuthorizationMembers + 1,
	)
	if (nextRows.size > limits.maximumAuthorizationMembers) throw CellCapturedMaintenanceLimitExceeded()
	val nextAuthorization = if (nextRows.isEmpty()) null else nextRows.toAuthorizationSnapshotOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (nextAuthorization != null &&
		(nextAuthorization.effectiveBootId != wal.clockDomainId ||
			nextAuthorization.effectiveElapsedRealtimeNanos <
				authorization.effectiveElapsedRealtimeNanos)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val callbackAuthorizationEnd = minOf(
		registrationEnd,
		nextAuthorization?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE,
	)
	val captureMember = authorization.authorizedMembers.singleOrNull { member ->
		member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
			member.logicalTrackingId == revision.logicalTrackingId &&
			member.serviceRunId == revision.serviceRunId &&
			member.manifestRevision == revision.manifestRevision &&
			member.lifecycleLeaseGeneration == revision.lifecycleLeaseGeneration &&
			member.sourcePolicyRevision == revision.sourcePolicyRevision &&
			member.consentEpoch == revision.captureConsentEpoch
	}
	val demandIds = authorization.authorizedMembers.mapNotNull { member -> member.demandId }
	val demands = brokerDao.demandsByIds(demandIds)
	val recomputed = runCatching {
		SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = revision.registrationGeneration,
			authorizationRevision = revision.authorizationRevision,
			demands = demands,
			effectiveBootId = authorization.effectiveBootId,
			effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
		)
	}.getOrNull()
	val purpose = if (revision.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
		qualifyCellPurposeItems(
			wal, walAuthentication.carrier.payload,
			captureMember?.demandId ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE),
			registrationStart, registrationEnd,
		) as? CellPurposeItems.Qualified
			?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	} else null
	val authorizationEnd = purpose?.continuousUntilNanos ?: callbackAuthorizationEnd
	val acceptedIndexes = walAuthentication.carrier.childQualification?.children
		?.withIndex()?.filter { it.value.disposition ==
			com.adsamcik.tracker.shared.base.database.data.CellWalChildDisposition.ACCEPTED }
		?.map { it.index }.orEmpty()
	if (authorization.isDenied || captureMember == null ||
		(revision.payloadVersion != CELL_WAL_PAYLOAD_VERSION &&
			(startAuthorization != authorization || endAuthorization != authorization)) ||
		demandIds.distinct().size != authorization.authorizedMembers.size ||
		demands.size != authorization.authorizedMembers.size ||
		recomputed?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId } ||
		authorization.authorizationFingerprint != revision.authorizationFingerprint ||
		authorization.purposeEligibilityMask != revision.purposeEligibilityMask ||
		authorization.effectiveBootId != revision.clockDomainId ||
		(if (purpose == null) {
			authorization.effectiveElapsedRealtimeNanos != revision.authorizationEffectStartNanos
		} else {
			purpose.continuousFromNanos != revision.authorizationEffectStartNanos ||
				purpose.indexes.isEmpty() || acceptedIndexes.any { it !in purpose.indexes } ||
				revision.maximumObservationAgeNanos != minOf(
					planMaximumAgeNanos, purpose.demandMaximumAgeNanos)
		}) ||
		!authorizationEnd.isExactSettlementOf(revision.authorizationEffectEndNanos) ||
		!authorizationEnd.isExactSettlementOf(revision.consentEffectEndNanos)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val earliestWall = CellWalCarrierAuthenticator.retentionLowerBound(
		revision,
		walAuthentication.carrier,
	) ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val latestWall = runCatching {
		Math.addExact(revision.observedWallTimeMs, revision.wallTimeUncertaintyMs)
	}.getOrNull() ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val zone = runCatching { ZoneId.of(revision.storedZoneId) }.getOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val firstDay = Instant.ofEpochMilli(earliestWall).atZone(zone).toLocalDate().toEpochDay()
	val lastDay = Instant.ofEpochMilli(latestWall).atZone(zone).toLocalDate().toEpochDay()
	val measuredDay = Instant.ofEpochMilli(revision.observedWallTimeMs).atZone(zone).toLocalDate().toEpochDay()
	if ((if (revision.payloadVersion == CELL_WAL_PAYLOAD_VERSION) measuredDay != revision.structuralEpochDay else
		firstDay != lastDay || firstDay != revision.structuralEpochDay) ||
		earliestWall < segment.startTimeMs || latestWall > segment.endTimeMs ||
		revision.coverageIntervalStartNanos < registrationStart ||
		revision.coverageIntervalEndNanos >= registrationEnd ||
		revision.coverageIntervalEndNanos >= authorizationEnd ||
		revision.coverageIntervalEndNanos >= sessionEnd ||
		revision.coverageIntervalEndNanos >= manifestEnd
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val capturedSources = membership.filter { source ->
		source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible
	}.mapTo(mutableSetOf(), SessionManifestSourceEntity::sourceKind)
	val controlSources = membership.filter { source ->
		source.purpose == SessionManifestPurposeCode.CONTROL
	}.mapTo(mutableSetOf(), SessionManifestSourceEntity::sourceKind)
	if (CELL_SOURCE !in capturedSources || capturedSources.intersect(controlSources).isNotEmpty()) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return AuthenticatedCellFactAuthority(
		reuseAuthority = CellAggregateReuseAuthority(
			logicalTrackingId = revision.logicalTrackingId,
			serviceRunId = revision.serviceRunId,
			sessionSegmentId = revision.sessionSegmentId,
			capturedSources = capturedSources,
			controlSources = controlSources,
			purposeEligibilityMask = revision.purposeEligibilityMask,
			captureConsentEpoch = revision.captureConsentEpoch,
			collectedDataEpoch = revision.collectedDataEpoch,
			scopeDeletionGeneration = revision.scopeDeletionGeneration,
			storedZoneId = revision.storedZoneId,
			structuralEpochDay = revision.structuralEpochDay,
		),
		aggregate = authenticatedAggregate.toHistoricalAggregate(),
		walCreatedAtMs = wal.createdAtMs,
		retentionLowerBoundMs = earliestWall,
	)
}

private suspend fun AppDatabase.boundedAuthorizationMembers(
	registrationGeneration: Long,
	authorizationRevision: Long,
	limits: CellCapturedMaintenanceLimits,
) = cellCapturedFactDao().maintenanceAuthorizationMembers(
	CELL_SOURCE,
	registrationGeneration,
	authorizationRevision,
	limits.maximumAuthorizationMembers + 1,
).also { rows ->
	if (rows.size > limits.maximumAuthorizationMembers) throw CellCapturedMaintenanceLimitExceeded()
}

/**
 * Authenticates the exact direct capture demands fenced by the consent change before retiring
 * them. A prepared BLOCKED demand was never provider authority; a RETIRING demand keeps its first
 * complete, monotonic source-clock boundary while the current provider authorization and callback
 * barrier independently prove that it can no longer admit capture.
 */
private suspend fun AppDatabase.authenticateRetiringCellCaptureDemands(
	demands: List<SourceDemandEntity>,
	revokedConsent: SourceConsentEpochEntity,
) {
	for (demand in demands) {
		val stateShape = when (demand.status) {
			SourceDemandEntity.STATUS_RETIRING -> {
				val retireBootId = demand.retireBootId
				val retireElapsedNanos = demand.retireElapsedRealtimeNanos
				val retiredAtMs = demand.retiredAtMs
				retireBootId != null && retireElapsedNanos != null && retiredAtMs != null &&
					retiredAtMs >= demand.requestedAtMs &&
					(retireBootId != demand.requestedBootId ||
						retireElapsedNanos >= demand.requestedElapsedRealtimeNanos)
			}
			SourceDemandEntity.STATUS_BLOCKED ->
				demand.retireBootId == null && demand.retireElapsedRealtimeNanos == null &&
					demand.retiredAtMs == null
			else -> false
		}
		val policy = sourcePolicyDao().policyAtRevision(demand.sourcePolicyRevision, CELL_SOURCE)
		val consent = sourcePolicyDao().consentEpoch(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			demand.consentEpoch,
		)
		if (!stateShape || demand.sourceKind != CELL_SOURCE ||
			demand.purpose != CAPTURE_PURPOSE || !demand.persistenceEligible ||
			demand.logicalTrackingId.isNullOrBlank() || demand.serviceRunId.isNullOrBlank() ||
			demand.manifestRevision == null || demand.lifecycleLeaseGeneration == null ||
			demand.sourcePolicyRevision >= revokedConsent.policyRevision || policy == null || consent == null ||
			!policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != demand.consentEpoch || policy.qosCode != demand.qosCode ||
			!consent.eligible || !consent.persistenceEligible ||
			consent.policyRevision > policy.policyRevision ||
			consent.effectiveWallTimeMs > demand.requestedAtMs ||
			(consent.effectiveBootId == demand.requestedBootId &&
				consent.effectiveElapsedRealtimeNanos > demand.requestedElapsedRealtimeNanos) ||
			demand.requestedBootId != policy.effectiveBootId ||
			demand.requestedElapsedRealtimeNanos < policy.effectiveElapsedRealtimeNanos ||
			demand.requestedAtMs < policy.effectiveWallTimeMs
		) block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

/**
 * A compatible Cell provider may remain ACTIVE for CONTROL after capture consent is revoked. Its
 * latest authorization must be reconstructed from the exact active demands, and the provider
 * owner must have acknowledged the complete historical capture revision after draining its FIFO.
 */
private suspend fun AppDatabase.authenticateCellCaptureDeletionBarrier(
	registration: ProviderRegistrationGenerationEntity,
	expectedCollectedDataEpoch: Long,
	currentPolicy: SourcePolicyEntity,
	limits: CellCapturedMaintenanceLimits,
) {
	val activeShape = registration.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
		registration.acceptedAtMs != null && registration.acceptedElapsedRealtimeNanos != null &&
		registration.retiredAtMs == null && registration.retiredElapsedRealtimeNanos == null &&
		registration.failureCode == null
	val retiringShape = registration.status == ProviderRegistrationGenerationEntity.STATUS_RETIRING &&
		registration.acceptedAtMs != null && registration.acceptedElapsedRealtimeNanos != null &&
		registration.retiredAtMs != null && registration.retiredElapsedRealtimeNanos != null &&
		!registration.failureCode.isNullOrBlank()
	if ((!activeShape && !retiringShape) || registration.sourceKind != CELL_SOURCE ||
		registration.ownerScope != "source-broker:$CELL_SOURCE" ||
		registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
		registration.providerProcessIncarnationId.isNullOrBlank() ||
		registration.collectedDataEpoch != expectedCollectedDataEpoch
	) block(CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)

	val latestRevision = cellCapturedFactDao().maximumRegistrationAuthorizationRevision(
		CELL_SOURCE,
		registration.registrationGeneration,
	)
	if (latestRevision <= 0L) {
		block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val rows = boundedAuthorizationMembers(registration.registrationGeneration, latestRevision, limits)
	val authorization = try {
		rows.toAuthorizationSnapshotOrNull()
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		null
	} ?: block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val activeDemands = cellCapturedFactDao().activeCellDemandsForDeletion(
		CELL_SOURCE,
		limits.maximumDirectDemands + 1,
	)
	if (activeDemands.size > limits.maximumDirectDemands) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	authenticateCurrentCellDemands(activeDemands, currentPolicy, authorization)
	val expectedRows = SourceBrokerAuthorization.rows(
		sourceKind = CELL_SOURCE,
		registrationGeneration = registration.registrationGeneration,
		authorizationRevision = latestRevision,
		demands = activeDemands,
		effectiveBootId = authorization.effectiveBootId,
		effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = rows.first().effectiveWallTimeMs,
	)
	if (rows.sortedBy { row -> row.memberId } != expectedRows.sortedBy { row -> row.memberId }) {
		block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (authorization.authorizedMembers.any { member ->
			member.persistenceEligible && member.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}) block(CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)

	val maximumCaptureRevision = sourceBrokerDao().maximumCaptureAuthorizationRevision(
		CELL_SOURCE,
		registration.registrationGeneration,
	)
	when {
		registration.captureCallbackBarrierAuthorizationRevision < maximumCaptureRevision ->
			block(CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
		registration.captureCallbackBarrierAuthorizationRevision > maximumCaptureRevision &&
			!hasExactRadioSessionCaptureCallbackBarrier(registration, authorization, maximumCaptureRevision) ->
			block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

/** Independently policy-authenticated CONTROL and Ambient may keep the shared provider resident. */
private suspend fun AppDatabase.authenticateCurrentCellDemands(
	demands: List<SourceDemandEntity>,
	currentPolicy: SourcePolicyEntity,
	authorization: SourceAuthorizationSnapshot,
) {
	for (demand in demands) {
		if (demand.sourceKind != CELL_SOURCE) {
			block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE) continue
		if (demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT) {
			val policy = sourcePolicyDao().policyAtRevision(demand.sourcePolicyRevision, CELL_SOURCE)
			val consent = sourcePolicyDao().latestConsentEpoch(CELL_SOURCE, SourceBrokerPurpose.AMBIENT_PRODUCT)
			if (policy == null || !policy.hasExactEligibleAmbientConsentReference(consent) ||
				!currentPolicy.hasExactEligibleAmbientConsentReference(consent) || consent == null ||
				policy.ambientConsentEpoch != demand.consentEpoch || currentPolicy.ambientConsentEpoch != demand.consentEpoch ||
				!demand.persistenceEligible ||
				demand.status != SourceDemandEntity.STATUS_ACTIVE || demand.logicalTrackingId != null ||
				demand.serviceRunId != null || demand.manifestRevision != null || demand.lifecycleLeaseGeneration != null ||
				demand.retireBootId != null || demand.retireElapsedRealtimeNanos != null || demand.retiredAtMs != null ||
				consent.policyRevision > policy.policyRevision ||
				!policy.isEffectiveAtOrBefore(demand.requestedBootId, demand.requestedElapsedRealtimeNanos, demand.requestedAtMs) ||
				!consent.isEffectiveAtOrBefore(demand.requestedBootId, demand.requestedElapsedRealtimeNanos, demand.requestedAtMs) ||
				demand.requestedBootId != authorization.effectiveBootId ||
				demand.requestedElapsedRealtimeNanos > authorization.effectiveElapsedRealtimeNanos
			) block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			continue
		}
		if (demand.purpose !in setOf(
				SourceBrokerPurpose.CONTROL_AUTOSTART,
				SourceBrokerPurpose.CONTROL_CONTINUATION,
			)
		) block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val policy = sourcePolicyDao().policyAtRevision(demand.sourcePolicyRevision, CELL_SOURCE)
		val consent = sourcePolicyDao().consentEpoch(
			CELL_SOURCE,
			CONTROL_POLICY_PURPOSE,
			demand.consentEpoch,
		)
		if (policy == null || consent == null ||
			policy.controlConsentEpoch != demand.consentEpoch ||
			policy.controlPersistenceEligible != demand.persistenceEligible ||
			!consent.eligible || consent.persistenceEligible != demand.persistenceEligible ||
			consent.policyRevision > policy.policyRevision ||
			demand.requestedBootId != policy.effectiveBootId ||
			demand.requestedElapsedRealtimeNanos < policy.effectiveElapsedRealtimeNanos ||
			demand.requestedAtMs < policy.effectiveWallTimeMs
		) block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.boundedAuthorizationAt(
	registrationGeneration: Long,
	bootId: String,
	observedElapsedRealtimeNanos: Long,
	limits: CellCapturedMaintenanceLimits,
) = cellCapturedFactDao().maintenanceAuthorizationAt(
	CELL_SOURCE,
	registrationGeneration,
	bootId,
	observedElapsedRealtimeNanos,
	limits.maximumAuthorizationMembers + 1,
).also { rows ->
	if (rows.size > limits.maximumAuthorizationMembers) throw CellCapturedMaintenanceLimitExceeded()
}.toAuthorizationSnapshotOrNull()
	?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

private fun authenticateAggregateDependencies(
	lineages: List<CellCapturedLineage>,
	lineagesById: Map<String, CellCapturedLineage>,
	reuseAuthorityById: Map<String, CellAggregateReuseAuthority>,
	aggregateById: Map<String, CellHistoricalIdentityFreeAggregate>,
) {
	for (lineage in lineages) {
		val ownerId = lineage.aggregateOwnerLogicalFactId ?: continue
		val ownerRevision = lineage.aggregateOwnerSemanticRevision
			?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val owner = lineagesById[ownerId]
			?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val referenced = owner.revisions.singleOrNull { revision ->
			revision.semanticRevision == ownerRevision
		}
		val dependent = lineage.revisions.last()
		val ownerAuthority = reuseAuthorityById[owner.logicalFactId]
		val dependentAuthority = reuseAuthorityById[lineage.logicalFactId]
		val dependentAggregate = aggregateById[lineage.logicalFactId]
		if (referenced == null || owner.revisions.last() != referenced || owner.revisions.any { revision ->
				revision.factKind != CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
			} || dependent.factKind != CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ||
			ownerAuthority == null || dependentAuthority == null ||
			!dependentAuthority.canReuseAggregateFrom(ownerAuthority) ||
			!referenced.hasFiniteAggregateReuseAuthority() ||
			dependentAggregate == null || !referenced.matchesIdentityFreeAggregate(dependentAggregate)
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.authenticateCellDeletionGenerations(
	lineages: List<CellCapturedLineage>,
	generations: List<CellCaptureDeletionGenerationEntity>,
	collectedDataEpoch: Long,
) {
	val scopesWithFacts = lineages.groupBy(CellCapturedLineage::scope)
	val generationByScope = generations.associateBy { generation ->
		CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for ((scope, scopedLineages) in scopesWithFacts) {
		val declaredGenerations = scopedLineages.map { lineage ->
			lineage.revisions.first().scopeDeletionGeneration
		}.distinct()
		if (declaredGenerations != listOf(0L) || generationByScope[scope] != null) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			scope.logicalTrackingId,
			scope.serviceRunId,
		)
		if (sourceDeletionFenceDao().contains(
				CELL_SOURCE,
				CAPTURE_PURPOSE,
				SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				digest,
			)
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for (generation in generations) {
		val scope = CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		if (generation.collectedDataEpoch != collectedDataEpoch || scope in scopesWithFacts) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val expectedFence = SourceDeletionFenceEntity.createLogicalServiceRun(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			generation.logicalTrackingId,
			generation.serviceRunId,
			generation.generation,
			generation.collectedDataEpoch,
			generation.updatedAtMs,
		)
		val retained = sourceDeletionFenceDao().get(
			expectedFence.sourceKind,
			expectedFence.purpose,
			expectedFence.scopeKind,
			expectedFence.scopeIdentityDigest,
		)
		if (retained != expectedFence) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
}

private fun affectedDependencyClosure(
	lineages: List<CellCapturedLineage>,
	canonicalRetentionLowerBoundById: Map<String, Long>,
	beforeMs: Long,
): Set<String> {
	val selected = lineages.filter { lineage ->
		canonicalRetentionLowerBoundById.getValue(lineage.logicalFactId) < beforeMs
	}.mapTo(mutableSetOf(), CellCapturedLineage::logicalFactId)
	if (selected.isEmpty()) return emptySet()
	val dependentsByOwner = lineages.filter { lineage -> lineage.aggregateOwnerLogicalFactId != null }
		.groupBy(CellCapturedLineage::aggregateOwnerLogicalFactId)
	val ownerByDependent = lineages.associate { lineage ->
		lineage.logicalFactId to lineage.aggregateOwnerLogicalFactId
	}
	var changed: Boolean
	do {
		changed = false
		for (lineageId in selected.toList()) {
			val ownerId = ownerByDependent[lineageId]
			if (ownerId != null && selected.add(ownerId)) changed = true
		}
		for (ownerId in selected.toList()) {
			for (dependent in dependentsByOwner[ownerId].orEmpty()) {
				if (selected.add(dependent.logicalFactId)) changed = true
			}
		}
	} while (changed)
	return selected
}

private suspend fun AppDatabase.authenticateHistoricalCellPlan(
	revision: Long,
	sourcePolicyRevision: Long,
	expectedPhysicalFingerprint: String,
	limits: CellCapturedMaintenanceLimits,
): CellHistoricalPlan {
	val planHeader = sourcePlanStateDao().revision(revision)
	val desiredPlans = cellCapturedFactDao().maintenanceDesiredPlans(
		revision,
		limits.maximumSourcesPerPlan + 1,
	)
	val desiredCell = desiredPlans.singleOrNull { plan -> plan.sourceKind == CELL_SOURCE }
	if (desiredPlans.size > limits.maximumSourcesPerPlan || planHeader == null || desiredCell == null ||
		planHeader.revision != revision || planHeader.planId.isBlank() || planHeader.createdAtMs < 0L ||
		planHeader.sourcePolicyRevision != sourcePolicyRevision ||
		desiredCell.revision != revision || desiredCell.payloadVersion != CELL_PLAN_PAYLOAD_VERSION ||
		desiredCell.payload.size > limits.maximumPlanPayloadBytes ||
		desiredCell.payloadChecksum != sha256(desiredCell.payload)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val plan = decodeCanonicalCellPlan(desiredCell.payload)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (plan.revision != revision || !plan.hasSupportedHistoricalShape() ||
		plan.physicalConfigurationFingerprint() != expectedPhysicalFingerprint
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return plan
}

private suspend fun AppDatabase.authenticateCellRegistration(
	registration: ProviderRegistrationGenerationEntity,
	expectedSourceInstanceId: String,
	expectedCollectedDataEpoch: Long,
	expectedClockDomainId: String,
	expectedPhysicalFingerprint: String,
	expectedAuthorizationRevision: Long,
): LongRange {
	val acceptedAtMs = registration.acceptedAtMs
	val acceptedElapsedNanos = registration.acceptedElapsedRealtimeNanos
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val retiredAtMs = registration.retiredAtMs
	val retiredElapsedNanos = registration.retiredElapsedRealtimeNanos
	val maximumCaptureAuthorizationRevision = sourceBrokerDao().maximumCaptureAuthorizationRevision(
		CELL_SOURCE,
		registration.registrationGeneration,
	)
	// The barrier is monotonic provider-owner evidence. Historical facts need only reject an
	// impossible acknowledgement without its exact original no-CAP envelope. Consent deletion
	// separately checks the current purpose and finite callback barrier; later capture cannot revoke old facts.
	val acceptedShape = when (registration.status) {
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
			retiredAtMs == null && retiredElapsedNanos == null && registration.failureCode == null
		ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
			retiredAtMs != null && retiredElapsedNanos != null && !registration.failureCode.isNullOrBlank()
		ProviderRegistrationGenerationEntity.STATUS_RETIRED ->
			retiredAtMs != null && retiredElapsedNanos != null
		else -> false
	}
	if (!acceptedShape || registration.sourceKind != CELL_SOURCE ||
		registration.sourceInstanceId != expectedSourceInstanceId ||
		registration.ownerScope != "source-broker:$CELL_SOURCE" ||
		registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
		registration.providerProcessIncarnationId.isNullOrBlank() ||
		registration.physicalConfigurationFingerprint != expectedPhysicalFingerprint ||
		registration.collectedDataEpoch != expectedCollectedDataEpoch ||
		registration.clockDomainId != expectedClockDomainId || acceptedAtMs == null ||
		registration.reservedAtMs > acceptedAtMs ||
		registration.reservedElapsedRealtimeNanos > acceptedElapsedNanos ||
		(retiredAtMs != null && acceptedAtMs > retiredAtMs) ||
		(retiredElapsedNanos != null && acceptedElapsedNanos > retiredElapsedNanos) ||
		expectedAuthorizationRevision <= 0L ||
		expectedAuthorizationRevision > maximumCaptureAuthorizationRevision ||
		(registration.captureCallbackBarrierAuthorizationRevision > maximumCaptureAuthorizationRevision &&
			!hasExactRetainedRadioSessionCaptureCallbackBarrier(registration, expectedAuthorizationRevision))
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return acceptedElapsedNanos..(retiredElapsedNanos ?: Long.MAX_VALUE)
}

private suspend fun AppDatabase.loadCellCapturedCursors(
	limits: CellCapturedMaintenanceLimits,
): Map<String, CellCapturedFactCursorEntity> {
	val rows = mutableListOf<CellCapturedFactCursorEntity>()
	var after: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumCursors - rows.size
		if (remaining <= 0) {
			if (cellCapturedFactDao().maintenanceCursorPage(WRITER_ID, WRITER_VERSION, after, 1)
					.isNotEmpty()
			) throw CellCapturedMaintenanceLimitExceeded()
			break
		}
		val pageSize = minOf(remaining, CURSOR_PAGE_SIZE)
		val page = cellCapturedFactDao().maintenanceCursorPage(
			WRITER_ID,
			WRITER_VERSION,
			after,
			pageSize,
		)
		if (page.isEmpty()) break
		if (page != page.sortedBy(CellCapturedFactCursorEntity::logicalFactId) ||
			page.distinctBy(CellCapturedFactCursorEntity::logicalFactId).size != page.size
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		rows += page
		after = page.last().logicalFactId
		if (page.size < pageSize) break
	}
	val result = rows.associateBy(CellCapturedFactCursorEntity::logicalFactId)
	if (result.size != rows.size) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return result
}

private suspend fun AppDatabase.loadCellDeletionGenerations(
	limits: CellCapturedMaintenanceLimits,
): List<CellCaptureDeletionGenerationEntity> {
	val rows = mutableListOf<CellCaptureDeletionGenerationEntity>()
	var afterLogicalTrackingId: String? = null
	var afterServiceRunId: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumDeletionGenerations - rows.size
		if (remaining <= 0) {
			if (cellCapturedFactDao().maintenanceDeletionGenerationPage(
					afterLogicalTrackingId,
					afterServiceRunId,
					1,
				).isNotEmpty()
			) throw CellCapturedMaintenanceLimitExceeded()
			break
		}
		val pageSize = minOf(remaining, DELETION_GENERATION_PAGE_SIZE)
		val page = cellCapturedFactDao().maintenanceDeletionGenerationPage(
			afterLogicalTrackingId,
			afterServiceRunId,
			pageSize,
		)
		if (page.isEmpty()) break
		rows += page
		afterLogicalTrackingId = page.last().logicalTrackingId
		afterServiceRunId = page.last().serviceRunId
		if (page.size < pageSize) break
	}
	return rows
}

private suspend fun AppDatabase.loadCellCapturedRetentionProofsForSourceErase(
	limits: CellCapturedMaintenanceLimits,
): List<CapturedRadioFactRetentionProofEntity> {
	val rows = mutableListOf<CapturedRadioFactRetentionProofEntity>()
	var afterAdmissionOrdinal: Long? = null
	var afterLogicalFactId: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumLogicalFacts - rows.size
		if (remaining <= 0) {
			if (capturedRadioFactRetentionProofDao().sourceErasePage(
					CELL_SOURCE,
					afterAdmissionOrdinal,
					afterLogicalFactId,
					1,
				).isNotEmpty()
			) throw CellCapturedMaintenanceLimitExceeded()
			break
		}
		val pageSize = minOf(remaining, limits.revisionPageSize)
		val page = capturedRadioFactRetentionProofDao().sourceErasePage(
			CELL_SOURCE,
			afterAdmissionOrdinal,
			afterLogicalFactId,
			pageSize,
		)
		if (page.isEmpty()) break
		rows += page
		afterAdmissionOrdinal = page.last().sourceAdmissionOrdinal
		afterLogicalFactId = page.last().logicalFactId
		if (page.size < pageSize) break
	}
	return rows
}

private suspend fun AppDatabase.loadCellSourceEventRetentionProofsForSourceErase(
	limits: CellCapturedMaintenanceLimits,
): List<SourceEventRetentionProofEntity> {
	val rows = mutableListOf<SourceEventRetentionProofEntity>()
	var afterAdmissionOrdinal: Long? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumWalEvents - rows.size
		if (remaining <= 0) {
			if (sourceEventRetentionProofDao().sourceCaptureErasePage(
					CELL_SOURCE,
					SourceBrokerPurpose.MASK_SESSION_CAPTURE,
					afterAdmissionOrdinal,
					1,
				).isNotEmpty()
			) throw CellCapturedMaintenanceLimitExceeded()
			break
		}
		val pageSize = minOf(remaining, limits.revisionPageSize)
		val page = sourceEventRetentionProofDao().sourceCaptureErasePage(
			CELL_SOURCE,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			afterAdmissionOrdinal,
			pageSize,
		)
		if (page.isEmpty()) break
		rows += page
		afterAdmissionOrdinal = page.last().sourceAdmissionOrdinal
		if (page.size < pageSize) break
	}
	return rows
}

private suspend fun AppDatabase.loadCellSourceEventRetentionScopeProofsForSourceErase(
	limits: CellCapturedMaintenanceLimits,
): List<SourceEventRetentionScopeProofEntity> {
	val rows = mutableListOf<SourceEventRetentionScopeProofEntity>()
	var afterScopeDigest: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumDeletionGenerations - rows.size
		if (remaining <= 0) {
			if (sourceEventRetentionScopeProofDao().sourceErasePage(
					CELL_SOURCE,
					afterScopeDigest,
					1,
				).isNotEmpty()
			) throw CellCapturedMaintenanceLimitExceeded()
			break
		}
		val pageSize = minOf(remaining, limits.revisionPageSize)
		val page = sourceEventRetentionScopeProofDao().sourceErasePage(
			CELL_SOURCE,
			afterScopeDigest,
			pageSize,
		)
		if (page.isEmpty()) break
		rows += page
		afterScopeDigest = page.last().logicalRunScopeDigest
		if (page.size < pageSize) break
	}
	return rows
}

/**
 * Audits every retained current-epoch Cell WAL carrier without retaining its payload in memory.
 * Capture-authorized carriers keep a run fence discoverable even after product retention removed
 * their fact, which prevents a later writer replay from resurrecting deleted Cell history.
 */
internal suspend fun AppDatabase.loadCapturedCellWalScopesForDeletion(
	evidenceState: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
): CellCapturedWalAudit {
	val dao = cellCapturedFactDao()
	val total = dao.maintenanceWalCount(CELL_SOURCE)
	if (total > limits.maximumWalEvents) throw CellCapturedMaintenanceLimitExceeded()
	val scopes = linkedSetOf<CellCapturedRunScope>()
	val carriers = mutableListOf<CellCapturedWalCarrier>()
	var afterAdmissionOrdinal = 0L
	var loaded = 0
	var latestDurableTimeMs = 0L
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumWalEvents - loaded
		if (remaining <= 0) break
		val pageSize = minOf(remaining, WAL_PAGE_SIZE)
		val keys = dao.maintenanceWalKeys(CELL_SOURCE, afterAdmissionOrdinal, pageSize)
		if (keys.isEmpty()) break
		if (keys != keys.sortedBy { key -> key.admissionOrdinal } ||
			keys.any { key -> key.admissionOrdinal <= afterAdmissionOrdinal } ||
			keys.distinctBy { key -> key.admissionOrdinal }.size != keys.size
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		for (key in keys) {
			currentCoroutineContext().ensureActive()
			val payloadBytes = dao.maintenanceWalPayloadByteCount(key.eventId)
			if (payloadBytes == null || payloadBytes > limits.maximumWalPayloadBytes) {
				block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			val wal = sourceEventWalDao().getByEventId(key.eventId)
			if (wal == null || wal.admissionOrdinal != key.admissionOrdinal ||
				wal.sourceKind != CELL_SOURCE || !wal.hasQualifiedIntegrity()
			) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			if (wal.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch ||
				wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
			) continue
			if (wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
				val groups = authenticateHistoricalCapturedCellWalCarriers(wal, limits)
				scopes += groups.map { it.scope }
				carriers += groups
				latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)
				continue
			}
			val captureBindingFields = listOf(
				wal.logicalTrackingId,
				wal.serviceRunId,
				wal.sourcePolicyRevision,
				wal.captureConsentEpoch,
				wal.sessionManifestRevision,
				wal.lifecycleLeaseGeneration,
			)
			val captureEligible = wal.authorizationPurposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
			if (!captureEligible && captureBindingFields.all { field -> field == null }) continue
			val captureEnvelopeFields = captureBindingFields + listOf(
				wal.configRevision,
				wal.physicalConfigurationFingerprint,
				wal.authorizationRevision,
				wal.authorizationFingerprint,
			)
			if (!captureEligible || captureEnvelopeFields.any { field -> field == null } ||
				wal.logicalTrackingId.isNullOrBlank() || wal.serviceRunId.isNullOrBlank() ||
				wal.deliveryIdentity?.matches(LOWERCASE_SHA_256) != true ||
				wal.deliveryUnitIndex != 0 || wal.deliveryUnitCount != 1 || wal.sourceSequence < 0L ||
				wal.configRevision!! <= 0L || wal.authorizationRevision!! <= 0L ||
				wal.sourcePolicyRevision!! <= 0L || wal.captureConsentEpoch!! < 0L ||
				wal.sessionManifestRevision!! <= 0L || wal.lifecycleLeaseGeneration!! <= 0L ||
				wal.planAttribution != CAPTURED_REGISTRATION_PLAN_ATTRIBUTION ||
				wal.activityAutomationEpoch != null
			) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			val scope = authenticateCapturedCellWalScopeForDeletion(wal, limits)
			scopes += scope
			carriers += CellCapturedWalCarrier(
				wal.eventId,
				wal.admissionOrdinal,
				scope,
				wal.sourceInstanceId,
				wal.registrationGeneration,
				requireNotNull(wal.sourcePolicyRevision),
				requireNotNull(wal.captureConsentEpoch),
				wal.capturedCollectedDataEpoch,
				wal.integrityIdentity,
				earliestCoveredWallTime(
					requireNotNull(wal.wallTimeMs),
					requireNotNull(wal.wallTimeUncertaintyMs),
					wal.observedElapsedNanos,
					requireNotNull(wal.observedIntervalStartNanos),
				),
			)
			latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)
		}
		loaded = Math.addExact(loaded, keys.size)
		afterAdmissionOrdinal = keys.last().admissionOrdinal
		if (keys.size < pageSize) break
	}
	if (loaded.toLong() != total) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return CellCapturedWalAudit(scopes, carriers, latestDurableTimeMs)
}

/**
 * Audits only WAL carriers that can belong to one portable logical-entry/run closure. The OR
 * predicate deliberately includes malformed one-sided bindings so the caller can reject them.
 */
@Suppress("ComplexCondition", "LongMethod")
internal suspend fun AppDatabase.loadPortableCapturedCellWalScopes(
	evidenceState: SourceEvidenceState,
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	capturedServiceRunIds: List<String>,
	activationFloorOrdinal: Long,
	admissionCeilingOrdinal: Long,
	limits: CellCapturedMaintenanceLimits,
): CellCapturedWalAudit {
	require(logicalTrackingId.isNotBlank())
	require(serviceRunIds.isNotEmpty() && serviceRunIds.distinct().size == serviceRunIds.size)
	require(capturedServiceRunIds.isNotEmpty() && capturedServiceRunIds.all(serviceRunIds::contains))
	require(capturedServiceRunIds.distinct().size == capturedServiceRunIds.size)
	require(activationFloorOrdinal >= 0L)
	require(admissionCeilingOrdinal >= activationFloorOrdinal)
	val dao = cellCapturedFactDao()
	val keys = dao.portableWalClosureKeys(
		CELL_SOURCE,
		logicalTrackingId,
		serviceRunIds,
		capturedServiceRunIds,
		activationFloorOrdinal,
		admissionCeilingOrdinal,
		limits.maximumWalEvents + 1,
	)
	if (keys.size > limits.maximumWalEvents) throw CellCapturedMaintenanceLimitExceeded()
	if (keys != keys.sortedBy { key -> key.admissionOrdinal } ||
		keys.any { key -> key.admissionOrdinal <= 0L } ||
		keys.distinctBy { key -> key.admissionOrdinal }.size != keys.size ||
		keys.distinctBy { key -> key.eventId }.size != keys.size
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val scopes = linkedSetOf<CellCapturedRunScope>()
	val carriers = mutableListOf<CellCapturedWalCarrier>()
	var latestDurableTimeMs = 0L
	for (key in keys) {
		currentCoroutineContext().ensureActive()
		val payloadBytes = dao.maintenanceWalPayloadByteCount(key.eventId)
		if (payloadBytes == null || payloadBytes > limits.maximumWalPayloadBytes) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val wal = sourceEventWalDao().getByEventId(key.eventId)
		if (wal == null || wal.admissionOrdinal != key.admissionOrdinal ||
			wal.sourceKind != CELL_SOURCE || !wal.hasQualifiedIntegrity()
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		if (wal.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch ||
			wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) continue
		if (wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			val groups = authenticateHistoricalCapturedCellWalCarriers(wal, limits)
			scopes += groups.map { it.scope }
			carriers += groups
			latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)
			continue
		}
		val captureBindingFields = listOf(
			wal.logicalTrackingId,
			wal.serviceRunId,
			wal.sourcePolicyRevision,
			wal.captureConsentEpoch,
			wal.sessionManifestRevision,
			wal.lifecycleLeaseGeneration,
		)
		val captureEligible = wal.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
		if (!captureEligible && captureBindingFields.all { field -> field == null }) continue
		val captureEnvelopeFields = captureBindingFields + listOf(
			wal.configRevision,
			wal.physicalConfigurationFingerprint,
			wal.authorizationRevision,
			wal.authorizationFingerprint,
		)
		if (!captureEligible || captureEnvelopeFields.any { field -> field == null } ||
			wal.logicalTrackingId.isNullOrBlank() || wal.serviceRunId.isNullOrBlank() ||
			wal.deliveryIdentity?.matches(LOWERCASE_SHA_256) != true ||
			wal.deliveryUnitIndex != 0 || wal.deliveryUnitCount != 1 || wal.sourceSequence < 0L ||
			wal.configRevision!! <= 0L || wal.authorizationRevision!! <= 0L ||
			wal.sourcePolicyRevision!! <= 0L || wal.captureConsentEpoch!! < 0L ||
			wal.sessionManifestRevision!! <= 0L || wal.lifecycleLeaseGeneration!! <= 0L ||
			wal.planAttribution != CAPTURED_REGISTRATION_PLAN_ATTRIBUTION ||
			wal.activityAutomationEpoch != null
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val scope = authenticateCapturedCellWalScopeForDeletion(wal, limits)
		scopes += scope
		carriers += CellCapturedWalCarrier(
			wal.eventId,
			wal.admissionOrdinal,
			scope,
			wal.sourceInstanceId,
			wal.registrationGeneration,
			requireNotNull(wal.sourcePolicyRevision),
			requireNotNull(wal.captureConsentEpoch),
			wal.capturedCollectedDataEpoch,
			wal.integrityIdentity,
			earliestCoveredWallTime(
				requireNotNull(wal.wallTimeMs),
				requireNotNull(wal.wallTimeUncertaintyMs),
				wal.observedElapsedNanos,
				requireNotNull(wal.observedIntervalStartNanos),
			),
		)
		latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)
	}
	return CellCapturedWalAudit(scopes, carriers, latestDurableTimeMs)
}

/** One raw callback can retain several historical session scopes, each with its own readings. */
private suspend fun AppDatabase.authenticateHistoricalCapturedCellWalCarriers(
	wal: SourceEventWalEntity,
	limits: CellCapturedMaintenanceLimits,
): List<CellCapturedWalCarrier> {
	val carrier = (CellWalCarrierAuthenticator.authenticate(wal) as? CellWalCarrierAuthentication.Authenticated)
		?.carrier ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val registration = sourceBrokerDao().registration(CELL_SOURCE, wal.registrationGeneration)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val start = registration.acceptedElapsedRealtimeNanos
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val end = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
	val items = qualifyCellPurposeItems(wal, carrier.payload, start, end)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return items.historicalMembers().filter { member ->
		member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
			items.forDemand(requireNotNull(member.demandId)).isNotEmpty()
	}.map { member ->
		val purpose = items.cellPurposeWindow(carrier.payload, requireNotNull(member.demandId), end)
			as? CellPurposeItems.Qualified
			?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val times = purpose.indexes.map { requireNotNull(carrier.payload.observations[it].providerTimestampNanos) }
		val selectedStart = times.minOrNull() ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val selectedEnd = requireNotNull(times.maxOrNull())
		val scope = authenticateCapturedCellWalScopeForDeletion(wal, limits, member, purpose, selectedStart, selectedEnd)
		CellCapturedWalCarrier(wal.eventId, wal.admissionOrdinal, scope, wal.sourceInstanceId,
			wal.registrationGeneration, requireNotNull(member.sourcePolicyRevision), requireNotNull(member.consentEpoch),
			wal.capturedCollectedDataEpoch, wal.integrityIdentity,
			requireNotNull(capturePointWallInterval(wal, selectedStart, selectedEnd)).first, wal.payloadVersion)
	}
}

/**
 * Proves that a retained WAL-only scope was genuinely capture-authorized before it can receive a
 * no-resurrection fence. This deliberately does not decode provider payload: deletion neither
 * materializes nor classifies it, but it must not trust caller-shaped scope scalars either.
 */
@Suppress("ComplexCondition", "LongMethod")
private suspend fun AppDatabase.authenticateCapturedCellWalScopeForDeletion(
	wal: SourceEventWalEntity,
	limits: CellCapturedMaintenanceLimits,
	historicalMember: SourceAuthorizationEntity? = null,
	purposeItems: CellPurposeItems.Qualified? = null,
	selectedStartNanos: Long = wal.observedElapsedNanos,
	selectedEndNanos: Long = wal.observedElapsedNanos,
): CellCapturedRunScope {
	val logicalTrackingId = requireNotNull(historicalMember?.logicalTrackingId ?: wal.logicalTrackingId)
	val serviceRunId = requireNotNull(historicalMember?.serviceRunId ?: wal.serviceRunId)
	val manifestRevision = requireNotNull(historicalMember?.manifestRevision ?: wal.sessionManifestRevision)
	val configurationRevision = if (historicalMember != null) {
		sourceSessionDao().manifest(logicalTrackingId, manifestRevision)?.acquisitionPlanRevision
			?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	} else requireNotNull(wal.configRevision)
	val policyRevision = requireNotNull(historicalMember?.sourcePolicyRevision ?: wal.sourcePolicyRevision)
	val consentEpoch = requireNotNull(historicalMember?.consentEpoch ?: wal.captureConsentEpoch)
	val leaseGeneration = requireNotNull(historicalMember?.lifecycleLeaseGeneration ?: wal.lifecycleLeaseGeneration)
	val physicalFingerprint = requireNotNull(wal.physicalConfigurationFingerprint)
	val authorizationRevision = historicalMember?.authorizationRevision ?: requireNotNull(wal.authorizationRevision)
	val authorizationFingerprint = historicalMember?.authorizationFingerprint ?: requireNotNull(wal.authorizationFingerprint)
	val purposeMask = historicalMember?.purposeEligibilityMask ?: wal.authorizationPurposeEligibilityMask
	val observedStart = requireNotNull(wal.observedIntervalStartNanos)
	// Earlier children may be stale. The delivery's latest observed child anchors its
	// capture attribution; retained facts qualify their individual children separately.
	val capturePoint = selectedStartNanos
	if (observedStart < 0L || wal.observedElapsedNanos < observedStart ||
		wal.receivedElapsedNanos < wal.observedElapsedNanos || wal.wallTimeMs == null ||
		wal.wallTimeMs < 0L || wal.wallTimeUncertaintyMs == null || wal.wallTimeUncertaintyMs < 0L
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val sequenceRow = sourceEventWalDao().getBySourceSequence(
		CELL_SOURCE,
		wal.sourceInstanceId,
		wal.sourceSequence,
	)
	val completeDelivery = sourceEventWalDao().deliveryUnitsBounded(
		CELL_SOURCE,
		wal.capturedCollectedDataEpoch,
		wal.clockDomainId,
		requireNotNull(wal.deliveryIdentity),
		2,
	).singleOrNull()
	if (sequenceRow?.eventId != wal.eventId || sequenceRow.admissionOrdinal != wal.admissionOrdinal ||
		sequenceRow.integrityIdentity != wal.integrityIdentity || completeDelivery == null ||
		completeDelivery.eventId != wal.eventId ||
		completeDelivery.admissionOrdinal != wal.admissionOrdinal ||
		completeDelivery.deliveryUnitIndex != 0 || completeDelivery.deliveryUnitCount != 1 ||
		completeDelivery.sourceInstanceId != wal.sourceInstanceId ||
		completeDelivery.registrationGeneration != wal.registrationGeneration ||
		completeDelivery.physicalConfigurationFingerprint != physicalFingerprint ||
		completeDelivery.authorizationRevision != wal.authorizationRevision ||
		completeDelivery.observedElapsedNanos != wal.observedElapsedNanos ||
		completeDelivery.observedIntervalStartNanos != observedStart ||
		completeDelivery.payloadVersion != wal.payloadVersion ||
		completeDelivery.payloadChecksum != wal.payloadChecksum
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	authenticateHistoricalCellPlan(
		configurationRevision,
		policyRevision,
		physicalFingerprint,
		limits,
	)

	val policy = sourcePolicyDao().policyAtRevision(policyRevision, CELL_SOURCE)
	val consent = sourcePolicyDao().consentEpoch(CELL_SOURCE, CAPTURE_PURPOSE, consentEpoch)
	if (policy == null || consent == null || !policy.enabled || !policy.capturePersistenceEligible ||
		policy.captureConsentEpoch != consentEpoch || policy.effectiveBootId != wal.clockDomainId ||
		policy.effectiveElapsedRealtimeNanos > capturePoint || !consent.eligible ||
		!consent.persistenceEligible || consent.policyRevision > policyRevision ||
		consent.effectiveBootId != wal.clockDomainId ||
		consent.effectiveElapsedRealtimeNanos > capturePoint
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val registration = sourceBrokerDao().registration(CELL_SOURCE, wal.registrationGeneration)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val registrationInterval = authenticateCellRegistration(
		registration = registration,
		expectedSourceInstanceId = wal.sourceInstanceId,
		expectedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
		expectedClockDomainId = wal.clockDomainId,
		expectedPhysicalFingerprint = physicalFingerprint,
		expectedAuthorizationRevision = authorizationRevision,
	)
	val registrationStart = registrationInterval.first
	val registrationEnd = registrationInterval.last
	if (capturePoint < registrationStart || capturePoint >= registrationEnd) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val authorizationRows = boundedAuthorizationMembers(
		wal.registrationGeneration,
		authorizationRevision,
		limits,
	)
	val authorization = authorizationRows.toAuthorizationSnapshotOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val endAuthorization = if (purposeItems == null) boundedAuthorizationAt(
		wal.registrationGeneration,
		wal.clockDomainId,
		wal.observedElapsedNanos,
		limits,
	) else null
	val nextRows = cellCapturedFactDao().maintenanceNextAuthorizationMembers(
		CELL_SOURCE,
		wal.registrationGeneration,
		authorizationRevision,
		limits.maximumAuthorizationMembers + 1,
	)
	if (nextRows.size > limits.maximumAuthorizationMembers) throw CellCapturedMaintenanceLimitExceeded()
	val nextAuthorization = if (nextRows.isEmpty()) null else nextRows.toAuthorizationSnapshotOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (nextAuthorization != null &&
		(nextAuthorization.effectiveBootId != wal.clockDomainId ||
			nextAuthorization.effectiveElapsedRealtimeNanos <
				authorization.effectiveElapsedRealtimeNanos)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val authorizationEnd = purposeItems?.continuousUntilNanos ?: minOf(
		registrationEnd,
		nextAuthorization?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE,
	)
	val captureMember = authorization.authorizedMembers.singleOrNull { member ->
		member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
			member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
			member.manifestRevision == manifestRevision &&
			member.lifecycleLeaseGeneration == leaseGeneration &&
			member.sourcePolicyRevision == policyRevision && member.consentEpoch == consentEpoch
	}
	val demandIds = authorization.authorizedMembers.mapNotNull { member -> member.demandId }
	val demands = sourceBrokerDao().demandsByIds(demandIds)
	val recomputed = runCatching {
		SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = wal.registrationGeneration,
			authorizationRevision = authorizationRevision,
			demands = demands,
			effectiveBootId = authorization.effectiveBootId,
			effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
		)
	}.getOrNull()
	if (authorization.isDenied || captureMember == null ||
		(purposeItems == null && endAuthorization != authorization) || demandIds.distinct().size != authorization.authorizedMembers.size ||
		demands.size != authorization.authorizedMembers.size ||
		recomputed?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId } ||
		authorization.authorizationFingerprint != authorizationFingerprint ||
		authorization.purposeEligibilityMask != purposeMask ||
		authorization.effectiveBootId != wal.clockDomainId ||
		(purposeItems?.continuousFromNanos ?: authorization.effectiveElapsedRealtimeNanos) > capturePoint ||
		selectedEndNanos >= authorizationEnd
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val sessionDao = sourceSessionDao()
	val run = sessionDao.serviceRun(serviceRunId)
	val session = sessionDao.session(logicalTrackingId)
	val cutoff = session?.let { savedSessionCutoff(it) }
	if (run == null || session == null || run.logicalTrackingId != logicalTrackingId ||
		!session.hasValidHistoricalCellShape() ||
		!run.hasValidHistoricalCellShape(session) ||
		!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, leaseGeneration) ||
		session.lifecycleLeaseGeneration < leaseGeneration ||
		run.startedElapsedNanos > capturePoint ||
		(purposeItems == null && session.finalAdmissionOrdinal != null && wal.admissionOrdinal > session.finalAdmissionOrdinal) ||
		(session.cutoffAtMs == null) != (session.cutoffElapsedNanos == null) ||
		selectedEndNanos >= requireNotNull(cutoff).endForBoot(wal.clockDomainId)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifests = sessionDao.manifestsForServiceRun(serviceRunId, limits.maximumManifestsPerRun + 1)
	val sources = trackingHistoryReadDao().manifestSources(
		listOf(serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	if (manifests.size > limits.maximumManifestsPerRun || sources.size > limits.maximumSourcesPerRun ||
		!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests) ||
		manifests.any { manifest ->
			!SessionManifestIntegrity.verify(
				manifest,
				sources.filter { source ->
					source.logicalTrackingId == manifest.logicalTrackingId &&
						source.manifestRevision == manifest.manifestRevision
				},
			)
		}
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifestIndex = manifests.indexOfFirst { manifest ->
		manifest.logicalTrackingId == logicalTrackingId &&
			manifest.manifestRevision == manifestRevision
	}
	if (manifestIndex < 0) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifest = manifests[manifestIndex]
	val membership = sources.filter { source ->
		source.logicalTrackingId == logicalTrackingId && source.manifestRevision == manifestRevision
	}
	if (membership.size > limits.maximumSourcesPerManifest) throw CellCapturedMaintenanceLimitExceeded()
	val binding = membership.singleOrNull { source ->
		source.sourceKind == CELL_SOURCE && source.purpose == CAPTURE_PURPOSE
	}
	val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
		?: minOf(requireNotNull(cutoff).endForBoot(wal.clockDomainId), registrationEnd, authorizationEnd)
	val segmentId = run.sessionSegmentId
	val segment = segmentId?.let { sessionSegmentDao().getById(it) }
	val wallInterval = capturePointWallInterval(wal, selectedStartNanos, selectedEndNanos)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (binding == null || !binding.isExactCapturedCellWalBinding(consentEpoch, manifest.sessionMode) ||
		manifest.serviceRunId != serviceRunId || manifest.sourcePolicyRevision != policyRevision ||
		manifest.acquisitionPlanRevision != configurationRevision ||
		manifest.effectiveBootId != wal.clockDomainId ||
		manifest.effectiveElapsedRealtimeNanos > capturePoint ||
		selectedEndNanos >= manifestEnd || manifest.zoneId.isBlank() ||
		!hasValidZone(manifest.zoneId) || policy.qosCode != binding.qosCode || segment == null ||
		segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId ||
		wallInterval.first < segment.startTimeMs || wallInterval.last > segment.endTimeMs
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return CellCapturedRunScope(logicalTrackingId, serviceRunId)
}

@Suppress("ComplexCondition")
private fun CellCapturedFactCursorEntity.matchesCurrent(
	revision: CellCapturedFactRevisionEntity,
): Boolean = logicalFactId == revision.logicalFactId &&
	logicalTrackingId == revision.logicalTrackingId && serviceRunId == revision.serviceRunId &&
	sessionSegmentId == revision.sessionSegmentId &&
	writerOwnerGeneration == revision.writerOwnerGeneration &&
	collectedDataEpoch == revision.collectedDataEpoch &&
	scopeDeletionGeneration == revision.scopeDeletionGeneration &&
	latestSemanticRevision == revision.semanticRevision && latestMutationId == revision.mutationId &&
	latestEffectChecksum == revision.effectChecksum &&
	latestSourceAdmissionOrdinal == revision.sourceAdmissionOrdinal &&
	cursorRevision == revision.semanticRevision && updatedAtMs == revision.appliedAtMs

private fun CellCapturedFactRevisionEntity.isExactSettlementSuccessorOf(
	previous: CellCapturedFactRevisionEntity,
): Boolean = providerAcceptanceEndNanos.isExactSettlementOf(previous.providerAcceptanceEndNanos) &&
	authorizationEffectEndNanos.isExactSettlementOf(previous.authorizationEffectEndNanos) &&
	consentEffectEndNanos.isExactSettlementOf(previous.consentEffectEndNanos) &&
	sessionRunEffectEndNanos.isExactSettlementOf(previous.sessionRunEffectEndNanos) &&
	deletionEffectEndNanos.isExactSettlementOf(previous.deletionEffectEndNanos)

private fun CellCapturedFactRevisionEntity.hasFiniteAggregateReuseAuthority(): Boolean =
	providerAcceptanceEndNanos != Long.MAX_VALUE &&
		authorizationEffectEndNanos != Long.MAX_VALUE &&
		consentEffectEndNanos != Long.MAX_VALUE &&
		sessionRunEffectEndNanos != Long.MAX_VALUE &&
		deletionEffectEndNanos != Long.MAX_VALUE

@Suppress("LongMethod")
private fun CellCapturedFactRevisionEntity.hasSameCorrectionEffect(
	other: CellCapturedFactRevisionEntity,
): Boolean = stableCorrectionParts() == other.stableCorrectionParts()

@Suppress("LongMethod")
private fun CellCapturedFactRevisionEntity.stableCorrectionParts(): List<Any?> = listOf(
	writerProjectionId,
	writerProjectionVersion,
	writerBindingGeneration,
	writerOwnerGeneration,
	logicalFactId,
	factKind,
	aggregateOwnerLogicalFactId,
	aggregateOwnerSemanticRevision,
	logicalTrackingId,
	serviceRunId,
	sessionSegmentId,
	purpose,
	sourceDeliveryIdentity,
	sourceEventId,
	sourceAdmissionOrdinal,
	walIntegrityIdentity,
	payloadChecksum,
	deliveryUnitIndex,
	deliveryUnitCount,
	sourceSequence,
	planAttribution,
	payloadVersion,
	canonicalProviderSemanticsDigest,
	sourceInstanceId,
	registrationGeneration,
	configurationRevision,
	physicalConfigurationFingerprint,
	authorizationRevision,
	authorizationFingerprint,
	purposeEligibilityMask,
	sourcePolicyRevision,
	captureConsentEpoch,
	manifestRevision,
	lifecycleLeaseGeneration,
	collectedDataEpoch,
	scopeDeletionGeneration,
	clockDomainId,
	storedZoneId,
	structuralEpochDay,
	providerAcceptanceStartNanos,
	authorizationEffectStartNanos,
	consentEffectStartNanos,
	sessionRunEffectStartNanos,
	deletionEffectStartNanos,
	maximumObservationAgeNanos,
	observedIntervalStartNanos,
	observedElapsedNanos,
	receivedElapsedNanos,
	coverageIntervalStartNanos,
	coverageIntervalEndNanos,
	observedWallTimeMs,
	wallTimeUncertaintyMs,
	acquiredAtMs,
	createdAtMs,
	qualityFlags,
	qualityConfidence,
	availability,
	submittedChildCount,
	acceptedChildCount,
	staleChildCount,
	futureTimeChildCount,
	missingTimeChildCount,
	clockUnverifiableChildCount,
	authorityMismatchChildCount,
	unsupportedTechnologyChildCount,
	subscriptionCompleteness,
	childCompleteness,
	observationCount,
	registeredObservationCount,
	gsmCount,
	cdmaCount,
	wcdmaCount,
	tdscdmaCount,
	lteCount,
	nrCount,
	qualityUnknownCount,
	qualityNoneOrUnknownCount,
	qualityPoorCount,
	qualityModerateCount,
	qualityGoodCount,
	qualityGreatCount,
	weakObservationCount,
	knownQualityObservationCount,
	allKnownQualityIsWeak,
	appliedAtMs,
)

private fun SessionManifestSourceEntity.isExactCapturedCellBinding(
	revision: CellCapturedFactRevisionEntity,
	sessionMode: String,
): Boolean = writerOwnerGeneration == revision.writerOwnerGeneration &&
	writerBindingGeneration == revision.writerBindingGeneration &&
	isExactCapturedCellWalBinding(revision.captureConsentEpoch, sessionMode)

private fun SessionManifestSourceEntity.isExactCapturedCellWalBinding(
	captureConsentEpoch: Long,
	sessionMode: String,
): Boolean = sourceKind == CELL_SOURCE && purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
	persistenceEligible && consentEpoch == captureConsentEpoch &&
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
	SourceWriterGenerationContract.captureModeMaskForKnownBinding(writerBindingGeneration ?: -1L) != null &&
	writerOwnerGeneration == writerBindingGeneration?.let(SourceWriterGenerationContract::canonicalOwnerGeneration) &&
	writerProjectionId == WRITER_ID && writerProjectionVersion == WRITER_VERSION &&
	(sessionMode == "MANUAL" || sessionMode == "AUTOMATIC" &&
		writerBindingGeneration == SourceWriterGenerationContract.FINAL_CAPTURE_BINDING_GENERATION)

private fun earliestCoveredWallTime(
	walObservedWallTimeMs: Long,
	uncertaintyMs: Long,
	walObservedElapsedNanos: Long,
	coverageStartNanos: Long,
): Long = CanonicalRetentionBounds.cellCoverage(
	walObservedWallTimeMs = walObservedWallTimeMs,
	wallTimeUncertaintyMs = uncertaintyMs,
	walObservedElapsedRealtimeNanos = walObservedElapsedNanos,
	coverageStartElapsedRealtimeNanos = coverageStartNanos,
) ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

private fun capturePointWallInterval(wal: SourceEventWalEntity,
	selectedStartNanos: Long = wal.observedElapsedNanos,
	selectedEndNanos: Long = wal.observedElapsedNanos,
): LongRange? {
	val wallTimeMs = wal.wallTimeMs ?: return null
	val uncertaintyMs = wal.wallTimeUncertaintyMs ?: return null
	return runCatching {
		val earliest = CanonicalRetentionBounds.cellCoverage(
			walObservedWallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = uncertaintyMs,
			walObservedElapsedRealtimeNanos = wal.observedElapsedNanos,
			coverageStartElapsedRealtimeNanos = selectedStartNanos,
		) ?: return null
		val latestWall = Math.subtractExact(wallTimeMs, (wal.observedElapsedNanos - selectedEndNanos) / 1_000_000L)
		val rounding = if ((wal.observedElapsedNanos - selectedEndNanos) % 1_000_000L == 0L) 0L else 1L
		val latest = Math.addExact(latestWall, Math.addExact(uncertaintyMs, rounding))
		earliest..latest
	}.getOrNull()
}

private fun CellCapturedFactRevisionEntity.matchesIdentityFreeAggregate(
	aggregate: CellHistoricalIdentityFreeAggregate,
): Boolean = observationCount == aggregate.observationCount &&
	registeredObservationCount == aggregate.registeredObservationCount && gsmCount == aggregate.gsmCount &&
	cdmaCount == aggregate.cdmaCount && wcdmaCount == aggregate.wcdmaCount &&
	tdscdmaCount == aggregate.tdscdmaCount && lteCount == aggregate.lteCount && nrCount == aggregate.nrCount &&
	qualityUnknownCount == aggregate.qualityUnknownCount &&
	qualityNoneOrUnknownCount == aggregate.qualityNoneOrUnknownCount &&
	qualityPoorCount == aggregate.qualityPoorCount &&
	qualityModerateCount == aggregate.qualityModerateCount && qualityGoodCount == aggregate.qualityGoodCount &&
	qualityGreatCount == aggregate.qualityGreatCount && weakObservationCount == aggregate.weakObservationCount &&
	knownQualityObservationCount == aggregate.knownQualityObservationCount &&
	allKnownQualityIsWeak == aggregate.allKnownQualityIsWeak

private fun CellWalIdentityFreeAggregate.toHistoricalAggregate() =
	CellHistoricalIdentityFreeAggregate(
		observationCount = observationCount,
		registeredObservationCount = registeredObservationCount,
		gsmCount = gsmCount,
		cdmaCount = cdmaCount,
		wcdmaCount = wcdmaCount,
		tdscdmaCount = tdscdmaCount,
		lteCount = lteCount,
		nrCount = nrCount,
		qualityUnknownCount = qualityUnknownCount,
		qualityNoneOrUnknownCount = qualityNoneOrUnknownCount,
		qualityPoorCount = qualityPoorCount,
		qualityModerateCount = qualityModerateCount,
		qualityGoodCount = qualityGoodCount,
		qualityGreatCount = qualityGreatCount,
		weakObservationCount = weakObservationCount,
		knownQualityObservationCount = knownQualityObservationCount,
		allKnownQualityIsWeak = allKnownQualityIsWeak,
	)

private fun LogicalTrackingSessionEntity.hasValidHistoricalCellShape(): Boolean {
	val terminal = state in TERMINAL_SESSION_STATES
	val nonterminal = state in NONTERMINAL_SESSION_STATES
	if (!terminal && !nonterminal) return false
	if (logicalTrackingId.isBlank() || lifecycleRevision <= 0L || desiredPlanRevision <= 0L ||
		rolloutRevision <= 0L || startOrigin.isBlank() || clockDomainId.isBlank() ||
		lifecycleBootId.isNullOrBlank() || lifecycleLeaseGeneration <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L ||
		(cutoffAtMs == null) != (cutoffElapsedNanos == null)
	) return false
	return if (terminal) {
		completedAtMs != null && cutoffAtMs != null && cutoffElapsedNanos != null &&
			completedAtMs >= cutoffAtMs && finalAdmissionOrdinal != null && finalAdmissionOrdinal >= 0L &&
			currentServiceRunId == null
	} else {
		completedAtMs == null && finalAdmissionOrdinal == null &&
			((state == SESSION_STATE_STOPPING) == (cutoffAtMs != null)) &&
			currentServiceRunId?.isNotBlank() == true
	}
}

private fun SourceServiceRunEntity.hasValidHistoricalCellShape(
	session: LogicalTrackingSessionEntity,
): Boolean {
	val terminal = state in TERMINAL_SESSION_STATES
	val nonterminal = state in NONTERMINAL_RUN_STATES
	if (!terminal && !nonterminal) return false
	if (serviceRunId.isBlank() || logicalTrackingId != session.logicalTrackingId ||
		bootId.isBlank() ||
		(session.currentServiceRunId == serviceRunId &&
			(leaseGeneration != session.lifecycleLeaseGeneration || bootId != session.lifecycleBootId)) ||
		leaseGeneration <= 0L || leaseGeneration > session.lifecycleLeaseGeneration ||
		desiredPlanRevision <= 0L || rolloutRevision <= 0L || startedAtMs < 0L ||
		startedElapsedNanos < 0L || startOrigin.isBlank() || sessionSegmentId == null ||
		preparedManifestRevision <= 0L || preparedIntentRevision <= 0L
	) return false
	if (terminal != (completedAtMs != null) || terminal != !completionReason.isNullOrBlank()) return false
	if (session.state in TERMINAL_SESSION_STATES) return terminal
	if (terminal) return session.currentServiceRunId != serviceRunId
	if (session.currentServiceRunId != serviceRunId) return false
	return (session.state in ACTIVE_ADMISSION_SESSION_STATES && state in ACTIVE_ADMISSION_RUN_STATES) ||
		(session.state == SESSION_STATE_ACTIVE && state == SESSION_STATE_STOPPING) ||
		(session.state == SESSION_STATE_STOPPING && state == SESSION_STATE_STOPPING)
}

internal fun decodeCanonicalCellPlan(bytes: ByteArray): CellHistoricalPlan? = runCatching {
	val plan = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
		require(input.readInt() == CELL_PLAN_FORMAT_VERSION)
		require(input.readUTF() == CELL_PLAN_SOURCE_NAME)
		val revision = input.readLong()
		val mode = input.readUTF()
		val minimumRefreshAttemptIntervalMs = input.readLong()
		val maximumAcceptableCachedAgeMs = input.readLong()
		val count = input.readInt()
		require(count in 0..MAX_CELL_PLAN_SUBSCRIPTIONS)
		val subscriptionIds = buildSet(count) { repeat(count) { add(input.readInt()) } }
		require(subscriptionIds.size == count)
		val backoffInitialDelayMs = input.readLong()
		val backoffMaximumDelayMs = input.readLong()
		val backoffMultiplier = input.readDouble()
		require(input.available() == 0)
		CellHistoricalPlan(
			revision = revision,
			mode = mode,
			minimumRefreshAttemptIntervalMs = minimumRefreshAttemptIntervalMs,
			maximumAcceptableCachedAgeMs = maximumAcceptableCachedAgeMs,
			subscriptionIds = subscriptionIds,
			backoffInitialDelayMs = backoffInitialDelayMs,
			backoffMaximumDelayMs = backoffMaximumDelayMs,
			backoffMultiplier = backoffMultiplier,
		)
	}
	val canonical = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeInt(CELL_PLAN_FORMAT_VERSION)
			output.writeUTF(CELL_PLAN_SOURCE_NAME)
			output.writeLong(plan.revision)
			output.writeUTF(plan.mode)
			output.writeLong(plan.minimumRefreshAttemptIntervalMs)
			output.writeLong(plan.maximumAcceptableCachedAgeMs)
			output.writeInt(plan.subscriptionIds.size)
			plan.subscriptionIds.sorted().forEach(output::writeInt)
			output.writeLong(plan.backoffInitialDelayMs)
			output.writeLong(plan.backoffMaximumDelayMs)
			output.writeDouble(plan.backoffMultiplier)
		}
		buffer.toByteArray()
	}
	require(canonical.contentEquals(bytes))
	plan
}.getOrNull()

private fun Long.isExactSettlementOf(previous: Long): Boolean =
	this == previous || (previous == Long.MAX_VALUE && this < Long.MAX_VALUE)

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

internal data class CellCapturedLineage(
	val logicalFactId: String,
	val revisions: List<CellCapturedFactRevisionEntity>,
	val aggregateOwnerLogicalFactId: String?,
	val aggregateOwnerSemanticRevision: Long?,
) {
	val scope: CellCapturedRunScope
		get() = revisions.first().let { revision ->
			CellCapturedRunScope(revision.logicalTrackingId, revision.serviceRunId)
		}
}

internal data class CellCapturedRunScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

/** One original callback may cover several policy/consent intervals in the same run. */
private data class CellCapturedEventAuthorityKey(
	val eventId: String,
	val logicalRunScopeDigest: String,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
)

private fun CellCapturedRunScope.retainedIdentity(
	collectedDataEpoch: Long,
): RetainedCaptureScopeIdentity = RetainedCaptureScopeIdentity(
	sourceKind = CELL_SOURCE,
	logicalTrackingId = logicalTrackingId,
	serviceRunId = serviceRunId,
	collectedDataEpoch = collectedDataEpoch,
	scopeDeletionGeneration = 0L,
	logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
		CELL_SOURCE,
		CAPTURE_PURPOSE,
		logicalTrackingId,
		serviceRunId,
	),
)

internal data class CellHistoricalPlan(
	val revision: Long,
	val mode: String,
	val minimumRefreshAttemptIntervalMs: Long,
	val maximumAcceptableCachedAgeMs: Long,
	val subscriptionIds: Set<Int>,
	val backoffInitialDelayMs: Long,
	val backoffMaximumDelayMs: Long,
	val backoffMultiplier: Double,
) {
	fun hasSupportedHistoricalShape(): Boolean =
		mode in CELL_CAPTURE_MODES && minimumRefreshAttemptIntervalMs >= 0L &&
			maximumAcceptableCachedAgeMs >= 0L && subscriptionIds.size <= MAX_CELL_PLAN_SUBSCRIPTIONS &&
			subscriptionIds.all { it >= 0 } && backoffInitialDelayMs >= 0L &&
			backoffMaximumDelayMs >= backoffInitialDelayMs && backoffMultiplier.isFinite() &&
			backoffMultiplier >= 1.0

	fun maximumObservationAgeNanos(): Long =
		if (maximumAcceptableCachedAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
			Long.MAX_VALUE
		} else {
			maximumAcceptableCachedAgeMs * NANOS_PER_MILLISECOND
		}

	fun physicalConfigurationFingerprint(): String {
		val components = when (mode) {
			CELL_MODE_OBSERVE_CHANGES -> listOf(
				CELL_PLAN_SOURCE_NAME,
				"CHANGE_CALLBACK",
				subscriptionIds.sorted().joinToString(","),
			)
			CELL_MODE_OBSERVE_AND_SPARSE_REFRESH -> listOf(
				CELL_PLAN_SOURCE_NAME,
				"CHANGE_CALLBACK",
				"EXPLICIT_REFRESH",
				subscriptionIds.sorted().joinToString(","),
				minimumRefreshAttemptIntervalMs,
				backoffInitialDelayMs,
				backoffMaximumDelayMs,
				backoffMultiplier,
			)
			else -> return ""
		}.joinToString("\u001f")
		return sha256(components.toByteArray(Charsets.UTF_8))
	}
}

internal data class CellCapturedWalAudit(
	val scopes: Set<CellCapturedRunScope>,
	val carriers: List<CellCapturedWalCarrier>,
	val latestDurableTimeMs: Long,
)

internal data class CellCapturedWalCarrier(
	val eventId: String,
	val admissionOrdinal: Long,
	val scope: CellCapturedRunScope,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val collectedDataEpoch: Long,
	val walIntegrityIdentity: String,
	val earliestPossibleWallTimeMs: Long,
	val payloadVersion: Int = com.adsamcik.tracker.shared.base.database.data.LEGACY_CELL_WAL_PAYLOAD_VERSION,
)

private data class AuthenticatedCellFactAuthority(
	val reuseAuthority: CellAggregateReuseAuthority,
	val aggregate: CellHistoricalIdentityFreeAggregate,
	val walCreatedAtMs: Long,
	val retentionLowerBoundMs: Long,
)

internal data class CellHistoricalIdentityFreeAggregate(
	val observationCount: Int,
	val registeredObservationCount: Int,
	val gsmCount: Int,
	val cdmaCount: Int,
	val wcdmaCount: Int,
	val tdscdmaCount: Int,
	val lteCount: Int,
	val nrCount: Int,
	val qualityUnknownCount: Int,
	val qualityNoneOrUnknownCount: Int,
	val qualityPoorCount: Int,
	val qualityModerateCount: Int,
	val qualityGoodCount: Int,
	val qualityGreatCount: Int,
	val weakObservationCount: Int,
	val knownQualityObservationCount: Int,
	val allKnownQualityIsWeak: Boolean,
)

internal data class CellCapturedFactAudit(
	val lineages: List<CellCapturedLineage>,
	val lineagesById: Map<String, CellCapturedLineage>,
	val cursorsById: Map<String, CellCapturedFactCursorEntity>,
	val canonicalRetentionLowerBoundById: Map<String, Long>,
	val aggregateById: Map<String, CellHistoricalIdentityFreeAggregate>,
	val generationByScope: Map<CellCapturedRunScope, CellCaptureDeletionGenerationEntity>,
	val latestDurableTimeMs: Long,
)

internal class CellCapturedRetentionBlockedException(
	val reason: CellCapturedRetentionBlockedReason,
) : IllegalStateException(reason.name)

private class CellCapturedSourceDeletionBlockedException(
	val reason: CellCapturedSourceDeletionBlockedReason,
) : IllegalStateException(reason.name)

internal class CellCapturedMaintenanceLimitExceeded : IllegalStateException()

private fun block(reason: CellCapturedRetentionBlockedReason): Nothing =
	throw CellCapturedRetentionBlockedException(reason)

private fun block(reason: CellCapturedSourceDeletionBlockedReason): Nothing =
	throw CellCapturedSourceDeletionBlockedException(reason)

private const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
private const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
private const val CONTROL_POLICY_PURPOSE = "CONTROL"
private const val WRITER_ID = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
private const val WRITER_VERSION = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
private const val CAPTURED_REGISTRATION_PLAN_ATTRIBUTION = 0
private const val INSERT_IGNORED = -1L
private const val FIRST_DELETION_GENERATION = 1L
private const val DELETE_BATCH_SIZE = 128
private const val CURSOR_PAGE_SIZE = 256
private const val DELETION_GENERATION_PAGE_SIZE = 256
private const val WAL_PAGE_SIZE = 256
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CELL_PLAN_FORMAT_VERSION = 1
private const val CELL_PLAN_PAYLOAD_VERSION = 1
private const val CELL_PLAN_SOURCE_NAME = "CELL"
private const val CELL_MODE_OBSERVE_CHANGES = "OBSERVE_CHANGES"
private const val CELL_MODE_OBSERVE_AND_SPARSE_REFRESH = "OBSERVE_AND_SPARSE_REFRESH"
private const val MAX_CELL_PLAN_SUBSCRIPTIONS = 10_000
private val LOWERCASE_SHA_256 = Regex("^[0-9a-f]{64}$")

private val CELL_CAPTURE_MODES = setOf(
	CELL_MODE_OBSERVE_CHANGES,
	CELL_MODE_OBSERVE_AND_SPARSE_REFRESH,
)
private const val SESSION_STATE_ACTIVE = "ACTIVE"
private const val SESSION_STATE_STOPPING = "STOPPING"
private val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED")
private val NONTERMINAL_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
private val NONTERMINAL_RUN_STATES = setOf("STARTING", "ACTIVE", "STOPPING")
private val ACTIVE_ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ACTIVE_ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")

private val DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS = CellCapturedMaintenanceLimits()

private fun Long.isKnownCapturedWriterOwnerGeneration(): Boolean =
	SourceWriterGenerationContract.bindingGenerationForCanonicalOwner(this)
		?.let(SourceWriterGenerationContract::captureModeMaskForKnownBinding) != null
