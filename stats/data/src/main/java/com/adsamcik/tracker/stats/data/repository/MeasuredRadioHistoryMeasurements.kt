package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellAcquisitionCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableCellChildCompleteness
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.CellObservedTower
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork

/** Internal tuple encoder mirrors SQLite discovery; it is never decoded into an action target. */
private fun framed(vararg parts: Any) = parts.joinToString(":") { it.toString().let { value ->
    "${value.length}:$value" } }

private fun WifiCapturedChildEntity.toMeasuredNetwork() = WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
    bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty), frequencyMhz, signalDbm)
private fun ImportedWifiChildEntity.toMeasuredNetwork() = WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
    bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty), frequencyMhz, signalDbm)
private fun CellCapturedChildEntity.toMeasuredTower() = CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
    towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType), registered, signalDbm)
private fun ImportedCellChildEntity.toMeasuredTower() = CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
    towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType), registered, signalDbm)

internal fun QualifiedMeasuredWifiFact.measuredWifiMeasurement(
    key: MeasuredHistoryCandidate,
    id: String,
    revision: Long,
): HistoryMeasurement {
    val fact = this.fact
    check(key.writerId == fact.writerProjectionId && key.writerVersion == fact.writerProjectionVersion)
    return if (key.kind == "captured_wifi_summary") {
        check(key.itemIndex == null && children.isEmpty() &&
            key.identity == framed(fact.writerProjectionId, fact.writerProjectionVersion, id, revision) &&
            key.startTimeMs == observation.intervalStartTime.raw &&
                key.endTimeMs == observation.observedTime.raw)
        HistoryMeasurement.WifiSummary(observation)
    } else {
        check(key.kind == "captured_wifi" && key.endTimeMs == null)
        val child = requireNotNull(children.singleOrNull { it.itemIndex == key.itemIndex })
        check(key.identity == framed(fact.writerProjectionId, fact.writerProjectionVersion,
            id, revision, child.itemIndex) &&
            key.startTimeMs == child.observedWallTimeMs)
        HistoryMeasurement.Wifi(child.toMeasuredNetwork(), partial)
    }
}

internal fun QualifiedMeasuredCellFact.measuredCellMeasurement(
    key: MeasuredHistoryCandidate,
    id: String,
    revision: Long,
): HistoryMeasurement {
    val fact = this.fact
    check(key.writerId == fact.writerProjectionId && key.writerVersion == fact.writerProjectionVersion)
    return if (key.kind == "captured_cell_summary") {
        check(key.itemIndex == null && children.isEmpty() &&
            key.identity == framed(fact.writerProjectionId, fact.writerProjectionVersion, id, revision) &&
            key.startTimeMs == observation.intervalStartTime.raw &&
                key.endTimeMs == observation.observedTime.raw)
        HistoryMeasurement.CellSummary(observation)
    } else {
        check(key.kind == "captured_cell" && key.endTimeMs == null)
        val child = requireNotNull(children.singleOrNull { it.itemIndex == key.itemIndex })
        check(key.identity == framed(fact.writerProjectionId, fact.writerProjectionVersion,
            id, revision, child.itemIndex) &&
            key.startTimeMs == child.observedWallTimeMs)
        HistoryMeasurement.Cell(child.toMeasuredTower(), partial)
    }
}

internal fun PortableCapturedWifiObservationV1.measuredImportedWifiMeasurement(
    selected: MeasuredHistoryCandidate,
    readable: ImportedWifiProductEvaluation.Readable,
    runIdentity: String,
    children: List<ImportedWifiChildEntity>,
    childrenByIndex: Map<Int, List<ImportedWifiChildEntity>>,
    partial: Boolean,
): HistoryMeasurement {
    return if (selected.kind == "imported_wifi_summary") {
        check(children.isEmpty() && selected.itemIndex == null &&
            selected.identity == framed(readable.entry.identity.value,
                readable.candidate.importRevision, runIdentity, identity.value) &&
            selected.startTimeMs == coverageStartTimeMs &&
                selected.endTimeMs == observedTimeMs)
        HistoryMeasurement.WifiSummary(toPublicObservation())
    } else {
        check(selected.kind == "imported_wifi" && selected.endTimeMs == null)
        val child = requireNotNull(childrenByIndex[selected.itemIndex]?.singleOrNull())
        check(selected.identity == framed(child.entryIdentity, child.entryImportRevision,
            child.runIdentity, child.observationIdentity, child.itemIndex) &&
            selected.startTimeMs == child.observedWallTimeMs)
        HistoryMeasurement.Wifi(child.toMeasuredNetwork(), partial)
    }
}

internal fun PortableCapturedWifiRunV1.hasPartialMeasuredWifiCoverage(
    readable: ImportedWifiProductEvaluation.Readable,
    observation: PortableCapturedWifiObservationV1,
): Boolean {
    if (readable.retentionLimited || retentionLoss) {
        return true
    }
    return captureCoverage != PortableWifiCaptureCoverage.WHOLE_RUN ||
        acquisitionCompleteness != PortableWifiAcquisitionCompleteness.COMPLETE ||
        hasUnresolvedProviderRange ||
        observation.resultCompleteness != PortableWifiResultCompleteness.COMPLETE
}

internal fun PortableCapturedCellObservationV1.measuredImportedCellMeasurement(
    selected: MeasuredHistoryCandidate,
    readable: ImportedCellProductEvaluation.Readable,
    runIdentity: String,
    children: List<ImportedCellChildEntity>,
    childrenByIndex: Map<Int, List<ImportedCellChildEntity>>,
    partial: Boolean,
): HistoryMeasurement {
    return if (selected.kind == "imported_cell_summary") {
        check(children.isEmpty() && selected.itemIndex == null &&
            selected.identity == framed(readable.entry.identity.value,
                readable.candidate.importRevision, runIdentity, identity.value) &&
            selected.startTimeMs == coverageStartTimeMs &&
                selected.endTimeMs == observedTimeMs)
        HistoryMeasurement.CellSummary(toPublicObservation())
    } else {
        check(selected.kind == "imported_cell" && selected.endTimeMs == null)
        val child = requireNotNull(childrenByIndex[selected.itemIndex]?.singleOrNull())
        check(selected.identity == framed(child.entryIdentity, child.entryImportRevision,
            child.runIdentity, child.observationIdentity, child.itemIndex) &&
            selected.startTimeMs == child.observedWallTimeMs)
        HistoryMeasurement.Cell(child.toMeasuredTower(), partial)
    }
}

internal fun PortableCapturedCellRunV1.hasPartialMeasuredCellCoverage(
    readable: ImportedCellProductEvaluation.Readable,
    observation: PortableCapturedCellObservationV1,
): Boolean {
    if (readable.retentionLimited || retentionLoss) {
        return true
    }
    return captureCoverage != PortableCellCaptureCoverage.WHOLE_RUN ||
        acquisitionCompleteness != PortableCellAcquisitionCompleteness.COMPLETE ||
        observation.childCompleteness != PortableCellChildCompleteness.COMPLETE
}
