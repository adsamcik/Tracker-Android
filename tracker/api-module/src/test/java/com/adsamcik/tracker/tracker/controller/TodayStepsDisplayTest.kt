package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayDay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayValue
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryBatch
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TodayStepsDisplayTest {
    @Test fun `a subscribed display reads today without a session and stops on cancellation`() = runTest {
        val oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val clock = FixedClock(Instant.parse("2026-10-13T10:00:00Z").toEpochMilli())
            val repository = RecordingDisplayRepository()
            val display = TodayStepsDisplay(repository, clock)
            assertTrue(repository.requests.isEmpty())
            val values = mutableListOf<StepsDailyDisplay>()
            val collection = backgroundScope.launch { display.observe().collect(values::add) }
            runCurrent()

            assertEquals(listOf(todayRequest("2026-10-13", "UTC")), repository.requests)
            assertEquals(complete("2026-10-13", 7L), values.last())
            collection.cancel()
            runCurrent()
            assertEquals(1, repository.closedReads)
            clock.advance(24 * 60 * 60 * 1_000L)
            advanceTimeBy(24 * 60 * 60 * 1_000L)
            runCurrent()
            assertEquals(1, repository.requests.size)
        } finally { TimeZone.setDefault(oldZone) }
    }

    @Test fun `resume retries a failed read and rebinds a same-day zone change`() = runTest {
        val oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val clock = FixedClock(Instant.parse("2026-10-13T10:00:00Z").toEpochMilli())
            val repository = RecordingDisplayRepository().apply { fail = true }
            val display = TodayStepsDisplay(repository, clock)
            val values = mutableListOf<StepsDailyDisplay>()
            backgroundScope.launch { display.observe().collect(values::add) }
            runCurrent()
            assertEquals(StepsDailyDisplay.StorageUnavailable, values.last())

            repository.fail = false
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Prague"))
            display.refresh()
            runCurrent()
            assertEquals(todayRequest("2026-10-13", "Europe/Prague"), repository.requests.last())
            assertEquals(complete("2026-10-13", 7L), values.last())
        } finally { TimeZone.setDefault(oldZone) }
    }

    @Test fun `midnight uses the local calendar including a DST transition`() = runTest {
        val oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Prague"))
        try {
            val start = LocalDate.parse("2026-03-29").atStartOfDay(ZoneId.of("Europe/Prague"))
                .toInstant().toEpochMilli()
            val clock = FixedClock(start)
            val repository = RecordingDisplayRepository()
            val display = TodayStepsDisplay(repository, clock)
            backgroundScope.launch { display.observe().collect { } }
            runCurrent()
            val shortDay = 23 * 60 * 60 * 1_000L
            clock.advance(shortDay)
            advanceTimeBy(shortDay)
            runCurrent()
            assertEquals(listOf(
                todayRequest("2026-03-29", "Europe/Prague"),
                todayRequest("2026-03-30", "Europe/Prague"),
            ), repository.requests)
        } finally { TimeZone.setDefault(oldZone) }
    }

    @Test fun `resume replaces the pending midnight after the clock changes`() = runTest {
        val oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val clock = FixedClock(Instant.parse("2026-10-13T10:00:00Z").toEpochMilli())
            val repository = RecordingDisplayRepository()
            val display = TodayStepsDisplay(repository, clock)
            backgroundScope.launch { display.observe().collect { } }
            runCurrent()
            clock.setTime(Instant.parse("2026-10-13T23:59:00Z").toEpochMilli())
            display.refresh()
            runCurrent()
            clock.advance(60_000L)
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(todayRequest("2026-10-14", "UTC"), repository.requests.last())
        } finally { TimeZone.setDefault(oldZone) }
    }

    @Test fun `a stale different-day read never appears as today`() = runTest {
        val oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val repository = RecordingDisplayRepository().apply { stale = complete("2026-10-12", 99L) }
            val clock = FixedClock(Instant.parse("2026-10-13T10:00:00Z").toEpochMilli())
            val values = mutableListOf<StepsDailyDisplay>()
            backgroundScope.launch { TodayStepsDisplay(repository, clock).observe().collect(values::add) }
            runCurrent()
            assertEquals(StepsDailyDisplay.StorageUnavailable, values.last())
            assertFalse(values.any { it == complete("2026-10-12", 99L) })
        } finally { TimeZone.setDefault(oldZone) }
    }

    private fun todayRequest(date: String, zone: String): StepsNumericSummaryRequest {
        val day = LocalDate.parse(date).toEpochDay()
        return StepsNumericSummaryRequest(day, day, zone)
    }

    private fun complete(date: String, count: Long) = StepsDailyDisplay.Snapshot(1L,
        listOf(StepsDailyDisplayDay(LocalDate.parse(date).toEpochDay(), StepsDailyDisplayValue.Complete(count))))
}

private class RecordingDisplayRepository : StepsNumericSummaryRepository {
    val requests = mutableListOf<StepsNumericSummaryRequest>()
    var closedReads = 0
    var fail = false
    var stale: StepsDailyDisplay? = null
    override fun observeDisplay(request: StepsNumericSummaryRequest): Flow<StepsDailyDisplay> = flow {
        requests += request
        if (fail) error("Local read failed")
        try {
            emit(stale ?: StepsDailyDisplay.Snapshot(1L,
                listOf(StepsDailyDisplayDay(request.firstEpochDay, StepsDailyDisplayValue.Complete(7L)))))
            kotlinx.coroutines.awaitCancellation()
        } finally { closedReads++ }
    }
    override suspend fun readDisplay(request: StepsNumericSummaryRequest): StepsDailyDisplay = error("observe only")
    override suspend fun read(request: StepsNumericSummaryRequest): StepsNumericSummary = error("strict read not used")
    override suspend fun readBatch(requests: List<StepsNumericSummaryRequest>): StepsNumericSummaryBatch = error("not used")
    override fun observe(request: StepsNumericSummaryRequest): Flow<StepsNumericSummary> = error("strict observe not used")
    override fun observeBatch(requests: List<StepsNumericSummaryRequest>): Flow<StepsNumericSummaryBatch> = error("not used")
}
