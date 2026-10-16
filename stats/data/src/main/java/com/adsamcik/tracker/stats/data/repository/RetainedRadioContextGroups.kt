package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.CellHistorySignalQuality
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGap
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason
import com.adsamcik.tracker.stats.api.repository.RadioContextSignalQuality
import com.adsamcik.tracker.stats.api.repository.WifiHistorySignalQuality

/** Multiple independently qualified groups remain one physical observation for each purpose. */
internal fun consolidateRetainedRadioGroups(input: List<QualifiedRetainedRadio>,
    gaps: MutableSet<RadioContextReadGap>): List<QualifiedRetainedRadio> = input
    .groupBy { it.input.sourceEventId to it.purpose }.values.mapNotNull { candidates ->
        val groups = candidates.distinctBy { it.aggregateIdentity }
        val first = groups.first()
        if (candidates.any { it.input.scope != first.input.scope || it.input.source != first.input.source } ||
            candidates.groupBy { it.aggregateIdentity }.values.any { values ->
                values.map { it.quality }.distinct().size > 1 }) {
            gaps += RadioContextReadGap(first.purpose, RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
            return@mapNotNull null
        }
        val quality = when (first.quality) {
            is RadioContextSignalQuality.Wifi -> {
                val values = groups.map { (it.quality as RadioContextSignalQuality.Wifi).value }
                val count = values.fold(0) { total, value -> Math.addExact(total, value.sampleCount) }
                RadioContextSignalQuality.Wifi(WifiHistorySignalQuality(values.maxOf { it.strongestSignalDbm },
                    values.minOf { it.weakestSignalDbm },
                        values.sumOf { it.meanSignalDbm * it.sampleCount } / count, count))
            }
            is RadioContextSignalQuality.Cell -> {
                val values = groups.map { (it.quality as RadioContextSignalQuality.Cell).value }
                fun sum(value: (CellHistorySignalQuality) -> Int) = values.fold(0) { total, next ->
                    Math.addExact(total, value(next)) }
                RadioContextSignalQuality.Cell(CellHistorySignalQuality(sum { it.unknownCount },
                    sum { it.noneOrUnknownCount },
                    sum { it.poorCount }, sum { it.moderateCount }, sum { it.goodCount }, sum { it.greatCount }))
            }
        }
        val latest = candidates.maxBy { it.input.observedElapsedNanos }
        first.copy(input = latest.input.copy(observedFromElapsedNanos =
            candidates.minOf { it.input.observedFromElapsedNanos },
            wallTimeUncertaintyMs = candidates.maxOf { it.input.wallTimeUncertaintyMs }),
            intervalStartMs = candidates.minOf { it.intervalStartMs }, quality = quality)
    }.sortedWith(
        compareBy<QualifiedRetainedRadio> { it.input.observedWallTimeMs }
            .thenBy { it.input.sourceEventId }.thenBy { it.purpose },
    )
