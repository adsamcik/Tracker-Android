package com.adsamcik.tracker.tracker.source.ambient.steps

import android.content.Context
import android.health.connect.HealthConnectManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/** One exact, second-aligned provider aggregation window. Absence is not covered zero. */
internal data class AmbientStepsProviderReadWindow(
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	init {
		require(startTimeMs >= 0L)
		require(endTimeMs > startTimeMs)
		require(startTimeMs % MILLIS_PER_SECOND == 0L)
		require(endTimeMs % MILLIS_PER_SECOND == 0L)
		require(endTimeMs - startTimeMs <= MAX_WINDOW_MILLIS)
	}

	val durationSeconds: Int
		get() = ((endTimeMs - startTimeMs) / MILLIS_PER_SECOND).toInt()

	private companion object {
		const val MILLIS_PER_SECOND = 1_000L
		const val MAX_WINDOW_MILLIS = 25L * 60L * 60L * MILLIS_PER_SECOND
	}
}

/** Provider-qualified total for exactly [window]. A null result means no affirmative evidence. */
internal data class AmbientStepsProviderAggregate(
	val provider: AmbientStepsProvider,
	val window: AmbientStepsProviderReadWindow,
	val stepCount: Long,
	val observedAtMs: Long,
	val logicalIntervalId: String = listOf(
		"ambient-steps-provider-window-v1",
		provider.name,
		window.startTimeMs,
		window.endTimeMs,
	).joinToString(":"),
) {
	init {
		require(stepCount >= 0L)
		require(observedAtMs >= window.endTimeMs)
		require(logicalIntervalId.isNotBlank())
	}
}

internal interface AmbientStepsProviderReader {
	val provider: AmbientStepsProvider

	/** Returns null when the provider supplies no affirmative aggregate for this exact window. */
	suspend fun read(
		window: AmbientStepsProviderReadWindow,
		observedAtMs: Long,
	): AmbientStepsProviderAggregate?
}

/**
 * Reads only current-device Health Connect Steps. Both the historical android origin and the
 * current application-scoped SPN are passed to aggregate; no origin identifier is persisted.
 */
@Singleton
internal class HealthConnectAmbientStepsProviderReader internal constructor(
	private val aggregate: suspend (AmbientStepsProviderReadWindow) -> Long?,
) : AmbientStepsProviderReader {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(
		aggregate = { window -> aggregateCurrentDeviceSteps(context, window) },
	)

	override val provider: AmbientStepsProvider =
		AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS

	override suspend fun read(
		window: AmbientStepsProviderReadWindow,
		observedAtMs: Long,
	): AmbientStepsProviderAggregate? {
		require(observedAtMs >= window.endTimeMs)
		val count = aggregate(window) ?: return null
		return AmbientStepsProviderAggregate(provider, window, count, observedAtMs)
	}
}

/** Raw aggregate shape kept independent of Google classes for deterministic normalization tests. */
internal data class LocalRecordingAggregateBucket(
	val startTimeMs: Long,
	val endTimeMs: Long,
	val stepValues: List<Long>,
)

/** Reads one exact Local Recording time bucket; empty buckets remain missing rather than zero. */
@Singleton
internal class LocalRecordingAmbientStepsProviderReader internal constructor(
	private val aggregate: suspend (
		AmbientStepsProviderReadWindow,
	) -> List<LocalRecordingAggregateBucket>,
) : AmbientStepsProviderReader {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(
		aggregate = { window -> readLocalRecordingSteps(context, window) },
	)

	override val provider: AmbientStepsProvider =
		AmbientStepsProvider.LOCAL_RECORDING_STEPS

	override suspend fun read(
		window: AmbientStepsProviderReadWindow,
		observedAtMs: Long,
	): AmbientStepsProviderAggregate? {
		require(observedAtMs >= window.endTimeMs)
		val buckets = aggregate(window)
		if (buckets.isEmpty()) return null
		check(buckets.size == 1) { "Local Recording returned overlapping aggregate buckets" }
		val bucket = buckets.single()
		check(bucket.startTimeMs == window.startTimeMs && bucket.endTimeMs == window.endTimeMs) {
			"Local Recording returned a mismatched aggregate window"
		}
		if (bucket.stepValues.isEmpty()) return null
		val count = bucket.stepValues.fold(0L) { total, value ->
			check(value >= 0L) { "Local Recording returned a negative Steps aggregate" }
			Math.addExact(total, value)
		}
		return AmbientStepsProviderAggregate(provider, window, count, observedAtMs)
	}
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private suspend fun aggregateCurrentDeviceSteps(
	context: Context,
	window: AmbientStepsProviderReadWindow,
): Long? {
	check(
		SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >=
			HEALTH_CONNECT_CURRENT_DEVICE_MIN_EXTENSION,
	) { "Health Connect current-device origin API is unavailable" }
	val manager = requireNotNull(context.getSystemService(HealthConnectManager::class.java)) {
		"Health Connect manager is unavailable"
	}
	val currentDeviceOrigin = requireNotNull(
		manager.currentDeviceDataSource?.deviceDataOrigin?.packageName,
	) { "Health Connect current-device origin is unavailable" }
	val origins = setOf(DataOrigin(LEGACY_ANDROID_HEALTH_ORIGIN), DataOrigin(currentDeviceOrigin))
	val response = HealthConnectClient.getOrCreate(context).aggregate(
		AggregateRequest(
			metrics = setOf(StepsRecord.COUNT_TOTAL),
			timeRangeFilter = TimeRangeFilter.between(
				Instant.ofEpochMilli(window.startTimeMs),
				Instant.ofEpochMilli(window.endTimeMs),
			),
			dataOriginFilter = origins,
		),
	)
	return response[StepsRecord.COUNT_TOTAL]
}

private suspend fun readLocalRecordingSteps(
	context: Context,
	window: AmbientStepsProviderReadWindow,
): List<LocalRecordingAggregateBucket> {
	val request = LocalDataReadRequest.Builder()
		.aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
		.bucketByTime(window.durationSeconds, TimeUnit.SECONDS)
		.setTimeRange(window.startTimeMs, window.endTimeMs, TimeUnit.MILLISECONDS)
		.build()
	val response = FitnessLocal.getLocalRecordingClient(context).readData(request).await()
	check(response.status.isSuccess) { "Local Recording Steps read failed" }
	return response.buckets.map { bucket ->
		LocalRecordingAggregateBucket(
			startTimeMs = bucket.getStartTime(TimeUnit.MILLISECONDS),
			endTimeMs = bucket.getEndTime(TimeUnit.MILLISECONDS),
			stepValues = bucket.dataSets.flatMap { dataSet ->
				dataSet.dataPoints.map { point ->
					point.getValue(LocalField.FIELD_STEPS).asInt().toLong()
				}
			},
		)
	}
}

private const val HEALTH_CONNECT_CURRENT_DEVICE_MIN_EXTENSION = 11
private const val LEGACY_ANDROID_HEALTH_ORIGIN = "android"
