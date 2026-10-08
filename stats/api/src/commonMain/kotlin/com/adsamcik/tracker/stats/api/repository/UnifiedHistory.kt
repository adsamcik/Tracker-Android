package com.adsamcik.tracker.stats.api.repository

/** Half-open wall-time selection. The zone controls presentation, never source authority. */
data class HistoryRange(
    val fromInclusiveMs: Long,
    val toExclusiveMs: Long,
    val viewZoneId: String,
    val sources: Set<HistorySource> = HistorySource.entries.toSet(),
    val origins: Set<HistoryOrigin> = HistoryOrigin.entries.toSet(),
    val purposes: Set<HistoryPurpose> = HistoryPurpose.entries.toSet(),
    val mode: HistoryBrowseMode = HistoryBrowseMode.RECORDINGS,
) {
    init {
        require(fromInclusiveMs >= 0 && fromInclusiveMs < toExclusiveMs)
        require(viewZoneId.isNotBlank() && viewZoneId.length <= 128)
    }
}

/** Existing exact organization readers retain record mode; History screens select measured data. */
enum class HistoryBrowseMode { RECORDINGS, OBSERVATIONS }

enum class HistoryOrigin { LOCAL, IMPORTED, LEGACY, ORGANIZATION }
enum class HistoryPurpose { CAPTURE, BACKGROUND, ORGANIZATION, UNKNOWN }

/** Strict global order. This is a browse position, not export or erase authority. */
data class HistoryPosition(val startTimeMs: Long, val originTag: String, val stableId: String) : Comparable<HistoryPosition> {
    init { require(startTimeMs >= 0); require(originTag.isNotBlank()); require(stableId.isNotBlank()) }
    override fun compareTo(other: HistoryPosition): Int =
        other.startTimeMs.compareTo(startTimeMs).takeIf { it != 0 }
            ?: originTag.compareTo(other.originTag).takeIf { it != 0 }
            ?: stableId.compareTo(other.stableId)
}

data class HistoryContinuation(val range: HistoryRange, val consumed: HistoryPosition)
data class HistoryRangeRequest(val range: HistoryRange, val limit: Int = 100, val after: HistoryContinuation? = null) {
    init { require(limit in 1..100); require(after == null || after.range == range) }
}

data class UnifiedHistoryRow(
    val position: HistoryPosition,
    val toExclusiveMs: Long?,
    val origins: Set<HistoryOrigin>,
    val purpose: HistoryPurpose,
    val name: String? = null,
    val sections: List<HistorySection>,
    val problems: Set<HistoryProblem> = emptySet(),
    val measurement: HistoryMeasurement? = null,
) {
    init {
        require(toExclusiveMs == null || toExclusiveMs >= position.startTimeMs ||
            measurement is HistoryMeasurement.Activity &&
                measurement.band.wallTimeContinuity == ActivityHistoryWallTimeContinuity.DISCONTINUITY_DETECTED &&
                position.startTimeMs == measurement.band.startTime.raw && toExclusiveMs == measurement.band.endTime.raw)
    }
    val sources: Set<HistorySource> get() = sections.mapNotNull { it.source }.toSet() +
        listOfNotNull(measurement?.source) + problems.mapNotNull { it.source }
}

/** All payloads have passed their source-owned reader in the containing snapshot. */
sealed interface HistorySection {
    val source: HistorySource?
    data class Steps(val segmentId: Long, val history: StepsHistory) : HistorySection { override val source = HistorySource.STEPS }
    data class Location(val points: List<HistoryLocationPoint>, val coverage: HistoryLocationCoverage) : HistorySection { override val source = HistorySource.LOCATION }
    data class Wifi(val history: WifiHistoryEntry) : HistorySection { override val source = HistorySource.WIFI }
    data class Cell(val history: CellHistoryEntry) : HistorySection { override val source = HistorySource.CELL }
    data class Activity(val history: ActivityHistoryEntry) : HistorySection { override val source = HistorySource.ACTIVITY }
    data class Pressure(val history: PressureHistory) : HistorySection { override val source = HistorySource.PRESSURE }
    data class ImportedSteps(val history: ImportedStepsHistoryEntry) : HistorySection { override val source = HistorySource.STEPS }
    data class BackgroundSteps(val day: AmbientStepsHistoryDay) : HistorySection { override val source = HistorySource.STEPS }
    data class BackgroundWifi(val read: AmbientWifiReadResult.Snapshot,
        val displayWindow: HistoryObservationWindow? = null) : HistorySection {
        override val source = HistorySource.WIFI
        /** A display selection never rewrites the saved fact, revision, or source calendar. */
        val networks: List<WifiObservedNetwork> get() = read.facts.flatMap { it.networks }
            .filter { displayWindow?.contains(it.observedTimeMs) != false }
        /** Missing legacy detail is unknown, rather than a measured zero. */
        val readingCount: Int? get() = if (read.facts.isEmpty() || read.facts.any { it.networks.size != it.observationCount }) null else networks.size
    }
    data class BackgroundCell(val read: AmbientCellReadResult.Snapshot,
        val displayWindow: HistoryObservationWindow? = null) : HistorySection {
        override val source = HistorySource.CELL
        val towers: List<CellObservedTower> get() = read.facts.flatMap { it.towers }
            .filter { displayWindow?.contains(it.observedTimeMs) != false }
        val readingCount: Int? get() = if (read.facts.isEmpty() || read.facts.any { it.towers.size != it.observationCount }) null else towers.size
    }
    data class Organization(val session: OrganizedSession) : HistorySection { override val source: HistorySource? = null }
}

