package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import androidx.room.InvalidationTracker
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.HistoryObservationWindow
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummary
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Current measured values, shared by statistics and achievements instead of stale distance caches. */
class SavedLocationMetricsReader @Inject constructor(
    private val database: AppDatabase,
    private val dispatchers: DispatchersProvider,
) {
    private val summaries by lazy { SavedLocationSummaryReader(database, dispatchers.io) }

    suspend fun read(fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE): SavedLocationMetrics =
        withContext(dispatchers.io) {
            require(fromMs >= 0L && toMs >= fromMs)
            val window = HistoryObservationWindow(fromMs, if (toMs == Long.MAX_VALUE) toMs else toMs + 1L)
            val zone = ZoneId.systemDefault()
            database.withTransaction {
                val state = checkNotNull(database.sourceEvidenceStateDao().get())
                val daily = SavedDailyDistanceAccumulator(zone)
                var maxSpeed: Double? = null
                val total = summaries.readWindowInTransaction(window,
                    TrackingHistoryReadSnapshot(state.collectedDataEpoch, state.revision)) { row ->
                    daily.add(row)
                    row?.sample?.speedMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble()?.let {
                        maxSpeed = maxOf(maxSpeed ?: it, it)
                    }
                }.measuredSummary()
                var onFoot = 0.0
                var cycling = 0.0
                var vehicle = 0.0
                var maxSession: Double? = null
                var maxCycle: Double? = null
                var maxMovingAverage: Double? = null
                var hasActivityDistances = false
                var partial = total.partial
                var afterId: Long? = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = database.sessionSegmentDao().locationMetricCandidatePage(fromMs, toMs, afterId, 64)
                    for (segment in page) {
                        val summary = try { summaries.readSessionWindow(segment.id, window).measuredSummary() }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { partial = true; continue }
                        val distance = summary.distanceMeters ?: continue
                        partial = partial || summary.partial
                        maxSession = maxOf(maxSession ?: distance, distance)
                        summary.movingAverageSpeedMetersPerSecond?.let {
                            maxMovingAverage = maxOf(maxMovingAverage ?: it, it)
                        }
                        when (segment.primaryActivity) {
                            in SessionActivityIds.ON_FOOT -> { onFoot += distance; hasActivityDistances = true }
                            in SessionActivityIds.CYCLING -> {
                                cycling += distance
                                maxCycle = maxOf(maxCycle ?: distance, distance)
                                hasActivityDistances = true
                            }
                            in SessionActivityIds.IN_VEHICLE -> { vehicle += distance; hasActivityDistances = true }
                        }
                    }
                    if (page.size < 64) break
                    afterId = page.last().id
                }
                SavedLocationMetrics(total.distanceMeters, total.pointCount, onFoot, cycling, vehicle,
                    maxSession, maxCycle, maxMovingAverage, daily.bestMeters, maxSpeed, hasActivityDistances, partial)
            }
        }

    suspend fun readTrip(segmentId: Long): SavedLocationSummary = summaries.readSession(segmentId).measuredSummary()

    /** A visible calendar range uses one Room snapshot and the existing bounded point stream. */
    suspend fun readDays(fromDay: Long, toDay: Long, zone: ZoneId = ZoneId.systemDefault()): Map<Long, SavedLocationDayMetrics> =
        withContext(dispatchers.io) {
            require(fromDay <= toDay)
            val from = java.time.LocalDate.ofEpochDay(fromDay).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtLeast(0L)
            val to = java.time.LocalDate.ofEpochDay(toDay).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (to <= from) return@withContext emptyMap()
            database.withTransaction {
                val state = checkNotNull(database.sourceEvidenceStateDao().get())
                val days = linkedMapOf<Long, SavedLocationDayAccumulator>()
                var lastDay: Long? = null
                fun dayAt(timeMs: Long): SavedLocationDayAccumulator {
                    val day = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toEpochDay()
                    lastDay = day
                    return days.getOrPut(day) { SavedLocationDayAccumulator() }
                }
                val read = summaries.readWindowInTransaction(HistoryObservationWindow(from, to),
                    TrackingHistoryReadSnapshot(state.collectedDataEpoch, state.revision),
                    onGap = { time -> dayAt(time).gap() },
                    onPoint = { point -> if (point != null) dayAt(point.sample.timeMs).add(point) })
                read.measuredSummary()
                // A failed continuation can leave more of the last day unread. Completed earlier
                // days keep their own measured state; dated qualification gaps mark their own day.
                if ((read as? SavedLocationSummaryRead.Snapshot)?.problem != null) {
                    lastDay?.let { days.getValue(it).gap() }
                }
                days.mapValues { it.value.snapshot() }
            }
        }

    fun observeChanges(): Flow<Unit> = database.invalidationTracker.createFlow(*SAVED_LOCATION_TABLES).map { }

    internal fun onSavedDataChange(onChange: () -> Unit): () -> Unit {
        val observer = object : InvalidationTracker.Observer(SAVED_LOCATION_TABLES) {
            override fun onInvalidated(tables: Set<String>) = onChange()
        }
        database.invalidationTracker.addObserver(observer)
        return { database.invalidationTracker.removeObserver(observer) }
    }

    private fun SavedLocationSummaryRead.measuredSummary(): SavedLocationSummary = when (this) {
        is SavedLocationSummaryRead.Snapshot -> {
            summary
        }
        SavedLocationSummaryRead.NotFound -> SavedLocationSummary(0L, null, null, false)
        is SavedLocationSummaryRead.Unavailable -> error("Saved Location is temporarily unavailable")
    }

    private companion object {
        val SAVED_LOCATION_TABLES = arrayOf("location_sample", "location_point_correction", "session_segment",
            "source_evidence_state", "source_deletion_fence", "location_file_archive", "location_file_archive_point",
            "location_sample_purpose_authority", "collected_data_deletion_operation")
    }
}

