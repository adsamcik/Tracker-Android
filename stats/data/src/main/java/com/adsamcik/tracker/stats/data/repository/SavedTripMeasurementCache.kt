package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummary
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A visible list/page owns a bounded cache. New immutable points only invalidate their own trips. */
internal class SavedTripMeasurementCache(
    private val database: AppDatabase,
    private val io: CoroutineDispatcher,
    private val summaries: SavedLocationSummaryReader,
) {
    private val mutex = Mutex()
    private val entries = linkedMapOf<Long, Entry>()
    private var throughId = 0L
    private var sampleCount = 0L
    private var purposeCount = 0L
    private var authorityStamp: String? = null

    suspend fun changed(tables: Set<String>): Boolean = mutex.withLock {
        database.withSavedLocationReadSnapshot(io) {
            refresh(tables)
        }
    }

    suspend fun read(segmentId: Long): SavedLocationSummary = mutex.withLock {
        database.withSavedLocationReadSnapshot(io) {
            if (authorityStamp == null) {
                refresh(emptySet())
            }
            val segment = requireNotNull(database.sessionSegmentDao().getById(segmentId))
            val existing = entries[segmentId]
            if (existing?.segment == segment) {
                return@withSavedLocationReadSnapshot existing.summary
            }
            val evidence = checkNotNull(database.sourceEvidenceStateDao().get())
            val read = summaries.readSessionInTransaction(segmentId,
                TrackingHistoryReadSnapshot(evidence.collectedDataEpoch, evidence.revision))
            remember(segment, read)
        }
    }

    private suspend fun remember(segment: SessionSegment, read: SavedLocationSummaryRead): SavedLocationSummary {
        val summary = when (read) {
            is SavedLocationSummaryRead.Snapshot -> read.summary
            SavedLocationSummaryRead.NotFound -> SavedLocationSummary(0L, null, null, false)
            is SavedLocationSummaryRead.Unavailable -> error("Saved Location is temporarily unavailable")
        }
        // Failed qualification remains retryable rather than becoming an unchanged cached value.
        if (read is SavedLocationSummaryRead.Snapshot && read.problem == null) {
            if (entries.size >= MAX_ENTRIES) {
                entries.remove(entries.keys.first())
            }
            entries[segment.id] = Entry(segment, summary, nativeAuthority(segment))
        }
        return summary
    }

    private suspend fun nativeAuthority(segment: SessionSegment): SavedLocationPrefixAuthority? {
        val logicalId = segment.logicalTrackingId ?: return null
        val runId = segment.serviceRunId ?: return null
        return SavedLocationPrefixAuthority.read(database, logicalId, runId,
            database.locationPurposeDao().savedSessionAuthorityKeys(logicalId, runId, segment.id,
                0L, throughId).toSet())
    }

    suspend fun keepOnly(segmentIds: Set<Long>) = mutex.withLock { entries.keys.retainAll(segmentIds) }

    private suspend fun refresh(tables: Set<String>): Boolean {
        val purpose = database.locationPurposeDao()
        val stamp = purpose.savedFileExportAuthorityStamp() + ":" +
            database.sourceEvidenceStateDao().get()?.restoredDataEpochs
        val before = purpose.savedAppendState(throughId)
        val appendOnly = before.throughId > throughId && before.samplePrefixCount == sampleCount &&
            before.purposePrefixCount == purposeCount
        val changedOriginals = tables.any { it in POINT_TABLES } && !appendOnly
        val rebuild = authorityStamp != stamp || tables.any { it in REBUILD_TABLES } || changedOriginals
        var affected = entries.isEmpty()
        if (rebuild) {
            affected = true
            entries.clear()
        } else {
            val authorityChanged = tables.any { it in AUTHORITY_TABLES }
            val changed = entries.filterValues { entryChanged(it, authorityChanged, appendOnly) }.keys
            affected = changed.isNotEmpty()
            changed.forEach { entries.remove(it) }
        }
        throughId = before.throughId
        val now = purpose.savedAppendState(throughId)
        sampleCount = now.samplePrefixCount
        purposeCount = now.purposePrefixCount
        authorityStamp = stamp
        return affected
    }

    private suspend fun entryChanged(entry: Entry, authorityChanged: Boolean, appended: Boolean): Boolean {
        val segment = entry.segment
        val logicalId = segment.logicalTrackingId
        val runId = segment.serviceRunId
        val authority = entry.authority
        if (authorityChanged && authority != null &&
            authority.refreshed(database, requireNotNull(logicalId), requireNotNull(runId)) != authority) {
            return true
        }
        if (!appended) {
            return false
        }
        val purpose = database.locationPurposeDao()
        return if (logicalId != null && runId != null) {
            purpose.hasSessionPointsAfter(logicalId, runId, segment.id, throughId)
        } else {
            purpose.hasWindowPointsAfter(segment.startTimeMs, segment.endTimeMs, throughId)
        }
    }

    private data class Entry(val segment: SessionSegment,
        val summary: SavedLocationSummary, val authority: SavedLocationPrefixAuthority?)
    private companion object {
        const val MAX_ENTRIES = 500
        val POINT_TABLES = setOf("location_sample", "location_accepted_receipt", "location_sample_purpose_authority")
        val REBUILD_TABLES = setOf("location_point_correction", "source_deletion_fence", "location_file_archive",
            "location_file_archive_point", "collected_data_deletion_operation",
            "ambient_location_retention_authority")
        val AUTHORITY_TABLES = setOf("source_policy", "source_consent_epoch", "source_service_run",
            "session_manifest_version", "session_manifest_source")
    }
}
