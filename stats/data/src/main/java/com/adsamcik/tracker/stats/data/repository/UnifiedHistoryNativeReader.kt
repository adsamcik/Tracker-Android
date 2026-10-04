package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.UnifiedHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryLocationPoint
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySection
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Source readers authenticate one native candidate cohort inside the containing Room snapshot. */
internal class UnifiedHistoryNativeReader @Inject constructor(
    private val database: AppDatabase,
    private val logical: LogicalTrackingHistoryReader,
    private val steps: StepsSegmentHistorySelector,
    private val wifi: DefaultWifiHistoryRepository,
    private val cell: DefaultCellHistoryRepository,
    private val activity: DefaultActivityHistoryRepository,
    private val pressure: PressureHistorySelector,
    private val qualifiedLocation: RetainedLocationContextReader,
) {
    suspend fun readInTransaction(
        candidates: List<UnifiedHistoryCandidate>,
        range: HistoryRange,
    ): Map<HistoryPosition, UnifiedHistoryRow> {
        require(candidates.size <= MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyMap()
        val logicalIds = candidates.filter { it.category == "native" }
            .map { it.identity.removePrefix("logical:") }.distinct()
        val membershipRead = logical.selectedLogicalMembersInTransaction(logicalIds)
        val membershipProblem = (membershipRead as? SelectedLogicalHistoryRead.Unavailable)?.let {
            HistoryProblemReason.DEPENDENCY_OVERFLOW }
        val members = when (val read = membershipRead) {
            is SelectedLogicalHistoryRead.Content -> read.members
            is SelectedLogicalHistoryRead.Unavailable -> emptyList()
            SelectedLogicalHistoryRead.NotFound -> emptyList()
        }
        val legacyIds = candidates.filter { it.category == "legacy" }.mapNotNull { it.segmentId }
        val legacy = if (legacyIds.isEmpty()) emptyList() else steps.selectManyInTransaction(
            database.trackingHistoryReadDao().segments(legacyIds),
        )
        val byLogicalId = members.groupBy { it.segment.logicalTrackingId }
        val byLegacyId = legacy.associateBy { it.segment.id }
        val annotations = database.organizationAnnotationDao().byAdapters(candidates.map { it.identity })
            .associateBy { it.recordingAdapterKey }
        val selectedSources = candidates.associate { candidate ->
            val exact = byLogicalId[candidate.identity.removePrefix("logical:")].orEmpty()
                .mapNotNull { it.captureAuthority as? HistoricalCaptureAuthority.Exact }
            candidate.identity to exact.flatMapTo(linkedSetOf()) { it.capturedInAnyRevision }
                .mapTo(linkedSetOf(), TrackingSourceComponent::toPublicSource)
                .intersect(range.sources)
        }
        val seedsBySource = HistorySource.entries.associateWith { source ->
            candidates.filter { source in selectedSources[it.identity].orEmpty() }
                .mapNotNull { it.segmentId }.distinct()
        }
        val wifiRead = readSource(HistorySource.WIFI, seedsBySource, range) { ids ->
            when (val page = wifi.selectBySegmentIdsInTransaction(ids)) {
                is WifiComposedPage.Available -> page.entries.associate {
                    "logical:${it.logicalTrackingId}" to HistorySection.Wifi(it.entry)
                }
                is WifiComposedPage.Failed -> throw NativeHistorySourceUnavailable(
                    if (page.cause == com.adsamcik.tracker.stats.api.repository.WifiHistoryCause.READ_BUDGET_EXCEEDED) HistoryProblemReason.DEPENDENCY_OVERFLOW
                    else HistoryProblemReason.INTEGRITY_UNAVAILABLE,
                )
            }
        }
        val cellRead = readSource(HistorySource.CELL, seedsBySource, range) { ids ->
            when (val page = cell.selectBySegmentIdsInTransaction(ids)) {
                is CellComposedPage.Available -> page.entries.associate {
                    "logical:${it.logicalTrackingId}" to HistorySection.Cell(it.entry)
                }
                is CellComposedPage.Failed -> throw NativeHistorySourceUnavailable(
                    if (page.cause == com.adsamcik.tracker.stats.api.repository.CellHistoryCause.READ_BUDGET_EXCEEDED) HistoryProblemReason.DEPENDENCY_OVERFLOW
                    else HistoryProblemReason.INTEGRITY_UNAVAILABLE,
                )
            }
        }
        val activityRead = readSource(HistorySource.ACTIVITY, seedsBySource, range) { ids ->
            when (val page = activity.selectBySegmentIdsInTransaction(ids)) {
                is ActivityComposedPage.Available -> page.entries.associate {
                    "logical:${it.logicalTrackingId}" to HistorySection.Activity(it.entry)
                }
                is ActivityComposedPage.Failed -> throw NativeHistorySourceUnavailable(
                    if (page.cause == com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause.READ_BUDGET_EXCEEDED) HistoryProblemReason.DEPENDENCY_OVERFLOW
                    else HistoryProblemReason.INTEGRITY_UNAVAILABLE,
                )
            }
        }
        val pressureRead = readSource(HistorySource.PRESSURE, seedsBySource, range) { ids ->
            if (ids.size > MAX_PRESSURE_MEMBERS) throw NativeHistorySourceUnavailable(
                HistoryProblemReason.DEPENDENCY_OVERFLOW,
            )
            val page = pressure.selectLogicalBySegmentIdsInTransaction(ids)
            if (page.hasDependencyOverflow) throw NativeHistorySourceUnavailable(
                HistoryProblemReason.DEPENDENCY_OVERFLOW,
            )
            page.mapNotNull { entry ->
                (entry.identity as? PressureHistoryEntryIdentity.Logical)?.let { id ->
                    "logical:${id.logicalTrackingId}" to
                        HistorySection.Pressure(entry.physicalMembers.toPublicPressureHistory())
                }
            }.toMap()
        }
        val sourceReads = mapOf(HistorySource.WIFI to wifiRead, HistorySource.CELL to cellRead,
            HistorySource.ACTIVITY to activityRead, HistorySource.PRESSURE to pressureRead)
        val locationRead = if (HistorySource.LOCATION in range.sources) {
            readLocationCompatibility(candidates, members, legacy, range)
        } else NativeHistoryLocationSections(emptyMap(), null)
        return candidates.mapNotNull { candidate ->
            currentCoroutineContext().ensureActive()
            val origins = buildSet {
                add(if (candidate.category == "legacy") HistoryOrigin.LEGACY else HistoryOrigin.LOCAL)
                if (candidate.identity in annotations) add(HistoryOrigin.ORGANIZATION)
            }
            if (origins.none { it in range.origins }) return@mapNotNull null
            val rowMembers = if (candidate.category == "legacy") {
                listOfNotNull(byLegacyId[candidate.segmentId])
            } else byLogicalId[candidate.identity.removePrefix("logical:")].orEmpty()
            val captures = rowMembers.map { it.captureAuthority as? HistoricalCaptureAuthority.Exact }
            val exactMembership = candidate.category == "native" && rowMembers.isNotEmpty() &&
                captures.all { it != null } && SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
                    captures.filterNotNull().map { capture -> capture.revisions.map { it.manifestRevision } },
                )
            val sources = selectedSources[candidate.identity].orEmpty()
            val problems = linkedSetOf<HistoryProblem>()
            val sections = buildList {
                if (exactMembership || candidate.category == "legacy") {
                    if (HistorySource.STEPS in range.sources &&
                        (HistorySource.STEPS in sources || candidate.category == "legacy")
                    ) rowMembers.forEach { member ->
                        val history = member.steps.toPublicHistory()
                        if (HistorySource.STEPS in sources || history.count != null) {
                            add(HistorySection.Steps(member.segment.id, history))
                        }
                    }
                    sourceReads.forEach { (source, read) ->
                        if (source in sources) {
                            read.sections[candidate.identity]?.let(::add)
                                ?: problems.add(HistoryProblem(source, read.problem
                                    ?: HistoryProblemReason.INTEGRITY_UNAVAILABLE))
                        }
                    }
                    if (HistorySource.LOCATION in range.sources &&
                        (HistorySource.LOCATION in sources || candidate.category == "legacy")
                    ) {
                        locationRead.sections[candidate.identity]?.let(::addAll)
                        locationRead.problem?.let { problems.add(HistoryProblem(HistorySource.LOCATION, it)) }
                    }
                } else {
                    range.sources.forEach { problems.add(HistoryProblem(it,
                        membershipProblem ?: HistoryProblemReason.INTEGRITY_UNAVAILABLE)) }
                }
            }
            if (sections.isEmpty() && problems.isEmpty()) return@mapNotNull null
            if (candidate.temporalUncertain) problems.add(HistoryProblem(null, HistoryProblemReason.TIME_UNCERTAIN))
            val position = HistoryPosition(candidate.startTimeMs, "local", candidate.identity)
            val annotation = annotations[candidate.identity]
            val narrowed = if (annotation?.startTimeMs != null && annotation.endTimeMs != null && rowMembers.isNotEmpty()) {
                val originalStart = rowMembers.minOf { it.segment.startTimeMs }
                val originalEnd = rowMembers.maxOf { it.segment.endTimeMs }
                val original = UnifiedHistoryRow(
                    HistoryPosition(originalStart, "local", candidate.identity),
                    originalEnd.takeIf { it >= originalStart }, origins, HistoryPurpose.CAPTURE,
                    sections = sections,
                )
                // Organization edits select retained evidence; they cannot clip or prorate a count.
                selectOrganizationInterval(listOf(original), annotation.startTimeMs, annotation.endTimeMs,
                    rowMembers.associate { it.segment.id to (it.segment.startTimeMs to it.segment.endTimeMs) })
            } else OrganizationIntervalSections(sections, emptySet())
            problems += narrowed.problems
            position to UnifiedHistoryRow(position, candidate.endTimeMs?.takeIf { it >= candidate.startTimeMs },
                origins, HistoryPurpose.CAPTURE, annotation?.name, narrowed.sections, problems)
        }.toMap()
    }

    private suspend fun readSource(source: HistorySource, seeds: Map<HistorySource, List<Long>>,
        range: HistoryRange, read: suspend (List<Long>) -> Map<String, HistorySection>): NativeHistorySections {
        val ids = seeds[source].orEmpty()
        if (source !in range.sources || ids.isEmpty()) return NativeHistorySections(emptyMap(), null)
        return try {
            NativeHistorySections(read(ids), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeHistorySourceUnavailable) {
            NativeHistorySections(emptyMap(), failure.reason)
        } catch (_: android.database.sqlite.SQLiteException) {
            NativeHistorySections(emptyMap(), HistoryProblemReason.STORAGE_UNAVAILABLE)
        } catch (_: RuntimeException) {
            NativeHistorySections(emptyMap(), HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
    }

    /** Keep saved points visible; source-owned retained proof labels only authenticated native points. */
    private suspend fun readLocationCompatibility(candidates: List<UnifiedHistoryCandidate>,
        members: List<HistoricalSegmentEvidence>, legacy: List<HistoricalSegmentEvidence>,
        range: HistoryRange): NativeHistoryLocationSections {
        val selected = members.filter { member ->
            (member.captureAuthority as? HistoricalCaptureAuthority.Exact)
                ?.capturedInAnyRevision?.contains(TrackingSourceComponent.LOCATION) == true
        } + legacy
        if (selected.isEmpty()) return NativeHistoryLocationSections(emptyMap(), null)
        // Session organization does not widen the measured dates selected by History.
        val from = maxOf(range.fromInclusiveMs, selected.minOf { it.segment.startTimeMs }.coerceAtLeast(0))
        val to = minOf(range.toExclusiveMs - 1, selected.maxOf { it.segment.endTimeMs })
        if (to < from) return NativeHistoryLocationSections(emptyMap(), null)
        return try {
            val probe = database.locationPurposeDao().visiblePathChunk(from, to, null, null,
                MAX_LOCATION_POINTS + 1)
            if (probe.size > MAX_LOCATION_POINTS) return NativeHistoryLocationSections(emptyMap(),
                HistoryProblemReason.DEPENDENCY_OVERFLOW)
            val fileImportedIds = probe.map { it.sample.id }.chunked(64).flatMap {
                database.locationPurposeDao().importedHistorySampleIds(it) }.toSet()
            val purposes = probe.map { it.sample.id }.chunked(64).flatMap { ids ->
                val rows = database.locationPurposeDao().contextPurposes(ids, ids.size * 2 + 1)
                if (rows.size > ids.size * 2) return NativeHistoryLocationSections(emptyMap(),
                    HistoryProblemReason.DEPENDENCY_OVERFLOW)
                rows
            }.filter { it.purpose == "SESSION_CAPTURE" }.groupBy { it.sampleId }
            val receipts = database.locationPurposeDao().contextReceiptsOverWindow(from,
                if (to == Long.MAX_VALUE) to else to + 1, MAX_LOCATION_POINTS + 1)
            var proofProblem: HistoryProblemReason? = null
            val qualified = if (receipts.size > MAX_LOCATION_POINTS) {
                proofProblem = HistoryProblemReason.DEPENDENCY_OVERFLOW
                null
            } else try {
                qualifiedLocation.qualifyInTransaction(receipts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: android.database.sqlite.SQLiteException) {
                proofProblem = HistoryProblemReason.STORAGE_UNAVAILABLE
                null
            } catch (_: RuntimeException) {
                proofProblem = HistoryProblemReason.INTEGRITY_UNAVAILABLE
                null
            }
            val sourceSamples = receipts.associate { it.sourceEventId to it.sampleId }
            val qualifiedBySample = qualified?.points.orEmpty().filter {
                it.scope is RadioContextScope.Session }.groupBy { sourceSamples[it.sourceEventId] }
            val groups = candidates.associate { candidate ->
                val points = probe.filter { point ->
                    if (candidate.category == "legacy") {
                        point.sample.timeMs >= candidate.startTimeMs &&
                            point.sample.timeMs <= (candidate.endTimeMs ?: Long.MAX_VALUE) &&
                            !point.ambientOnly && point.sample.id !in fileImportedIds
                    } else purposes[point.sample.id].orEmpty().any {
                        it.logicalTrackingId == candidate.identity.removePrefix("logical:")
                    }
                }.mapNotNull { point ->
                    val sample = point.sample
                    val latitude = sample.latE7 ?: return@mapNotNull null
                    val longitude = sample.lonE7 ?: return@mapNotNull null
                    val publicPoint = HistoryLocationPoint(sample.timeMs, latitude, longitude,
                        sample.hAccM, point.continuityId, sample.id)
                    val nativeProof = candidate.category == "native" && qualifiedBySample[sample.id].orEmpty().any {
                        val owner = (it.scope as RadioContextScope.Session).owner
                        owner.logicalTrackingId == candidate.identity.removePrefix("logical:") &&
                            it.observedWallTimeMs == sample.timeMs && it.latitudeE7 == latitude && it.longitudeE7 == longitude }
                    publicPoint to if (nativeProof) HistoryLocationCoverage.QUALIFIED else HistoryLocationCoverage.LEGACY_UNVERIFIABLE
                }
                val sections = points.groupBy { it.second }.map { (coverage, values) ->
                    HistorySection.Location(values.map { it.first }, coverage) }
                candidate.identity to sections
            }
            NativeHistoryLocationSections(groups, proofProblem ?: qualified?.historyProblem ?: if (qualified != null &&
                com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in qualified.gaps)
                    HistoryProblemReason.DEPENDENCY_OVERFLOW else null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: android.database.sqlite.SQLiteException) {
            NativeHistoryLocationSections(emptyMap(), HistoryProblemReason.STORAGE_UNAVAILABLE)
        } catch (_: RuntimeException) {
            NativeHistoryLocationSections(emptyMap(), HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
    }

    private data class NativeHistorySections(val sections: Map<String, HistorySection>, val problem: HistoryProblemReason?)
    private data class NativeHistoryLocationSections(val sections: Map<String, List<HistorySection.Location>>, val problem: HistoryProblemReason?)
    private class NativeHistorySourceUnavailable(val reason: HistoryProblemReason) : RuntimeException(null, null, false, false)
    private companion object {
        const val MAX_CANDIDATES = 100
        const val MAX_PRESSURE_MEMBERS = 64
        const val MAX_LOCATION_POINTS = 4_096
    }
}
