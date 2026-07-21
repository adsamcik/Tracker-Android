package com.adsamcik.tracker.map.layers.registry

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class BoundedChunkCollectorTest {

    @Test
    fun `million-row history never decodes beyond the materialization ceiling`() = runTest {
        val availableRows = 1_000_000
        var nextRow = 0
        var requestedRows = 0
        var fetches = 0

        val rows = collectBoundedChunks(
            chunkSize = 2_000,
            maxMaterializedRows = 59_999,
        ) { limit ->
            fetches++
            requestedRows += limit
            List(minOf(limit, availableRows - nextRow)) { nextRow++ }
        }

        rows.size shouldBe 59_999
        requestedRows shouldBe 59_999
        fetches shouldBe 30

        // Production reserves the final materialization slot for a descending one-row query,
        // so the real end of the selected history stays pinned without exceeding 60,000 rows.
        val rowsWithFinalEndpoint = rows + (availableRows - 1)
        rowsWithFinalEndpoint.size shouldBe 60_000
        val sampled = downSampleEvenly(rowsWithFinalEndpoint, maxPoints = 30_000)
        sampled.size shouldBe 30_000
        sampled.first() shouldBe 0
        sampled.last() shouldBe availableRows - 1
    }
}
