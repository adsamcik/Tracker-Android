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
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Counts the same qualified measured identities shown in History, without copying raw radio rows. */
class SavedRadioCountsReader @Inject constructor(private val history: TrackingHistoryRepository) {
    suspend fun read(source: HistorySource, fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE): SavedRadioCount {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(fromMs >= 0L && toMs >= fromMs)
        if (fromMs == Long.MAX_VALUE) return SavedRadioCount(0L, false)
        val range = HistoryRange(fromMs, if (toMs == Long.MAX_VALUE) toMs else toMs + 1L,
            ZoneId.systemDefault().id, sources = setOf(source), mode = HistoryBrowseMode.OBSERVATIONS)
        val identities = hashSetOf<String>()
        var after: HistoryContinuation? = null
        var snapshot: TrackingHistoryReadSnapshot? = null
        var partial = false
        try {
            do {
                currentCoroutineContext().ensureActive()
                val page = history.readRange(HistoryRangeRequest(range, 100, after)) as? HistoryRangeRead.Page
                    ?: return SavedRadioCount(identities.size.toLong().takeIf { it > 0L }, true)
                if (snapshot != null && snapshot != page.snapshot) return SavedRadioCount(null, true)
                snapshot = page.snapshot
                partial = partial || page.problems.any { it.source == source || it.source == null }
                for (row in page.rows) {
                    partial = partial || row.problems.any { it.source == source || it.source == null }
                    val identity = when (val measurement = row.measurement) {
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
                        is HistoryMeasurement.Gap -> { partial = true; continue }
                        null -> continue
                        else -> { partial = true; continue }
                    }
                    if (identity.isNullOrBlank()) partial = true else identities += identity
                }
                val next = page.next
                check(next == null || after == null || next.consumed > requireNotNull(after).consumed)
                after = next
            } while (after != null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return SavedRadioCount(identities.size.toLong().takeIf { it > 0L }, true) }
        return SavedRadioCount(identities.size.toLong().takeIf { it > 0L || !partial }, partial)
    }
}

data class SavedRadioCount(val count: Long?, val partial: Boolean)
