package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Counts the same qualified measured identities shown in History, without copying raw radio rows. */
class SavedRadioCountsReader @Inject constructor(private val history: TrackingHistoryRepository) {
    /**
     * Counts distinct saved Wi-Fi access points or Cell towers within inclusive UTC-millisecond bounds;
     * failures preserve a known partial count.
     */
    suspend fun read(source: HistorySource, fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE): SavedRadioCount {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(fromMs >= 0L && toMs >= fromMs)
        if (fromMs == Long.MAX_VALUE) {
            return SavedRadioCount(0L, false)
        }
        val range = HistoryRange(fromMs, if (toMs == Long.MAX_VALUE) {
            toMs
        } else {
            toMs + 1L
        },
            ZoneId.systemDefault().id, sources = setOf(source), mode = HistoryBrowseMode.OBSERVATIONS)
        return readPages(source, range)
    }

    private suspend fun readPages(source: HistorySource, range: HistoryRange): SavedRadioCount {
        val count = SavedRadioIdentityCount()
        var after: HistoryContinuation? = null
        var snapshot: TrackingHistoryReadSnapshot? = null
        try {
            do {
                currentCoroutineContext().ensureActive()
                val page = history.readRange(HistoryRangeRequest(range, 100, after)) as? HistoryRangeRead.Page
                    ?: return count.result(forcePartial = true)
                if (snapshot != null && snapshot != page.snapshot) {
                    return SavedRadioCount(null, true)
                }
                snapshot = page.snapshot
                count.partial = count.partial || page.problems.any { it.source == source || it.source == null }
                for (row in page.rows) {
                    count.add(row, source)
                }
                val next = page.next
                requireAdvancingContinuation(after, next)
                after = next
            } while (after != null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return count.result(forcePartial = true) }
        return count.result()
    }

    private fun requireAdvancingContinuation(after: HistoryContinuation?, next: HistoryContinuation?) {
        check(next == null || after == null || next.consumed > requireNotNull(after).consumed)
    }
}

/** Per-read saved identity set and its coverage flag; no paging, authorization or shared lifetime. */
private class SavedRadioIdentityCount {
    private val identities = hashSetOf<String>()
    var partial = false

    fun add(row: UnifiedHistoryRow, source: HistorySource) {
        partial = partial || row.problems.any { it.source == source || it.source == null }
        addMeasurement(row.measurement)
    }

    // Finite saved measurement variants supply identity/partial fields; paging and admission are outside this scope.
    @Suppress("CyclomaticComplexMethod")
    private fun addMeasurement(measurement: HistoryMeasurement?) {
        val identity = when (measurement) {
            is HistoryMeasurement.Wifi -> {
                partial = partial || measurement.partial
                measurement.network.bssid?.lowercase(Locale.ROOT)
            }
            is HistoryMeasurement.Cell -> {
                partial = partial || measurement.partial
                measurement.tower.identity
            }
            is HistoryMeasurement.LegacyWifi -> measurement.bssid.lowercase(Locale.ROOT)
            is HistoryMeasurement.LegacyCell ->
                "legacy:${measurement.networkType}:${measurement.mcc}:${measurement.mnc}:${measurement.lac}:${measurement.cellId}"
            is HistoryMeasurement.Gap -> { partial = true; return }
            null -> return
            else -> { partial = true; return }
        }
        addIdentity(identity)
    }

    private fun addIdentity(identity: String?) {
        if (identity.isNullOrBlank()) {
            partial = true
        } else {
            identities += identity
        }
    }

    fun result(forcePartial: Boolean = partial): SavedRadioCount =
        SavedRadioCount(identities.size.toLong().takeIf { it > 0L || !forcePartial }, forcePartial)
}

/**
 * Distinct saved radio identities; null count means no trustworthy number, and partial means coverage or
 * reading was incomplete.
 */
data class SavedRadioCount(val count: Long?, val partial: Boolean)
