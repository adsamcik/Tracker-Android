package com.adsamcik.tracker.stats.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.WifiObservationBrowseRow
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseFilter
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseItem
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationStatsSummary
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DefaultWifiObservationRepository @Inject constructor(
	private val wifiObservationDao: WifiObservationDao,
	private val dispatchers: DispatchersProvider,
) : WifiObservationRepository {

	override suspend fun getBrowseItems(
		filter: WifiObservationBrowseFilter,
	): Either<StatsError, List<WifiObservationBrowseItem>> = withContext(dispatchers.io) {
		try {
			val normalizedFilter = filter.normalize()
			wifiObservationDao.getBrowseItems(
				bssid = normalizedFilter.bssid,
				ssid = normalizedFilter.ssid,
				capabilities = normalizedFilter.capabilities,
				frequencyPrefix = normalizedFilter.frequencyPrefix,
				limit = normalizedFilter.limit,
			).map { it.toBrowseItem() }.right()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load Wi-Fi observations: ${e.message}", e).left()
		}
	}

	override suspend fun getStatsSummary(): Either<StatsError, WifiObservationStatsSummary> =
		withContext(dispatchers.io) {
			try {
				val scanSummary = wifiObservationDao.getScanSummary()
				WifiObservationStatsSummary(
					uniqueNetworks = wifiObservationDao.countDistinctBssid().coerceAtLeast(0L),
					totalScans = scanSummary.distinctScanTimes.coerceAtLeast(0L),
					averageNetworksPerScan = if (scanSummary.distinctScanTimes > 0L) {
						scanSummary.totalObservations.toDouble() / scanSummary.distinctScanTimes
					} else {
						0.0
					},
				).right()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				StatsError.DatabaseError("Failed to load Wi-Fi stats: ${e.message}", e).left()
			}
		}

	private fun WifiObservationBrowseFilter.normalize(): WifiObservationBrowseFilter {
		return copy(
			bssid = bssid.normalizedLikeQuery(),
			ssid = ssid.normalizedLikeQuery(),
			capabilities = capabilities.normalizedLikeQuery(),
			frequencyPrefix = frequencyPrefix.normalizedLikeQuery(),
			limit = limit.coerceAtLeast(1),
		)
	}

	private fun String?.normalizedLikeQuery(): String? {
		return this
			?.trim()
			?.takeIf(String::isNotEmpty)
			?.escapeLike()
	}

	private fun String.escapeLike(): String {
		return buildString(length) {
			for (character in this@escapeLike) {
				if (character == '\\' || character == '%' || character == '_') {
					append('\\')
				}
				append(character)
			}
		}
	}

	private fun WifiObservationBrowseRow.toBrowseItem(): WifiObservationBrowseItem {
		return WifiObservationBrowseItem(
			bssid = bssid,
			ssid = ssid,
			capabilities = capabilities,
			frequency = frequency,
			firstSeenAt = EpochMs(firstSeenMs),
			lastSeenAt = EpochMs(lastSeenMs),
		)
	}
}
