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
    val displayWindow = HistoryObservationWindow(fromInclusiveMs, toExclusiveMs)
    for (member in members) for (section in member.sections) {
        selectOrganizationSection(member, section, displayWindow, stepsBounds, stepsBoundsProblem, problems)
            ?.let(sections::add)
    }
    return OrganizationIntervalSections(sections, problems)
}

// The exhaustive source dispatch only chooses the existing typed projection; selection lives below.
@Suppress("CyclomaticComplexMethod")
private fun selectOrganizationSection(
    member: UnifiedHistoryRow,
    section: HistorySection,
    window: HistoryObservationWindow,
    stepsBounds: Map<Long, Pair<Long, Long>>,
    stepsBoundsProblem: HistoryProblemReason?,
    problems: MutableSet<HistoryProblem>,
): HistorySection? = when (section) {
    is HistorySection.Location -> selectOrganizationLocation(section, window)
    is HistorySection.Steps -> selectOrganizationSteps(section, window, stepsBounds, stepsBoundsProblem, problems)
    is HistorySection.ImportedSteps -> selectContainedSection(section, section.history.startTime.raw,
        section.history.endTime.raw, window, problems)
    is HistorySection.BackgroundSteps -> selectContainedSection(section, section.day.structuralDayStartTimeMs,
        section.day.structuralDayEndTimeMs, window, problems)
    is HistorySection.Activity -> selectContainedSection(section, section.history.startTime.raw,
        section.history.endTime.raw, window, problems)
    is HistorySection.Pressure -> selectOrganizationPressure(member, section, window, problems)
    is HistorySection.Wifi -> selectOrganizationWifi(section, window, problems)
    is HistorySection.Cell -> selectOrganizationCell(section, window, problems)
    is HistorySection.BackgroundWifi -> selectOrganizationBackgroundWifi(section, window, problems)
    is HistorySection.BackgroundCell -> selectOrganizationBackgroundCell(section, window, problems)
    is HistorySection.Organization -> null
}

private fun selectOrganizationLocation(
    section: HistorySection.Location, window: HistoryObservationWindow,
): HistorySection? = section.points.filter {
    it.timeMs in window.fromInclusiveMs until window.toExclusiveMs
}.takeIf { it.isNotEmpty() }?.let { section.copy(points = it) }

private fun selectContainedSection(
    section: HistorySection, start: Long, end: Long, window: HistoryObservationWindow,
    problems: MutableSet<HistoryProblem>,
): HistorySection? = if (window.containsCompleteInterval(start, end)) {
    section
} else {
    problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN)
    null
}

private fun selectOrganizationSteps(
    section: HistorySection.Steps, window: HistoryObservationWindow, stepsBounds: Map<Long, Pair<Long, Long>>,
    stepsBoundsProblem: HistoryProblemReason?, problems: MutableSet<HistoryProblem>,
): HistorySection? {
    val bounds = stepsBounds[section.segmentId]
    return if (bounds == null) {
        problems += HistoryProblem(section.source, stepsBoundsProblem ?: HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        null
    } else {
        selectContainedSection(section, bounds.first, bounds.second, window, problems)
    }
}

private fun selectOrganizationPressure(
    member: UnifiedHistoryRow, section: HistorySection.Pressure, window: HistoryObservationWindow,
    problems: MutableSet<HistoryProblem>,
): HistorySection? {
    val end = member.toExclusiveMs
    return if (end != null && window.containsCompleteInterval(member.position.startTimeMs, end) &&
        section.history.windows.all { it.intervalStartTime.raw >= window.fromInclusiveMs &&
            it.intervalEndTime.raw < window.toExclusiveMs }) {
        section
    } else {
        problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN)
        null
    }
}

