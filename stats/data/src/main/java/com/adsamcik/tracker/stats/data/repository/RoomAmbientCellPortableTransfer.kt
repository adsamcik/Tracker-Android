package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellFactDao
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellReceiptEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.isActiveApproval
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellFactV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellGapV1
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
internal class RoomAmbientCellPortableTransfer internal constructor(
	private val database: AppDatabase,
	private val repository: RoomAmbientCellRepository,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val ensurePortableRetention: suspend () -> Boolean = { true },
) : ExportPortableAmbientCell, ImportPortableAmbientCell {
	@Inject
	constructor(
		database: AppDatabase,
		repository: RoomAmbientCellRepository,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
		retentionAuthorityProducer: RetentionAuthorityProducer,
	) : this(
		database,
		repository,
		ioDispatcher,
		{
			retentionAuthorityProducer.approvePortableImport(TrackingSource.CELL)
				.isActiveApproval()
		},
	)

	override suspend fun export(
		request: ExportPortableAmbientCellRequest,
		sink: PortableAmbientCellSink,
	): ExportPortableAmbientCellResult {
		val read = repository.read(
			AmbientCellReadRequest(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.origins,
				AmbientCellPortableFormatV1.MAX_FACTS,
			),
		)
		val snapshot = when (read) {
			is AmbientCellReadResult.Snapshot -> read
			AmbientCellReadResult.DependencyOverflow ->
				return ExportPortableAmbientCellResult.DependencyOverflow
			AmbientCellReadResult.StorageUnavailable ->
				return ExportPortableAmbientCellResult.StorageUnavailable
		}
		if (snapshot.facts.isEmpty() && snapshot.gaps.isEmpty()) {
			return ExportPortableAmbientCellResult.NoData
		}
		val localFactIds = snapshot.facts.filter {
			it.origin == AmbientCellOrigin.LOCAL_DEVICE
		}.map { it.identity }
		val localFacts = if (localFactIds.isEmpty()) emptyList() else {
			database.ambientCellFactDao().effectiveLocalLineages(
				localFactIds,
				AmbientCellPortableFormatV1.MAX_FACTS + 1,
			).map { row ->
				AmbientCellPortableIntegrity.createFact(
					row.toApiFact() ?: return ExportPortableAmbientCellResult.StorageUnavailable,
				)
			}
		}
		val importedFactIds = snapshot.facts
			.filter { it.origin == AmbientCellOrigin.PORTABLE_IMPORT }
			.map { it.identity }
		val importedFacts = if (importedFactIds.isEmpty()) emptyList() else {
			loadFactRevisions(database.ambientCellFactDao(), importedFactIds)
				.map(ImportedAmbientCellFactEntity::toPortableFact)
		}
		val portableFacts = localFacts + importedFacts
		if (portableFacts.size > AmbientCellPortableFormatV1.MAX_FACTS) {
			return ExportPortableAmbientCellResult.DependencyOverflow
		}
		val localGaps = snapshot.gaps.filter { it.origin == AmbientCellOrigin.LOCAL_DEVICE }
			.map(AmbientCellPortableIntegrity::createGap)
		val importedGapIds = snapshot.gaps
			.filter { it.origin == AmbientCellOrigin.PORTABLE_IMPORT }
			.map { it.identity }
		val importedGaps = if (importedGapIds.isEmpty()) emptyList() else {
			loadGaps(database.ambientCellFactDao(), importedGapIds)
				.map(ImportedAmbientCellGapEntity::toPortableGap)
		}
		val portableGaps = localGaps + importedGaps
		if (portableGaps.size > AmbientCellPortableFormatV1.MAX_GAPS) {
			return ExportPortableAmbientCellResult.DependencyOverflow
		}
		val archiveId = AmbientCellPortableIntegrity.opaqueIdentity(
			"archive",
			listOf(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.origins.sortedBy(AmbientCellOrigin::name).joinToString(),
				portableFacts.joinToString {
					"${it.identity}:${it.semanticRevision}:${it.contentChecksum}"
				},
				portableGaps.joinToString { it.identity },
			).joinToString("\u001f"),
		)
		val archive = AmbientCellPortableIntegrity.createArchive(
			archiveId,
			portableFacts,
			portableGaps,
		)
		sink.emit(archive)
		return ExportPortableAmbientCellResult.Exported(
			archive.facts.size,
			archive.gaps.size,
		)
	}

	override suspend fun importArchive(
		request: ImportPortableAmbientCellRequest,
	): ImportPortableAmbientCellResult = withContext(ioDispatcher) {
		try {
			if (!ensurePortableRetention()) {
				return@withContext ImportPortableAmbientCellResult.RetentionAuthorityUnavailable
			}
			database.withTransaction { importInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			ImportPortableAmbientCellResult.StorageUnavailable
		}
	}

	private suspend fun importInTransaction(
		request: ImportPortableAmbientCellRequest,
	): ImportPortableAmbientCellResult {
		val evidence = database.sourceEvidenceStateDao().get()
			?: return ImportPortableAmbientCellResult.StorageUnavailable
		if (evidence.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			return ImportPortableAmbientCellResult.CollectedDataEpochChanged
		}
		if (request.archive.facts.any {
				it.latestPossibleTimeMs > request.receipt.receivedAtMs
			} || request.archive.gaps.any {
				it.endTimeMs > request.receipt.receivedAtMs
			}
		) return ImportPortableAmbientCellResult.InvalidReceipt
		if (evidence.retainedFromMs?.let { floor ->
				request.archive.facts.any { fact -> fact.coverageStartTimeMs < floor } ||
					request.archive.gaps.any { gap -> gap.startTimeMs < floor }
			} == true
		) {
			return ImportPortableAmbientCellResult.RetentionBoundary
		}
		val dao = database.ambientCellFactDao()
		val retention = dao.latestRetentionAuthority(
			AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
		)?.takeIf {
			AmbientCellRetentionAuthorityIntegrity.isAuthentic(it) &&
				it.isActive &&
				it.collectedDataEpoch == request.expectedCollectedDataEpoch
		} ?: return ImportPortableAmbientCellResult.RetentionAuthorityUnavailable
		dao.importTombstone(request.archive.archiveId)?.let { tombstone ->
			if (tombstone.effectChecksum !=
				AmbientCellFactIntegrity.importTombstoneChecksum(tombstone)
			) return ImportPortableAmbientCellResult.StorageUnavailable
			return ImportPortableAmbientCellResult.DeletedArchive
		}
		val footprintStates = mutableListOf<CellReplayFootprintState>()
		footprintStates += dao.footprintState(
				AmbientCellReplayFootprintEntity.KIND_ARCHIVE_SCOPE,
				request.archive.archiveId,
				0L,
			)
		request.archive.facts.forEach { fact ->
			footprintStates += dao.footprintState(
					AmbientCellReplayFootprintEntity.KIND_FACT_IDENTITY,
					fact.identity,
					fact.semanticRevision,
				)
			footprintStates += dao.footprintState(
					AmbientCellReplayFootprintEntity.KIND_FACT_EFFECT,
					fact.effectChecksum,
					0L,
				)
		}
		request.archive.gaps.forEach { gap ->
			footprintStates += dao.footprintState(
					AmbientCellReplayFootprintEntity.KIND_GAP_IDENTITY,
					gap.identity,
					0L,
				)
			footprintStates += dao.footprintState(
					AmbientCellReplayFootprintEntity.KIND_GAP_EFFECT,
					gap.effectChecksum,
					0L,
				)
		}
		if (CellReplayFootprintState.CORRUPT in footprintStates) {
			return ImportPortableAmbientCellResult.StorageUnavailable
		}
		if (CellReplayFootprintState.AUTHENTIC in footprintStates) {
			return ImportPortableAmbientCellResult.DeletedArchive
		}
		val priorReceipt = dao.importReceipt(request.receipt.jobId, request.receipt.entryKey)
		if (priorReceipt != null) {
			return if (
				priorReceipt.archiveId == request.archive.archiveId &&
				priorReceipt.archiveChecksum == request.archive.contentChecksum &&
				priorReceipt.sourceName == request.receipt.sourceName &&
				priorReceipt.receivedAtMs == request.receipt.receivedAtMs &&
				priorReceipt.collectedDataEpoch == request.expectedCollectedDataEpoch
			) {
				ImportPortableAmbientCellResult.Duplicate
			} else ImportPortableAmbientCellResult.ReceiptConflict
		}
		val histories = loadFactRevisions(
			dao,
			request.archive.facts.map { it.identity },
		)
		if (histories.size > MAX_IMPORTED_CELL_REVISIONS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		val historiesByFact = histories.groupBy(ImportedAmbientCellFactEntity::factId)
		val duplicateRevisions = mutableSetOf<Pair<String, Long>>()
		for ((factId, lineage) in request.archive.facts.groupBy { it.identity }) {
			val history = historiesByFact[factId].orEmpty()
			if (history.any {
					it.collectedDataEpoch != request.expectedCollectedDataEpoch
				}
			) return ImportPortableAmbientCellResult.StorageUnavailable
			if (history.withIndex().any { (index, revision) ->
					revision.semanticRevision != index.toLong() + 1L
				}
			) return ImportPortableAmbientCellResult.ReceiptConflict
			var latestRevision = history.lastOrNull()?.semanticRevision ?: 0L
			for (fact in lineage.sortedBy { it.semanticRevision }) {
				val sameRevision = history.singleOrNull {
					it.semanticRevision == fact.semanticRevision
				}
				if (sameRevision != null) {
					if (sameRevision.contentChecksum != fact.contentChecksum ||
						sameRevision.portableEffectChecksum != fact.effectChecksum
					) return ImportPortableAmbientCellResult.ReceiptConflict
					duplicateRevisions += fact.identity to fact.semanticRevision
					continue
				}
				if (fact.semanticRevision != latestRevision + 1L ||
					fact.supersedesSemanticRevision != latestRevision.takeIf { it > 0L }
				) return ImportPortableAmbientCellResult.ReceiptConflict
				latestRevision = fact.semanticRevision
			}
		}
		val storedGapIdentities = loadGaps(dao, request.archive.gaps.map { it.identity })
		if (storedGapIdentities.size > AmbientCellPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		val storedGapsById = storedGapIdentities.associateBy { it.gapId }
		val duplicateGapIds = mutableSetOf<String>()
		for (gap in request.archive.gaps) {
			val stored = storedGapsById[gap.identity] ?: continue
			if (stored.contentChecksum != gap.contentChecksum ||
				stored.portableEffectChecksum != gap.effectChecksum
			) {
				return ImportPortableAmbientCellResult.ReceiptConflict
			}
			duplicateGapIds += gap.identity
		}
		val existing = dao.importedForArchive(
			request.archive.archiveId,
			AmbientCellPortableFormatV1.MAX_FACTS + 1,
		)
		val existingGaps = dao.importedGapsForArchive(
			request.archive.archiveId,
			AmbientCellPortableFormatV1.MAX_GAPS + 1,
		)
		if (existing.size > AmbientCellPortableFormatV1.MAX_FACTS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		if (existingGaps.size > AmbientCellPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		val requestedFacts = request.archive.facts.associateBy { it.identity to it.semanticRevision }
		if (existing.any { stored ->
				requestedFacts[stored.factId to stored.semanticRevision]?.let { requested ->
					requested.contentChecksum == stored.contentChecksum &&
						requested.effectChecksum == stored.portableEffectChecksum
				} != true
			}
		) return ImportPortableAmbientCellResult.ReceiptConflict
		val requestedGaps = request.archive.gaps.associateBy { it.identity }
		if (existingGaps.any { stored ->
				requestedGaps[stored.gapId]?.let { requested ->
					requested.contentChecksum == stored.contentChecksum &&
						requested.effectChecksum == stored.portableEffectChecksum
				} != true
			}
		) {
			return ImportPortableAmbientCellResult.ReceiptConflict
		}
		request.archive.facts.forEach { fact ->
			if (fact.identity to fact.semanticRevision in duplicateRevisions) return@forEach
			val technology = fact.technologyMix
			val quality = fact.qualityDistribution
			dao.insertImportedFact(
				ImportedAmbientCellFactEntity(
					archiveId = request.archive.archiveId,
					factId = fact.identity,
					semanticRevision = fact.semanticRevision,
					supersedesSemanticRevision = fact.supersedesSemanticRevision,
					contentChecksum = fact.contentChecksum,
					portableEffectChecksum = fact.effectChecksum,
					portableOrigin = fact.origin.name,
					coverageStartTimeMs = fact.coverageStartTimeMs,
					observedTimeMs = fact.observedTimeMs,
					latestPossibleTimeMs = fact.latestPossibleTimeMs,
					storedZoneId = fact.storedZoneId,
					structuralEpochDay = fact.structuralEpochDay,
					coverageCompleteness = fact.coverage.name,
					subscriptionCompleteness = fact.subscriptionCompleteness.name,
					observationCount = fact.observationCount,
					registeredObservationCount = fact.registeredObservationCount,
					gsmCount = technology.gsmCount,
					cdmaCount = technology.cdmaCount,
					wcdmaCount = technology.wcdmaCount,
					tdscdmaCount = technology.tdscdmaCount,
					lteCount = technology.lteCount,
					nrCount = technology.nrCount,
					qualityUnknownCount = quality.unknownCount,
					qualityNoneOrUnknownCount = quality.noneOrUnknownCount,
					qualityPoorCount = quality.poorCount,
					qualityModerateCount = quality.moderateCount,
					qualityGoodCount = quality.goodCount,
					qualityGreatCount = quality.greatCount,
					retentionPolicyId = retention.opaquePolicyId,
					retentionApprovalRevision = retention.approvalRevision,
					collectedDataEpoch = request.expectedCollectedDataEpoch,
					importDeletionGeneration = 0L,
					receivedAtMs = request.receipt.receivedAtMs,
				),
			)
		}
		request.archive.gaps.forEach { gap ->
			if (gap.identity in duplicateGapIds) return@forEach
			dao.insertImportedGap(
				ImportedAmbientCellGapEntity(
					request.archive.archiveId,
					gap.identity,
					gap.contentChecksum,
					gap.effectChecksum,
					gap.origin.name,
					gap.startTimeMs,
					gap.endTimeMs,
					gap.storedZoneId,
					gap.structuralEpochDay,
					gap.reason,
					retention.opaquePolicyId,
					retention.approvalRevision,
					request.expectedCollectedDataEpoch,
					request.receipt.receivedAtMs,
				),
			)
		}
		dao.insertImportReceipt(request.toReceipt())
		check(database.sourceEvidenceStateDao().incrementRevision(request.receipt.receivedAtMs) == 1)
		return if (duplicateRevisions.size == request.archive.facts.size &&
			duplicateGapIds.size == request.archive.gaps.size
		) {
			ImportPortableAmbientCellResult.Duplicate
		} else {
			ImportPortableAmbientCellResult.Applied(
				request.archive.facts.size - duplicateRevisions.size,
				request.archive.gaps.size - duplicateGapIds.size,
			)
		}
	}

	private fun ImportPortableAmbientCellRequest.toReceipt() =
		ImportedAmbientCellReceiptEntity(
			receipt.jobId,
			receipt.entryKey,
			receipt.sourceName,
			archive.archiveId,
			archive.contentChecksum,
			expectedCollectedDataEpoch,
			receipt.receivedAtMs,
		)

	private suspend fun loadFactRevisions(
		dao: AmbientCellFactDao,
		factIds: List<String>,
	): List<ImportedAmbientCellFactEntity> = buildList {
		factIds.distinct().chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(dao.importedFactRevisions(chunk, MAX_IMPORTED_CELL_REVISIONS + 1 - size))
			if (size > MAX_IMPORTED_CELL_REVISIONS) return@buildList
		}
	}

	private suspend fun loadGaps(
		dao: AmbientCellFactDao,
		gapIds: List<String>,
	): List<ImportedAmbientCellGapEntity> = buildList {
		gapIds.distinct().chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(dao.importedGapsByIds(
				chunk,
				AmbientCellPortableFormatV1.MAX_GAPS + 1 - size,
			))
			if (size > AmbientCellPortableFormatV1.MAX_GAPS) return@buildList
		}
	}

	private companion object {
		const val MAX_IMPORTED_CELL_REVISIONS = 65_536
		const val SQLITE_ID_CHUNK_SIZE = 400
	}
}

private suspend fun AmbientCellFactDao.footprintState(
	kind: String,
	identity: String,
	semanticRevision: Long,
): CellReplayFootprintState {
	val footprint = replayFootprint(kind, identity, semanticRevision)
		?: return CellReplayFootprintState.ABSENT
	return if (AmbientCellFactIntegrity.isAuthentic(footprint)) {
		CellReplayFootprintState.AUTHENTIC
	} else {
		CellReplayFootprintState.CORRUPT
	}
}

private enum class CellReplayFootprintState {
	ABSENT,
	AUTHENTIC,
	CORRUPT,
}

private fun ImportedAmbientCellFactEntity.toPortableFact() = PortableAmbientCellFactV1(
	factId,
	contentChecksum,
	portableEffectChecksum,
	AmbientCellOrigin.valueOf(portableOrigin),
	coverageStartTimeMs,
	observedTimeMs,
	latestPossibleTimeMs,
	structuralEpochDay,
	storedZoneId,
	AmbientCellCoverage.valueOf(coverageCompleteness),
	com.adsamcik.tracker.stats.api.repository.AmbientCellSubscriptionCompleteness.valueOf(
		subscriptionCompleteness,
	),
	observationCount,
	registeredObservationCount,
	com.adsamcik.tracker.stats.api.repository.AmbientCellTechnologyMix(
		gsmCount,
		cdmaCount,
		wcdmaCount,
		tdscdmaCount,
		lteCount,
		nrCount,
	),
	com.adsamcik.tracker.stats.api.repository.AmbientCellQualityDistribution(
		qualityUnknownCount,
		qualityNoneOrUnknownCount,
		qualityPoorCount,
		qualityModerateCount,
		qualityGoodCount,
		qualityGreatCount,
	),
	semanticRevision,
	supersedesSemanticRevision,
)

private fun ImportedAmbientCellGapEntity.toPortableGap() = PortableAmbientCellGapV1(
	gapId,
	contentChecksum,
	portableEffectChecksum,
	AmbientCellOrigin.valueOf(portableOrigin),
	structuralEpochDay,
	startTimeMs,
	endTimeMs,
	storedZoneId,
	reason,
)
