package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationReadScope
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryLocationPoint
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Existing saved Location storage and retained qualifier, selected by actual point keys. */
internal class MeasuredLocationHistoryReader @Inject constructor(
    private val database: AppDatabase,
    private val qualified: RetainedLocationContextReader,
) {
    suspend fun candidates(range: HistoryRange, after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        if (HistorySource.LOCATION !in range.sources) return emptyList()
        val origins = range.origins.intersect(setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED, HistoryOrigin.LEGACY))
        if (origins.isEmpty()) return emptyList()
        val capture = if (HistoryPurpose.CAPTURE in range.purposes) 1 else 0
        val background = if (HistoryPurpose.BACKGROUND in range.purposes) 1 else 0
        val unknown = if (HistoryPurpose.UNKNOWN in range.purposes) 1 else 0
        val purpose = """CASE WHEN $capture AND EXISTS (SELECT 1 FROM location_sample_purpose_authority p
            WHERE p.sample_id = location_sample.id AND p.purpose = 'SESSION_CAPTURE') THEN 'CAPTURE'
            WHEN $background AND EXISTS (SELECT 1 FROM location_sample_purpose_authority p
            WHERE p.sample_id = location_sample.id AND p.purpose = 'AMBIENT_PRODUCT') THEN 'BACKGROUND'
            ELSE 'UNKNOWN' END"""
        val names = origins.joinToString(",") { "'${it.name}'" }
        val seek = if (after == null) "" else """AND (startTimeMs < ? OR (startTimeMs = ? AND
            (sortTag > ? OR (sortTag = ? AND identity > ?))))"""
        val bindings = mutableListOf<Any?>(range.fromInclusiveMs, range.toExclusiveMs)
        if (after != null) bindings.addAll(listOf(after.startTimeMs, after.startTimeMs,
            after.originTag, after.originTag, after.stableId))
        val query = SimpleSQLiteQuery("""SELECT kind, origin, purpose, identity, startTimeMs, endTimeMs,
            rowId, writerId, writerVersion, factId, factRevision, itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity FROM (
            SELECT 'location' AS kind, ${LocationReadScope.HISTORY_ORIGIN} AS origin,
            $purpose AS purpose, 'location:' || printf('%020d', location_sample.id) AS identity,
            location_sample.time_ms AS startTimeMs, NULL AS endTimeMs, location_sample.id AS rowId,
            NULL AS writerId, NULL AS writerVersion, NULL AS factId, NULL AS factRevision,
            NULL AS itemIndex, lower(${LocationReadScope.HISTORY_ORIGIN}) || ':location' AS sortTag
            FROM location_sample WHERE time_ms >= ? AND time_ms < ?
            AND lat_e7 BETWEEN -900000000 AND 900000000 AND lon_e7 BETWEEN -1800000000 AND 1800000000
            AND ${LocationReadScope.HISTORY_RETAINED})
            WHERE origin IN ($names) AND (purpose != 'UNKNOWN' OR $unknown) $seek
            ORDER BY startTimeMs DESC, sortTag, identity LIMIT 101""", bindings.toTypedArray())
        return database.trackingHistoryReadDao().measuredHistoryCandidates(query)
    }

    suspend fun qualify(candidates: List<MeasuredHistoryCandidate>, range: HistoryRange): MeasuredLocationHistoryRead {
        require(candidates.size <= 100 && candidates.all { it.kind == "location" })
        if (candidates.isEmpty()) return MeasuredLocationHistoryRead(emptyMap(), emptySet())
        val ids = candidates.map { requireNotNull(it.rowId) }
        require(ids.distinct().size == ids.size)
        val points = ids.chunked(64).flatMap { database.locationPurposeDao().savedFileSamples(it) }
            .associateBy { it.sample.id }
        val receipts = ids.chunked(64).flatMap { chunk ->
            database.locationPurposeDao().contextReceiptsForSamples(chunk, chunk.size + 1).also {
                require(it.size <= chunk.size) }
        }
        var readProblem: HistoryProblemReason? = null
        val proof = try { qualified.qualifySavedInTransaction(receipts) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: android.database.sqlite.SQLiteException) {
            readProblem = HistoryProblemReason.STORAGE_UNAVAILABLE
            RetainedLocationSavedSnapshot(emptyList())
        } catch (_: RuntimeException) {
            readProblem = HistoryProblemReason.INTEGRITY_UNAVAILABLE
            RetainedLocationSavedSnapshot(emptyList())
        }
        val byId = proof.samples.groupBy { it.sample.id }
        val imported = ids.chunked(64).flatMap { database.locationPurposeDao().importedHistorySampleIds(it) }.toSet()
        val names = MeasuredNativeSessionNames.load(database, proof.samples.mapNotNull { row ->
            (row.scope as? RadioContextScope.Session)?.owner?.logicalTrackingId
        })
        val problems = linkedSetOf<HistoryProblem>()
        (readProblem ?: proof.historyProblem)?.let { problems += HistoryProblem(HistorySource.LOCATION, it) }
        if (RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in proof.gaps) problems +=
            HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.DEPENDENCY_OVERFLOW)
        val rows = candidates.mapNotNull { candidate ->
            val path = points[candidate.rowId] ?: return@mapNotNull null
            val sample = path.sample
            if (sample.timeMs != candidate.startTimeMs || sample.timeMs !in range.fromInclusiveMs until range.toExclusiveMs) {
                problems += HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                return@mapNotNull null
            }
            if (sample.id in proof.erasedNativeSampleIds && sample.id !in imported) return@mapNotNull null
            val available = byId[sample.id].orEmpty()
            val purpose = when {
                HistoryPurpose.CAPTURE in range.purposes && available.any { it.scope is RadioContextScope.Session } ->
                    HistoryPurpose.CAPTURE
                HistoryPurpose.BACKGROUND in range.purposes && available.any { it.scope is RadioContextScope.Ambient } ->
                    HistoryPurpose.BACKGROUND
                HistoryPurpose.UNKNOWN in range.purposes && available.isEmpty() &&
                    (sample.id in imported || receipts.none { it.sampleId == sample.id } ||
                        proof.erasedNativeScopeSampleIds?.let { sample.id !in it } == true) -> HistoryPurpose.UNKNOWN
                else -> return@mapNotNull null
            }
            val grants = available.filter {
                when (purpose) {
                    HistoryPurpose.CAPTURE -> it.scope is RadioContextScope.Session
                    HistoryPurpose.BACKGROUND -> it.scope is RadioContextScope.Ambient
                    else -> false
                }
            }
            val name = grants.mapNotNull { (it.scope as? RadioContextScope.Session)?.owner?.logicalTrackingId }
                .mapNotNull { names.point(it, sample.timeMs) }.distinct().joinToString(", ").takeIf(String::isNotBlank)
            val point = HistoryLocationPoint(sample.timeMs, requireNotNull(sample.latE7), requireNotNull(sample.lonE7),
                sample.hAccM?.takeIf { it.isFinite() && it >= 0f }, path.continuityId, sample.id)
            val position = measuredHistoryPosition(candidate)
            position to UnifiedHistoryRow(position, null, setOf(HistoryOrigin.valueOf(candidate.origin)), purpose,
                name = name, sections = emptyList(), measurement = HistoryMeasurement.Location(point,
                    if (grants.isNotEmpty()) HistoryLocationCoverage.QUALIFIED else HistoryLocationCoverage.LEGACY_UNVERIFIABLE))
        }.toMap()
        return MeasuredLocationHistoryRead(rows, problems)
    }
}

internal data class MeasuredLocationHistoryRead(val rows: Map<HistoryPosition, UnifiedHistoryRow>,
    val problems: Set<HistoryProblem>)
