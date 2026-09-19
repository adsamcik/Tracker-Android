package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.base.database.authenticatedGraph
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedStepsCaptureRevision
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryMember
import com.adsamcik.tracker.stats.api.repository.PORTABLE_STEPS_ENTRY_ORDER
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.value.EpochMs

internal sealed interface ImportedStepsRecentRead {
	data class Ready(
		val entries: List<ImportedStepsHistoryEntry>,
	) : ImportedStepsRecentRead

	data class Unavailable(
		val reason: ImportedStepsReadFailure,
	) : ImportedStepsRecentRead
}

/** Caller owns one Room snapshot; all retained source validation is shared with deletion/retention. */
internal class ImportedStepsProductReader(
	private val database: AppDatabase,
	private val retained: ImportedStepsRetainedReader = ImportedStepsRetainedReader(database),
) {

	suspend fun selectSessionsInTransaction(segmentIds: List<Long>): Map<Long, SessionHistory> {
		val segments = database.trackingHistoryReadDao().segments(segmentIds)
			.filter { it.source == SegmentSource.PORTABLE_STEPS_IMPORT }
		if (segments.isEmpty()) { return emptyMap() }
		val ids = segments.mapNotNull { it.logicalTrackingId }.distinct()
		val result = readSessionEntries(ids)
		val histories = result.entries.flatMap { it.sessionHistories() }.associateBy { it.segmentId }
		return segments.associate { segment ->
			segment.id to (histories[segment.id] ?: unverifiableSession(
				segment.id, result.unverifiableEntries[segment.logicalTrackingId],
			))
		}
	}

	private suspend fun readSessionEntries(ids: List<String>): ImportedStepsRetainedRead.Ready {
		val entries = mutableListOf<RetainedImportedStepsEntry>()
		val failures = mutableMapOf<String, ImportedStepsReadFailure>()
		for (batch in ids.chunked(ImportedStepsRetainedReader.MAX_ENTRY_BATCH)) {
			when (val result = retained.readEntriesInTransaction(batch)) {
				is ImportedStepsRetainedRead.Ready -> {
					entries += result.entries
					failures += result.unverifiableEntries
				}
				is ImportedStepsRetainedRead.Unverifiable -> batch.forEach { failures[it] = result.reason }
			}
		}
		return ImportedStepsRetainedRead.Ready(entries, failures)
	}

	suspend fun recentInTransaction(limit: Int): List<ImportedStepsHistoryEntry> {
		require(limit in 1..100)
		val entries = database.importedStepsDao().recentEntries(limit)
		if (entries.isEmpty()) return emptyList()
		return entries.chunked(ImportedStepsRetainedReader.MAX_ENTRY_BATCH).flatMap { batch ->
			when (val result = retained.readEntriesInTransaction(batch.map { it.identity })) {
				is ImportedStepsRetainedRead.Ready -> result.entries.map { it.toListEntry() }
				is ImportedStepsRetainedRead.Unverifiable -> emptyList()
			}
		}
	}

	/**
	 * Typed recent read for coordinated history.
	 *
	 * One failed candidate makes the whole bounded origin unavailable so a corrupt newest entry
	 * cannot disappear and reveal an older row as if the page were complete.
	 */
	suspend fun recentSourceAwareInTransaction(limit: Int): ImportedStepsRecentRead {
		require(limit in 1..100)
		val entries = database.importedStepsDao().recentEntries(limit)
		if (entries.isEmpty()) return ImportedStepsRecentRead.Ready(emptyList())
		val publicEntries = mutableListOf<ImportedStepsHistoryEntry>()
		for (batch in entries.chunked(ImportedStepsRetainedReader.MAX_ENTRY_BATCH)) {
			val batchIds = batch.map { it.identity }
			when (val result = retained.readEntriesInTransaction(batch.map { it.identity })) {
				is ImportedStepsRetainedRead.Unverifiable ->
					return ImportedStepsRecentRead.Unavailable(result.reason)
				is ImportedStepsRetainedRead.Ready -> {
					val failed = batchIds.firstNotNullOfOrNull(result.unverifiableEntries::get)
					if (failed != null) return ImportedStepsRecentRead.Unavailable(failed)
					if (result.unverifiableEntries.keys.any { it !in batchIds }) {
						return ImportedStepsRecentRead.Unavailable(
							ImportedStepsReadFailure.INTEGRITY,
						)
					}
					val returnedIds = result.entries.map { it.metadata.identity }
					if (
						returnedIds.size != batchIds.size ||
						returnedIds.toSet() != batchIds.toSet()
					) {
						return ImportedStepsRecentRead.Unavailable(
							ImportedStepsReadFailure.MISSING,
						)
					}
					publicEntries += result.entries.map { it.toListEntry() }
				}
			}
		}
		return ImportedStepsRecentRead.Ready(publicEntries)
	}

	suspend fun exportInTransaction(request: ExportPortableStepsRequest): PortableStepsSnapshot {
		val entries = database.importedStepsDao().entriesOverlapping(
			request.fromInclusiveMs, request.toExclusiveMs, StepsPortableFormatV1.MAX_ENTRIES + 1,
		)
		if (entries.size > StepsPortableFormatV1.MAX_ENTRIES) {
			return unavailable(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}

		suspend fun exportV2InTransaction(request: ExportPortableStepsRequest): PortableStepsV2Snapshot {
			val entries = database.importedStepsDao().entriesOverlapping(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				StepsPortableFormatV1.MAX_ENTRIES + 1,
			)
			if (entries.size > StepsPortableFormatV1.MAX_ENTRIES) {
				return v2Unavailable(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			if (entries.isEmpty()) {
				return PortableStepsV2Snapshot.Outcome(ExportPortableStepsResult.NoEntries)
			}
			val retainedEntries = mutableListOf<RetainedImportedStepsEntry>()
			for (batch in entries.chunked(ImportedStepsRetainedReader.MAX_ENTRY_BATCH)) {
				when (val result = retained.readEntriesInTransaction(batch.map { it.identity })) {
					is ImportedStepsRetainedRead.Unverifiable ->
						return v2Unavailable(result.reason.toExportReason())
					is ImportedStepsRetainedRead.Ready -> {
						val failure = result.unverifiableEntries.values.firstOrNull()
						if (failure != null) return v2Unavailable(failure.toExportReason())
						retainedEntries += result.entries
					}
				}
			}
			if (retainedEntries.size != entries.size) {
				return v2Unavailable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
			val graphDao = database.importedPortableStepsCountDomainDao()
			val result = mutableListOf<PortableStepsEntryV2>()
			for (entry in retainedEntries) {
				val product = entry.portable
					?: return v2Unavailable(
						PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					)
				val binding = graphDao.binding(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
					entry.metadata.identity,
					IMPORTED_SESSION_PRODUCT_REVISION,
				) ?: return v2Unavailable(
					PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
				)
				val graph = graphDao.authenticatedGraph(
					binding.graphIdentity,
					com.adsamcik.tracker.shared.base.database.data
						.ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
				)
					?: return v2Unavailable(
						PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					)
				if (binding.sourceSchemaVersion !in 1..2) {
					return v2Unavailable(
						PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					)
				}
				result += try {
					PortableStepsEntryV2(product, graph)
				} catch (_: IllegalArgumentException) {
					return v2Unavailable(
						PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					)
				}
			}
			return PortableStepsV2Snapshot.Ready(
				result.sortedWith(com.adsamcik.tracker.stats.api.repository.PORTABLE_STEPS_ENTRY_V2_ORDER),
			)
		}
		val portable = mutableListOf<PortableStepsEntryV1>()
		for (batch in entries.chunked(ImportedStepsRetainedReader.MAX_ENTRY_BATCH)) {
			when (val result = retained.readEntriesInTransaction(batch.map { it.identity })) {
				is ImportedStepsRetainedRead.Unverifiable -> return unavailable(result.reason.toExportReason())
				is ImportedStepsRetainedRead.Ready -> {
					val failure = result.unverifiableEntries.values.firstOrNull()?.toExportReason()
						?: appendExportEntries(result.entries, portable, entries.size)
					if (failure != null) {
						return unavailable(failure)
					}
				}
			}
		}
		return if (portable.isEmpty()) {
			PortableStepsSnapshot.Outcome(ExportPortableStepsResult.NoEntries)
		} else {
			PortableStepsSnapshot.Ready(portable.sortedWith(PORTABLE_STEPS_ENTRY_ORDER))
		}
	}

	/** Stops before requesting another Room batch once this origin's export budget is exhausted. */
	private fun appendExportEntries(
		entries: List<RetainedImportedStepsEntry>,
		portable: MutableList<PortableStepsEntryV1>,
		totalEntryCount: Int,
	): PortableStepsExportUnverifiableReason? {
		var dependencies = totalEntryCount + portable.sumOf { it.runDependencyCount() }
		for (entry in entries) {
			val wire = entry.portable ?: return PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE
			dependencies += wire.runDependencyCount()
			if (dependencies > MAX_EXPORT_DEPENDENCIES) {
				return PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW
			}
			portable += wire
		}
		return null
	}

	private fun PortableStepsEntryV1.runDependencyCount(): Int = runs.sumOf { 2 + it.manifests.size + it.facts.size }

	private fun RetainedImportedStepsEntry.sessionHistories(): List<SessionHistory> = runs.map { run ->
		val wire = portableRunsById.getValue(run.identity)
		val steps = wire.toImportedStepsHistory(run.identity in retentionTruncatedRunIds)
		SessionHistory(
			segmentId = segmentsByRun.getValue(run.identity).id,
			capture = HistoryCapture.ImportedSteps(wire.manifests.map {
				ImportedStepsCaptureRevision(it.revision, EpochMs(it.effectiveWallTimeMs))
			}),
			qualifiedSources = if (steps.count != null) { setOf(HistorySource.STEPS) } else { emptySet() },
			steps = steps,
		)
	}

	private fun RetainedImportedStepsEntry.toListEntry(): ImportedStepsHistoryEntry {
		val histories = sessionHistories().associateBy { it.segmentId }
		val members = runs.map { run ->
			val segment = segmentsByRun.getValue(run.identity)
			ImportedStepsHistoryMember(
				segment.id, EpochMs(run.startTimeMs), EpochMs(run.endTimeMs), histories.getValue(segment.id).steps,
			)
		}.sortedWith(compareBy<ImportedStepsHistoryMember> { it.startTime }.thenBy { it.segmentId })
		return ImportedStepsHistoryEntry(
			TrackingHistoryEntryKey("imported-steps:${metadata.identity}"),
			members.minOf { it.startTime }, members.maxOf { it.endTime }, members,
		)
	}

	private fun unverifiableSession(segmentId: Long, failure: ImportedStepsReadFailure?) = SessionHistory(
		segmentId, HistoryCapture.Unverifiable, emptySet(), StepsHistory(
			count = null, availability = HistoryAvailability.RETAINED_IMPORTED, evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.FAILED, coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(when (failure) {
				ImportedStepsReadFailure.RETENTION -> StepsHistoryCause.RETENTION_LIMIT
				ImportedStepsReadFailure.DELETION -> StepsHistoryCause.DELETED
				ImportedStepsReadFailure.MISSING -> StepsHistoryCause.HISTORY_MEMBERSHIP_UNAVAILABLE
				else -> StepsHistoryCause.HISTORY_INTEGRITY_FAILED
			}),
		),
	)

	private fun ImportedStepsReadFailure.toExportReason(): PortableStepsExportUnverifiableReason = when (this) {
		ImportedStepsReadFailure.RETENTION -> PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY
		ImportedStepsReadFailure.DEPENDENCY_OVERFLOW -> PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW
		else -> PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE
	}

	private fun unavailable(reason: PortableStepsExportUnverifiableReason) =
		PortableStepsSnapshot.Outcome(ExportPortableStepsResult.Unverifiable(reason))

	private fun v2Unavailable(reason: PortableStepsExportUnverifiableReason) =
		PortableStepsV2Snapshot.Outcome(ExportPortableStepsResult.Unverifiable(reason))

	private companion object {
		const val MAX_EXPORT_DEPENDENCIES = 16_384
		const val IMPORTED_SESSION_PRODUCT_REVISION = 1L
	}
}
