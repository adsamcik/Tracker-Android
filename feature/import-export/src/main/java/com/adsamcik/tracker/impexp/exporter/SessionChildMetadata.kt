package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.CellSourceFileV2
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableJsonBytes
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.WifiSourceFileV2
import com.adsamcik.tracker.impexp.portable.portableStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.portableAmbientStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.readPortableBytes
import com.adsamcik.tracker.stats.api.repository.HistorySource
import java.io.File

internal data class SessionChildMetadata(val formatId: String, val version: Int, val fromMs: Long, val toMs: Long)

/** Original typed measurement bounds, never a fabricated timestamp or a duration inferred from gaps. */
internal suspend fun describeSessionChild(source: HistorySource, file: File, name: String): SessionChildMetadata {
    val bounds = SessionChildBounds()
    return when (source) {
        HistorySource.LOCATION -> {
            bounds.includeLocation(file)
            bounds.metadata(LocationFileJsonV1.FORMAT)
        }
        HistorySource.STEPS -> {
            val bytes = file.inputStream().use { readPortableBytes(it, file.length()) }
            bounds.describeSteps(bytes, name)
        }
        HistorySource.ACTIVITY -> {
            bounds.includeActivity(file)
            bounds.metadata("portable-activity-v1")
        }
        HistorySource.PRESSURE -> {
            bounds.includePressure(file)
            bounds.metadata("portable-pressure-v1")
        }
        HistorySource.WIFI -> {
            val data = file.inputStream().use { RadioSourceFileJsonV2Codec().readWifi(it) }
            val version = data.schemaVersion
            bounds.includeWifi(data)
            bounds.metadata(RadioSourceFileV2.WIFI_FORMAT, version)
        }
        HistorySource.CELL -> {
            val data = file.inputStream().use { RadioSourceFileJsonV2Codec().readCell(it) }
            val version = data.schemaVersion
            bounds.includeCell(data)
            bounds.metadata(RadioSourceFileV2.CELL_FORMAT, version)
        }
    }
}

private suspend fun SessionChildBounds.includeLocation(file: File) {
    file.inputStream().use {
        LocationFileJsonV1().read(it) { point -> include(point.measurement.timeMs) }
    }
}

private suspend fun SessionChildBounds.includeActivity(file: File) {
    file.inputStream().use { input ->
        PortableActivityJsonV1Codec().decode(input).entries.forEach {
            include(it.startTimeMs, it.endTimeMs)
        }
    }
}

private suspend fun SessionChildBounds.includePressure(file: File) {
    file.inputStream().use { input ->
        PortablePressureJsonV1Codec().decode(input) { include(it.startTimeMs, it.endTimeMs) }
    }
}

private suspend fun SessionChildBounds.describeSteps(bytes: PortableJsonBytes, name: String): SessionChildMetadata =
    if (name != "steps") {
        val version = portableAmbientStepsSchemaVersion(bytes)
        if (version == 1) {
            PortableAmbientStepsJsonV1Codec().decode(bytes).archive.days.forEach {
                include(it.structuralDayStartTimeMs, it.structuralDayEndTimeMs)
            }
        } else {
            PortableAmbientStepsJsonV2Codec().decode(bytes).archive.days.forEach {
                include(it.product.structuralDayStartTimeMs, it.product.structuralDayEndTimeMs)
            }
        }
        metadata(PortableAmbientStepsExporter.FORMAT_ID, version)
    } else {
        val version = portableStepsSchemaVersion(bytes)
        if (version == 1) {
            PortableStepsJsonV1Codec().decode(bytes) { include(it.startTimeMs, it.endTimeMs) }
        } else {
            PortableStepsJsonV2Codec().decode(bytes).archive.entries.forEach {
                include(it.product.startTimeMs, it.product.endTimeMs)
            }
        }
        metadata("portable-steps-v1", version)
    }

private fun SessionChildBounds.includeWifi(data: WifiSourceFileV2) {
    data.sessions.forEach { session ->
        val childrenByObservation = session.children.groupBy { it.observationIdentity }
        session.entry.runs.forEach { run ->
            if (run.observations.isEmpty()) {
                include(run.startTimeMs, run.endTimeMs)
            }
            run.observations.forEach { observation ->
                val children = childrenByObservation[observation.identity.value].orEmpty()
                includeReadingsOrCoverage(children, observation.coverageStartTimeMs, observation.observedTimeMs) {
                    it.observedWallTimeMs
                }
            }
        }
    }
    val backgroundChildren = data.backgroundChildren.groupBy { it.factIdentity to it.semanticRevision }
    data.background?.facts.orEmpty().forEach { fact ->
        val children = backgroundChildren[fact.identity to fact.semanticRevision].orEmpty()
        includeReadingsOrCoverage(children, fact.coverageStartTimeMs, fact.observedTimeMs) { it.observedWallTimeMs }
    }
    data.background?.gaps.orEmpty().forEach { include(it.startTimeMs, it.endTimeMs) }
}

private fun SessionChildBounds.includeCell(data: CellSourceFileV2) {
    data.sessions.forEach { session ->
        val childrenByObservation = session.children.groupBy { it.observationIdentity }
        session.entry.runs.forEach { run ->
            if (run.observations.isEmpty()) {
                include(run.startTimeMs, run.endTimeMs)
            }
            run.observations.forEach { observation ->
                val children = childrenByObservation[observation.identity.value].orEmpty()
                includeReadingsOrCoverage(children, observation.coverageStartTimeMs, observation.observedTimeMs) {
                    it.observedWallTimeMs
                }
            }
        }
    }
    val backgroundChildren = data.backgroundChildren.groupBy { it.factIdentity to it.semanticRevision }
    data.background?.facts.orEmpty().forEach { fact ->
        val children = backgroundChildren[fact.identity to fact.semanticRevision].orEmpty()
        includeReadingsOrCoverage(children, fact.coverageStartTimeMs, fact.observedTimeMs) { it.observedWallTimeMs }
    }
    data.background?.gaps.orEmpty().forEach { include(it.startTimeMs, it.endTimeMs) }
}

private class SessionChildBounds {
    private var first = Long.MAX_VALUE
    private var last = Long.MIN_VALUE

    fun include(from: Long, to: Long = from) {
        require(from >= 0L && to >= 0L)
        first = minOf(first, from, to)
        last = maxOf(last, from, to)
    }

    fun <T> includeReadingsOrCoverage(readings: List<T>, from: Long, to: Long, time: (T) -> Long) {
        if (readings.isEmpty()) {
            include(from, to)
        } else {
            readings.forEach { include(time(it)) }
        }
    }

    fun metadata(formatId: String, version: Int = 1): SessionChildMetadata {
        require(first != Long.MAX_VALUE && last != Long.MIN_VALUE)
        return SessionChildMetadata(formatId, version, first, last)
    }
}
