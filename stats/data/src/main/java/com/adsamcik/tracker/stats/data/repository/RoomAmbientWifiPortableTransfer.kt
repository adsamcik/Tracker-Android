package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiFactDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiReceiptEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiSink
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Source-only portable transfer. Import writes no demand, provider, authorization, session, or WAL. */
@Singleton
internal class RoomAmbientWifiPortableTransfer @Inject constructor(
	private val database: AppDatabase,
	private val repository: RoomAmbientWifiRepository,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportPortableAmbientWifi, ImportPortableAmbientWifi {
	override suspend fun export(
		request: ExportPortableAmbientWifiRequest,
		sink: PortableAmbientWifiSink,
	): ExportPortableAmbientWifiResult {
		val read = repository.read(
			AmbientWifiReadRequest(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.origins,
				AmbientWifiPortableFormatV1.MAX_FACTS,
			),
		)
		val snapshot = when (read) {
			is AmbientWifiReadResult.Snapshot -> read
			AmbientWifiReadResult.DependencyOverflow ->
				return ExportPortableAmbientWifiResult.DependencyOverflow
			AmbientWifiReadResult.StorageUnavailable ->
				return ExportPortableAmbientWifiResult.StorageUnavailable
		}
		if (snapshot.facts.isEmpty()) return ExportPortableAmbientWifiResult.NoData
		val portableFacts = snapshot.facts.map(AmbientWifiPortableIntegrity::createFact)
		val portableGaps = snapshot.gaps.map(AmbientWifiPortableIntegrity::createGap)
		val archiveId = AmbientWifiPortableIntegrity.opaqueIdentity(
			"archive",
			listOf(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.origins.sortedBy(AmbientWifiOrigin::name).joinToString(),
				portableFacts.joinToString {
					"${it.identity}:${it.semanticRevision}:${it.contentChecksum}"
				},
				portableGaps.joinToString { it.identity },
			).joinToString("\u001f"),
		)
		val archive = AmbientWifiPortableIntegrity.createArchive(
			archiveId,
			portableFacts,
			portableGaps,
		)
		sink.emit(archive)
		return ExportPortableAmbientWifiResult.Exported(
			archive.facts.size,
			archive.gaps.size,
		)
	}

	override suspend fun importArchive(
		request: ImportPortableAmbientWifiRequest,
	): ImportPortableAmbientWifiResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { importInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			ImportPortableAmbientWifiResult.StorageUnavailable
		}
	}

	private suspend fun importInTransaction(
		request: ImportPortableAmbientWifiRequest,
	): ImportPortableAmbientWifiResult {
		val evidence = database.sourceEvidenceStateDao().get()
			?: return ImportPortableAmbientWifiResult.StorageUnavailable
		if (evidence.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			return ImportPortableAmbientWifiResult.CollectedDataEpochChanged
		}
		if (request.archive.facts.any {
				it.latestPossibleTimeMs > request.receipt.receivedAtMs
			} || request.archive.gaps.any {
				it.endTimeMs > request.receipt.receivedAtMs
			}
		) return ImportPortableAmbientWifiResult.InvalidReceipt
		if (evidence.retainedFromMs?.let { floor ->
				request.archive.facts.any { fact -> fact.coverageStartTimeMs < floor } ||
					request.archive.gaps.any { gap -> gap.startTimeMs < floor }
			} == true
		) {
			return ImportPortableAmbientWifiResult.RetentionBoundary
		}
		val dao = database.ambientWifiFactDao()
		if (dao.importTombstone(request.archive.archiveId) != null) {
			return ImportPortableAmbientWifiResult.DeletedArchive
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
				ImportPortableAmbientWifiResult.Duplicate
			} else ImportPortableAmbientWifiResult.ReceiptConflict
		}
		val histories = loadFactRevisions(
			dao,
			request.archive.facts.map { it.identity },
		)
		if (histories.size > MAX_IMPORTED_WIFI_REVISIONS) {
			return ImportPortableAmbientWifiResult.DependencyOverflow
		}
		val historiesByFact = histories.groupBy(ImportedAmbientWifiFactEntity::factId)
		val duplicateRevisions = mutableSetOf<Pair<String, Long>>()
		for (fact in request.archive.facts) {
			val history = historiesByFact[fact.identity].orEmpty()
			if (history.any {
					it.collectedDataEpoch != request.expectedCollectedDataEpoch
				}
			) return ImportPortableAmbientWifiResult.StorageUnavailable
			if (history.withIndex().any { (index, revision) ->
					revision.semanticRevision != index.toLong() + 1L
				}
			) return ImportPortableAmbientWifiResult.ReceiptConflict
			val sameRevision = history.singleOrNull {
				it.semanticRevision == fact.semanticRevision
			}
			if (sameRevision != null) {
				if (sameRevision.contentChecksum != fact.contentChecksum) {
					return ImportPortableAmbientWifiResult.ReceiptConflict
				}
				duplicateRevisions += fact.identity to fact.semanticRevision
				continue
			}
			val latestRevision = history.lastOrNull()?.semanticRevision ?: 0L
			if (fact.semanticRevision != latestRevision + 1L ||
				fact.supersedesSemanticRevision != latestRevision.takeIf { it > 0L }
			) return ImportPortableAmbientWifiResult.ReceiptConflict
		}
		val storedGapIdentities = loadGaps(dao, request.archive.gaps.map { it.identity })
		if (storedGapIdentities.size > AmbientWifiPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientWifiResult.DependencyOverflow
		}
		val storedGapsById = storedGapIdentities.associateBy { it.gapId }
		val duplicateGapIds = mutableSetOf<String>()
		for (gap in request.archive.gaps) {
			val stored = storedGapsById[gap.identity] ?: continue
			if (stored.contentChecksum != gap.contentChecksum) {
				return ImportPortableAmbientWifiResult.ReceiptConflict
			}
			duplicateGapIds += gap.identity
		}
		val existing = dao.importedForArchive(
			request.archive.archiveId,
			AmbientWifiPortableFormatV1.MAX_FACTS + 1,
		)
		val existingGaps = dao.importedGapsForArchive(
			request.archive.archiveId,
			AmbientWifiPortableFormatV1.MAX_GAPS + 1,
		)
		if (existing.size > AmbientWifiPortableFormatV1.MAX_FACTS) {
			return ImportPortableAmbientWifiResult.DependencyOverflow
		}
		if (existingGaps.size > AmbientWifiPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientWifiResult.DependencyOverflow
		}
		val requestedFacts = request.archive.facts.associateBy { it.identity to it.semanticRevision }
		if (existing.any { stored ->
				requestedFacts[stored.factId to stored.semanticRevision]?.contentChecksum !=
					stored.contentChecksum
			}
		) return ImportPortableAmbientWifiResult.ReceiptConflict
		val requestedGaps = request.archive.gaps.associateBy { it.identity }
		if (existingGaps.any { stored ->
				requestedGaps[stored.gapId]?.contentChecksum != stored.contentChecksum
			}
		) {
			return ImportPortableAmbientWifiResult.ReceiptConflict
		}
		request.archive.facts.forEach { fact ->
			if (fact.identity to fact.semanticRevision in duplicateRevisions) return@forEach
			dao.insertImportedFact(
				ImportedAmbientWifiFactEntity(
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
					observationCount = fact.observationCount,
					twoPointFourGhzCount = fact.twoPointFourGhzCount,
					fiveGhzCount = fact.fiveGhzCount,
					sixGhzCount = fact.sixGhzCount,
					otherBandCount = fact.otherBandCount,
					strongestSignalDbm = fact.strongestSignalDbm,
					weakestSignalDbm = fact.weakestSignalDbm,
					meanSignalDbm = fact.meanSignalDbm,
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
				ImportedAmbientWifiGapEntity(
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
			ImportPortableAmbientWifiResult.Duplicate
		} else {
			ImportPortableAmbientWifiResult.Applied(
				request.archive.facts.size,
				request.archive.gaps.size,
			)
		}
	}

	private fun ImportPortableAmbientWifiRequest.toReceipt() =
		ImportedAmbientWifiReceiptEntity(
			receipt.jobId,
			receipt.entryKey,
			receipt.sourceName,
			archive.archiveId,
			archive.contentChecksum,
			expectedCollectedDataEpoch,
			receipt.receivedAtMs,
		)

	private suspend fun loadFactRevisions(
		dao: AmbientWifiFactDao,
		factIds: List<String>,
	): List<ImportedAmbientWifiFactEntity> = buildList {
		factIds.distinct().chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(dao.importedFactRevisions(chunk, MAX_IMPORTED_WIFI_REVISIONS + 1 - size))
			if (size > MAX_IMPORTED_WIFI_REVISIONS) return@buildList
		}
	}

	private suspend fun loadGaps(
		dao: AmbientWifiFactDao,
		gapIds: List<String>,
	): List<ImportedAmbientWifiGapEntity> = buildList {
		gapIds.distinct().chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(dao.importedGapsByIds(
				chunk,
				AmbientWifiPortableFormatV1.MAX_GAPS + 1 - size,
			))
			if (size > AmbientWifiPortableFormatV1.MAX_GAPS) return@buildList
		}
	}

	private companion object {
		const val MAX_IMPORTED_WIFI_REVISIONS = 65_536
		const val SQLITE_ID_CHUNK_SIZE = 400
	}
}
