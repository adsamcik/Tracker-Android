package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellFactDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellReceiptEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
internal class RoomAmbientCellPortableTransfer @Inject constructor(
	private val database: AppDatabase,
	private val repository: RoomAmbientCellRepository,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableAmbientCell, ImportPortableAmbientCell {
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
		if (snapshot.facts.isEmpty()) return ExportPortableAmbientCellResult.NoData
		val portableFacts = snapshot.facts.map(AmbientCellPortableIntegrity::createFact)
		val portableGaps = snapshot.gaps.map(AmbientCellPortableIntegrity::createGap)
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
		if (dao.importTombstone(request.archive.archiveId) != null) {
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
		for (fact in request.archive.facts) {
			val history = historiesByFact[fact.identity].orEmpty()
			if (history.any {
					it.collectedDataEpoch != request.expectedCollectedDataEpoch
				}
			) return ImportPortableAmbientCellResult.StorageUnavailable
			if (history.withIndex().any { (index, revision) ->
					revision.semanticRevision != index.toLong() + 1L
				}
			) return ImportPortableAmbientCellResult.ReceiptConflict
			val sameRevision = history.singleOrNull {
				it.semanticRevision == fact.semanticRevision
			}
			if (sameRevision != null) {
				if (sameRevision.contentChecksum != fact.contentChecksum) {
					return ImportPortableAmbientCellResult.ReceiptConflict
				}
				duplicateRevisions += fact.identity to fact.semanticRevision
				continue
			}
			val latestRevision = history.lastOrNull()?.semanticRevision ?: 0L
			if (fact.semanticRevision != latestRevision + 1L ||
				fact.supersedesSemanticRevision != latestRevision.takeIf { it > 0L }
			) return ImportPortableAmbientCellResult.ReceiptConflict
		}
		val storedGapIdentities = loadGaps(dao, request.archive.gaps.map { it.identity })
		if (storedGapIdentities.size > AmbientCellPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		val storedGapsById = storedGapIdentities.associateBy { it.gapId }
		val duplicateGapIds = mutableSetOf<String>()
		for (gap in request.archive.gaps) {
			val stored = storedGapsById[gap.identity] ?: continue
			if (stored.contentChecksum != gap.contentChecksum) {
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
				requestedFacts[stored.factId to stored.semanticRevision]?.contentChecksum !=
					stored.contentChecksum
			}
		) return ImportPortableAmbientCellResult.ReceiptConflict
		val requestedGaps = request.archive.gaps.associateBy { it.identity }
		if (existingGaps.any { stored ->
				requestedGaps[stored.gapId]?.contentChecksum != stored.contentChecksum
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
					retentionPolicyId = request.retentionPolicyId,
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
					gap.origin.name,
					gap.startTimeMs,
					gap.endTimeMs,
					gap.storedZoneId,
					gap.structuralEpochDay,
					gap.reason,
					request.retentionPolicyId,
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
				request.archive.facts.size,
				request.archive.gaps.size,
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