private fun selectOrganizationWifi(
    section: HistorySection.Wifi, window: HistoryObservationWindow, problems: MutableSet<HistoryProblem>,
): HistorySection? {
    val observations = section.history.observations.filter {
        it.networks.any { point -> window.contains(point.observedTimeMs) } ||
            it.networks.isEmpty() && window.contains(it.observedTime.raw)
    }
    if (observations.size != section.history.observations.size) {
        problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN)
    }
    return if (observations.isNotEmpty()) {
        section.copy(history = section.history.copy(observations = observations.map {
            it.copy(networks = it.networks.filter { point -> window.contains(point.observedTimeMs) })
        }))
    } else if (section.history.observations.isEmpty() &&
        window.containsCompleteInterval(section.history.startTime.raw, section.history.endTime.raw)) {
        section
    } else {
        problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN)
        null
    }
}

private fun selectOrganizationCell(
    section: HistorySection.Cell, window: HistoryObservationWindow, problems: MutableSet<HistoryProblem>,
): HistorySection? {
    val observations = section.history.observations.filter {
        it.towers.any { point -> window.contains(point.observedTimeMs) } ||
            it.towers.isEmpty() && window.contains(it.observedTime.raw)
    }
    if (observations.size != section.history.observations.size) {
        problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN)
    }
    return if (observations.isNotEmpty()) {
        section.copy(history = section.history.copy(observations = observations.map {
            it.copy(towers = it.towers.filter { point -> window.contains(point.observedTimeMs) })
        }))
    } else if (section.history.observations.isEmpty() &&
        window.containsCompleteInterval(section.history.startTime.raw, section.history.endTime.raw)) {
        section
    } else {
        problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN)
        null
    }
}

private fun selectOrganizationBackgroundWifi(
    section: HistorySection.BackgroundWifi, window: HistoryObservationWindow, problems: MutableSet<HistoryProblem>,
): HistorySection? {
    val facts = section.read.facts.filter { fact ->
        fact.networks.any { window.contains(it.observedTimeMs) } ||
            fact.networks.isEmpty() && fact.coverageStartTimeMs < window.toExclusiveMs &&
                fact.latestPossibleTimeMs >= window.fromInclusiveMs
    }
    val gaps = section.read.gaps.filter { window.containsCompleteInterval(it.startTimeMs, it.endTimeMs) }
    if (facts.size != section.read.facts.size || gaps.size != section.read.gaps.size) {
        problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN)
    }
    return if (facts.isNotEmpty() || gaps.isNotEmpty()) {
        section.copy(read = section.read.copy(facts = facts, gaps = gaps), displayWindow = window)
    } else {
        null
    }
}

private fun selectOrganizationBackgroundCell(
    section: HistorySection.BackgroundCell, window: HistoryObservationWindow, problems: MutableSet<HistoryProblem>,
): HistorySection? {
    val facts = section.read.facts.filter { fact ->
        fact.towers.any { window.contains(it.observedTimeMs) } ||
            fact.towers.isEmpty() && fact.coverageStartTimeMs < window.toExclusiveMs &&
                fact.latestPossibleTimeMs >= window.fromInclusiveMs
    }
    val gaps = section.read.gaps.filter { window.containsCompleteInterval(it.startTimeMs, it.endTimeMs) }
    if (facts.size != section.read.facts.size || gaps.size != section.read.gaps.size) {
        problems += HistoryProblem(section.source, HistoryProblemReason.TIME_UNCERTAIN)
    }
    return if (facts.isNotEmpty() || gaps.isNotEmpty()) {
        section.copy(read = section.read.copy(facts = facts, gaps = gaps), displayWindow = window)
    } else {
        null
    }
}

private fun HistoryObservationWindow.containsCompleteInterval(start: Long, end: Long): Boolean {
    if (start < fromInclusiveMs || end < start || end > toExclusiveMs) {
        return false
    }
    return end > start || start < toExclusiveMs
}

internal data class OrganizationIntervalSections(val sections: List<HistorySection>, val problems: Set<HistoryProblem>)
