package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.dao.UnifiedHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap
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
        if (candidates.isEmpty()) {
            return emptyMap()
        }
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
        val legacy = if (legacyIds.isEmpty()) {
            emptyList()
        } else {
            steps.selectManyInTransaction(
            database.trackingHistoryReadDao().segments(legacyIds),
        )
        }
        val byLogicalId = members.groupBy { it.segment.logicalTrackingId }
        val byLegacyId = legacy.associateBy { it.segment.id }
        val annotations = database.organizationAnnotationDao().byAdapters(candidates.map { it.identity })
            .associateBy { it.recordingAdapterKey }
        val selectedSources = selectedNativeSources(candidates, byLogicalId, range)
        val seedsBySource = HistorySource.entries.associateWith { source ->
            candidates.filter { source in selectedSources[it.identity].orEmpty() }
                .mapNotNull { it.segmentId }.distinct()
        }
        val wifiRead = readSource(HistorySource.WIFI, seedsBySource, range, ::readWifiSections)
        val cellRead = readSource(HistorySource.CELL, seedsBySource, range, ::readCellSections)
        val activityRead = readSource(HistorySource.ACTIVITY, seedsBySource, range, ::readActivitySections)
        val pressureRead = readSource(HistorySource.PRESSURE, seedsBySource, range, ::readPressureSections)
        val sourceReads = mapOf(HistorySource.WIFI to wifiRead, HistorySource.CELL to cellRead,
            HistorySource.ACTIVITY to activityRead, HistorySource.PRESSURE to pressureRead)
        val locationRead = if (HistorySource.LOCATION in range.sources) {
            readLocationCompatibility(candidates, members, legacy, range)
        } else {
            NativeHistoryLocationSections(emptyMap(), null)
        }
        return candidates.mapNotNull { candidate ->
            currentCoroutineContext().ensureActive()
            val rowMembers = if (candidate.category == "legacy") {
                listOfNotNull(byLegacyId[candidate.segmentId])
            } else {
                byLogicalId[candidate.identity.removePrefix("logical:")].orEmpty()
            }
            nativeHistoryRow(candidate, rowMembers, range, annotations[candidate.identity],
                selectedSources[candidate.identity].orEmpty(), sourceReads, locationRead, membershipProblem)
                ?.let { it.position to it }
        }.toMap()
    }

    private suspend fun readSource(source: HistorySource, seeds: Map<HistorySource, List<Long>>,
        range: HistoryRange, read: suspend (List<Long>) -> Map<String, HistorySection>): NativeHistorySections {
        val ids = seeds[source].orEmpty()
        if (source !in range.sources || ids.isEmpty()) {
            return NativeHistorySections(emptyMap(), null)
        }
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
        if (selected.isEmpty()) {
            return NativeHistoryLocationSections(emptyMap(), null)
        }
        // Session organization does not widen the measured dates selected by History.
        val from = maxOf(range.fromInclusiveMs, selected.minOf { it.segment.startTimeMs }.coerceAtLeast(0))
        val to = minOf(range.toExclusiveMs - 1, selected.maxOf { it.segment.endTimeMs })
        if (to < from) {
            return NativeHistoryLocationSections(emptyMap(), null)
        }
        return try {
            readLocationSnapshot(candidates, from, to)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: android.database.sqlite.SQLiteException) {
            NativeHistoryLocationSections(emptyMap(), HistoryProblemReason.STORAGE_UNAVAILABLE)
        } catch (_: RuntimeException) {
            NativeHistoryLocationSections(emptyMap(), HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
    }

    private suspend fun readLocationSnapshot(
        candidates: List<UnifiedHistoryCandidate>, from: Long, to: Long,
    ): NativeHistoryLocationSections {
        val probe = database.locationPurposeDao().visiblePathChunk(from, to, null, null, MAX_LOCATION_POINTS + 1)
        if (probe.size > MAX_LOCATION_POINTS) {
            return NativeHistoryLocationSections(emptyMap(), HistoryProblemReason.DEPENDENCY_OVERFLOW)
        }
        val fileImportedIds = probe.map { it.sample.id }.chunked(64).flatMap {
            database.locationPurposeDao().importedHistorySampleIds(it)
        }.toSet()
        val purposes = readLocationPurposes(probe)
            ?: return NativeHistoryLocationSections(emptyMap(), HistoryProblemReason.DEPENDENCY_OVERFLOW)
        val receipts = database.locationPurposeDao().contextReceiptsOverWindow(from,
            if (to == Long.MAX_VALUE) { to } else { to + 1 }, MAX_LOCATION_POINTS + 1)
        val qualified = readLocationProof(receipts)
        val groups = nativeLocationSections(candidates, probe, fileImportedIds, purposes, qualified)
        return NativeHistoryLocationSections(groups, qualified.historyProblem ?: if (
            RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in qualified.gaps
        ) {
            HistoryProblemReason.DEPENDENCY_OVERFLOW
        } else {
            null
        })
    }

    private suspend fun readLocationPurposes(
        probe: List<LocationPathSampleRow>,
    ): Map<Long, List<LocationSamplePurposeAuthorityEntity>>? {
        val purposes = probe.map { it.sample.id }.chunked(64).flatMap { ids ->
            val rows = database.locationPurposeDao().contextPurposes(ids, ids.size * 2 + 1)
            if (rows.size > ids.size * 2) {
                return null
            }
            rows
        }
        return purposes.filter { it.purpose == "SESSION_CAPTURE" }.groupBy { it.sampleId }
    }

    private suspend fun readLocationProof(
        receipts: List<LocationAcceptedReceiptEntity>,
    ): RetainedLocationSavedSnapshot {
        if (receipts.size > MAX_LOCATION_POINTS) {
            return RetainedLocationSavedSnapshot(emptyList(), historyProblem = HistoryProblemReason.DEPENDENCY_OVERFLOW)
        }
        return try {
            qualifiedLocation.qualifySavedInTransaction(receipts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: android.database.sqlite.SQLiteException) {
            RetainedLocationSavedSnapshot(emptyList(), historyProblem = HistoryProblemReason.STORAGE_UNAVAILABLE)
        } catch (_: RuntimeException) {
            RetainedLocationSavedSnapshot(emptyList(), historyProblem = HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
    }

    private suspend fun readWifiSections(ids: List<Long>): Map<String, HistorySection> {
        return when (val page = wifi.selectBySegmentIdsInTransaction(ids)) {
            is WifiComposedPage.Available -> page.entries.associate {
                "logical:${it.logicalTrackingId}" to HistorySection.Wifi(it.entry)
            }
            is WifiComposedPage.Failed -> throw NativeHistorySourceUnavailable(
                if (page.cause == com.adsamcik.tracker.stats.api.repository.WifiHistoryCause.READ_BUDGET_EXCEEDED) {
                    HistoryProblemReason.DEPENDENCY_OVERFLOW
                }
                else {
                    HistoryProblemReason.INTEGRITY_UNAVAILABLE
                },
            )
        }
    }

    private suspend fun readCellSections(ids: List<Long>): Map<String, HistorySection> {
        return when (val page = cell.selectBySegmentIdsInTransaction(ids)) {
            is CellComposedPage.Available -> page.entries.associate {
                "logical:${it.logicalTrackingId}" to HistorySection.Cell(it.entry)
            }
            is CellComposedPage.Failed -> throw NativeHistorySourceUnavailable(
                if (page.cause == com.adsamcik.tracker.stats.api.repository.CellHistoryCause.READ_BUDGET_EXCEEDED) {
                    HistoryProblemReason.DEPENDENCY_OVERFLOW
                }
                else {
                    HistoryProblemReason.INTEGRITY_UNAVAILABLE
                },
            )
        }
    }

    private suspend fun readActivitySections(ids: List<Long>): Map<String, HistorySection> {
        return when (val page = activity.selectBySegmentIdsInTransaction(ids)) {
            is ActivityComposedPage.Available -> page.entries.associate {
                "logical:${it.logicalTrackingId}" to HistorySection.Activity(it.entry)
            }
            is ActivityComposedPage.Failed -> throw NativeHistorySourceUnavailable(
                if (page.cause == ActivityHistoryCause.READ_BUDGET_EXCEEDED) {
                    HistoryProblemReason.DEPENDENCY_OVERFLOW
                }
                else {
                    HistoryProblemReason.INTEGRITY_UNAVAILABLE
                },
            )
        }
    }

    private suspend fun readPressureSections(ids: List<Long>): Map<String, HistorySection> {
        if (ids.size > MAX_PRESSURE_MEMBERS) {
            throw NativeHistorySourceUnavailable(
            HistoryProblemReason.DEPENDENCY_OVERFLOW,
        )
        }
        val page = pressure.selectLogicalBySegmentIdsInTransaction(ids)
        if (page.any { entry ->
            entry.physicalMembers.any { member ->
                PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW in member.reasons
            }
        }) {
            throw NativeHistorySourceUnavailable(
            HistoryProblemReason.DEPENDENCY_OVERFLOW,
        )
        }
        return page.mapNotNull { entry ->
            (entry.identity as? PressureHistoryEntryIdentity.Logical)?.let { id ->
                "logical:${id.logicalTrackingId}" to
                    HistorySection.Pressure(entry.physicalMembers.toPublicPressureHistory())
            }
        }.toMap()
    }

    private class NativeHistorySourceUnavailable(val reason: HistoryProblemReason) : RuntimeException(null,
        null, false, false)
    private companion object {
        const val MAX_CANDIDATES = 100
        const val MAX_PRESSURE_MEMBERS = 64
        const val MAX_LOCATION_POINTS = 4_096
    }
}

private fun selectedNativeSources(
    candidates: List<UnifiedHistoryCandidate>, byLogicalId: Map<String?, List<HistoricalSegmentEvidence>>,
    range: HistoryRange,
): Map<String, Set<HistorySource>> = candidates.associate { candidate ->
    val exact = byLogicalId[candidate.identity.removePrefix("logical:")].orEmpty()
        .mapNotNull { it.captureAuthority as? HistoricalCaptureAuthority.Exact }
    candidate.identity to exact.flatMapTo(linkedSetOf()) { it.capturedInAnyRevision }
        .mapTo(linkedSetOf(), TrackingSourceComponent::toPublicSource).intersect(range.sources)
}

// Keep the captured projection inputs explicit instead of adding a context carrier or rereading authority.
@Suppress("LongParameterList")
private fun nativeHistoryRow(
    candidate: UnifiedHistoryCandidate, rowMembers: List<HistoricalSegmentEvidence>, range: HistoryRange,
    annotation: OrganizationAnnotationEntity?, sources: Set<HistorySource>,
    sourceReads: Map<HistorySource, NativeHistorySections>, locationRead: NativeHistoryLocationSections,
    membershipProblem: HistoryProblemReason?,
): UnifiedHistoryRow? {
    val origins = nativeHistoryOrigins(candidate, annotation)
    if (origins.none { it in range.origins }) {
        return null
    }
    val exactMembership = rowMembers.hasExactNativeMembership(candidate)
    val problems = linkedSetOf<HistoryProblem>()
    val sections = if (exactMembership || candidate.category == "legacy") {
        nativeAvailableSections(candidate, rowMembers, range, sources, sourceReads, locationRead, problems)
    } else {
        range.sources.forEach { problems.add(HistoryProblem(it,
            membershipProblem ?: HistoryProblemReason.INTEGRITY_UNAVAILABLE)) }
        emptyList()
    }
    if (sections.isEmpty() && problems.isEmpty()) {
        return null
    }
    if (candidate.temporalUncertain) {
        problems.add(HistoryProblem(null, HistoryProblemReason.TIME_UNCERTAIN))
    }
    val position = HistoryPosition(candidate.startTimeMs, "local", candidate.identity)
    val narrowed = selectNativeOrganizationInterval(candidate, rowMembers, sections, origins, annotation)
    problems += narrowed.problems
    return UnifiedHistoryRow(position, candidate.endTimeMs?.takeIf { it >= candidate.startTimeMs },
        origins, HistoryPurpose.CAPTURE, annotation?.name, narrowed.sections, problems)
}

// These are the same immutable source reads and original row accumulation; no additional owner is needed.
@Suppress("LongParameterList")
private fun nativeAvailableSections(
    candidate: UnifiedHistoryCandidate, rowMembers: List<HistoricalSegmentEvidence>, range: HistoryRange,
    sources: Set<HistorySource>, sourceReads: Map<HistorySource, NativeHistorySections>,
    locationRead: NativeHistoryLocationSections, problems: MutableSet<HistoryProblem>,
): List<HistorySection> = buildList {
    addAll(nativeStepsSections(candidate, rowMembers, range, sources))
    sourceReads.forEach { (source, read) ->
        if (source in sources) {
            val section = read.sections[candidate.identity]
            if (section != null) {
                add(section)
            } else {
                problems.add(HistoryProblem(source, read.problem ?: HistoryProblemReason.INTEGRITY_UNAVAILABLE))
            }
        }
    }
    if (HistorySource.LOCATION in range.sources &&
        (HistorySource.LOCATION in sources || candidate.category == "legacy")
    ) {
        locationRead.sections[candidate.identity]?.let(::addAll)
        locationRead.problem?.let { problems.add(HistoryProblem(HistorySource.LOCATION, it)) }
    }
}

private fun nativeHistoryOrigins(
    candidate: UnifiedHistoryCandidate, annotation: OrganizationAnnotationEntity?,
): Set<HistoryOrigin> = buildSet {
    add(if (candidate.category == "legacy") { HistoryOrigin.LEGACY } else { HistoryOrigin.LOCAL })
    if (annotation != null) { add(HistoryOrigin.ORGANIZATION) }
}

private fun List<HistoricalSegmentEvidence>.hasExactNativeMembership(candidate: UnifiedHistoryCandidate): Boolean {
    val captures = map { it.captureAuthority as? HistoricalCaptureAuthority.Exact }
    return candidate.category == "native" && isNotEmpty() && captures.all { it != null } &&
        SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
            captures.filterNotNull().map { capture -> capture.revisions.map { it.manifestRevision } },
        )
}

private fun nativeStepsSections(
    candidate: UnifiedHistoryCandidate, rowMembers: List<HistoricalSegmentEvidence>,
    range: HistoryRange, sources: Set<HistorySource>,
): List<HistorySection.Steps> = buildList {
    if (HistorySource.STEPS in range.sources &&
        (HistorySource.STEPS in sources || candidate.category == "legacy")
    ) {
        rowMembers.forEach { member ->
            val history = member.steps.toPublicHistory()
            if (HistorySource.STEPS in sources || history.count != null) {
                add(HistorySection.Steps(member.segment.id, history))
            }
        }
    }
}

private fun selectNativeOrganizationInterval(
    candidate: UnifiedHistoryCandidate,
    rowMembers: List<HistoricalSegmentEvidence>,
    sections: List<HistorySection>,
    origins: Set<HistoryOrigin>,
    annotation: OrganizationAnnotationEntity?,
): OrganizationIntervalSections {
    val annotationStart = annotation?.startTimeMs
    val annotationEnd = annotation?.endTimeMs
    return if (annotationStart != null && annotationEnd != null && rowMembers.isNotEmpty()) {
        val originalStart = rowMembers.minOf { it.segment.startTimeMs }
        val originalEnd = rowMembers.maxOf { it.segment.endTimeMs }
        val original = UnifiedHistoryRow(
            HistoryPosition(originalStart, "local", candidate.identity),
            originalEnd.takeIf { it >= originalStart }, origins, HistoryPurpose.CAPTURE,
            sections = sections,
        )
        // Organization edits select retained evidence; they cannot clip or prorate a count.
        selectOrganizationInterval(listOf(original), annotationStart, annotationEnd,
            rowMembers.associate { it.segment.id to (it.segment.startTimeMs to it.segment.endTimeMs) })
    } else {
        OrganizationIntervalSections(sections, emptySet())
    }
}

private fun nativeLocationSections(
    candidates: List<UnifiedHistoryCandidate>, probe: List<LocationPathSampleRow>, fileImportedIds: Set<Long>,
    purposes: Map<Long, List<LocationSamplePurposeAuthorityEntity>>, qualified: RetainedLocationSavedSnapshot,
): Map<String, List<HistorySection.Location>> {
    val qualifiedBySample = qualified.samples.filter { it.scope is RadioContextScope.Session }.groupBy { it.sample.id }
    return candidates.associate { candidate ->
        val points = probe.filter { it.matchesNativeCandidate(candidate, fileImportedIds, purposes) }
            .mapNotNull { nativeLocationPoint(it, candidate, qualifiedBySample) }
        candidate.identity to points.groupBy { it.second }.map { (coverage, values) ->
            HistorySection.Location(values.map { it.first }, coverage)
        }
    }
}

private fun LocationPathSampleRow.matchesNativeCandidate(
    candidate: UnifiedHistoryCandidate, fileImportedIds: Set<Long>,
    purposes: Map<Long, List<LocationSamplePurposeAuthorityEntity>>,
): Boolean = if (candidate.category == "legacy") {
    sample.timeMs >= candidate.startTimeMs && sample.timeMs <= (candidate.endTimeMs ?: Long.MAX_VALUE) &&
        !ambientOnly && sample.id !in fileImportedIds
} else {
    purposes[sample.id].orEmpty().any { it.logicalTrackingId == candidate.identity.removePrefix("logical:") }
}

private fun nativeLocationPoint(
    point: LocationPathSampleRow, candidate: UnifiedHistoryCandidate,
    qualifiedBySample: Map<Long, List<QualifiedRetainedLocationSample>>,
): Pair<HistoryLocationPoint, HistoryLocationCoverage>? {
    val sample = point.sample
    val latitude = sample.latE7?.takeIf { it in -900_000_000..900_000_000 } ?: return null
    val longitude = sample.lonE7?.takeIf { it in -1_800_000_000..1_800_000_000 } ?: return null
    val publicPoint = HistoryLocationPoint(sample.timeMs, latitude, longitude,
        sample.hAccM?.takeIf { it.isFinite() && it >= 0f }, point.continuityId, sample.id)
    val nativeProof = candidate.category == "native" && qualifiedBySample[sample.id].orEmpty().any {
        val owner = (it.scope as RadioContextScope.Session).owner
        owner.logicalTrackingId == candidate.identity.removePrefix("logical:") && it.sample.timeMs == sample.timeMs &&
            it.sample.latE7 == latitude && it.sample.lonE7 == longitude
    }
    return publicPoint to if (nativeProof) { HistoryLocationCoverage.QUALIFIED } else {
        HistoryLocationCoverage.LEGACY_UNVERIFIABLE
    }
}

private data class NativeHistorySections(val sections: Map<String, HistorySection>,
    val problem: HistoryProblemReason?)
private data class NativeHistoryLocationSections(val sections: Map<String, List<HistorySection.Location>>,
    val problem: HistoryProblemReason?)
