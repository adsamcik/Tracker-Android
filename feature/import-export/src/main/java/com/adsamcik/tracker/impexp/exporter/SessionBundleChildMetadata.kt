package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.portableStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.portableAmbientStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.readPortableBytes
import com.adsamcik.tracker.stats.api.repository.HistorySource
import java.io.File

internal data class SessionChildMetadata(val formatId: String, val version: Int, val fromMs: Long, val toMs: Long)

/** Original typed measurement bounds, never a fabricated timestamp or a duration inferred from gaps. */
internal suspend fun describeSessionChild(source: HistorySource, file: File, name: String): SessionChildMetadata {
    var first = Long.MAX_VALUE
    var last = Long.MIN_VALUE
    fun include(from: Long, to: Long = from) {
        require(from >= 0L && to >= 0L)
        first = minOf(first, from, to); last = maxOf(last, from, to)
    }
    var version = 1
    val format = when (source) {
        HistorySource.LOCATION -> {
            file.inputStream().use { LocationFileJsonV1().read(it) { point -> include(point.measurement.timeMs) } }
            LocationFileJsonV1.FORMAT
        }
        HistorySource.STEPS -> {
            val bytes = file.inputStream().use { readPortableBytes(it, file.length()) }
            if (name != "steps") {
                version = portableAmbientStepsSchemaVersion(bytes)
                if (version == 1) PortableAmbientStepsJsonV1Codec().decode(bytes).archive.days.forEach {
                    include(it.structuralDayStartTimeMs, it.structuralDayEndTimeMs)
                } else PortableAmbientStepsJsonV2Codec().decode(bytes).archive.days.forEach {
                    include(it.product.structuralDayStartTimeMs, it.product.structuralDayEndTimeMs)
                }
                PortableAmbientStepsExporter.FORMAT_ID
            } else {
                version = portableStepsSchemaVersion(bytes)
                if (version == 1) PortableStepsJsonV1Codec().decode(bytes) { include(it.startTimeMs, it.endTimeMs) }
                else PortableStepsJsonV2Codec().decode(bytes).archive.entries.forEach { include(it.product.startTimeMs, it.product.endTimeMs) }
                "portable-steps-v1"
            }
        }
        HistorySource.ACTIVITY -> {
            file.inputStream().use { PortableActivityJsonV1Codec().decode(it).entries.forEach { include(it.startTimeMs, it.endTimeMs) } }
            "portable-activity-v1"
        }
        HistorySource.PRESSURE -> {
            file.inputStream().use { PortablePressureJsonV1Codec().decode(it) { include(it.startTimeMs, it.endTimeMs) } }
            "portable-pressure-v1"
        }
        HistorySource.WIFI -> {
            val data = file.inputStream().use { RadioSourceFileJsonV2Codec().readWifi(it) }
            version = data.schemaVersion
            data.sessions.forEach { session ->
                val childrenByObservation = session.children.groupBy { it.observationIdentity }
                session.entry.runs.forEach { run ->
                    if (run.observations.isEmpty()) include(run.startTimeMs, run.endTimeMs)
                    run.observations.forEach { observation ->
                        val children = childrenByObservation[observation.identity.value].orEmpty()
                        if (children.isEmpty()) include(observation.coverageStartTimeMs, observation.observedTimeMs)
                        else children.forEach { include(it.observedWallTimeMs) }
                    }
                }
            }
            val backgroundChildren = data.backgroundChildren.groupBy { it.factIdentity to it.semanticRevision }
            data.background?.facts.orEmpty().forEach { fact ->
                val children = backgroundChildren[fact.identity to fact.semanticRevision].orEmpty()
                if (children.isEmpty()) include(fact.coverageStartTimeMs, fact.observedTimeMs)
                else children.forEach { include(it.observedWallTimeMs) }
            }
            data.background?.gaps.orEmpty().forEach { include(it.startTimeMs, it.endTimeMs) }
            RadioSourceFileV2.WIFI_FORMAT
        }
        HistorySource.CELL -> {
            val data = file.inputStream().use { RadioSourceFileJsonV2Codec().readCell(it) }
            version = data.schemaVersion
            data.sessions.forEach { session ->
                val childrenByObservation = session.children.groupBy { it.observationIdentity }
                session.entry.runs.forEach { run ->
                    if (run.observations.isEmpty()) include(run.startTimeMs, run.endTimeMs)
                    run.observations.forEach { observation ->
                        val children = childrenByObservation[observation.identity.value].orEmpty()
                        if (children.isEmpty()) include(observation.coverageStartTimeMs, observation.observedTimeMs)
                        else children.forEach { include(it.observedWallTimeMs) }
                    }
                }
            }
            val backgroundChildren = data.backgroundChildren.groupBy { it.factIdentity to it.semanticRevision }
            data.background?.facts.orEmpty().forEach { fact ->
                val children = backgroundChildren[fact.identity to fact.semanticRevision].orEmpty()
                if (children.isEmpty()) include(fact.coverageStartTimeMs, fact.observedTimeMs)
                else children.forEach { include(it.observedWallTimeMs) }
            }
            data.background?.gaps.orEmpty().forEach { include(it.startTimeMs, it.endTimeMs) }
            RadioSourceFileV2.CELL_FORMAT
        }
    }
    require(first != Long.MAX_VALUE && last != Long.MIN_VALUE)
    return SessionChildMetadata(format, version, first, last)
}
