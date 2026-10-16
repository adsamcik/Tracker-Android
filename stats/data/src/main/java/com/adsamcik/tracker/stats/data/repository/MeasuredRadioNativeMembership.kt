package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedCellProductFailure
import com.adsamcik.tracker.shared.base.database.RadioSelectedFileMember
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun requireMeasuredWifiReadable(
    evaluation: ImportedWifiProductEvaluation,
): ImportedWifiProductEvaluation.Readable =
    evaluation as? ImportedWifiProductEvaluation.Readable ?: throw MeasuredRadioParentFailure(
        if ((evaluation as? ImportedWifiProductEvaluation.Unverifiable)?.reason ==
            ImportedWifiProductFailure.DEPENDENCY_OVERFLOW) {
            HistoryProblemReason.DEPENDENCY_OVERFLOW
        } else {
            HistoryProblemReason.INTEGRITY_UNAVAILABLE
        })

internal fun requireMeasuredCellReadable(
    evaluation: ImportedCellProductEvaluation,
): ImportedCellProductEvaluation.Readable =
    evaluation as? ImportedCellProductEvaluation.Readable ?: throw MeasuredRadioParentFailure(
        if ((evaluation as? ImportedCellProductEvaluation.Unverifiable)?.reason ==
            ImportedCellProductFailure.DEPENDENCY_OVERFLOW) {
            HistoryProblemReason.DEPENDENCY_OVERFLOW
        } else {
            HistoryProblemReason.INTEGRITY_UNAVAILABLE
        })

internal fun measuredRadioMembershipProblem(
    source: HistorySource,
    failure: Exception,
) = HistoryProblem(source, when (failure) {
    is MeasuredRadioParentFailure -> failure.reason
    is android.database.sqlite.SQLiteException -> HistoryProblemReason.STORAGE_UNAVAILABLE
    else -> HistoryProblemReason.INTEGRITY_UNAVAILABLE
})

internal suspend fun <T> optionalMeasuredNativeMatch(compare: suspend () -> T?): T? = try { compare() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { null }

internal fun addMeasuredCapturedRadioRow(
    key: MeasuredHistoryCandidate,
    measurement: HistoryMeasurement,
    range: HistoryRange,
    output: MutableMap<HistoryPosition, UnifiedHistoryRow>,
) {
    val hasLastObservation = measurement is HistoryMeasurement.WifiSummary ||
        measurement is HistoryMeasurement.CellSummary
    check(key.startTimeMs >= 0L && key.startTimeMs < range.toExclusiveMs &&
        (key.startTimeMs >= range.fromInclusiveMs || key.endTimeMs?.let {
            it > range.fromInclusiveMs || hasLastObservation && it == range.fromInclusiveMs
        } == true))
    val position = measuredHistoryPosition(key)
    output[position] = UnifiedHistoryRow(position, key.endTimeMs, setOf(HistoryOrigin.valueOf(key.origin)),
        HistoryPurpose.CAPTURE, sections = emptyList(), measurement = measurement)
}

/** Parent and item identity are checked before this exact measured-value comparison. */
internal fun WifiCapturedChildEntity.hasSameMeasuredValues(other: ImportedWifiChildEntity): Boolean {
    if (providerTimestampNanos != other.providerTimestampNanos ||
        observedWallTimeMs != other.observedWallTimeMs || wallTimeUncertaintyMs != other.wallTimeUncertaintyMs
    ) {
        return false
    }
    return bssid == other.bssid && ssid == other.ssid && frequencyMhz == other.frequencyMhz &&
        signalDbm == other.signalDbm
}

/** Parent and item identity are checked before this exact measured-value comparison. */
internal fun CellCapturedChildEntity.hasSameMeasuredValues(other: ImportedCellChildEntity): Boolean {
    if (providerTimestampNanos != other.providerTimestampNanos ||
        observedWallTimeMs != other.observedWallTimeMs || wallTimeUncertaintyMs != other.wallTimeUncertaintyMs
    ) {
        return false
    }
    return towerIdentity == other.towerIdentity && radioType == other.radioType &&
        registered == other.registered && signalDbm == other.signalDbm
}

internal suspend fun DefaultWifiHistoryRepository.cachedMeasuredWifiImport(
    identity: String,
    cache: MutableMap<String, ImportedWifiProductEvaluation?>,
): ImportedWifiProductEvaluation? = if (identity in cache) {
    cache[identity]
} else {
    measuredImportedEvaluationInTransaction(identity).also { cache[identity] = it }
}

internal suspend fun DefaultWifiHistoryRepository.cachedMeasuredWifiFact(
    factId: String,
    revision: Long,
    cache: MutableMap<Pair<String, Long>, QualifiedMeasuredWifiFact?>,
): QualifiedMeasuredWifiFact? {
    val key = factId to revision
    return if (key in cache) {
        cache[key]
    } else {
        measuredFactInTransaction(factId, revision).also { cache[key] = it }
    }
}

internal suspend fun DefaultCellHistoryRepository.cachedMeasuredCellImport(
    identity: String,
    cache: MutableMap<String, ImportedCellProductEvaluation?>,
): ImportedCellProductEvaluation? = if (identity in cache) {
    cache[identity]
} else {
    measuredImportedEvaluationInTransaction(identity).also { cache[identity] = it }
}

internal suspend fun DefaultCellHistoryRepository.cachedMeasuredCellFact(
    factId: String,
    revision: Long,
    cache: MutableMap<Pair<String, Long>, QualifiedMeasuredCellFact?>,
): QualifiedMeasuredCellFact? {
    val key = factId to revision
    return if (key in cache) {
        cache[key]
    } else {
        measuredFactInTransaction(factId, revision).also { cache[key] = it }
    }
}

// Resolve the original immutable native identity tuple within the current snapshot.
@Suppress("LongParameterList")
internal suspend fun AppDatabase.measuredNativeRadioCapturePosition(
    source: HistorySource,
    selected: List<MeasuredHistoryCandidate>,
    writerId: String,
    writerVersion: Int,
    factId: String,
    revision: Long,
    itemIndex: Int,
): HistoryPosition? {
    val identities = selected.map { requireNotNull(it.physicalIdentity) }.distinct()
    val times = selected.map { it.startTimeMs }.distinct()
    var after: MeasuredHistoryCandidate? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        val batch = if (source == HistorySource.WIFI) {
            radioObservationChildDao().wifiNativeMeasuredPurposeCandidates(
                identities, listOf("CAPTURE"), times, after?.kind, after?.identity,
                MAX_MEASURED_CAPTURED_RADIO_CANDIDATES + 1,
            )
        }
        else {
            radioObservationChildDao().cellNativeMeasuredPurposeCandidates(
                identities, listOf("CAPTURE"), times, after?.kind, after?.identity,
                MAX_MEASURED_CAPTURED_RADIO_CANDIDATES + 1,
            )
        }
        val consumed = batch.take(MAX_MEASURED_CAPTURED_RADIO_CANDIDATES)
        consumed.firstOrNull { it.matchesMeasuredRadioCaptureIdentity(
            writerId, writerVersion, factId, revision, itemIndex,
        ) }
            ?.let { return measuredHistoryPosition(it) }
        if (batch.size <= MAX_MEASURED_CAPTURED_RADIO_CANDIDATES) {
            return null
        }
        val next = consumed.last()
        check(after == null || next.kind > after.kind || next.kind == after.kind && next.identity > after.identity)
        after = next
    }
}