data class HistoryProblem(val source: HistorySource?, val reason: HistoryProblemReason)
enum class HistoryProblemReason { DEPENDENCY_OVERFLOW, INTEGRITY_UNAVAILABLE, STORAGE_UNAVAILABLE, TIME_UNCERTAIN }
sealed interface HistoryRangeRead {
    data class Page(
        val rows: List<UnifiedHistoryRow>,
        val snapshot: TrackingHistoryReadSnapshot,
        val next: HistoryContinuation?,
        val problems: Set<HistoryProblem> = emptySet(),
    ) : HistoryRangeRead
    data class Unavailable(val reason: HistoryProblemReason) : HistoryRangeRead
}

/** A finite sorted buffer and at most one key-only lookahead. */
data class HistoryStreamBuffer<T>(
    val candidates: List<T>,
    val position: (T) -> HistoryPosition,
    val lookahead: HistoryPosition? = null,
) {
    init {
        require(candidates.size <= 100)
        require(candidates.zipWithNext().all { (a, b) -> position(a) < position(b) })
        require(lookahead == null || candidates.isNotEmpty() && position(candidates.last()) < lookahead)
    }
}

data class HistoryMergeResult<T>(val rows: List<T>, val consumed: HistoryPosition?, val hasMore: Boolean)

/** Never consumes prefetched heads or emits across an unknown exhausted-stream frontier. */
suspend fun <C, R> mergeHistoryBuffers(
    streams: List<HistoryStreamBuffer<C>>,
    limit: Int,
    qualify: suspend (HistoryPosition, List<C>) -> R?,
): HistoryMergeResult<R> {
    require(limit in 1..100)
    val offsets = IntArray(streams.size)
    val output = ArrayList<R>(limit)
    var consumed: HistoryPosition? = null
    while (output.size < limit) {
        val heads = streams.indices.mapNotNull { i ->
            streams[i].candidates.getOrNull(offsets[i])?.let { i to streams[i].position(it) }
        }
        val head = heads.minOfOrNull { it.second } ?: break
        val unknown = streams.indices.mapNotNull { i ->
            streams[i].lookahead?.takeIf { offsets[i] == streams[i].candidates.size }
        }.minOrNull()
        if (unknown != null && unknown <= head) break
        val matching = heads.filter { it.second == head }.map { it.first }
        val candidates = matching.map { streams[it].candidates[offsets[it]] }
        qualify(head, candidates)?.let(output::add)
        matching.forEach { offsets[it]++ }
        consumed = head
    }
    val hasMore = streams.indices.any { offsets[it] < streams[it].candidates.size || streams[it].lookahead != null }
    return HistoryMergeResult(output, consumed, hasMore)
}

/** Geographic compatibility evidence remains distinct from authenticated native purpose proof. */
enum class HistoryLocationCoverage { QUALIFIED, LEGACY_UNVERIFIABLE }
data class HistoryLocationPoint(
    val timeMs: Long, val latitudeE7: Int, val longitudeE7: Int,
    val accuracyMeters: Float?, val continuityId: String?,
    /** Canonical saved point identity in the containing history snapshot, including sibling purposes. */
    val sampleId: Long? = null,
) {
    init {
        require(timeMs >= 0L)
        require(latitudeE7 in -900_000_000..900_000_000 && longitudeE7 in -1_800_000_000..1_800_000_000)
        require(accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters >= 0f)
        require(sampleId == null || sampleId > 0L)
    }
}

