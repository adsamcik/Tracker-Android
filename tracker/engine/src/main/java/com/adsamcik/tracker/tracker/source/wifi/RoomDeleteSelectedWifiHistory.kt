@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.wifi

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionRunMarker
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistory
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistoryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistoryResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionBlockedReason
import com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistorySelection
import com.adsamcik.tracker.stats.api.value.EpochMs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact selected Wi-Fi deletion. Imported and native authority remain separate and source-local. */
@Singleton
@Suppress("LargeClass", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal class RoomDeleteSelectedWifiHistory internal constructor(
	private val database: AppDatabase,
	private val importedEvaluator: ImportedWifiProductEvaluator,
	private val localPortableReader: ReadLocalPortableCapturedWifi,
	private val maintenance: WifiCapturedFactMaintenance,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val limits: WifiSelectedDeletionLimits,
	private val checkpoint: suspend (WifiSelectedDeletionCheckpoint) -> Unit,
) : DeleteSelectedWifiHistory, WifiDeletedHistoryReader {
	@Inject
	constructor(
		database: AppDatabase,
		importedEvaluator: ImportedWifiProductEvaluator,
		localPortableReader: ReadLocalPortableCapturedWifi,
		maintenance: WifiCapturedFactMaintenance,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		database,
		importedEvaluator,
		localPortableReader,
		maintenance,
		ioDispatcher,
		WifiSelectedDeletionLimits(),
		{ currentCoroutineContext().ensureActive() },
	)

	override suspend fun delete(
		request: DeleteSelectedWifiHistoryRequest,
	): DeleteSelectedWifiHistoryResult = withContext(ioDispatcher) {
		try {
			database.withTransaction {
				val state = database.sourceEvidenceStateDao().get()
					?: unverifiable(WifiHistoryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
				if (!state.hasValidSelectedWifiDeletionShape()) storedCorrupt()
				if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
					blocked(WifiHistoryDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
				}
				if (request.deletedAtMs < state.updatedAtMs) {
					blocked(WifiHistoryDeletionBlockedReason.STALE_REQUEST)
				}
				checkpoint(WifiSelectedDeletionCheckpoint.TRANSACTION_STARTED)
				when (val selection = request.selection) {
					is WifiHistorySelection.Imported -> deleteImported(request, selection, state)
					is WifiHistorySelection.Local -> deleteLocal(request, selection, state)
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: WifiSelectedDeletionAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			DeleteSelectedWifiHistoryResult.RetryableFailure(
				WifiHistoryDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			DeleteSelectedWifiHistoryResult.RetryableFailure(
				WifiHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: ArithmeticException) {
			unverifiableResult(WifiHistoryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
		} catch (_: RuntimeException) {
			unverifiableResult(WifiHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	override suspend fun readDeletedInTransaction(
		selection: WifiHistorySelection,
	): WifiDeletedHistoryResult {
		return try {
			val state = database.sourceEvidenceStateDao().get()
				?: return WifiDeletedHistoryResult.Unverifiable(
					WifiHistoryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
				)
			if (!state.hasValidSelectedWifiDeletionShape()) {
				return WifiDeletedHistoryResult.Unverifiable(
					WifiHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			val receipt = authenticateStoredDeletion(selection, state)
				?: return WifiDeletedHistoryResult.NotDeleted
			WifiDeletedHistoryResult.Deleted(receipt.toDeletedHistoryEntry(selection))
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: WifiSelectedDeletionAbort) {
			val result = abort.result
			WifiDeletedHistoryResult.Unverifiable(
				(result as? DeleteSelectedWifiHistoryResult.Unverifiable)?.reason
					?: WifiHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		} catch (_: RuntimeException) {
			WifiDeletedHistoryResult.Unverifiable(
				WifiHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
	}

	private suspend fun deleteImported(
		request: DeleteSelectedWifiHistoryRequest,
		selection: WifiHistorySelection.Imported,
		state: SourceEvidenceState,
	): DeleteSelectedWifiHistoryResult {
		val dao = database.importedWifiDao()
		val selected = selection.selected
		dao.selectedDeletionReceipt(
			selected.key.value,
			WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
		)?.let { receipt ->
			return authenticateReplay(request, receipt, state)
		}
		if (dao.entryDeletion(selected.key.value) != null) {
			unverifiable(WifiHistoryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
		}
		val evaluation = importedEvaluator.selectIdentityInTransaction(selected.key)
			?: return DeleteSelectedWifiHistoryResult.NotFound
		val readable = when (evaluation) {
			is ImportedWifiProductEvaluation.Unverifiable -> unverifiable(evaluation.reason.toDeletionReason())
			is ImportedWifiProductEvaluation.Readable -> evaluation
		}
		if (readable.candidate.selection != selected) {
			blocked(WifiHistoryDeletionBlockedReason.STALE_SELECTION)
		}
		if (readable.retentionLimited) blocked(WifiHistoryDeletionBlockedReason.RETENTION_BOUNDARY)
		if (readable.entryDeleted) storedCorrupt()
		val identity = selected.key.value
		val lineage = authenticateImportedLineage(identity, state.collectedDataEpoch)
		if (lineage.revisions.last().entry != readable.entry) storedCorrupt()
		if (request.deletedAtMs < maxOf(
				readable.candidate.receivedAtMs,
				lineage.receipts.maxOfOrNull { it.receivedAtMs } ?: 0L,
			)
		) blocked(WifiHistoryDeletionBlockedReason.STALE_REQUEST)
		val authority = WifiSelectedDeletionAuthorityFactory.imported(
			lineage,
			state.collectedDataEpoch,
			request.deletedAtMs,
		)
		preflightNewAuthority(dao, authority)
		authenticateProtectedNamespace(authority, state, allowOwnReceipt = false)
		val existingRunMarkers = dao.deletionGenerationsByEntry(
			listOf(identity),
			authority.runMarkers.size + 1,
		)
		if (existingRunMarkers.size > authority.runMarkers.size ||
			existingRunMarkers.any { marker ->
				authority.runMarkers.none {
					it.runIdentity == marker.runIdentity &&
						it.entryIdentity == marker.entryIdentity &&
						it.deletionScopeDigest == marker.deletionScopeDigest
				} || marker.collectedDataEpoch != state.collectedDataEpoch || marker.generation != 1L
			}
		) storedCorrupt()
		val existingByRun = existingRunMarkers.associateBy(ImportedWifiDeletionGenerationEntity::runIdentity)
		val missingImportedRunMarkers = authority.runMarkers
			.filter { it.runIdentity !in existingByRun }
			.map { marker ->
				ImportedWifiDeletionGenerationEntity.create(
					marker.runIdentity,
					marker.entryIdentity,
					marker.deletionScopeDigest,
					marker.collectedDataEpoch,
					marker.generation,
					marker.deletedAtMs,
				)
			}
		if (missingImportedRunMarkers.isNotEmpty() &&
			dao.insertImportedDeletionGenerations(missingImportedRunMarkers).any { it < 0L }
		) {
			concurrentMutation()
		}
		val finalImportedRunMarkers = dao.deletionGenerationsByEntry(
			listOf(identity),
			authority.runMarkers.size + 1,
		)
		if (finalImportedRunMarkers.size != authority.runMarkers.size) storedCorrupt()
		val finalAuthority = authority.copy(
			runMarkers = finalImportedRunMarkers.map { marker ->
				WifiSelectedDeletionRunMarker(
					marker.runIdentity,
					marker.entryIdentity,
					marker.deletionScopeDigest,
					marker.collectedDataEpoch,
					marker.generation,
					marker.deletedAtMs,
				)
			},
		)
		val sourceFences = exactSourceFences(authority, state)
		if (request.deletedAtMs < maxOf(
				finalImportedRunMarkers.maxOfOrNull { it.deletedAtMs } ?: 0L,
				sourceFences.maxOfOrNull { it.deletedAtMs } ?: 0L,
			)
		) blocked(WifiHistoryDeletionBlockedReason.STALE_REQUEST)
		val entryDeletion = ImportedWifiEntryDeletionEntity.create(
			identity,
			state.collectedDataEpoch,
			selected.importRevision,
			request.deletedAtMs,
		)
		dao.insertEntryDeletion(entryDeletion)
		insertReceiptAndProtected(
			dao,
			finalAuthority,
			WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
			selected.importRevision,
			selected.contentChecksum,
			sourceFences,
			state,
			request.deletedAtMs,
		)
		checkpoint(WifiSelectedDeletionCheckpoint.MARKERS_RECORDED)
		val expectedObservationRows = lineage.revisions.sumOf { revision ->
			revision.entry.runs.sumOf { it.observations.size }
		}
		val expectedDependentRows = lineage.revisions.sumOf { revision ->
			revision.entry.runs.sumOf { run ->
				run.observations.count { it.aggregateOwnerIdentity != null }
			}
		}
		if (dao.deleteDependentObservations(identity) != expectedDependentRows ||
			dao.deleteRemainingObservations(identity) != expectedObservationRows - expectedDependentRows ||
			dao.deleteEntryRevisions(identity) != lineage.revisions.size
		) concurrentMutation()
		authenticatePayloadAbsent(finalAuthority, imported = true)
		checkpoint(WifiSelectedDeletionCheckpoint.PAYLOAD_REMOVED)
		return DeleteSelectedWifiHistoryResult.Deleted(
			WifiHistoryOrigin.IMPORTED,
			finalAuthority.runMarkers.size,
			finalAuthority.protectedIdentities.count {
				it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION
			},
		)
	}

	private suspend fun deleteLocal(
		request: DeleteSelectedWifiHistoryRequest,
		selection: WifiHistorySelection.Local,
		state: SourceEvidenceState,
	): DeleteSelectedWifiHistoryResult {
		val dao = database.importedWifiDao()
		dao.selectedDeletionReceipt(
			selection.key.value,
			WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
		)?.let { receipt ->
			return authenticateReplay(request, receipt, state)
		}
		val logicalTrackingId = resolveOriginalLogicalId(selection.key.value)
			?: return DeleteSelectedWifiHistoryResult.NotFound
		val runs = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
			listOf(logicalTrackingId),
			MAX_SELECTED_RUNS + 1,
			null,
			null,
			null,
		)
		if (runs.size !in 1..MAX_SELECTED_RUNS) {
			blocked(
				if (runs.isEmpty()) WifiHistoryDeletionBlockedReason.ORIGINAL_SCOPE_MISSING else
					WifiHistoryDeletionBlockedReason.ORIGINAL_SCOPE_REBOUND,
			)
		}
		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId)
		if (segmentIds.size != runs.size || segmentIds.distinct().size != segmentIds.size) {
			blocked(WifiHistoryDeletionBlockedReason.ORIGINAL_SCOPE_REBOUND)
		}
		val segments = database.trackingHistoryReadDao().segments(segmentIds)
		if (segments.size != segmentIds.size || segments.any { segment ->
				segment.logicalTrackingId != logicalTrackingId ||
					runs.singleOrNull { it.serviceRunId == segment.serviceRunId }?.sessionSegmentId != segment.id
			}
		) blocked(WifiHistoryDeletionBlockedReason.ORIGINAL_SCOPE_REBOUND)
		if (segments.any { it.distanceM != 0f || it.steps != null || it.sampleCount != 0 }) {
			blocked(WifiHistoryDeletionBlockedReason.MIXED_CAPTURE_SCOPE)
		}
		if (dao.selectedSkiSegmentCount(segmentIds) != 0L) {
			blocked(WifiHistoryDeletionBlockedReason.MIXED_CAPTURE_SCOPE)
		}
		authenticateOnlyWifiManifests(logicalTrackingId, runs)
		val portable = when (val read = localPortableReader.readInTransaction(
			ExportPortableCapturedWifiRequest(logicalTrackingId),
		)) {
			is ReadLocalPortableCapturedWifiResult.Ready -> read.entry
			is ReadLocalPortableCapturedWifiResult.Outcome -> when (read.result) {
				com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult.Active ->
					blocked(WifiHistoryDeletionBlockedReason.ACTIVE_CAPTURE)
				com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult.Materializing ->
					blocked(WifiHistoryDeletionBlockedReason.MATERIALIZING)
				is com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult.Unavailable ->
					blocked(WifiHistoryDeletionBlockedReason.RETENTION_BOUNDARY)
				else -> storedCorrupt()
			}
		}
		if (portable.identity.value != selection.key.value) {
			blocked(WifiHistoryDeletionBlockedReason.ORIGINAL_SCOPE_REBOUND)
		}
		val importedCollision = importedEvaluator.selectIdentityInTransaction(
			com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey(
				selection.key.value,
			),
		)
		if (importedCollision != null && (
				importedCollision !is ImportedWifiProductEvaluation.Readable ||
					!importedCollision.isReExportable || importedCollision.entry != portable
				)
		) originConflict()
		val completeness = database.trackingHistoryReadDao().completenessForSource(
			WIFI_SOURCE,
			logicalTrackingId,
			runs.map(SourceServiceRunEntity::serviceRunId),
			MAX_COMPLETENESS + 1,
		)
		if (completeness.size > MAX_COMPLETENESS) dependencyOverflow()
		val audit = maintenance.auditPortableInTransaction(
			state,
			logicalTrackingId,
			runs.map(SourceServiceRunEntity::serviceRunId),
			completeness,
			WifiCapturedMaintenanceLimits(),
		)
		if (request.deletedAtMs < audit.latestDurableTimeMs) {
			blocked(WifiHistoryDeletionBlockedReason.STALE_REQUEST)
		}
		val authority = WifiSelectedDeletionAuthorityFactory.local(
			portable,
			audit,
			state.collectedDataEpoch,
			request.deletedAtMs,
		)
		preflightNewAuthority(dao, authority)
		authenticateProtectedNamespace(authority, state, allowOwnReceipt = false)
		val localDeletionRows = mutableListOf<WifiCaptureDeletionGenerationEntity>()
		val sourceFences = mutableListOf<SourceDeletionFenceEntity>()
		val portableRunByIdentity = portable.runs.associateBy { it.identity.value }
		for (run in runs) {
			val portableRunIdentity = PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.PHYSICAL_RUN,
				run.serviceRunId,
			).value
			val portableRun = portableRunByIdentity[portableRunIdentity]
				?: blocked(WifiHistoryDeletionBlockedReason.ORIGINAL_SCOPE_REBOUND)
			localDeletionRows += WifiCaptureDeletionGenerationEntity(
				logicalTrackingId,
				run.serviceRunId,
				state.collectedDataEpoch,
				1L,
				request.deletedAtMs,
			)
			val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
				WIFI_SOURCE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId,
				run.serviceRunId,
				1L,
				state.collectedDataEpoch,
				request.deletedAtMs,
			)
			if (fence.scopeIdentityDigest != portableRun.deletionScopeDigest.value) {
				storedCorrupt()
			}
			sourceFences += fence
		}
		if (dao.insertLocalDeletionGenerations(localDeletionRows).any { it < 0L } ||
			dao.insertSourceDeletionFences(sourceFences).any { it < 0L }
		) {
			concurrentMutation()
		}
		val storedGenerations = database.wifiCapturedFactDao().historyDeletionGenerations(
			listOf(logicalTrackingId),
			runs.map(SourceServiceRunEntity::serviceRunId),
			localDeletionRows.size + 1,
		)
		if (storedGenerations.toSet() != localDeletionRows.toSet() ||
			exactSourceFences(authority, state).toSet() != sourceFences.toSet()
		) {
			concurrentMutation()
		}
		insertReceiptAndProtected(
			dao,
			authority,
			WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
			null,
			null,
			sourceFences,
			state,
			request.deletedAtMs,
		)
		checkpoint(WifiSelectedDeletionCheckpoint.MARKERS_RECORDED)
		for (lineageBatch in audit.lineages.dependencySafeDeletionOrder().chunked(DELETE_BATCH_SIZE)) {
			val ids = lineageBatch.map(WifiCapturedLineage::logicalFactId)
			if (database.wifiCapturedFactDao().deleteExactCursors(
					SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
					ids,
				) != lineageBatch.size ||
				database.wifiCapturedFactDao().deleteExactRevisionLineages(
					SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
					ids,
				) != lineageBatch.sumOf { it.revisions.size }
			) concurrentMutation()
		}
		if (dao.deleteSelectedLocalSegments(
				logicalTrackingId,
				segments.map { it.id },
				runs.map { it.serviceRunId },
			) != segments.size
		) {
			concurrentMutation()
		}
		authenticatePayloadAbsent(authority, imported = false)
		checkpoint(WifiSelectedDeletionCheckpoint.PAYLOAD_REMOVED)
		return DeleteSelectedWifiHistoryResult.Deleted(
			WifiHistoryOrigin.LOCAL,
			authority.runMarkers.size,
			authority.protectedIdentities.count {
				it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION
			},
		)
	}

	private suspend fun authenticateOnlyWifiManifests(
		logicalTrackingId: String,
		runs: List<SourceServiceRunEntity>,
	) {
		val runIds = runs.map(SourceServiceRunEntity::serviceRunId)
		val manifests = database.trackingHistoryReadDao().manifests(runIds, MAX_MANIFESTS + 1)
		val sources = database.trackingHistoryReadDao().manifestSources(runIds, MAX_MANIFEST_SOURCES + 1)
		if (manifests.size > MAX_MANIFESTS || sources.size > MAX_MANIFEST_SOURCES) dependencyOverflow()
		val manifestsByRun = manifests.groupBy { it.serviceRunId }
		val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
		if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				manifestsByRun.values.map { values -> values.map { it.manifestRevision } },
			) || runs.any { run ->
				val timeline = manifestsByRun[run.serviceRunId].orEmpty()
				!SessionManifestIntegrity.hasValidServiceRunTimeline(run, timeline) ||
					timeline.any { manifest ->
						manifest.logicalTrackingId != logicalTrackingId ||
							!SessionManifestIntegrity.verify(
								manifest,
								sourcesByManifest[logicalTrackingId to manifest.manifestRevision].orEmpty(),
							)
					}
			}
		) storedCorrupt()
		for (manifest in manifests) {
			val membership = sourcesByManifest[logicalTrackingId to manifest.manifestRevision].orEmpty()
			val captured = membership.filter {
				it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && it.persistenceEligible
			}
			if (captured.mapTo(linkedSetOf(), SessionManifestSourceEntity::sourceKind) != setOf(WIFI_SOURCE) ||
				captured.singleOrNull()?.isExactSelectedWifiWriter() != true
			) blocked(WifiHistoryDeletionBlockedReason.MIXED_CAPTURE_SCOPE)
		}
	}

	private suspend fun resolveOriginalLogicalId(selectionIdentity: String): String? {
		val dao = database.importedWifiDao()
		val expectedCount = dao.localEntryOwnerCount()
		if (expectedCount < 0L || expectedCount > ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS) {
			dependencyOverflow()
		}
		val matches = mutableListOf<String>()
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = dao.localEntryOwnerPage(after, ImportedWifiDao.OWNER_PAGE_SIZE)
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) -> left >= right } ||
				after?.let { previous -> page.firstOrNull()?.let { it <= previous } } == true
			) storedCorrupt()
			page.forEach { logicalId ->
				if (PortableWifiOpaqueIdentity.derive(
						PortableWifiIdentityKind.LOGICAL_ENTRY,
						logicalId,
					).value == selectionIdentity
				) matches += logicalId
			}
			loaded = Math.addExact(loaded, page.size.toLong())
			if (loaded > expectedCount) storedCorrupt()
			if (page.isEmpty() || page.size < ImportedWifiDao.OWNER_PAGE_SIZE) break
			after = page.last()
		}
		if (loaded != expectedCount || matches.size > 1) storedCorrupt()
		return matches.singleOrNull()
	}

	private suspend fun authenticateImportedLineage(
		identity: String,
		epoch: Long,
	): AuthenticatedImportedWifiLineage {
		val dao = database.importedWifiDao()
		return try {
			ImportedWifiLineageAuthenticator.authenticate(
				identity,
				epoch,
				dao.entryRevisionsForAdmission(identity),
				dao.receiptsForAdmission(identity),
				dao.allRunsForAdmission(identity),
				dao.allRunZonesForAdmission(identity),
				dao.allObservationsForAdmission(identity),
			)
		} catch (failure: ImportedWifiLineageFailure) {
			unverifiable(failure.reason.toDeletionReason())
		}
	}

	private suspend fun preflightNewAuthority(
		dao: ImportedWifiDao,
		authority: WifiSelectedDeletionAuthority,
	) {
		if (authority.protectedIdentities.size > limits.maximumProtectedIdentities ||
			authority.protectedIdentities.sumOf(::protectedIdentityByteSize) >
			limits.maximumProtectedBytes
		) dependencyOverflow()
		val receiptCount = dao.selectedDeletionReceiptCount()
		val protectedCount = dao.selectedDeletionProtectedIdentityCount()
		if (receiptCount < 0L || protectedCount < 0L ||
			Math.addExact(receiptCount, 1L) > ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS ||
			Math.addExact(protectedCount, authority.protectedIdentities.size.toLong()) >
			ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS
		) dependencyOverflow()
	}

	private suspend fun insertReceiptAndProtected(
		dao: ImportedWifiDao,
		authority: WifiSelectedDeletionAuthority,
		origin: String,
		importRevision: Long?,
		contentChecksum: String?,
		sourceFences: List<SourceDeletionFenceEntity>,
		state: SourceEvidenceState,
		deletedAtMs: Long,
	) {
		val receipt = WifiSelectedDeletionReceiptEntity.create(
			selectionIdentity = authority.selectionIdentity,
			origin = origin,
			collectedDataEpoch = state.collectedDataEpoch,
			selectedImportRevision = importRevision,
			selectedContentChecksum = contentChecksum,
			startTimeMs = authority.startTimeMs,
			endTimeMs = authority.endTimeMs,
			protectedIdentities = authority.protectedIdentities,
			runDeletionRows = authority.runMarkers,
			sourceFences = sourceFences,
			retainedFromMs = state.retainedFromMs,
			deletedAtMs = deletedAtMs,
		)
		dao.insertSelectedDeletionReceipt(receipt)
		dao.insertSelectedDeletionProtectedIdentities(authority.protectedIdentities)
		if (dao.selectedDeletionReceipt(authority.selectionIdentity, origin) != receipt ||
			dao.selectedDeletionProtectedIdentities(
				authority.selectionIdentity,
				origin,
				authority.protectedIdentities.size + 1,
			).toSet() != authority.protectedIdentities.toSet()
		) concurrentMutation()
	}

	private suspend fun authenticateReplay(
		request: DeleteSelectedWifiHistoryRequest,
		receipt: WifiSelectedDeletionReceiptEntity,
		state: SourceEvidenceState,
	): DeleteSelectedWifiHistoryResult {
		if (authenticateStoredDeletion(request.selection, state) != receipt) storedCorrupt()
		if (request.deletedAtMs < maxOf(receipt.deletedAtMs, state.updatedAtMs)) {
			blocked(WifiHistoryDeletionBlockedReason.STALE_REQUEST)
		}
		return DeleteSelectedWifiHistoryResult.AlreadyDeleted(request.selection.origin)
	}

	private suspend fun authenticateStoredDeletion(
		selection: WifiHistorySelection,
		state: SourceEvidenceState,
	): WifiSelectedDeletionReceiptEntity? {
		val selectionIdentity = selection.selectionIdentity()
		val origin = selection.receiptOrigin()
		val receipt = database.importedWifiDao().selectedDeletionReceipt(
			selectionIdentity,
			origin,
		) ?: return null
		if (receipt.selectionIdentity != selectionIdentity ||
			receipt.collectedDataEpoch != state.collectedDataEpoch
		) storedCorrupt()
		when (selection) {
			is WifiHistorySelection.Imported -> if (
				receipt.origin != WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED ||
				receipt.selectedImportRevision != selection.selected.importRevision ||
				receipt.selectedContentChecksum != selection.selected.contentChecksum
			) blocked(WifiHistoryDeletionBlockedReason.STALE_SELECTION)
			is WifiHistorySelection.Local -> if (
				receipt.origin != WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL ||
				receipt.selectedImportRevision != null || receipt.selectedContentChecksum != null
			) blocked(WifiHistoryDeletionBlockedReason.STALE_SELECTION)
		}
		if (receipt.retainedFromMs != state.retainedFromMs) {
			blocked(WifiHistoryDeletionBlockedReason.RETENTION_BOUNDARY)
		}
		val protected = database.importedWifiDao().selectedDeletionProtectedIdentities(
			selectionIdentity,
			origin,
			receipt.expectedProtectedIdentityCount + 1,
		)
		if (protected.size != receipt.expectedProtectedIdentityCount ||
			WifiSelectedDeletionReceiptEntity.checksumProtectedIdentities(protected) !=
			receipt.protectedIdentitySetChecksum
		) storedCorrupt()
		val authority = authorityFromReceipt(receipt, protected)
		authenticateProtectedNamespace(authority, state, allowOwnReceipt = true)
		authenticatePayloadAbsent(
			authority,
			imported = receipt.origin == WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
		)
		authenticateReplayRunAuthority(receipt, authority, state)
		val sourceFences = exactSourceFences(authority, state)
		if (WifiSelectedDeletionReceiptEntity.checksumSourceFences(sourceFences) !=
			receipt.sourceFenceSetChecksum
		) storedCorrupt()
		return receipt
	}

	private suspend fun authenticateReplayRunAuthority(
		receipt: WifiSelectedDeletionReceiptEntity,
		authority: WifiSelectedDeletionAuthority,
		state: SourceEvidenceState,
	) {
		val actual = if (receipt.origin == WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED) {
			val entryDeletion = database.importedWifiDao().entryDeletion(receipt.selectionIdentity)
				?: storedCorrupt()
			if (entryDeletion.collectedDataEpoch != state.collectedDataEpoch ||
				entryDeletion.deletedImportRevision != receipt.selectedImportRevision ||
				entryDeletion.deletedAtMs != receipt.deletedAtMs
			) storedCorrupt()
			database.importedWifiDao().deletionGenerationsByEntry(
				listOf(receipt.selectionIdentity),
				authority.runMarkers.size + 1,
			).map { marker ->
				WifiSelectedDeletionRunMarker(
					marker.runIdentity,
					marker.entryIdentity,
					marker.deletionScopeDigest,
					marker.collectedDataEpoch,
					marker.generation,
					marker.deletedAtMs,
				)
			}
		} else {
			loadLocalReplayRunMarkers(receipt.selectionIdentity, authority)
		}
		if (actual.size != authority.runMarkers.size ||
			WifiSelectedDeletionReceiptEntity.checksumRunDeletions(actual) !=
			receipt.runDeletionSetChecksum
		) storedCorrupt()
	}

	private suspend fun loadLocalReplayRunMarkers(
		selectionIdentity: String,
		authority: WifiSelectedDeletionAuthority,
	): List<WifiSelectedDeletionRunMarker> {
		val expectedRuns = authority.runMarkers.associateBy(WifiSelectedDeletionRunMarker::runIdentity)
		val matched = mutableListOf<WifiSelectedDeletionRunMarker>()
		var afterLogical: String? = null
		var afterRun: String? = null
		var loaded = 0L
		val total = database.importedWifiDao().localDeletionOwnerCount()
		if (total < 0L || total > ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS) dependencyOverflow()
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = database.importedWifiDao().localDeletionGenerationPage(
				afterLogical,
				afterRun,
				ImportedWifiDao.OWNER_PAGE_SIZE,
			)
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) ->
					left.logicalTrackingId > right.logicalTrackingId ||
						left.logicalTrackingId == right.logicalTrackingId &&
						left.serviceRunId >= right.serviceRunId
				} || afterLogical?.let { logical ->
					val first = page.firstOrNull()
					first != null && (
						first.logicalTrackingId < logical ||
							first.logicalTrackingId == logical &&
							first.serviceRunId <= requireNotNull(afterRun)
						)
				} == true
			) storedCorrupt()
			for (marker in page) {
				val entryIdentity = PortableWifiOpaqueIdentity.derive(
					PortableWifiIdentityKind.LOGICAL_ENTRY,
					marker.logicalTrackingId,
				).value
				val runIdentity = PortableWifiOpaqueIdentity.derive(
					PortableWifiIdentityKind.PHYSICAL_RUN,
					marker.serviceRunId,
				).value
				if (entryIdentity == selectionIdentity || runIdentity in expectedRuns) {
					val expected = expectedRuns[runIdentity] ?: originConflict()
					val scope = SourceDeletionFenceEntity.logicalServiceRunIdentity(
						WIFI_SOURCE,
						SessionManifestPurposeCode.SESSION_CAPTURE,
						marker.logicalTrackingId,
						marker.serviceRunId,
					)
					if (entryIdentity != selectionIdentity ||
						expected.deletionScopeDigest != scope ||
						marker.collectedDataEpoch != expected.collectedDataEpoch ||
						marker.generation != expected.generation
					) originConflict()
					matched += WifiSelectedDeletionRunMarker(
						runIdentity,
						entryIdentity,
						scope,
						marker.collectedDataEpoch,
						marker.generation,
						marker.updatedAtMs,
					)
				}
			}
			loaded = Math.addExact(loaded, page.size.toLong())
			if (page.isEmpty() || page.size < ImportedWifiDao.OWNER_PAGE_SIZE) break
			val last = page.last()
			afterLogical = last.logicalTrackingId
			afterRun = last.serviceRunId
		}
		if (loaded != total) storedCorrupt()
		return matched
	}

	private suspend fun authenticateProtectedNamespace(
		authority: WifiSelectedDeletionAuthority,
		state: SourceEvidenceState,
		allowOwnReceipt: Boolean,
	) {
		val dao = database.importedWifiDao()
		val targetOrigin = authority.protectedIdentities.first().receiptOrigin
		val expectedByKey = authority.protectedIdentities.associateBy {
			it.identityKind to it.protectedIdentity
		}
		for (identities in authority.protectedIdentities.map { it.protectedIdentity }
			.chunked(PROTECTED_QUERY_BATCH_SIZE)
		) {
			val owners = dao.selectedDeletionProtectedIdentityOwners(
				identities,
				limits.maximumProtectedIdentities + 1,
			)
			if (owners.size > limits.maximumProtectedIdentities ||
				owners.any { owner ->
					val expected = expectedByKey[owner.identityKind to owner.protectedIdentity]
					owner.collectedDataEpoch != state.collectedDataEpoch || expected == null ||
						owner.selectionIdentity != authority.selectionIdentity ||
						owner.receiptOrigin == targetOrigin && (
							!allowOwnReceipt || !owner.hasCompatibleProtectedOwner(expected)
							) ||
						owner.receiptOrigin != targetOrigin &&
						!owner.hasCompatibleProtectedOwner(expected)
				}
			) originConflict()
			val fences = dao.deletionFenceIdentityOwners(
				identities,
				limits.maximumProtectedIdentities + 1,
			)
			if (fences.size > limits.maximumProtectedIdentities ||
				fences.any { fence ->
					fence.collectedDataEpoch != state.collectedDataEpoch ||
						fence.scopeIdentityDigest !in authority.runMarkers.map {
							it.deletionScopeDigest
						} || fence.sourceKind != WIFI_SOURCE ||
						fence.purpose != SessionManifestPurposeCode.SESSION_CAPTURE
				}
			) originConflict()
		}
	}

	private suspend fun authenticatePayloadAbsent(
		authority: WifiSelectedDeletionAuthority,
		imported: Boolean,
	) {
		val dao = database.importedWifiDao()
		if (imported) {
			if (dao.entryRevisionsForAdmission(authority.selectionIdentity).isNotEmpty() ||
				dao.receiptsForAdmission(authority.selectionIdentity).isNotEmpty() ||
				dao.allRunsForAdmission(authority.selectionIdentity).isNotEmpty() ||
				dao.allRunZonesForAdmission(authority.selectionIdentity).isNotEmpty() ||
				dao.allObservationsForAdmission(authority.selectionIdentity).isNotEmpty()
			) concurrentMutation()
		} else {
			val protectedObservations = authority.protectedIdentities.filter {
				it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION
			}.mapTo(hashSetOf(), WifiSelectedDeletionProtectedIdentityEntity::protectedIdentity)
			var after: String? = null
			var loaded = 0L
			val expected = dao.localObservationOwnerCount()
			if (expected < 0L || expected > ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS) dependencyOverflow()
			while (true) {
				val page = dao.localObservationOwnerPage(after, ImportedWifiDao.OWNER_PAGE_SIZE)
				if (page.any { owner ->
						PortableWifiOpaqueIdentity.derive(
							PortableWifiIdentityKind.OBSERVATION,
							owner.logicalFactId,
						).value in protectedObservations
					}
				) concurrentMutation()
				loaded = Math.addExact(loaded, page.size.toLong())
				if (page.isEmpty() || page.size < ImportedWifiDao.OWNER_PAGE_SIZE) break
				after = page.last().logicalFactId
			}
			if (loaded != expected) storedCorrupt()
		}
	}

	private suspend fun exactSourceFences(
		authority: WifiSelectedDeletionAuthority,
		state: SourceEvidenceState,
	): List<SourceDeletionFenceEntity> {
		val scopes = authority.runMarkers.map(WifiSelectedDeletionRunMarker::deletionScopeDigest)
		val fences = database.trackingHistoryReadDao().deletionFences(
			WIFI_SOURCE,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopes,
		)
		if (fences.distinctBy(SourceDeletionFenceEntity::scopeIdentityDigest).size != fences.size ||
			fences.any {
				it.collectedDataEpoch != state.collectedDataEpoch || it.fenceGeneration <= 0L ||
					it.scopeIdentityDigest !in scopes
			}
		) storedCorrupt()
		return fences
	}

	private fun authorityFromReceipt(
		receipt: WifiSelectedDeletionReceiptEntity,
		protected: List<WifiSelectedDeletionProtectedIdentityEntity>,
	): WifiSelectedDeletionAuthority {
		val entryRows = protected.filter {
			it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_ENTRY
		}
		val runProtected = protected.filter {
			it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN
		}
		val scopeProtected = protected.filter {
			it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_DELETION_SCOPE
		}
		val observationProtected = protected.filter {
			it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION
		}
		if (entryRows.singleOrNull()?.protectedIdentity != receipt.selectionIdentity ||
			runProtected.size != receipt.expectedRunCount ||
			scopeProtected.size != receipt.expectedRunCount ||
			observationProtected.size != receipt.expectedObservationCount
		) storedCorrupt()
		val runRows = runProtected.map { marker ->
			WifiSelectedDeletionRunMarker(
				marker.protectedIdentity,
				marker.ownerEntryIdentity,
				requireNotNull(marker.deletionScopeDigest),
				marker.collectedDataEpoch,
				1L,
				receipt.deletedAtMs,
			)
		}
		val runByIdentity = runRows.associateBy(WifiSelectedDeletionRunMarker::runIdentity)
		if (runByIdentity.size != runRows.size ||
			scopeProtected.any { scope ->
				val run = scope.ownerRunIdentity?.let(runByIdentity::get)
				run == null || scope.protectedIdentity != run.deletionScopeDigest ||
					scope.ownerEntryIdentity != receipt.selectionIdentity
			} || observationProtected.any { observation ->
				observation.ownerEntryIdentity != receipt.selectionIdentity ||
					observation.ownerRunIdentity !in runByIdentity ||
					observation.aggregateOwnerIdentity?.let { aggregate ->
						observationProtected.singleOrNull {
							it.protectedIdentity == aggregate
						}?.let { owner -> owner.aggregateOwnerIdentity == null } != true
					} == true
			}
		) storedCorrupt()
		return WifiSelectedDeletionAuthority(
			receipt.selectionIdentity,
			receipt.startTimeMs,
			receipt.endTimeMs,
			protected,
			runRows,
		)
	}

	private fun protectedIdentityByteSize(
		value: WifiSelectedDeletionProtectedIdentityEntity,
	): Long = listOfNotNull(
		value.selectionIdentity,
		value.identityKind,
		value.protectedIdentity,
		value.ownerEntryIdentity,
		value.ownerRunIdentity,
		value.deletionScopeDigest,
		value.aggregateOwnerIdentity,
		value.revisionSetChecksum,
		value.effectChecksum,
	).sumOf { it.length.toLong() }

	private fun WifiHistorySelection.selectionIdentity(): String = when (this) {
		is WifiHistorySelection.Local -> key.value
		is WifiHistorySelection.Imported -> selected.key.value
	}

	private fun WifiHistorySelection.receiptOrigin(): String = when (this) {
		is WifiHistorySelection.Local -> WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL
		is WifiHistorySelection.Imported -> WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED
	}

	private fun blocked(reason: WifiHistoryDeletionBlockedReason): Nothing =
		throw WifiSelectedDeletionAbort(DeleteSelectedWifiHistoryResult.Blocked(reason))

	private fun storedCorrupt(): Nothing =
		unverifiable(WifiHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

	private fun originConflict(): Nothing =
		unverifiable(WifiHistoryDeletionUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)

	private fun dependencyOverflow(): Nothing =
		unverifiable(WifiHistoryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)

	private fun concurrentMutation(): Nothing = throw WifiSelectedDeletionAbort(
		DeleteSelectedWifiHistoryResult.RetryableFailure(
			WifiHistoryDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
		),
	)

	private fun unverifiable(reason: WifiHistoryDeletionUnverifiableReason): Nothing =
		throw WifiSelectedDeletionAbort(unverifiableResult(reason))

	private companion object {
		const val MAX_SELECTED_RUNS = 64
		const val MAX_MANIFESTS = 256
		const val MAX_MANIFEST_SOURCES = 4_096
		const val MAX_COMPLETENESS = 256
		const val PROTECTED_QUERY_BATCH_SIZE = 256
		const val DELETE_BATCH_SIZE = 256
		const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
	}
}

internal data class WifiSelectedDeletionLimits(
	val maximumProtectedIdentities: Int = 262_144,
	val maximumProtectedBytes: Long = 8L * 1024L * 1024L,
) {
	init {
		require(maximumProtectedIdentities in 1..ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS)
		require(maximumProtectedBytes > 0L)
	}
}

internal enum class WifiSelectedDeletionCheckpoint {
	TRANSACTION_STARTED,
	MARKERS_RECORDED,
	PAYLOAD_REMOVED,
}

private class WifiSelectedDeletionAbort(
	val result: DeleteSelectedWifiHistoryResult,
) : RuntimeException(null, null, false, false)

private fun unverifiableResult(reason: WifiHistoryDeletionUnverifiableReason) =
	DeleteSelectedWifiHistoryResult.Unverifiable(reason)

private fun SourceEvidenceState.hasValidSelectedWifiDeletionShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L

private fun WifiSelectedDeletionReceiptEntity.toDeletedHistoryEntry(
	selection: WifiHistorySelection,
) = WifiHistoryEntry(
	key = WifiHistoryEntryKey("wifi-deleted:$selectionIdentity"),
	startTime = EpochMs(startTimeMs),
	endTime = EpochMs(endTimeMs),
	storedZoneIds = emptySet(),
	state = WifiHistoryProductState.DELETED,
	coverage = WifiHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(WifiHistoryCause.DELETED),
	origin = selection.origin,
	importedSelection = (selection as? WifiHistorySelection.Imported)?.selected,
	localSelection = (selection as? WifiHistorySelection.Local)?.key,
	capturesOnlyWifi = origin == WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
)

private fun SessionManifestSourceEntity.isExactSelectedWifiWriter(): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI &&
		purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		persistenceEligible &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS &&
		writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
		writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
		writerBindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION

private fun WifiSelectedDeletionProtectedIdentityEntity.hasCompatibleProtectedOwner(
	other: WifiSelectedDeletionProtectedIdentityEntity,
): Boolean = selectionIdentity == other.selectionIdentity &&
	identityKind == other.identityKind && protectedIdentity == other.protectedIdentity &&
	ownerEntryIdentity == other.ownerEntryIdentity && ownerRunIdentity == other.ownerRunIdentity &&
	deletionScopeDigest == other.deletionScopeDigest &&
	aggregateOwnerIdentity == other.aggregateOwnerIdentity &&
	aggregateOwnerSemanticRevision == other.aggregateOwnerSemanticRevision &&
	collectedDataEpoch == other.collectedDataEpoch

private fun com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure.toDeletionReason():
	WifiHistoryDeletionUnverifiableReason = when (this) {
	com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure.DEPENDENCY_OVERFLOW ->
		WifiHistoryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW
	com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT ->
		WifiHistoryDeletionUnverifiableReason.ORIGIN_IDENTITY_CONFLICT
	else -> WifiHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
}

private fun ImportedWifiLineageAuthenticator.Reason.toDeletionReason():
	WifiHistoryDeletionUnverifiableReason = when (this) {
	ImportedWifiLineageAuthenticator.Reason.DEPENDENCY_OVERFLOW,
	ImportedWifiLineageAuthenticator.Reason.RUN_OVERFLOW,
	ImportedWifiLineageAuthenticator.Reason.ZONE_OVERFLOW,
	ImportedWifiLineageAuthenticator.Reason.OBSERVATION_OVERFLOW,
	ImportedWifiLineageAuthenticator.Reason.REVISION_OVERFLOW,
	-> WifiHistoryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedWifiLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE ->
		WifiHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
}
