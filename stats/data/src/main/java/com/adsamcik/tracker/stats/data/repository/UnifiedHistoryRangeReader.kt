package com.adsamcik.tracker.stats.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.TRACKING_HISTORY_INVALIDATION_TABLES
import com.adsamcik.tracker.shared.base.database.dao.LocationHistoryDayRow
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.UnifiedHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadResult
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryDay
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryFactOriginKind
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryValue
import com.adsamcik.tracker.stats.api.repository.AmbientStepsImportedDisposition
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryLocationPoint
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryObservationWindow
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistorySection
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.HistoryStreamBuffer
import com.adsamcik.tracker.stats.api.repository.LocationFileArchiveRead
import com.adsamcik.tracker.stats.api.repository.LocationFileArchiveRequest
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import com.adsamcik.tracker.stats.api.repository.mergeHistoryBuffers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only, finite key discovery and source-owned qualification in one Room transaction. */
internal class UnifiedHistoryRangeReader @Inject constructor(
    private val database: AppDatabase,
    private val native: UnifiedHistoryNativeReader,
    private val wifi: DefaultWifiHistoryRepository,
    private val cell: DefaultCellHistoryRepository,
    private val activity: DefaultActivityHistoryRepository,
    private val pressure: PressureHistoryPageReader,
    private val backgroundSteps: DefaultAmbientStepsHistoryRepository,
    private val backgroundWifi: RoomAmbientWifiRepository,
    private val backgroundCell: RoomAmbientCellRepository,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val qualifiedLocation: RetainedLocationContextReader,
    private val stepsSelector: StepsSegmentHistorySelector,
    private val pressureSelector: PressureHistorySelector,
    private val allDaySources: RoomAllDaySourceStatusQuery = RoomAllDaySourceStatusQuery(
        database, backgroundSteps, backgroundWifi, backgroundCell, io),
) {
    private val importedSteps = ImportedStepsProductReader(database)

    suspend fun read(request: HistoryRangeRequest): HistoryRangeRead = withContext(io) {
        if (!database.isOpen) return@withContext HistoryRangeRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
        try {
            database.withTransaction { readInTransaction(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: android.database.sqlite.SQLiteException) {
            HistoryRangeRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
        } catch (_: RuntimeException) {
            HistoryRangeRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
    }

    private suspend fun readInTransaction(request: HistoryRangeRequest): HistoryRangeRead {
        if (database.activeRetentionFloorSettlement() != null) {
            return HistoryRangeRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
        val state = database.sourceEvidenceStateDao().get()
            ?: return HistoryRangeRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        if (!state.hasValidRangeShape()) return HistoryRangeRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        try { ZoneId.of(request.range.viewZoneId) } catch (_: RuntimeException) {
            return HistoryRangeRead.Unavailable(HistoryProblemReason.TIME_UNCERTAIN)
        }
        val snapshot = TrackingHistoryReadSnapshot(state.collectedDataEpoch, state.revision)
        if (request.range.mode == HistoryBrowseMode.OBSERVATIONS) {
            return readObservationsInTransaction(request, state, snapshot)
        }
        val problems = linkedSetOf<HistoryProblem>()
        val streams = mutableListOf<HistoryStreamBuffer<RangeHistoryCandidate>>()
        val range = request.range
        val after = request.after?.consumed
        suspend fun addKeys(kind: RangeHistoryKind, source: HistorySource?, tag: String,
            read: suspend () -> List<UnifiedHistoryCandidate>) {
            try {
                currentCoroutineContext().ensureActive()
                val keys = read().map { seed -> RangeHistoryCandidate(kind, source, seed,
                    HistoryPosition(seed.startTimeMs, tag, seed.identity)) }
                streams += keys.toHistoryBuffer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RangeHistoryUnavailable) {
                problems += HistoryProblem(source, failure.reason)
            } catch (_: android.database.sqlite.SQLiteException) {
                problems += HistoryProblem(source, HistoryProblemReason.STORAGE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            }
        }
        if (HistoryPurpose.CAPTURE in range.purposes && range.sources.isNotEmpty()) {
            if (range.origins.any { it in setOf(HistoryOrigin.LOCAL, HistoryOrigin.LEGACY, HistoryOrigin.ORGANIZATION) }) {
                addKeys(RangeHistoryKind.NATIVE, null, "local") {
                    database.trackingHistoryReadDao().unifiedHistoryCandidates(
                        UnifiedHistoryCandidateQueries.native(range, after))
                }
            }
            if (HistoryOrigin.IMPORTED in range.origins) for (source in range.sources) {
                if (source == HistorySource.LOCATION) continue
                val name = source.name.lowercase(java.util.Locale.ROOT)
                addKeys(RangeHistoryKind.IMPORTED, source, "imported:$name") {
                    database.trackingHistoryReadDao().unifiedHistoryCandidates(
                        UnifiedHistoryCandidateQueries.imported(name, range, after))
                }
            }
        }
        if (HistoryPurpose.BACKGROUND in range.purposes &&
            range.origins.any { it in setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED, HistoryOrigin.LEGACY) }) {
            for (source in range.sources.intersect(BACKGROUND_SOURCES)) {
                if (source != HistorySource.LOCATION && range.origins.none {
                    it == HistoryOrigin.LOCAL || it == HistoryOrigin.IMPORTED }) continue
                addKeys(RangeHistoryKind.BACKGROUND, source, "background") {
                    backgroundKeys(source, range, after)
                }
            }
        }
        if (HistoryPurpose.ORGANIZATION in range.purposes && HistoryOrigin.ORGANIZATION in range.origins) {
            addKeys(RangeHistoryKind.ORGANIZATION, null, "organization") {
                database.trackingHistoryReadDao().unifiedHistoryCandidates(
                    UnifiedHistoryCandidateQueries.organization(range, after))
            }
        }
        var nativeRows: Map<HistoryPosition, UnifiedHistoryRow>? = null
        val importedRows = mutableMapOf<HistorySource, RangeHistorySections>()
        val backgroundRows = mutableMapOf<HistorySource, RangeHistorySections>()
        var annotationRows: Map<String, OrganizationAnnotationEntity>? = null
        val labels = NativeSessionActivity.entries.associate { it.id to context.getString(it.nameRes) }.toMutableMap()
        var localOrganizationContents: HistoryRangeRead? = null
        var loadedOrganizationContents = false
        var localOrganizationStepBounds: Map<Long, Pair<Long, Long>>? = null
        var localOrganizationStepsProblem: HistoryProblemReason? = null
        var remainingOrganizationSelectors = MAX_CANDIDATES
        var remainingOrganizationLocationPoints = MAX_DAY_FACTS
        suspend fun nativeSnapshot(): Map<HistoryPosition, UnifiedHistoryRow> {
            nativeRows?.let { return it }
            val candidates = streams.flatMap { it.candidates }.filter { it.kind == RangeHistoryKind.NATIVE }
            val rows = try {
                native.readInTransaction(candidates.map { it.seed }, range)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: android.database.sqlite.SQLiteException) {
                candidates.associate { it.position to unavailableRow(it.position, it.seed.endTimeMs,
                    if (it.seed.category == "legacy") HistoryOrigin.LEGACY else HistoryOrigin.LOCAL,
                    HistoryPurpose.CAPTURE, null, HistoryProblemReason.STORAGE_UNAVAILABLE) }
            } catch (_: RuntimeException) {
                candidates.associate { it.position to unavailableRow(it.position, it.seed.endTimeMs,
                    if (it.seed.category == "legacy") HistoryOrigin.LEGACY else HistoryOrigin.LOCAL,
                    HistoryPurpose.CAPTURE, null, HistoryProblemReason.INTEGRITY_UNAVAILABLE) }
            }
            return rows.also { nativeRows = it }
        }
        suspend fun organizationLabels(rows: Collection<OrganizationAnnotationEntity>): Map<Long, String> {
            val ids = rows.mapNotNull { it.activityLabelId }.distinct().filter { it >= 0 && it !in labels }
            require(ids.size <= MAX_CANDIDATES)
            ids.chunked(64).forEach { batch -> labels += database.activityDao().getByIds(batch).associate { it.id to it.name } }
            return labels
        }
        suspend fun organizationSnapshot(): Map<String, OrganizationAnnotationEntity> {
            annotationRows?.let { return it }
            val ids = streams.flatMap { it.candidates }.filter { it.kind == RangeHistoryKind.ORGANIZATION }
                .map { it.seed.identity.removePrefix("annotation:") }
            return database.organizationAnnotationDao().byIds(ids).associateBy { it.localId }.also { annotationRows = it }
        }
        suspend fun localOrganizationSnapshot(): HistoryRangeRead? {
            if (loadedOrganizationContents) return localOrganizationContents
            loadedOrganizationContents = true
            val intervals = organizationSnapshot().values.filter {
                it.importedSourceSelection == null && it.startTimeMs != null && it.endTimeMs != null && it.endTimeMs > it.startTimeMs }
            if (intervals.isEmpty()) return null
            val contentsRange = range.copy(fromInclusiveMs = intervals.minOf { requireNotNull(it.startTimeMs) },
                toExclusiveMs = intervals.maxOf { requireNotNull(it.endTimeMs) },
                purposes = range.purposes - HistoryPurpose.ORGANIZATION,
                origins = range.origins - HistoryOrigin.ORGANIZATION)
            val read = try {
                readInTransaction(HistoryRangeRequest(contentsRange))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: android.database.sqlite.SQLiteException) {
                HistoryRangeRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                HistoryRangeRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            }
            return read.also { localOrganizationContents = it }
        }
        suspend fun organizationStepBounds(contents: HistoryRangeRead?): Map<Long, Pair<Long, Long>> {
            localOrganizationStepBounds?.let { return it }
            val ids = (contents as? HistoryRangeRead.Page)?.rows.orEmpty().flatMap { it.sections }
                .filterIsInstance<HistorySection.Steps>().map { it.segmentId }.distinct()
            val bounds = try {
                if (ids.size > MAX_DAY_FACTS) throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
                ids.chunked(64).flatMap { database.trackingHistoryReadDao().segments(it) }
                    .associate { it.id to (it.startTimeMs to it.endTimeMs) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: RangeHistoryUnavailable) {
                localOrganizationStepsProblem = failure.reason
                emptyMap()
            } catch (_: android.database.sqlite.SQLiteException) {
                localOrganizationStepsProblem = HistoryProblemReason.STORAGE_UNAVAILABLE
                emptyMap()
            } catch (_: RuntimeException) {
                localOrganizationStepsProblem = HistoryProblemReason.INTEGRITY_UNAVAILABLE
                emptyMap()
            }
            return bounds.also { localOrganizationStepBounds = it }
        }
        val result = mergeHistoryBuffers(streams, request.limit) { position, representatives ->
            currentCoroutineContext().ensureActive()
            if (representatives.any { it.kind == RangeHistoryKind.NATIVE }) {
                return@mergeHistoryBuffers nativeSnapshot()[position]
            }
            if (representatives.any { it.kind == RangeHistoryKind.ORGANIZATION }) {
                val seed = representatives.single().seed
                val row = try { organizationSnapshot()[seed.identity.removePrefix("annotation:")]
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: android.database.sqlite.SQLiteException) {
                    return@mergeHistoryBuffers unavailableRow(position, seed.endTimeMs,
                        HistoryOrigin.ORGANIZATION, HistoryPurpose.ORGANIZATION, null,
                        HistoryProblemReason.STORAGE_UNAVAILABLE)
                } catch (_: RuntimeException) {
                    return@mergeHistoryBuffers unavailableRow(position, seed.endTimeMs,
                        HistoryOrigin.ORGANIZATION, HistoryPurpose.ORGANIZATION, null,
                        HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                }
                    ?: return@mergeHistoryBuffers unavailableRow(position, seed.endTimeMs,
                        HistoryOrigin.ORGANIZATION, HistoryPurpose.ORGANIZATION, null,
                        HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                val group = try { row.toOrganizedSession(snapshot, range.viewZoneId,
                    organizationLabels(organizationSnapshot().values))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: android.database.sqlite.SQLiteException) { return@mergeHistoryBuffers unavailableRow(position, seed.endTimeMs,
                    HistoryOrigin.ORGANIZATION, HistoryPurpose.ORGANIZATION, null, HistoryProblemReason.STORAGE_UNAVAILABLE)
                } catch (_: RuntimeException) { return@mergeHistoryBuffers unavailableRow(position, seed.endTimeMs,
                    HistoryOrigin.ORGANIZATION, HistoryPurpose.ORGANIZATION, null, HistoryProblemReason.INTEGRITY_UNAVAILABLE) }
                val contents = if (row.importedSourceSelection == null) {
                    localOrganizationSnapshot()
                } else null
                val members = (contents as? HistoryRangeRead.Page)?.rows.orEmpty().filter { member ->
                    val end = row.endTimeMs
                    end != null && member.position.startTimeMs < end &&
                        (member.toExclusiveMs == null || member.toExclusiveMs > position.startTimeMs ||
                            member.toExclusiveMs == member.position.startTimeMs && member.position.startTimeMs >= position.startTimeMs) }
                val intervalSelection = if (row.importedSourceSelection == null && row.endTimeMs != null) {
                    selectOrganizationInterval(members, position.startTimeMs, row.endTimeMs,
                        organizationStepBounds(contents), localOrganizationStepsProblem)
                } else OrganizationIntervalSections(emptyList(), emptySet())
                val sourceSections = intervalSelection.sections.toMutableList()
                val groupProblems = linkedSetOf<HistoryProblem>()
                groupProblems += intervalSelection.problems
                if (contents is HistoryRangeRead.Page) {
                    groupProblems += contents.problems
                    groupProblems += members.flatMap { it.problems }
                    // An older unseen recording may still overlap this annotation's interval.
                    if (contents.next != null) {
                        groupProblems += HistoryProblem(null, HistoryProblemReason.DEPENDENCY_OVERFLOW) }
                } else if (contents is HistoryRangeRead.Unavailable) groupProblems += HistoryProblem(null, contents.reason)
                if (row.importedSourceSelection != null && group.importedSourceSelection?.isNotEmpty() == true) {
                    val selected = group.importedSourceSelection.filter { it.source in range.sources }
                    if (selected.size > remainingOrganizationSelectors) {
                        selected.map { it.source }.distinct().forEach {
                            groupProblems += HistoryProblem(it, HistoryProblemReason.DEPENDENCY_OVERFLOW) }
                    } else {
                        remainingOrganizationSelectors -= selected.size
                        val read = selectedOrganization(selected, range, snapshot, remainingOrganizationLocationPoints)
                        remainingOrganizationLocationPoints -= read.locationMembersRead
                        sourceSections += read.sections
                        groupProblems += read.problems
                    }
                }
                return@mergeHistoryBuffers UnifiedHistoryRow(position, seed.endTimeMs?.takeIf { it >= position.startTimeMs },
                    setOf(HistoryOrigin.ORGANIZATION), HistoryPurpose.ORGANIZATION, row.name,
                    listOf(HistorySection.Organization(group)) + sourceSections, groupProblems)
            }
            val sections = mutableListOf<HistorySection>()
            val rowProblems = linkedSetOf<HistoryProblem>()
            val origins = linkedSetOf<HistoryOrigin>()
            for (candidate in representatives) {
                val source = requireNotNull(candidate.source)
                val sourceRead = when (candidate.kind) {
                    RangeHistoryKind.IMPORTED -> importedRows.getOrPutSuspending(source) {
                        val seeds = streams.flatMap { it.candidates }.filter {
                            it.kind == RangeHistoryKind.IMPORTED && it.source == source }
                        selectedImported(source, seeds, range)
                    }
                    RangeHistoryKind.BACKGROUND -> backgroundRows.getOrPutSuspending(source) {
                        val seeds = streams.flatMap { it.candidates }.filter {
                            it.kind == RangeHistoryKind.BACKGROUND && it.source == source }
                        selectedBackground(source, seeds, range, snapshot)
                    }
                    else -> error("Native and annotation candidates were handled above")
                }
                sourceRead.sections[candidate.seed.identity]?.let { section ->
                    sections += section
                    origins += sourceRead.origins[candidate.seed.identity].orEmpty()
                }
                sections += sourceRead.additionalSections[candidate.seed.identity].orEmpty()
                sourceRead.problems[candidate.seed.identity]?.let { rowProblems += HistoryProblem(source, it) }
                if (candidate.seed.temporalUncertain) rowProblems += HistoryProblem(source, HistoryProblemReason.TIME_UNCERTAIN)
            }
            if (sections.isEmpty() && rowProblems.isEmpty()) null else UnifiedHistoryRow(position,
                representatives.mapNotNull { it.seed.endTimeMs }.maxOrNull()?.takeIf { it >= position.startTimeMs },
                origins.ifEmpty { if (representatives.first().kind == RangeHistoryKind.IMPORTED)
                    setOf(HistoryOrigin.IMPORTED) else range.origins.intersect(setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED)) },
                if (representatives.first().kind == RangeHistoryKind.IMPORTED) HistoryPurpose.CAPTURE else HistoryPurpose.BACKGROUND,
                sections = sections, problems = rowProblems)
        }
        if (database.sourceEvidenceStateDao().get() != state) {
            return HistoryRangeRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
        return HistoryRangeRead.Page(result.rows, snapshot,
            result.consumed?.takeIf { result.hasMore }?.let { HistoryContinuation(range, it) }, problems)
    }

    /** Finite key-only per-zone probes become one globally ordered source buffer before limiting. */
    private suspend fun backgroundKeys(source: HistorySource, range: HistoryRange,
        after: HistoryPosition?): List<UnifiedHistoryCandidate> {
        if (source != HistorySource.STEPS) return displayedDayKeys(source, range, after)
        val name = source.name.lowercase(java.util.Locale.ROOT)
        val zones = database.trackingHistoryReadDao().unifiedHistoryZones(
            UnifiedHistoryCandidateQueries.backgroundZones(name, range))
        if (zones.size > MAX_CANDIDATES) throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
        val keys = mutableListOf<UnifiedHistoryCandidate>()
        for (zoneName in zones) {
            currentCoroutineContext().ensureActive()
            val zone = try { ZoneId.of(zoneName) } catch (_: RuntimeException) {
                throw RangeHistoryUnavailable(HistoryProblemReason.TIME_UNCERTAIN)
            }
            val first = Instant.ofEpochMilli(range.fromInclusiveMs).atZone(zone).toLocalDate().toEpochDay()
            val last = Instant.ofEpochMilli(range.toExclusiveMs - 1).atZone(zone).toLocalDate().toEpochDay()
            val afterDay = after?.let {
                val date = Instant.ofEpochMilli(it.startTimeMs).atZone(zone).toLocalDate()
                val position = HistoryPosition(date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    "background", "${date.toEpochDay()}:$zoneName")
                date.toEpochDay() - if (position <= it) 1L else 0L
            }
            val probe = database.trackingHistoryReadDao().unifiedHistoryCandidates(
                UnifiedHistoryCandidateQueries.backgroundDays(name, range, zoneName, first, last, afterDay))
            if (probe.size > MAX_CANDIDATES + 1) throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
            probe.forEach { seed ->
                val day = LocalDate.ofEpochDay(requireNotNull(seed.epochDay))
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (start < 0 || end <= start) throw RangeHistoryUnavailable(HistoryProblemReason.TIME_UNCERTAIN)
                keys += seed.copy(startTimeMs = start, endTimeMs = end)
            }
        }
        return keys.distinctBy { it.identity }.sortedWith(compareBy {
            HistoryPosition(it.startTimeMs, "background", it.identity) }).take(MAX_CANDIDATES + 1)
    }

    /** At most 101 key-only probes; skip a whole displayed day rather than paging its individual readings. */
    private suspend fun displayedDayKeys(source: HistorySource, range: HistoryRange,
        after: HistoryPosition?): List<UnifiedHistoryCandidate> {
        val zone = ZoneId.of(range.viewZoneId)
        val name = source.name.lowercase(java.util.Locale.ROOT)
        var before = range.toExclusiveMs
        if (after != null) {
            val date = Instant.ofEpochMilli(after.startTimeMs).atZone(zone).toLocalDate()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli().coerceAtLeast(0L)
            val dayPosition = HistoryPosition(start, "background", "${date.toEpochDay()}:${range.viewZoneId}")
            before = minOf(before, if (dayPosition <= after) start
                else date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }
        val keys = mutableListOf<UnifiedHistoryCandidate>()
        while (before > range.fromInclusiveMs && keys.size <= MAX_CANDIDATES) {
            currentCoroutineContext().ensureActive()
            val seed = database.trackingHistoryReadDao().unifiedHistoryCandidates(
                UnifiedHistoryCandidateQueries.backgroundObservationBefore(name, range, before)).singleOrNull() ?: break
            val day = Instant.ofEpochMilli(seed.startTimeMs).atZone(zone).toLocalDate()
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli().coerceAtLeast(0L)
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (end <= start || start >= before) throw RangeHistoryUnavailable(HistoryProblemReason.TIME_UNCERTAIN)
            keys += seed.copy(identity = "${day.toEpochDay()}:${range.viewZoneId}", startTimeMs = start,
                endTimeMs = end, zoneId = range.viewZoneId, epochDay = day.toEpochDay())
            before = start
        }
        return keys
    }

    private suspend fun selectedImported(source: HistorySource, candidates: List<RangeHistoryCandidate>,
        range: HistoryRange): RangeHistorySections = sourceRead(candidates) {
        if (candidates.any { it.seed.candidateConflict }) throw RangeHistoryUnavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        selectedImportedEntries(source, candidates.map { it.seed.identity }, range)
    }

    private suspend fun selectedImportedEntries(source: HistorySource, ids: List<String>,
        range: HistoryRange): RangeHistorySections {
        val values: Map<String, HistorySection> = when (source) {
            HistorySource.WIFI -> wifi.selectedImportedInTransaction(ids, range).mapValues { HistorySection.Wifi(it.value) }
            HistorySource.CELL -> cell.selectedImportedInTransaction(ids, range).mapValues { HistorySection.Cell(it.value) }
            HistorySource.ACTIVITY -> activity.selectedImportedForSharedHistoryInTransaction(ids,
                range).entriesOrThrow().associate { it.identity.value to HistorySection.Activity(it.entry) }
            HistorySource.PRESSURE -> pressure.selectedImportedInTransaction(ids, range)
                .entriesOrThrow().associate { it.identity.value to HistorySection.Pressure(it.entry.pressure) }
            HistorySource.STEPS -> {
                val (entries, failures) = importedSteps.selectedInTransaction(ids)
                return RangeHistorySections(entries.mapValues { HistorySection.ImportedSteps(it.value) },
                    failures.mapValues { if (it.value == com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure.DEPENDENCY_OVERFLOW)
                        HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE },
                    entries.keys.associateWith { setOf(HistoryOrigin.IMPORTED) })
            }
            HistorySource.LOCATION -> error("Location uses saved point membership, not typed capture envelopes")
        }
        return RangeHistorySections(values, emptyMap(), values.keys.associateWith { setOf(HistoryOrigin.IMPORTED) })
    }

    private suspend fun selectedBackground(source: HistorySource, candidates: List<RangeHistoryCandidate>,
        range: HistoryRange, snapshot: TrackingHistoryReadSnapshot): RangeHistorySections = sourceRead(candidates) {
        val from = maxOf(range.fromInclusiveMs, candidates.minOf { it.seed.startTimeMs })
        val to = minOf(range.toExclusiveMs, candidates.maxOf { requireNotNull(it.seed.endTimeMs) })
        val values = linkedMapOf<String, HistorySection>()
        val origins = linkedMapOf<String, Set<HistoryOrigin>>()
        val additional = linkedMapOf<String, List<HistorySection>>()
        val problems = linkedMapOf<String, HistoryProblemReason>()
        fun window(candidate: RangeHistoryCandidate) = HistoryObservationWindow(
            maxOf(range.fromInclusiveMs, candidate.seed.startTimeMs),
            minOf(range.toExclusiveMs, requireNotNull(candidate.seed.endTimeMs)))
        when (source) {
            HistorySource.LOCATION -> {
                val points = database.locationPurposeDao().sessionlessHistoryOverWindow(from, to, MAX_DAY_FACTS + 1,
                    HistoryOrigin.LOCAL in range.origins, HistoryOrigin.IMPORTED in range.origins, HistoryOrigin.LEGACY in range.origins)
                if (points.size > MAX_DAY_FACTS) throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
                var qualificationProblem: HistoryProblemReason? = null
                val qualified = try {
                    val receipts = points.map { it.point.sample.id }.chunked(64).flatMap { ids ->
                        currentCoroutineContext().ensureActive()
                        database.locationPurposeDao().contextReceiptsForSamples(ids, ids.size + 1).also {
                            if (it.size > ids.size) throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW) }
                    }
                    allDaySources.qualifyLocationInTransaction(receipts)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: RangeHistoryUnavailable) {
                    qualificationProblem = failure.reason
                    RetainedLocationSavedSnapshot(emptyList())
                }
                catch (_: android.database.sqlite.SQLiteException) {
                    qualificationProblem = HistoryProblemReason.STORAGE_UNAVAILABLE
                    RetainedLocationSavedSnapshot(emptyList())
                } catch (_: RuntimeException) {
                    qualificationProblem = HistoryProblemReason.INTEGRITY_UNAVAILABLE
                    RetainedLocationSavedSnapshot(emptyList())
                }
                val qualifiedBySample = qualified.samples.filter { it.scope is com.adsamcik.tracker.stats.api.repository.RadioContextScope.Ambient }
                    .groupBy { it.sample.id }
                fun hasQualifiedAmbient(row: LocationHistoryDayRow): Boolean {
                    val sample = row.point.sample
                    return qualifiedBySample[sample.id].orEmpty().any { it.sample.timeMs == sample.timeMs &&
                        it.sample.latE7 == sample.latE7 && it.sample.lonE7 == sample.lonE7 }
                }
                candidates.forEach { candidate ->
                    val display = window(candidate)
                    val selected = points.filter { row -> display.contains(row.point.sample.timeMs) &&
                        (hasQualifiedAmbient(row) || row.hasCurrentImportCopy ||
                            row.point.sample.id !in qualified.erasedNativeSampleIds) }
                    val sections = selected.groupBy { row ->
                        if (hasQualifiedAmbient(row)) HistoryLocationCoverage.QUALIFIED
                        else HistoryLocationCoverage.LEGACY_UNVERIFIABLE
                    }.map { (coverage, rows) -> HistorySection.Location(rows.map { row ->
                        val sample = row.point.sample
                        HistoryLocationPoint(sample.timeMs, requireNotNull(sample.latE7), requireNotNull(sample.lonE7),
                            sample.hAccM?.takeIf { it.isFinite() && it >= 0f }, row.point.continuityId, sample.id)
                    }, coverage) }
                    if (sections.isNotEmpty()) {
                        values[candidate.seed.identity] = sections.first()
                        additional[candidate.seed.identity] = sections.drop(1)
                        origins[candidate.seed.identity] = selected.mapTo(linkedSetOf()) { HistoryOrigin.valueOf(it.historyOrigin) }
                        if (com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in qualified.gaps)
                            problems[candidate.seed.identity] = HistoryProblemReason.DEPENDENCY_OVERFLOW
                        (qualificationProblem ?: qualified.historyProblem)?.let { problems[candidate.seed.identity] = it }
                    }
                }
            }
            HistorySource.WIFI -> {
                val selectedOrigins = range.origins.mapNotNullTo(linkedSetOf()) { it.toWifiOrigin() }
                val read = allDaySources.readWifiHistoryInTransaction(AmbientWifiReadRequest(from, to, selectedOrigins, MAX_DAY_FACTS))
                val payload = when (read) {
                    is AmbientWifiReadResult.Snapshot -> read
                    AmbientWifiReadResult.DependencyOverflow -> throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
                    AmbientWifiReadResult.StorageUnavailable -> throw RangeHistoryUnavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                }
                candidates.forEach { candidate ->
                    val display = window(candidate)
                    val facts = payload.facts.filter { if (it.networks.isEmpty()) display.contains(it.observedTimeMs)
                        else it.networks.any { network -> display.contains(network.observedTimeMs) } }
                    val gaps = payload.gaps.filter { it.startTimeMs < display.toExclusiveMs && it.endTimeMs > display.fromInclusiveMs }
                    if (facts.isNotEmpty() || gaps.isNotEmpty()) {
                        values[candidate.seed.identity] = HistorySection.BackgroundWifi(payload.copy(facts = facts, gaps = gaps), display)
                        origins[candidate.seed.identity] = (facts.map { it.origin } + gaps.map { it.origin }).mapTo(linkedSetOf()) {
                            if (it == AmbientWifiOrigin.LOCAL_DEVICE) HistoryOrigin.LOCAL else HistoryOrigin.IMPORTED }
                    }
                }
            }
            HistorySource.CELL -> {
                val selectedOrigins = range.origins.mapNotNullTo(linkedSetOf()) { it.toCellOrigin() }
                val read = allDaySources.readCellHistoryInTransaction(AmbientCellReadRequest(from, to, selectedOrigins, MAX_DAY_FACTS))
                val payload = when (read) {
                    is AmbientCellReadResult.Snapshot -> read
                    AmbientCellReadResult.DependencyOverflow -> throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
                    AmbientCellReadResult.StorageUnavailable -> throw RangeHistoryUnavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                }
                candidates.forEach { candidate ->
                    val display = window(candidate)
                    val facts = payload.facts.filter { if (it.towers.isEmpty()) display.contains(it.observedTimeMs)
                        else it.towers.any { tower -> display.contains(tower.observedTimeMs) } }
                    val gaps = payload.gaps.filter { it.startTimeMs < display.toExclusiveMs && it.endTimeMs > display.fromInclusiveMs }
                    if (facts.isNotEmpty() || gaps.isNotEmpty()) {
                        values[candidate.seed.identity] = HistorySection.BackgroundCell(payload.copy(facts = facts, gaps = gaps), display)
                        origins[candidate.seed.identity] = (facts.map { it.origin } + gaps.map { it.origin }).mapTo(linkedSetOf()) {
                            if (it == AmbientCellOrigin.LOCAL_DEVICE) HistoryOrigin.LOCAL else HistoryOrigin.IMPORTED }
                    }
                }
            }
            HistorySource.STEPS -> {
                val days = candidates.map { AmbientStepsStructuralDay(requireNotNull(it.seed.epochDay), requireNotNull(it.seed.zoneId)) }
                    .sortedWith(compareBy({ it.epochDay }, { it.storedZoneId }))
                val batches = mutableListOf<MutableList<AmbientStepsStructuralDay>>()
                for (day in days) {
                    val batch = batches.lastOrNull()
                    if (batch == null || day.epochDay - batch.first().epochDay >= AmbientStepsHistoryRangeRequest.MAX_DAY_COUNT) {
                        batches += mutableListOf(day)
                    } else batch += day
                }
                var factCount = 0
                for (batch in batches) {
                    currentCoroutineContext().ensureActive()
                    val read = allDaySources.readStepsHistoryInTransaction(AmbientStepsHistoryRangeRequest(batch),
                        snapshot.sourceEvidenceRevision, HistoryOrigin.LOCAL in range.origins, HistoryOrigin.IMPORTED in range.origins)
                    val payload = when (read) {
                        is AmbientStepsHistoryRead.Snapshot -> read
                        is AmbientStepsHistoryRead.DependencyOverflow -> throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
                        is AmbientStepsHistoryRead.Unavailable -> throw RangeHistoryUnavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                        AmbientStepsHistoryRead.StorageUnavailable -> throw RangeHistoryUnavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
                    }
                    for (day in payload.days) {
                        factCount = Math.addExact(factCount, day.factOrigins.size)
                        if (factCount > MAX_DAY_FACTS) throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
                        val id = "${day.day.epochDay}:${day.day.storedZoneId}"
                        if (day.factOrigins.isEmpty() && day.importedDisposition == AmbientStepsImportedDisposition.NONE &&
                            (day.total as? AmbientStepsHistoryValue.Unavailable)?.causes == setOf(AmbientStepsHistoryCause.NO_EVIDENCE)) continue
                        values[id] = HistorySection.BackgroundSteps(day)
                        origins[id] = day.factOrigins.mapTo(linkedSetOf()) {
                            if (it.kind == AmbientStepsHistoryFactOriginKind.NATIVE_PROVIDER) HistoryOrigin.LOCAL else HistoryOrigin.IMPORTED
                        }.ifEmpty { range.origins.intersect(setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED)) }
                    }
                }
            }
            else -> error("Source has no sessionless structural-day product")
        }
        RangeHistorySections(values, problems, origins, additional)
    }

    private suspend fun selectedOrganization(selectors: List<OrganizationSourceSelector>, range: HistoryRange,
        snapshot: TrackingHistoryReadSnapshot, locationBudget: Int): OrganizationHistorySections {
        if (selectors.isEmpty() || HistoryOrigin.IMPORTED !in range.origins) return OrganizationHistorySections()
        val sections = mutableListOf<HistorySection>()
        val problems = linkedSetOf<HistoryProblem>()
        val locationPoints = linkedMapOf<Long, HistoryLocationPoint>()
        var locationMembers = 0
        suspend fun readSource(source: HistorySource, block: suspend () -> Unit) {
            try { currentCoroutineContext().ensureActive(); block()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: RangeHistoryUnavailable) { problems += HistoryProblem(source, failure.reason)
            } catch (_: AmbientStepsHistorySelectionOverflow) {
                problems += HistoryProblem(source, HistoryProblemReason.DEPENDENCY_OVERFLOW)
            } catch (_: android.database.sqlite.SQLiteException) {
                problems += HistoryProblem(source, HistoryProblemReason.STORAGE_UNAVAILABLE)
            } catch (_: RuntimeException) { problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE) }
        }
        if (HistoryPurpose.CAPTURE in range.purposes) {
            selectors.filterIsInstance<OrganizationSourceSelector.CapturedEntry>().groupBy { it.source }.forEach { (source, selected) ->
                readSource(source) {
                    val result = selectedImportedEntries(source, selected.map { it.entryIdentity },
                        range.copy(origins = setOf(HistoryOrigin.IMPORTED)))
                    sections += result.sections.values
                    result.problems.values.forEach { problems += HistoryProblem(source, it) }
                    if (result.sections.size + result.problems.size != selected.size) {
                        problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE) }
                }
            }
        }
        if (range.purposes.any { it == HistoryPurpose.CAPTURE || it == HistoryPurpose.BACKGROUND }) {
            for (selector in selectors.filterIsInstance<OrganizationSourceSelector.LocationFile>()) {
                readSource(HistorySource.LOCATION) {
                    val result = selectedLocationFile(selector.archiveIdentity, snapshot, locationBudget - locationMembers,
                        range.purposes) { count ->
                        // Charge admitted membership before payload work, even if a later member is malformed.
                        locationMembers = Math.addExact(locationMembers, count)
                    }
                    locationPoints.putAll(result.points)
                    if (result.hasMissingMembers) problems += HistoryProblem(HistorySource.LOCATION,
                        HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                    if (result.hasUnknownPurpose) problems += HistoryProblem(HistorySource.LOCATION,
                        HistoryProblemReason.TIME_UNCERTAIN)
                }
            }
            if (locationPoints.isNotEmpty()) sections += HistorySection.Location(locationPoints.values.sortedBy { it.timeMs },
                HistoryLocationCoverage.LEGACY_UNVERIFIABLE)
        }
        if (HistoryPurpose.BACKGROUND in range.purposes) {
            val selected = selectors.filterIsInstance<OrganizationSourceSelector.BackgroundStepsDay>()
            if (selected.isNotEmpty()) readSource(HistorySource.STEPS) {
                sections += backgroundSteps.readSelectedImportedInCurrentTransaction(selected.map { it.dayIdentity },
                    snapshot.sourceEvidenceRevision).values.map { HistorySection.BackgroundSteps(it) }
            }
            // The current radio importer does not retain aliases for members skipped during overlapping-file dedup.
            // An archive receipt alone cannot reconstruct that exact file membership.
            selectors.filterIsInstance<OrganizationSourceSelector.BackgroundRadioArchive>().map { it.source }
                .distinct().forEach { problems += HistoryProblem(it, HistoryProblemReason.INTEGRITY_UNAVAILABLE) }
        }
        return OrganizationHistorySections(sections, problems, locationMembers)
    }

    private suspend fun selectedLocationFile(identity: String, snapshot: TrackingHistoryReadSnapshot,
        budget: Int, purposes: Set<HistoryPurpose>, admitMembers: (Int) -> Unit): SelectedLocationFilePoints {
        val selection = RoomLocationFileArchiveRepository(database, io).readInTransaction(
            LocationFileArchiveRequest(identity, snapshot.collectedDataEpoch))
        val archive = selection as? LocationFileArchiveRead.Found
            ?: throw RangeHistoryUnavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        if (archive.declaredPointCount > budget) throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
        admitMembers(archive.declaredPointCount)
        val floor = database.sourceEvidenceStateDao().get()?.retainedFromMs
        val dao = database.locationFileIdentityDao()
        val result = linkedMapOf<Long, HistoryLocationPoint>()
        val unrestricted = HistoryPurpose.CAPTURE in purposes && HistoryPurpose.BACKGROUND in purposes
        var availableMembers = 0
        var unknownPurpose = false
        var after = -1
        var count = 0
        while (count < archive.declaredPointCount) {
            currentCoroutineContext().ensureActive()
            val membership = dao.archivePointPage(identity, after, minOf(256, archive.declaredPointCount - count))
            check(membership.isNotEmpty() && membership.withIndex().all { (index, point) -> point.ordinal == count + index })
            val samples = database.locationSampleDao().byIds(membership.mapNotNull { it.sampleId }.distinct()).associateBy { it.id }
            val currentIds = dao.byPortableIds(membership.map { it.portableId }).associateBy { it.portableId }
            val localIds = if (unrestricted) emptyList() else currentIds.values.filter { !it.imported }.map { it.sampleId }
            val localPurposeRows = if (localIds.isEmpty()) emptyList() else {
                val rows = database.locationPurposeDao().contextPurposes(localIds, localIds.size * 2 + 1)
                if (rows.size > localIds.size * 2) throw RangeHistoryUnavailable(HistoryProblemReason.DEPENDENCY_OVERFLOW)
                rows
            }
            val savedLocalPurposes = localPurposeRows.groupBy { it.sampleId }
            for (point in membership) {
                val sample = samples[point.sampleId] ?: continue
                val savedIdentity = currentIds[point.portableId] ?: continue
                if (savedIdentity.sampleId != sample.id || floor?.let { sample.timeMs < it } == true) continue
                val latitude = sample.latE7 ?: continue
                val longitude = sample.lonE7 ?: continue
                availableMembers++
                if (!unrestricted) {
                    // Descriptive saved labels select history only; they never grant native recording authority.
                    val savedPurposes = if (savedIdentity.imported) when (savedIdentity.reportedOrigin.lowercase(java.util.Locale.ROOT)) {
                        "session" -> setOf(HistoryPurpose.CAPTURE)
                        "background" -> setOf(HistoryPurpose.BACKGROUND)
                        "both" -> setOf(HistoryPurpose.CAPTURE, HistoryPurpose.BACKGROUND)
                        else -> emptySet()
                    } else savedLocalPurposes[sample.id].orEmpty().mapNotNullTo(linkedSetOf()) {
                        when (it.purpose) {
                            "SESSION_CAPTURE" -> HistoryPurpose.CAPTURE
                            "AMBIENT_PRODUCT" -> HistoryPurpose.BACKGROUND
                            else -> null
                        }
                    }
                    if (savedPurposes.isEmpty()) {
                        unknownPurpose = true
                        continue
                    }
                    if (savedPurposes.none { it in purposes }) continue
                }
                result[sample.id] = HistoryLocationPoint(sample.timeMs, latitude, longitude, sample.hAccM, null, sample.id)
            }
            count = Math.addExact(count, membership.size)
            after = membership.last().ordinal
        }
        return SelectedLocationFilePoints(result, availableMembers < archive.availablePointCount ||
            archive.availablePointCount < archive.declaredPointCount, unknownPurpose)
    }

    /** Plan a finite raw frontier first; rejected measurements still advance browsing. */
    private suspend fun readObservationsInTransaction(request: HistoryRangeRequest,
        state: SourceEvidenceState, snapshot: TrackingHistoryReadSnapshot): HistoryRangeRead {
        val range = request.range
        val after = request.after?.consumed
        val problems = linkedSetOf<HistoryProblem>()
        val streams = mutableListOf<HistoryStreamBuffer<MeasuredRangeCandidate>>()
        val location = MeasuredLocationHistoryReader(database, qualifiedLocation)
        val radio = MeasuredRadioHistoryReader(database, backgroundWifi, backgroundCell)
        val capturedRadio = MeasuredCapturedRadioHistoryReader(database, wifi, cell)
        val nativeActivity = MeasuredNativeActivityHistoryReader(database, activity)
        val nativePressure = MeasuredNativePressureHistoryReader(database, pressureSelector)
        val nativeSteps = MeasuredNativeStepsHistoryReader(database, stepsSelector)
        val legacy = MeasuredLegacySensorHistoryReader(database)
        val imported = MeasuredImportedSensorHistoryReader(database)
        val ambientSteps = MeasuredBackgroundStepsHistoryReader(database)
        suspend fun add(kind: MeasuredRangeKind, source: HistorySource,
            read: suspend () -> List<MeasuredHistoryCandidate>) {
            if (source !in range.sources) return
            try {
                currentCoroutineContext().ensureActive()
                val keys = read().map { MeasuredRangeCandidate(kind, source, it) }
                require(keys.size <= MAX_CANDIDATES + 1)
                streams += HistoryStreamBuffer(keys.take(MAX_CANDIDATES),
                    { measuredHistoryPosition(it.seed) }, keys.getOrNull(MAX_CANDIDATES)?.let { measuredHistoryPosition(it.seed) })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: android.database.sqlite.SQLiteException) {
                problems += HistoryProblem(source, HistoryProblemReason.STORAGE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            }
        }
        add(MeasuredRangeKind.LOCATION, HistorySource.LOCATION) { location.candidates(range, after) }
        add(MeasuredRangeKind.WIFI, HistorySource.WIFI) { radio.candidates(HistorySource.WIFI, range, after) }
        add(MeasuredRangeKind.CELL, HistorySource.CELL) { radio.candidates(HistorySource.CELL, range, after) }
        add(MeasuredRangeKind.NATIVE_ACTIVITY, HistorySource.ACTIVITY) { nativeActivity.candidates(range, after) }
        add(MeasuredRangeKind.NATIVE_PRESSURE, HistorySource.PRESSURE) { nativePressure.candidates(range, after) }
        add(MeasuredRangeKind.NATIVE_STEPS, HistorySource.STEPS) { nativeSteps.candidates(range, after) }
        add(MeasuredRangeKind.LEGACY_ACTIVITY, HistorySource.ACTIVITY) { legacy.candidates(HistorySource.ACTIVITY, range, after) }
        add(MeasuredRangeKind.LEGACY_PRESSURE, HistorySource.PRESSURE) { legacy.candidates(HistorySource.PRESSURE, range, after) }
        if (HistoryOrigin.IMPORTED in range.origins && HistoryPurpose.CAPTURE in range.purposes) {
            add(MeasuredRangeKind.IMPORTED_ACTIVITY, HistorySource.ACTIVITY) { imported.candidates(HistorySource.ACTIVITY, range, after) }
            add(MeasuredRangeKind.IMPORTED_PRESSURE, HistorySource.PRESSURE) { imported.candidates(HistorySource.PRESSURE, range, after) }
            add(MeasuredRangeKind.IMPORTED_STEPS, HistorySource.STEPS) { imported.candidates(HistorySource.STEPS, range, after) }
        }
        if (HistoryPurpose.BACKGROUND in range.purposes &&
            range.origins.any { it == HistoryOrigin.LOCAL || it == HistoryOrigin.IMPORTED }) {
            add(MeasuredRangeKind.BACKGROUND_STEPS, HistorySource.STEPS) { ambientSteps.candidates(range, after) }
        }
        val planned = mergeHistoryBuffers(streams, request.limit) { _, representatives -> representatives }
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for ((kind, selected) in planned.rows.flatten().groupBy { it.kind }) {
            currentCoroutineContext().ensureActive()
            val keys = selected.map { it.seed }
            val positions = keys.mapTo(hashSetOf(), ::measuredHistoryPosition)
            fun append(readRows: Map<HistoryPosition, UnifiedHistoryRow>, readProblems: Set<HistoryProblem>) {
                check(readRows.keys.all { it in positions } && readRows.keys.none { it in rows })
                rows += readRows
                problems += readProblems
            }
            try {
                when (kind) {
                    MeasuredRangeKind.LOCATION -> location.qualify(keys, range).let { append(it.rows, it.problems) }
                    MeasuredRangeKind.WIFI -> radio.qualify(HistorySource.WIFI, keys, range, capturedRadio)
                        .let { append(it.rows, it.problems) }
                    MeasuredRangeKind.CELL -> radio.qualify(HistorySource.CELL, keys, range, capturedRadio)
                        .let { append(it.rows, it.problems) }
                    MeasuredRangeKind.NATIVE_ACTIVITY -> nativeActivity.qualify(keys, range).let { append(it.rows, it.problems) }
                    MeasuredRangeKind.NATIVE_PRESSURE -> nativePressure.qualify(keys, range).let { append(it.rows, it.problems) }
                    MeasuredRangeKind.NATIVE_STEPS -> nativeSteps.qualify(keys, range).let { append(it.rows, it.problems) }
                    MeasuredRangeKind.LEGACY_ACTIVITY -> legacy.qualify(HistorySource.ACTIVITY, keys, range)
                        .let { append(it.rows, it.problems) }
                    MeasuredRangeKind.LEGACY_PRESSURE -> legacy.qualify(HistorySource.PRESSURE, keys, range)
                        .let { append(it.rows, it.problems) }
                    MeasuredRangeKind.IMPORTED_ACTIVITY -> imported.qualify(HistorySource.ACTIVITY, keys, range)
                        .let { append(it.rows, it.problems) }
                    MeasuredRangeKind.IMPORTED_PRESSURE -> imported.qualify(HistorySource.PRESSURE, keys, range)
                        .let { append(it.rows, it.problems) }
                    MeasuredRangeKind.IMPORTED_STEPS -> imported.qualify(HistorySource.STEPS, keys, range)
                        .let { append(it.rows, it.problems) }
                    MeasuredRangeKind.BACKGROUND_STEPS -> ambientSteps.qualify(keys, range).let { append(it.rows, it.problems) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: android.database.sqlite.SQLiteException) {
                problems += HistoryProblem(selected.first().source, HistoryProblemReason.STORAGE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                problems += HistoryProblem(selected.first().source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            }
        }
        val ordered = planned.rows.mapNotNull { representatives ->
            rows[measuredHistoryPosition(representatives.first().seed)] }
        val named = MeasuredSessionNamesReader(database, capturedRadio).decorate(ordered,
            planned.rows.map { it.first().seed }, range)
        problems += named.problems
        if (database.sourceEvidenceStateDao().get() != state) {
            return HistoryRangeRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
        val next = if (planned.hasMore && planned.consumed != null) HistoryContinuation(range, planned.consumed) else null
        return HistoryRangeRead.Page(named.rows, snapshot, next, problems)
    }

    private suspend fun sourceRead(candidates: List<RangeHistoryCandidate>,
        read: suspend () -> RangeHistorySections): RangeHistorySections = try {
        currentCoroutineContext().ensureActive()
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: RangeHistoryUnavailable) {
        RangeHistorySections(emptyMap(), candidates.associate { it.seed.identity to failure.reason }, emptyMap())
    } catch (_: AmbientStepsHistorySelectionOverflow) {
        RangeHistorySections(emptyMap(), candidates.associate { it.seed.identity to HistoryProblemReason.DEPENDENCY_OVERFLOW }, emptyMap())
    } catch (_: android.database.sqlite.SQLiteException) {
        RangeHistorySections(emptyMap(), candidates.associate { it.seed.identity to HistoryProblemReason.STORAGE_UNAVAILABLE }, emptyMap())
    } catch (_: RuntimeException) {
        RangeHistorySections(emptyMap(), candidates.associate { it.seed.identity to HistoryProblemReason.INTEGRITY_UNAVAILABLE }, emptyMap())
    }

    companion object {
        private const val MAX_CANDIDATES = 100
        private const val MAX_DAY_FACTS = 4_096
        private val BACKGROUND_SOURCES = setOf(HistorySource.LOCATION, HistorySource.STEPS, HistorySource.WIFI, HistorySource.CELL)
        val invalidationTables: List<String> = (TRACKING_HISTORY_INVALIDATION_TABLES +
            DefaultAmbientStepsHistoryRepository.AMBIENT_HISTORY_DEPENDENCY_TABLES + listOf(
                "activity", "activity_snapshot", "pressure_sample", "ambient_wifi_authority", "ambient_wifi_retention_authority", "ambient_wifi_fact_cursor",
                "ambient_cell_authority", "ambient_cell_retention_authority", "ambient_cell_fact_cursor",
                "imported_ambient_wifi_receipt", "imported_ambient_cell_receipt",
                "wifi_selected_deletion_receipt", "wifi_selected_deletion_protected_identity",
                "imported_pressure_retention_receipt", "imported_pressure_retained_identity",
                "imported_pressure_identity_fence", "imported_pressure_source_erase", "imported_pressure_source_erase_witness",
                "location_sample", "location_accepted_receipt", "location_sample_purpose_authority",
                "ambient_location_retention_authority", "location_file_identity", "location_file_archive", "location_file_archive_point",
                "collected_data_deletion_operation",
            )).distinct()
    }
}

private enum class RangeHistoryKind { NATIVE, IMPORTED, BACKGROUND, ORGANIZATION }
private data class RangeHistoryCandidate(val kind: RangeHistoryKind, val source: HistorySource?,
    val seed: UnifiedHistoryCandidate, val position: HistoryPosition)
private data class RangeHistorySections(val sections: Map<String, HistorySection>,
    val problems: Map<String, HistoryProblemReason>, val origins: Map<String, Set<HistoryOrigin>>,
    val additionalSections: Map<String, List<HistorySection>> = emptyMap())
private class RangeHistoryUnavailable(val reason: HistoryProblemReason) : RuntimeException(null, null, false, false)
private data class OrganizationHistorySections(val sections: List<HistorySection> = emptyList(),
    val problems: Set<HistoryProblem> = emptySet(), val locationMembersRead: Int = 0)
private data class SelectedLocationFilePoints(val points: Map<Long, HistoryLocationPoint>,
    val hasMissingMembers: Boolean, val hasUnknownPurpose: Boolean)

private fun List<RangeHistoryCandidate>.toHistoryBuffer(): HistoryStreamBuffer<RangeHistoryCandidate> {
    require(size <= 101)
    val candidates = take(100)
    return HistoryStreamBuffer(candidates, { it.position }, getOrNull(100)?.position)
}
private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspending(key: K, create: suspend () -> V): V =
    get(key) ?: create().also { put(key, it) }
private fun <T> ImportedHistoryEligiblePage<T>.entriesOrThrow(): List<T> = when (this) {
    is ImportedHistoryEligiblePage.Available -> entries
    is ImportedHistoryEligiblePage.Unavailable -> throw RangeHistoryUnavailable(
        if (reason == SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED)
            HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)
}
internal fun SourceEvidenceState.hasValidRangeShape() = id == SourceEvidenceState.SINGLETON_ID &&
    revision >= 0 && collectedDataEpoch >= 0 && retainedFromMs?.let { it >= 0 } != false &&
    deletedSourceEventHighWaterOrdinal >= 0 && updatedAtMs >= 0
private fun HistoryOrigin.toWifiOrigin(): AmbientWifiOrigin? = when (this) {
    HistoryOrigin.LOCAL -> AmbientWifiOrigin.LOCAL_DEVICE
    HistoryOrigin.IMPORTED -> AmbientWifiOrigin.PORTABLE_IMPORT
    else -> null
}
private fun HistoryOrigin.toCellOrigin(): AmbientCellOrigin? = when (this) {
    HistoryOrigin.LOCAL -> AmbientCellOrigin.LOCAL_DEVICE
    HistoryOrigin.IMPORTED -> AmbientCellOrigin.PORTABLE_IMPORT
    else -> null
}
private fun OrganizationAnnotationEntity.toOrganizedSession(snapshot: TrackingHistoryReadSnapshot,
    viewZoneId: String, labels: Map<Long, String>): OrganizedSession {
    val zone = displayZoneId ?: viewZoneId
    ZoneId.of(zone)
    return OrganizedSession(OrganizationSessionId.Annotation(localId), revision, snapshot.collectedDataEpoch,
        name, activityLabelId, labels[activityLabelId] ?: importedActivityLabel, zone, startTimeMs, endTimeMs,
        false, startTimeMs == null || endTimeMs == null || endTimeMs <= startTimeMs,
        importedSourceSelection = importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode))
}
private fun unavailableRow(position: HistoryPosition, end: Long?, origin: HistoryOrigin,
    purpose: HistoryPurpose, source: HistorySource?, reason: HistoryProblemReason) = UnifiedHistoryRow(
    position, end?.takeIf { it >= position.startTimeMs }, setOf(origin), purpose,
    sections = emptyList(), problems = setOf(HistoryProblem(source, reason)))

private enum class MeasuredRangeKind {
    LOCATION, WIFI, CELL, NATIVE_ACTIVITY, NATIVE_PRESSURE, NATIVE_STEPS,
    LEGACY_ACTIVITY, LEGACY_PRESSURE, IMPORTED_ACTIVITY, IMPORTED_PRESSURE, IMPORTED_STEPS, BACKGROUND_STEPS,
}
private data class MeasuredRangeCandidate(val kind: MeasuredRangeKind, val source: HistorySource,
    val seed: MeasuredHistoryCandidate)
