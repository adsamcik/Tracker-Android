package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SavedRadioCountsReaderTest {
    private val history = mockk<TrackingHistoryRepository>()
    private val reader = SavedRadioCountsReader(history)

    @Test fun `counts qualified capture background and imported networks once across pages`() = runTest {
        coEvery { history.readRange(any()) } coAnswers {
            val request = firstArg<com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest>()
            request.range.sources shouldBe setOf(HistorySource.WIFI)
            if (request.after == null) {
                val rows = listOf(wifi(300L, "AA:BB", HistoryOrigin.LOCAL), wifi(200L, "aa:bb", HistoryOrigin.IMPORTED))
                HistoryRangeRead.Page(rows, TrackingHistoryReadSnapshot(0L, 1L),
                    HistoryContinuation(request.range, rows.last().position))
            } else {
                HistoryRangeRead.Page(listOf(wifi(100L, "CC:DD", HistoryOrigin.LEGACY)),
                TrackingHistoryReadSnapshot(0L, 1L), null)
            }
        }
        reader.read(HistorySource.WIFI) shouldBe SavedRadioCount(2L, false)
    }

    @Test fun `one unavailable source does not erase the other measured count`() = runTest {
        coEvery { history.readRange(any()) } coAnswers {
            if (HistorySource.CELL in firstArg<com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest>().range.sources)
                {
                HistoryRangeRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
            }
            else {
                HistoryRangeRead.Page(listOf(wifi(100L, "aa:bb", HistoryOrigin.LOCAL)),
                TrackingHistoryReadSnapshot(0L, 1L), null)
            }
        }
        reader.read(HistorySource.CELL) shouldBe SavedRadioCount(null, true)
        reader.read(HistorySource.WIFI) shouldBe SavedRadioCount(1L, false)
    }

    @Test fun `a changed saved-data snapshot cannot publish a mixed unique count`() = runTest {
        coEvery { history.readRange(any()) } coAnswers {
            val request = firstArg<com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest>()
            val row = wifi(if (request.after == null) {
                200L
            } else {
                100L
            }, "aa:bb", HistoryOrigin.LOCAL)
            HistoryRangeRead.Page(listOf(row), TrackingHistoryReadSnapshot(0L, if (request.after == null) {
                1L
            } else {
                2L
            }),
                if (request.after == null) {
                    HistoryContinuation(request.range, row.position)
                } else {
                    null
                })
        }
        reader.read(HistorySource.WIFI) shouldBe SavedRadioCount(null, true)
    }

    private fun wifi(time: Long, id: String, origin: HistoryOrigin) = UnifiedHistoryRow(
        HistoryPosition(time, origin.name, id), null, setOf(origin), HistoryPurpose.BACKGROUND,
        sections = emptyList(), measurement = HistoryMeasurement.Wifi(WifiObservedNetwork(time, 0L, id, "Saved", 2412, -50)))
}