private fun MeasuredHistoryCandidate.matchesMeasuredRadioCaptureIdentity(
    expectedWriterId: String,
    expectedWriterVersion: Int,
    expectedFactId: String,
    expectedRevision: Long,
    expectedItemIndex: Int,
): Boolean {
    if (writerId != expectedWriterId || writerVersion != expectedWriterVersion) {
        return false
    }
    return factId == expectedFactId && factRevision == expectedRevision && itemIndex == expectedItemIndex
}

internal fun MeasuredHistoryCandidate.isMeasuredNativeRadioLeaf(source: HistorySource): Boolean {
    if (origin != "LOCAL" || physicalIdentity == null) {
        return false
    }
    return source == HistorySource.WIFI && kind in setOf("captured_wifi", "ambient_wifi") ||
        source == HistorySource.CELL && kind in setOf("captured_cell", "ambient_cell")
}

internal fun Map<String, RadioSelectedFileMember>?.matchesMeasuredRadioFileMember(
    identity: String,
    revision: Long,
    checksum: String,
): Boolean {
    if (this == null) {
        return true
    }
    val member = this[identity] ?: return false
    return member.revision == revision && member.checksum == checksum
}

internal fun List<MeasuredHistoryCandidate>.importedRadioMembershipPositions(
    entryIdentity: String,
    importRevision: Long,
    observationIdentity: String,
): List<HistoryPosition> = filter {
    it.origin == "IMPORTED" && it.writerId == entryIdentity &&
        it.factRevision == importRevision && it.factId == observationIdentity
}.map(::measuredHistoryPosition)

internal fun List<MeasuredHistoryCandidate>.containsSelectedMeasuredRadioTime(
    coverageStartTimeMs: Long,
    latestPossibleTimeMs: Long,
): Boolean = any { it.startTimeMs >= coverageStartTimeMs && it.startTimeMs <= latestPossibleTimeMs }

internal const val MAX_MEASURED_CAPTURED_RADIO_CANDIDATES = 100
