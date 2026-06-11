package com.adsamcik.tracker.stats.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.repository.LiveStats
import com.adsamcik.tracker.stats.api.repository.LiveStatsRepository
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.data.store.LiveStatsProto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

private object LiveStatsSerializer : Serializer<LiveStatsProto> {
	override val defaultValue: LiveStatsProto = LiveStatsProto.getDefaultInstance()

	override suspend fun readFrom(input: InputStream): LiveStatsProto = try {
		LiveStatsProto.parseFrom(input)
	} catch (e: Exception) {
		Log.w("LiveStats", "Corruption while reading live stats proto – using defaults", e)
		defaultValue
	}

	override suspend fun writeTo(t: LiveStatsProto, output: OutputStream) {
		t.writeTo(output)
	}
}

private val Context.liveStatsDataStore: DataStore<LiveStatsProto> by dataStore(
	fileName = "live_stats.pb",
	serializer = LiveStatsSerializer,
)

class ProtoLiveStatsRepository @Inject constructor(
	@ApplicationContext private val context: Context,
) : LiveStatsRepository {

	override fun observeLiveStats(): Flow<LiveStats> {
		return context.liveStatsDataStore.data.map { proto ->
			LiveStats(
				sessionDistance = DistanceM.coerced(proto.sessionDistanceM),
				sessionSteps = StepCount.coerced(proto.sessionSteps),
				sessionDuration = DurationMs(proto.sessionDurationMs.coerceAtLeast(0L)),
				currentSpeed = SpeedMps.coerced(proto.currentSpeedMps),
				avgSpeed = SpeedMps.coerced(proto.avgSpeedMps),
				maxSpeed = SpeedMps.coerced(proto.maxSpeedMps),
				dayTotalDistance = DistanceM.coerced(proto.dayTotalDistanceM),
				dayTotalSteps = StepCount.coerced(proto.dayTotalSteps),
				dominantActivity = proto.dominantActivity.takeIf { it.isNotEmpty() }
					?.let { name ->
						runCatching { DetectedActivityType.valueOf(name) }.getOrNull()
					},
				tripCount = proto.tripCount,
			)
		}
	}

	override suspend fun updateLiveStats(stats: LiveStats) {
		context.liveStatsDataStore.updateData {
			it.toBuilder()
				.setSessionDistanceM(stats.sessionDistance.raw)
				.setSessionSteps(stats.sessionSteps.raw)
				.setSessionDurationMs(stats.sessionDuration.raw)
				.setCurrentSpeedMps(stats.currentSpeed.raw)
				.setAvgSpeedMps(stats.avgSpeed.raw)
				.setMaxSpeedMps(stats.maxSpeed.raw)
				.setDayTotalDistanceM(stats.dayTotalDistance.raw)
				.setDayTotalSteps(stats.dayTotalSteps.raw)
				.setDominantActivity(stats.dominantActivity?.name.orEmpty())
				.setTripCount(stats.tripCount)
				.build()
		}
	}

	override suspend fun clear() {
		context.liveStatsDataStore.updateData {
			LiveStatsProto.getDefaultInstance()
		}
	}
}
