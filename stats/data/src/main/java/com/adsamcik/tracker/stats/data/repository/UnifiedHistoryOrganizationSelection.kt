package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistorySection
import com.adsamcik.tracker.stats.api.repository.HistoryObservationWindow
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow

/** A retrospective interval selects existing evidence; it never prorates or changes its authority. */
internal fun selectOrganizationInterval(
    members: List<UnifiedHistoryRow>,
    fromInclusiveMs: Long,
    toExclusiveMs: Long,
    stepsBounds: Map<Long, Pair<Long, Long>>,
    stepsBoundsProblem: HistoryProblemReason? = null,
): OrganizationIntervalSections {
    val sections = mutableListOf<HistorySection>()
    val problems = linkedSetOf<HistoryProblem>()
    fun contains(start: Long, end: Long) = start >= fromInclusiveMs && end >= start &&
        end <= toExclusiveMs && (end > start || start < toExclusiveMs)
    val displayWindow = HistoryObservationWindow(fromInclusiveMs, toExclusiveMs)
    for (member in members) for (section in member.sections) {
        fun crossing() { problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN) }
        when (section) {
            is HistorySection.Location -> {
                val points = section.points.filter { it.timeMs in fromInclusiveMs until toExclusiveMs }
                if (points.isNotEmpty()) sections += section.copy(points = points)
            }
            is HistorySection.Steps -> {
                val bounds = stepsBounds[section.segmentId]
                if (bounds == null) problems += HistoryProblem(section.source,
                    stepsBoundsProblem ?: HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                else if (contains(bounds.first, bounds.second)) sections += section else crossing()
            }
            is HistorySection.ImportedSteps -> {
                if (contains(section.history.startTime.raw, section.history.endTime.raw)) sections += section else crossing()
            }
            is HistorySection.BackgroundSteps -> {
                if (contains(section.day.structuralDayStartTimeMs, section.day.structuralDayEndTimeMs)) {
                    sections += section
                } else crossing()
            }
            is HistorySection.Activity -> {
                // Its active-time total and untimed gaps cannot be split by a wall-time boundary.
                if (contains(section.history.startTime.raw, section.history.endTime.raw)) sections += section else crossing()
            }
            is HistorySection.Pressure -> {
                val end = member.toExclusiveMs
                if (end != null && contains(member.position.startTimeMs, end) &&
                    section.history.windows.all { it.intervalStartTime.raw >= fromInclusiveMs &&
                        it.intervalEndTime.raw < toExclusiveMs }) {
                    sections += section
                } else crossing()
            }
            is HistorySection.Wifi -> {
                val observations = section.history.observations.filter {
                    it.networks.any { point -> displayWindow.contains(point.observedTimeMs) } ||
                        it.networks.isEmpty() && displayWindow.contains(it.observedTime.raw) }
                if (observations.size != section.history.observations.size) crossing()
                if (observations.isNotEmpty()) sections += section.copy(history = section.history.copy(observations =
                    observations.map { it.copy(networks = it.networks.filter { point -> displayWindow.contains(point.observedTimeMs) }) }))
                else if (section.history.observations.isEmpty() && contains(section.history.startTime.raw, section.history.endTime.raw)) {
                    sections += section
                } else crossing()
            }
            is HistorySection.Cell -> {
                val observations = section.history.observations.filter {
                    it.towers.any { point -> displayWindow.contains(point.observedTimeMs) } ||
                        it.towers.isEmpty() && displayWindow.contains(it.observedTime.raw) }
                if (observations.size != section.history.observations.size) crossing()
                if (observations.isNotEmpty()) sections += section.copy(history = section.history.copy(observations =
                    observations.map { it.copy(towers = it.towers.filter { point -> displayWindow.contains(point.observedTimeMs) }) }))
                else if (section.history.observations.isEmpty() && contains(section.history.startTime.raw, section.history.endTime.raw)) {
                    sections += section
                } else crossing()
            }
            is HistorySection.BackgroundWifi -> {
                val facts = section.read.facts.filter { fact -> fact.networks.any { displayWindow.contains(it.observedTimeMs) } ||
                    fact.networks.isEmpty() && fact.coverageStartTimeMs < toExclusiveMs && fact.latestPossibleTimeMs >= fromInclusiveMs }
                val gaps = section.read.gaps.filter { contains(it.startTimeMs, it.endTimeMs) }
                if (facts.size != section.read.facts.size || gaps.size != section.read.gaps.size) crossing()
                if (facts.isNotEmpty() || gaps.isNotEmpty()) sections += section.copy(read = section.read.copy(facts = facts, gaps = gaps),
                    displayWindow = displayWindow)
            }
            is HistorySection.BackgroundCell -> {
                val facts = section.read.facts.filter { fact -> fact.towers.any { displayWindow.contains(it.observedTimeMs) } ||
                    fact.towers.isEmpty() && fact.coverageStartTimeMs < toExclusiveMs && fact.latestPossibleTimeMs >= fromInclusiveMs }
                val gaps = section.read.gaps.filter { contains(it.startTimeMs, it.endTimeMs) }
                if (facts.size != section.read.facts.size || gaps.size != section.read.gaps.size) crossing()
                if (facts.isNotEmpty() || gaps.isNotEmpty()) sections += section.copy(read = section.read.copy(facts = facts, gaps = gaps),
                    displayWindow = displayWindow)
            }
            is HistorySection.Organization -> Unit
        }
    }
    return OrganizationIntervalSections(sections, problems)
}

internal data class OrganizationIntervalSections(val sections: List<HistorySection>, val problems: Set<HistoryProblem>)