/** Half-open UTC selection for a displayed day; the saved source clock and bounds stay intact. */
data class HistoryObservationWindow(val fromInclusiveMs: Long, val toExclusiveMs: Long) {
    init { require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs) }
    fun contains(timeMs: Long): Boolean = timeMs in fromInclusiveMs until toExclusiveMs
}

/** A saved measured leaf or original window, independent of its optional session organization. */
sealed interface HistoryMeasurement {
    val source: HistorySource
    data class Location(val point: HistoryLocationPoint, val coverage: HistoryLocationCoverage) : HistoryMeasurement {
        override val source = HistorySource.LOCATION
    }
    data class Wifi(val network: WifiObservedNetwork, val partial: Boolean = false) : HistoryMeasurement {
        override val source = HistorySource.WIFI
    }
    data class Cell(val tower: CellObservedTower, val partial: Boolean = false) : HistoryMeasurement {
        override val source = HistorySource.CELL
    }
    /** Summary-only saved records remain summaries; a missing network list is never an empty scan. */
    data class WifiSummary(val observation: WifiHistoryObservation) : HistoryMeasurement { override val source = HistorySource.WIFI }
    data class CellSummary(val observation: CellHistoryObservation) : HistoryMeasurement { override val source = HistorySource.CELL }
    data class BackgroundWifiSummary(val fact: AmbientWifiFact) : HistoryMeasurement { override val source = HistorySource.WIFI }
    data class BackgroundCellSummary(val fact: AmbientCellFact) : HistoryMeasurement { override val source = HistorySource.CELL }
    data class Activity(val band: ActivityHistoryFragment.Band) : HistoryMeasurement { override val source = HistorySource.ACTIVITY }
    data class Pressure(val window: PressureHistoryWindow) : HistoryMeasurement { override val source = HistorySource.PRESSURE }
    /** The containing row keeps the original count window; no prorated daily count is created. */
    data class Steps(val history: StepsHistory) : HistoryMeasurement { override val source = HistorySource.STEPS }
    /** A saved, timed source gap is data. Read failures instead use untimed PageProblems. */
    data class Gap(override val source: HistorySource, val reason: String) : HistoryMeasurement {
        init { require(reason.isNotBlank()) }
    }
    /** Released rows retain original fields and unknown purpose without modern authority claims. */
    data class LegacyWifi(val bssid: String, val ssid: String, val capabilities: String,
        val frequencyMhz: Int, val signalDbm: Int) : HistoryMeasurement { override val source = HistorySource.WIFI }
    /** Legacy Activity is a timestamped observation, never an invented duration band. */
    data class LegacyActivity(val activityType: Int, val confidencePercent: Int,
        val isTransition: Boolean) : HistoryMeasurement { override val source = HistorySource.ACTIVITY }
    /** Released Pressure aggregates keep only their known timestamp and saved aggregate values. */
    data class LegacyPressure(val meanHectopascals: Float, val sampleCount: Int,
        val minimumHectopascals: Float?, val maximumHectopascals: Float?,
        val standardDeviationHectopascals: Float?, val altitudeMeters: Float?,
        val windowStartElapsedRealtimeNanos: Long?, val windowEndElapsedRealtimeNanos: Long?) : HistoryMeasurement {
        override val source = HistorySource.PRESSURE
    }
    /** Original scan count and device position; these are not access-point coordinates. */
    data class LegacyWifiCount(val count: Int, val latitude: Double, val longitude: Double,
        val altitudeMeters: Double?) : HistoryMeasurement { override val source = HistorySource.WIFI }
    data class LegacyCell(val cellId: Long, val lac: Int, val mcc: Int, val mnc: Int,
        val networkType: Int, val signalStrength: Int) : HistoryMeasurement { override val source = HistorySource.CELL }
}

/** Paging metadata is deliberately untimed, separate from saved measurement rows. */
sealed interface HistoryPagingItem {
    val snapshot: TrackingHistoryReadSnapshot
    data class Row(val row: UnifiedHistoryRow, override val snapshot: TrackingHistoryReadSnapshot) : HistoryPagingItem
    data class PageProblems(val problems: Set<HistoryProblem>, override val snapshot: TrackingHistoryReadSnapshot,
        val after: HistoryContinuation?) : HistoryPagingItem { init { require(problems.isNotEmpty()) } }
}