data class SavedLocationMetrics(
    val distanceMeters: Double?,
    val pointCount: Long,
    val onFootDistanceMeters: Double,
    val cyclingDistanceMeters: Double,
    val vehicleDistanceMeters: Double,
    val maxSessionDistanceMeters: Double?,
    val maxCyclingSessionDistanceMeters: Double?,
    val maxMovingAverageSpeedMetersPerSecond: Double?,
    val bestDayDistanceMeters: Double?,
    val maxObservedSpeedMetersPerSecond: Double? = null,
    val hasActivityDistances: Boolean = true,
    val partial: Boolean = false,
)

data class SavedLocationDayMetrics(val pointCount: Long, val distanceMeters: Double?, val partial: Boolean)

private class SavedLocationDayAccumulator {
    private val distance = SavedTripDistanceAccumulator()
    private var count = 0L
    private var partial = false
    fun gap() { partial = true; distance.breakContinuity() }
    fun add(row: com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow) {
        distance.add(row)
        val sample = row.sample
        if (sample.latE7?.let { it in -900_000_000..900_000_000 } == true &&
            sample.lonE7?.let { it in -1_800_000_000..1_800_000_000 } == true) {
            count = Math.addExact(count, 1L)
        } else partial = true
    }
    fun snapshot() = SavedLocationDayMetrics(count, distance.measuredMeters, partial || distance.hasDisconnectedSections)
}

/** Chronological measured points make local-day measurement constant-memory, including DST. */
internal class SavedDailyDistanceAccumulator(private val zone: ZoneId) {
    private var day: Long? = null
    private var accumulator = SavedTripDistanceAccumulator()
    private var completedMaximum: Double? = null
    val bestMeters: Double? get() = listOfNotNull(completedMaximum, accumulator.measuredMeters).maxOrNull()

    fun add(row: com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow?) {
        if (row == null) {
            accumulator.breakContinuity()
            return
        }
        val measuredDay = Instant.ofEpochMilli(row.sample.timeMs).atZone(zone).toLocalDate().toEpochDay()
        if (day != measuredDay) {
            completedMaximum = bestMeters
            accumulator = SavedTripDistanceAccumulator()
            day = measuredDay
        }
        accumulator.add(row)
    }
}
