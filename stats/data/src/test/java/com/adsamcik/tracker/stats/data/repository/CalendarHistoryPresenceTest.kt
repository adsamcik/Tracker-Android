package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** IMPLEMENTED_UNVALIDATED: exact page behavior and refresh work bounds, no providers or UI. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarHistoryPresenceTest {
    private val zone = ZoneId.of("UTC")
    private val first = LocalDate.of(2026, 3, 1)
    private val range = HistoryRange(time(first), time(first.plusMonths(1)), zone.id,
        mode = HistoryBrowseMode.OBSERVATIONS)
    private val snapshot = TrackingHistoryReadSnapshot(3, 8)

    @Test fun `sparse month skips empty dates and dense days stop after the first measurement`() = runTest {
        for (rows in listOf(listOf(row(time(first) + 1), row(time(first.plusDays(14)) + 1)),
            List(5_000) { row(time(first.plusDays(14)) + it + 1) })) {
            val requests = mutableListOf<HistoryRangeRequest>()
            val result = readCalendarPresenceSnapshot(range, zone) { request ->
                requests += request
                val selected = rows.filter { it.position.startTimeMs in request.range.fromInclusiveMs until
                    request.range.toExclusiveMs }.sortedByDescending { it.position.startTimeMs }
                val page = selected.take(request.limit)
                HistoryRangeRead.Page(page, snapshot, if (selected.size > page.size) {
                    HistoryContinuation(request.range, page.last().position)
                } else { null }, emptySet())
            } as CalendarHistoryPresenceRead.Snapshot
            result.epochDays shouldBe rows.map { java.time.Instant.ofEpochMilli(it.position.startTimeMs)
                .atZone(zone).toLocalDate().toEpochDay() }.toSet()
            requests.size shouldBe 2
            requests.all { it.limit == 1 } shouldBe true
        }
    }

    @Test fun `an interval ending at midnight does not mark the next date`() = runTest {
        val day = first.plusDays(14)
        var reads = 0
        val result = readCalendarPresenceSnapshot(range, zone) { request ->
            reads++
            HistoryRangeRead.Page(if (reads == 1) {
                listOf(row(time(day)).copy(toExclusiveMs = time(day.plusDays(1))))
            } else {
                emptyList()
            }, snapshot, null, emptySet())
        } as CalendarHistoryPresenceRead.Snapshot
        result.epochDays shouldBe setOf(day.toEpochDay())
    }

    @Test fun `rejected frontiers advance with visible bounded uncertainty without inventing dots`() = runTest {
        var calls = 0
        val result = readCalendarPresenceSnapshot(range, zone) { request ->
            calls++
            HistoryRangeRead.Page(emptyList(), snapshot,
                HistoryContinuation(request.range, HistoryPosition(range.toExclusiveMs - calls,
                    "local:pressure", "rejected-" + calls)),
                setOf(HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.INTEGRITY_UNAVAILABLE)))
        } as CalendarHistoryPresenceRead.Snapshot
        calls shouldBe 64
        result.epochDays shouldBe emptySet()
        result.problems.contains(HistoryProblem(null, HistoryProblemReason.DEPENDENCY_OVERFLOW)) shouldBe true
    }

    @Test fun `interval presence covers actual local DST dates and preserves sibling problems`() = runTest {
        val prague = ZoneId.of("Europe/Prague")
        val day = LocalDate.of(2026, 3, 29)
        val from = day.atStartOfDay(prague).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(prague).toInstant().toEpochMilli()
        val selected = HistoryRange(from, to, prague.id, mode = HistoryBrowseMode.OBSERVATIONS)
        val problem = HistoryProblem(HistorySource.CELL, HistoryProblemReason.STORAGE_UNAVAILABLE)
        val result = readCalendarPresenceSnapshot(selected, prague) { HistoryRangeRead.Page(
            listOf(row(from + 1)), snapshot, null, setOf(problem)) } as CalendarHistoryPresenceRead.Snapshot
        result.epochDays shouldBe setOf(day.toEpochDay())
        result.problems shouldBe setOf(problem)
        to - from shouldBe 23L * 60L * 60L * 1_000L
    }

    @Test fun `capture bursts finish the active read and coalesce into one pending refresh`() = runTest {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 100)
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val results = mutableListOf<CalendarHistoryPresenceRead>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCalendarPresenceChanges(changes, { snapshot }) {
                reads++
                if (reads == 1) {
                    release.await()
                }
                CalendarHistoryPresenceRead.Snapshot(setOf(first.toEpochDay()), snapshot)
            }.collect { results += it }
        }
        changes.emit(Unit); runCurrent()
        repeat(100) { changes.emit(Unit) }
        runCurrent(); reads shouldBe 1
        release.complete(Unit); runCurrent()
        results.filterIsInstance<CalendarHistoryPresenceRead.Snapshot>().size shouldBe 1
        advanceTimeBy(1_000); runCurrent(); reads shouldBe 2
        advanceTimeBy(5_000); runCurrent(); reads shouldBe 2
        collector.cancel()
    }

    private fun time(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun row(at: Long) = UnifiedHistoryRow(HistoryPosition(at, "local:ambient_wifi", "wifi:" + at), null,
        setOf(HistoryOrigin.LOCAL), HistoryPurpose.BACKGROUND, sections = emptyList(),
        measurement = HistoryMeasurement.Wifi(WifiObservedNetwork(at, 0, "ap", "Cafe", 2412, -45)))
}
