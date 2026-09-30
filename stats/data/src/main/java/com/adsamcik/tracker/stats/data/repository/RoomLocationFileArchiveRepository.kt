package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.LocationFileArchiveRead
import com.adsamcik.tracker.stats.api.repository.LocationFileArchiveRepository
import com.adsamcik.tracker.stats.api.repository.LocationFileArchiveRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** One bounded Room snapshot. Missing/deleted members cannot acquire coincident-time substitutes. */
@Singleton
class RoomLocationFileArchiveRepository @Inject constructor(
	private val database: AppDatabase,
	@IoDispatcher private val io: CoroutineDispatcher,
) : LocationFileArchiveRepository {
	override suspend fun read(request: LocationFileArchiveRequest): LocationFileArchiveRead =
		withContext(io) {
			try {
				database.withTransaction { readInTransaction(request) }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				LocationFileArchiveRead.StorageUnavailable
			}
		}

	internal suspend fun readInTransaction(request: LocationFileArchiveRequest): LocationFileArchiveRead {
		if (database.activeRetentionFloorSettlement() != null) return LocationFileArchiveRead.StorageUnavailable
		val state = database.sourceEvidenceStateDao().get() ?: return LocationFileArchiveRead.StorageUnavailable
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) return LocationFileArchiveRead.DataChanged
		val dao = database.locationFileIdentityDao()
		val archive = dao.archive(request.archiveIdentity) ?: return LocationFileArchiveRead.NotFound
		if (archive.collectedDataEpoch != state.collectedDataEpoch) return LocationFileArchiveRead.DataChanged
		val counts = dao.archivePointCounts(archive.identity, state.retainedFromMs)
		if (counts.membershipCount != archive.pointCount) return LocationFileArchiveRead.IncompleteMembership
		return LocationFileArchiveRead.Found(archive.identity, archive.pointCount, counts.availableCount)
	}
}
