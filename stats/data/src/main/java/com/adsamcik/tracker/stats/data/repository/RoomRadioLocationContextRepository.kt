package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.*
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Source qualification and optional Location qualification share exactly one read-only snapshot. */
internal class RoomRadioLocationContextRepository @Inject constructor(
    private val database: AppDatabase,
    private val wifiHistory: DefaultWifiHistoryRepository,
    private val cellHistory: DefaultCellHistoryRepository,
    private val ambientWifi: RoomAmbientWifiRepository,
    private val ambientCell: RoomAmbientCellRepository,
    private val location: RetainedLocationContextReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RadioLocationContextRepository {
    override suspend fun read(request: RadioLocationContextRequest): RadioLocationContextReadResult = withContext(io) {
        try {
            database.withTransaction { readInTransaction(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RadioLocationContextReadResult.StorageUnavailable
        }
    }

    internal suspend fun readInTransaction(request: RadioLocationContextRequest): RadioLocationContextReadResult {
        val gaps = mutableSetOf<RadioContextReadGap>()
        val captured = if (request.source == RadioContextSource.WIFI) capturedWifi(request, gaps) else capturedCell(request, gaps)
        val ambient = if (request.source == RadioContextSource.WIFI) ambientWifi(request, gaps) else ambientCell(request, gaps)
        val radios = consolidateRetainedRadioGroups(captured + ambient, gaps)
        if (radios.size > request.limit) return RadioLocationContextReadResult.DependencyLimit
        val context = location.readInTransaction(radios.map { it.input })
        val matches = RadioLocationContextResolver.resolve(radios.map { it.input }, context.points)
            .associateBy { it.radioSourceEventId to it.scope }
        // Resolve each purpose separately: one physical event may have two different valid grants.
        val observations = radios.map { radio ->
            val match = matches[radio.input.sourceEventId to radio.input.scope]
            val optional = match?.let {
                OptionalRetainedLocationContext.Located(RetainedLocationContext(it.locationSourceEventId,
                    (it.latitude * 10_000_000.0).roundToInt(), (it.longitude * 10_000_000.0).roundToInt(),
                    it.locationObservedWallTimeMs, it.locationWallTimeUncertaintyMs,
                    it.horizontalAccuracyMeters, it.separationMillis))
            } ?: OptionalRetainedLocationContext.Unlocated(context.gaps.firstOrNull {
                it == RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT
            } ?: context.gaps.firstOrNull() ?: RadioLocationContextGap.NO_COMPATIBLE_LOCATION)
            RadioContextObservation(radio.input.sourceEventId, radio.purpose, radio.intervalStartMs,
                radio.input.observedWallTimeMs, radio.input.wallTimeUncertaintyMs, radio.quality, optional)
        }
        return RadioLocationContextReadResult.Ready(request.source, observations, gaps)
    }

    private suspend fun capturedWifi(request: RadioLocationContextRequest, gaps: MutableSet<RadioContextReadGap>): List<QualifiedRetainedRadio> {
        val facts = database.wifiCapturedFactDao().contextCandidates(request.fromInclusiveMs, request.toExclusiveMs, request.limit + 1)
        if (facts.size > request.limit) return gap(gaps, RadioContextPurpose.CAPTURE, RadioContextReadGapReason.DEPENDENCY_LIMIT)
        if (facts.isEmpty()) return emptyList()
        val segments = facts.map { it.sessionSegmentId }.distinct()
        if (segments.size > MAX_CONTEXT_SEGMENTS) return gap(gaps, RadioContextPurpose.CAPTURE, RadioContextReadGapReason.DEPENDENCY_LIMIT)
        val page = wifiHistory.selectBySegmentIdsInTransaction(segments)
        if (page !is WifiComposedPage.Available) return gap(gaps, RadioContextPurpose.CAPTURE, RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val factsByEvent = facts.groupBy { it.sourceEventId to it.logicalTrackingId }
        val qualified = mutableListOf<QualifiedRetainedRadio>()
        for (group in page.entries) {
            if (group.entry.causes.any { it.isIntegrityFailure }) {
                gaps += RadioContextReadGap(RadioContextPurpose.CAPTURE, RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
                continue
            }
            for (observation in group.entry.observations) {
                val event = observation.localSourceEventId ?: continue
                for (fact in factsByEvent[event to group.logicalTrackingId].orEmpty()) {
                    if (fact.observedWallTimeMs != observation.observedTime.raw || fact.wallTimeUncertaintyMs != observation.wallTimeUncertaintyMs) continue
                    qualified += QualifiedRetainedRadio(RadioContextPurpose.CAPTURE,
                        RadioContextInput(RadioContextSource.WIFI, event, fact.contextScope(), fact.coverageIntervalEndNanos,
                            fact.observedWallTimeMs, true, fact.coverageIntervalStartNanos, fact.wallTimeUncertaintyMs),
                        observation.intervalStartTime.raw, RadioContextSignalQuality.Wifi(observation.signalQuality),
                        fact.aggregateOwnerLogicalFactId ?: fact.logicalFactId)
                }
            }
        }
        if (facts.any { fact -> qualified.none { it.input.sourceEventId == fact.sourceEventId } }) {
            gaps += RadioContextReadGap(RadioContextPurpose.CAPTURE, RadioContextReadGapReason.MATERIALIZATION_BEHIND)
        }
        return qualified
    }

    private suspend fun capturedCell(request: RadioLocationContextRequest, gaps: MutableSet<RadioContextReadGap>): List<QualifiedRetainedRadio> {
        val facts = database.cellCapturedFactDao().contextCandidates(request.fromInclusiveMs, request.toExclusiveMs, request.limit + 1)
        if (facts.size > request.limit) return gap(gaps, RadioContextPurpose.CAPTURE, RadioContextReadGapReason.DEPENDENCY_LIMIT)
        if (facts.isEmpty()) return emptyList()
        val segments = facts.map { it.sessionSegmentId }.distinct()
        if (segments.size > MAX_CONTEXT_SEGMENTS) return gap(gaps, RadioContextPurpose.CAPTURE, RadioContextReadGapReason.DEPENDENCY_LIMIT)
        val page = cellHistory.selectBySegmentIdsInTransaction(segments)
        if (page !is CellComposedPage.Available) return gap(gaps, RadioContextPurpose.CAPTURE, RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val factsByEvent = facts.groupBy { it.sourceEventId to it.logicalTrackingId }
        val qualified = mutableListOf<QualifiedRetainedRadio>()
        for (group in page.entries) {
            if (group.entry.causes.any { it.isIntegrityFailure }) {
                gaps += RadioContextReadGap(RadioContextPurpose.CAPTURE, RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
                continue
            }
            for (observation in group.entry.observations) {
                val event = observation.localSourceEventId ?: continue
                for (fact in factsByEvent[event to group.logicalTrackingId].orEmpty()) {
                    if (fact.observedWallTimeMs != observation.observedTime.raw || fact.wallTimeUncertaintyMs != observation.wallTimeUncertaintyMs) continue
                    qualified += QualifiedRetainedRadio(RadioContextPurpose.CAPTURE,
                        RadioContextInput(RadioContextSource.CELL, event, fact.contextScope(), fact.coverageIntervalEndNanos,
                            fact.observedWallTimeMs, true, fact.coverageIntervalStartNanos, fact.wallTimeUncertaintyMs),
                        observation.intervalStartTime.raw, RadioContextSignalQuality.Cell(observation.signalQuality),
                        fact.aggregateOwnerLogicalFactId ?: fact.logicalFactId)
                }
            }
        }
        if (facts.any { fact -> qualified.none { it.input.sourceEventId == fact.sourceEventId } }) {
            gaps += RadioContextReadGap(RadioContextPurpose.CAPTURE, RadioContextReadGapReason.MATERIALIZATION_BEHIND)
        }
        return qualified
    }

    private suspend fun ambientWifi(request: RadioLocationContextRequest, gaps: MutableSet<RadioContextReadGap>): List<QualifiedRetainedRadio> {
        val qualified = ambientWifi.readQualifiedInTransaction(AmbientWifiReadRequest(request.fromInclusiveMs,
            request.toExclusiveMs, setOf(AmbientWifiOrigin.LOCAL_DEVICE), request.limit))
        gaps += qualified.readGaps.map { RadioContextReadGap(RadioContextPurpose.AMBIENT, it) }
        val read = qualified.result
        if (read !is AmbientWifiReadResult.Snapshot) return gap(gaps, RadioContextPurpose.AMBIENT,
            if (read == AmbientWifiReadResult.DependencyOverflow) RadioContextReadGapReason.DEPENDENCY_LIMIT else RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        if (read.gaps.isNotEmpty()) gaps += RadioContextReadGap(RadioContextPurpose.AMBIENT, RadioContextReadGapReason.ACQUISITION_PARTIAL)
        val rows = qualified.nativeRows
        val factsByIdentity = read.facts.associateBy { it.identity to it.semanticRevision }
        return rows.mapNotNull { row ->
            val fact = row.fact
            val api = factsByIdentity[fact.logicalFactId to fact.semanticRevision] ?: return@mapNotNull null
            QualifiedRetainedRadio(RadioContextPurpose.AMBIENT,
                RadioContextInput(RadioContextSource.WIFI, fact.sourceEventId, fact.contextScope(), fact.observedElapsedNanos,
                    fact.observedWallTimeMs, true, fact.observedIntervalStartNanos, fact.wallTimeUncertaintyMs), api.coverageStartTimeMs,
                RadioContextSignalQuality.Wifi(WifiHistorySignalQuality(api.strongestSignalDbm, api.weakestSignalDbm,
                    api.meanSignalDbm, api.observationCount)), fact.aggregateOwnerLogicalFactId ?: fact.logicalFactId)
        }
    }

    private suspend fun ambientCell(request: RadioLocationContextRequest, gaps: MutableSet<RadioContextReadGap>): List<QualifiedRetainedRadio> {
        val qualified = ambientCell.readQualifiedInTransaction(AmbientCellReadRequest(request.fromInclusiveMs,
            request.toExclusiveMs, setOf(AmbientCellOrigin.LOCAL_DEVICE), request.limit))
        gaps += qualified.readGaps.map { RadioContextReadGap(RadioContextPurpose.AMBIENT, it) }
        val read = qualified.result
        if (read !is AmbientCellReadResult.Snapshot) return gap(gaps, RadioContextPurpose.AMBIENT,
            if (read == AmbientCellReadResult.DependencyOverflow) RadioContextReadGapReason.DEPENDENCY_LIMIT else RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        if (read.gaps.isNotEmpty()) gaps += RadioContextReadGap(RadioContextPurpose.AMBIENT, RadioContextReadGapReason.ACQUISITION_PARTIAL)
        val rows = qualified.nativeRows
        val factsByIdentity = read.facts.associateBy { it.identity to it.semanticRevision }
        return rows.mapNotNull { row ->
            val fact = row.fact
            val api = factsByIdentity[fact.logicalFactId to fact.semanticRevision] ?: return@mapNotNull null
            val quality = api.qualityDistribution
            QualifiedRetainedRadio(RadioContextPurpose.AMBIENT,
                RadioContextInput(RadioContextSource.CELL, fact.sourceEventId, fact.contextScope(), fact.observedElapsedNanos,
                    fact.observedWallTimeMs, true, fact.observedIntervalStartNanos, fact.wallTimeUncertaintyMs), api.coverageStartTimeMs,
                RadioContextSignalQuality.Cell(CellHistorySignalQuality(quality.unknownCount, quality.noneOrUnknownCount,
                    quality.poorCount, quality.moderateCount, quality.goodCount, quality.greatCount)),
                fact.aggregateOwnerLogicalFactId ?: fact.logicalFactId)
        }
    }
}
internal data class QualifiedRetainedRadio(val purpose: RadioContextPurpose, val input: RadioContextInput,
    val intervalStartMs: Long, val quality: RadioContextSignalQuality, val aggregateIdentity: String)

private fun WifiCapturedFactRevisionEntity.contextScope() = RadioContextScope.Session(SessionContextScope(
    logicalTrackingId, serviceRunId, sessionSegmentId, clockDomainId, clockDomainId, collectedDataEpoch))
private fun CellCapturedFactRevisionEntity.contextScope() = RadioContextScope.Session(SessionContextScope(
    logicalTrackingId, serviceRunId, sessionSegmentId, clockDomainId, clockDomainId, collectedDataEpoch))
private fun AmbientWifiFactRevisionEntity.contextScope() = RadioContextScope.Ambient(AmbientContextScope(clockDomainId, clockDomainId, collectedDataEpoch))
private fun AmbientCellFactRevisionEntity.contextScope() = RadioContextScope.Ambient(AmbientContextScope(clockDomainId, clockDomainId, collectedDataEpoch))
private fun gap(gaps: MutableSet<RadioContextReadGap>, purpose: RadioContextPurpose, reason: RadioContextReadGapReason): List<QualifiedRetainedRadio> {
    gaps += RadioContextReadGap(purpose, reason)
    return emptyList()
}
private const val MAX_CONTEXT_SEGMENTS = 100
