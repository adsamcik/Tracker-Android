package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class UnifiedHistoryMergeTest {
    private fun key(time: Long, origin: String = "local", id: String = "$time") = HistoryPosition(time, origin, id)
    private fun stream(vararg keys: HistoryPosition, lookahead: HistoryPosition? = null) =
        HistoryStreamBuffer(keys.toList(), { it }, lookahead)

    @Test fun `interleaved pages retain every prefetched unconsumed head`() = runTest {
        val all = (0 until 6).map { source ->
            (0 until 250).map { index -> key(3000L - index * 6 - source, "source:$source") }
        }
        var after: HistoryPosition? = null
        val emitted = mutableListOf<HistoryPosition>()
        do {
            val buffers = all.map { keys ->
                val remaining = keys.filter { after == null || it > requireNotNull(after) }
                HistoryStreamBuffer(remaining.take(100), { it }, remaining.getOrNull(100))
            }
            val page = mergeHistoryBuffers(buffers, 100) { position, _ -> position }
            emitted += page.rows
            after = page.consumed
        } while (page.hasMore)
        assertEquals(all.flatten().sorted(), emitted)
        assertEquals(1500, emitted.distinct().size)
    }

    @Test fun `ties use ascending origin and stable identity`() = runTest {
        val result = mergeHistoryBuffers(listOf(stream(key(5, "z", "a")),
            stream(key(5, "a", "b")), stream(key(5, "a", "a"))), 100) { k, _ -> k }
        assertEquals(listOf(key(5, "a", "a"), key(5, "a", "b"), key(5, "z", "a")), result.rows)
        assertFalse(result.hasMore)
    }

    @Test fun `equal representatives qualify once and consume all duplicate seeds`() = runTest {
        val representative = key(10, "local", "logical:one")
        var calls = 0
        val page = mergeHistoryBuffers(listOf(stream(representative, key(8)),
            stream(representative, key(7))), 1) { k, candidates ->
            calls++
            assertEquals(2, candidates.size)
            k
        }
        assertEquals(1, calls)
        assertEquals(representative, page.consumed)
        assertTrue(page.hasMore)
    }

    @Test fun `rejected heads consume only when reached and filtered empty pages continue`() = runTest {
        val reached = mutableListOf<HistoryPosition>()
        val page = mergeHistoryBuffers(listOf(stream(key(10), lookahead = key(9)),
            stream(key(8))), 100) { k, _ -> reached += k; null as HistoryPosition? }
        assertEquals(listOf(key(10)), reached)
        assertTrue(page.rows.isEmpty())
        assertEquals(key(10), page.consumed)
        assertTrue(page.hasMore)
    }

    @Test fun `unknown stream frontier stops before lower fetched heads`() = runTest {
        val page = mergeHistoryBuffers(listOf(stream(key(10), lookahead = key(9)),
            stream(key(11), key(8))), 100) { k, _ -> k }
        assertEquals(listOf(key(11), key(10)), page.rows)
        assertEquals(key(10), page.consumed)
        assertTrue(page.hasMore)
    }

    @Test fun `same key at unknown frontier cannot emit an incomplete replacement`() = runTest {
        val page = mergeHistoryBuffers(listOf(stream(key(10), lookahead = key(9)),
            stream(key(9))), 100) { k, _ -> k }
        assertEquals(listOf(key(10)), page.rows)
        assertEquals(key(10), page.consumed)
    }

    @Test fun `continuation cannot be reused for different range or filters`() {
        val range = HistoryRange(0, 1000, "Europe/Prague")
        assertFailsWith<IllegalArgumentException> {
            HistoryRangeRequest(range.copy(sources = setOf(HistorySource.STEPS)), after = HistoryContinuation(range, key(5)))
        }
        assertFailsWith<IllegalArgumentException> { stream(key(1), key(2)) }
    }
}
