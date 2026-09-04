package com.adsamcik.tracker.impexp.exporter.data

import android.content.Context
import com.adsamcik.tracker.impexp.exporter.pagedLocationSequence
import com.adsamcik.tracker.shared.base.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomImportExportDataRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appDatabase: AppDatabase,
) : ImportExportDataRepository {

    override suspend fun hasTrips(): Boolean =
        appDatabase.tripDao().countAllTrips() > 0L

    override suspend fun countLocationSamples(
        fromMs: Long,
        toMs: Long,
    ): Int = appDatabase.locationSampleDao().countBetween(fromMs, toMs)

    override fun pagedLocationSamples(
        fromMs: Long,
        toMs: Long,
        pageSize: Int,
    ) = pagedLocationSequence(
        locationSampleDao = appDatabase.locationSampleDao(),
        fromMs = fromMs,
        toMs = toMs,
        pageSize = pageSize,
    )

    override suspend fun loadTripShareSnapshot(
        fromMs: Long,
        toMs: Long,
    ): TripShareSnapshot? {
        val tripDao = appDatabase.tripDao()
        if (tripDao.countTripsBetween(fromMs, toMs) == 0L) return null

        val trips = tripDao.getBetween(fromMs, toMs)
        val primaryTrip = trips.firstOrNull() ?: return null
        val activity = primaryTrip.primaryActivity?.toLong()?.let { activityId ->
            appDatabase.activityDao().getLocalized(appContext, activityId)
        }

        return TripShareSnapshot(
            startTimeMs = primaryTrip.startTimeMs,
            totalDistanceM = trips.sumOf { it.distanceM.toDouble() }.toFloat(),
            totalDurationMs = trips.sumOf { it.durationMs },
            activityName = activity?.name,
            activityId = activity?.id,
        )
    }
}
