package com.adsamcik.tracker.app.debug

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.map.MapSettingsRepository
import com.adsamcik.tracker.shared.preferences.map.MapSettingsState
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingStateProto
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Debug-only local data injector used by QC and developers.
 *
 * All methods are guarded by [BuildConfig.DEBUG] and this class is compiled only from src/debug.
 */
class TestDataSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val onboardingRepository: com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository,
    private val trackerSettingsRepository: TrackerSettingsRepository,
    private val mapSettingsRepository: MapSettingsRepository,
    private val trackingParamsRepository: TrackingParamsRepository,
    private val retentionConfigStore: RetentionConfigStore,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun seedSessions(
        count: Int,
        distanceKm: Double = DEFAULT_DISTANCE_KM,
        profile: String = DEFAULT_PROFILE,
    ) {
        if (!BuildConfig.DEBUG) return
        val safeCount = count.coerceIn(1, MAX_SESSIONS_PER_SEED)
        val safeDistanceM = (distanceKm.coerceIn(MIN_DISTANCE_KM, MAX_DISTANCE_KM) * METERS_PER_KM).toFloat()
        val normalizedProfile = profile.lowercase().trim().ifBlank { DEFAULT_PROFILE }
        val speedMps = normalizedProfile.speedMps()
        val sampleIntervalMs = normalizedProfile.sampleIntervalMs()
        val primaryActivity = normalizedProfile.detectedActivity().value
        val steps = normalizedProfile.stepsFor(safeDistanceM)
        val durationMs = ((safeDistanceM / speedMps) * MILLIS_PER_SECOND).roundToLong()
            .coerceAtLeast(MIN_SESSION_DURATION_MS)
        val sampleCount = (durationMs / sampleIntervalMs).toInt().coerceIn(MIN_SAMPLE_COUNT, MAX_SAMPLE_COUNT)
        val now = System.currentTimeMillis()
        val seededDays = linkedSetOf<Long>()

        withContext(dispatchers.io) {
            appDatabase.withTransaction {
                repeat(safeCount) { index ->
                    val spacingMs = SESSION_SPACING_MS * (safeCount - index)
                    val startMs = now - spacingMs - durationMs
                    val endMs = startMs + durationMs
                    val createdAt = now
                    val route = generateRoute(
                        profile = normalizedProfile,
                        distanceM = safeDistanceM.toDouble(),
                        sampleCount = sampleCount,
                        sessionIndex = index,
                    )

                    appDatabase.trackerRunDao().insert(
                        TrackerRun(
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            policy = "DEBUG_SEED_${normalizedProfile.uppercase()}",
                            policyParams = """{"distanceKm":${safeDistanceM / METERS_PER_KM},"profile":"$normalizedProfile"}""",
                            userInitiated = true,
                            createdAt = createdAt,
                        )
                    )

                    appDatabase.locationSampleDao().insert(
                        route.mapIndexed { pointIndex, point ->
                            val timestamp = startMs + ((durationMs * pointIndex) / (sampleCount - 1).coerceAtLeast(1))
                            LocationSample(
                                timeMs = timestamp,
                                elapsedRealtimeNanos = timestamp * NANOS_PER_MILLI,
                                latE7 = point.lat.toE7(),
                                lonE7 = point.lon.toE7(),
                                altitudeM = point.altitudeM,
                                rawGpsAltitudeM = point.altitudeM,
                                hAccM = normalizedProfile.accuracyM(),
                                vAccM = 8f,
                                speedMps = speedMps,
                                speedAccuracyMps = 0.6f,
                                provider = "debug",
                                quality = SampleQuality.HIGH,
                                motionState = MotionState.MOVING,
                                policy = "DEBUG_SEED",
                                bucketId = null,
                                createdAt = createdAt,
                            )
                        }
                    )

                    appDatabase.sessionSegmentDao().insert(
                        SessionSegment(
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            distanceM = safeDistanceM,
                            steps = steps,
                            primaryActivity = primaryActivity,
                            activityConfidence = 95,
                            sampleCount = sampleCount,
                            source = SegmentSource.USER_CREATED,
                            inferenceVersion = "debug-seed-v1",
                            createdAt = createdAt,
                        )
                    )

                    if (steps > 0) {
                        val sensorStart = index * 10_000
                        appDatabase.stepIntervalDao().insert(
                            StepInterval(
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                stepCount = steps,
                                sensorValueStart = sensorStart,
                                sensorValueEnd = sensorStart + steps,
                                sensorReset = false,
                                createdAt = createdAt,
                            )
                        )
                    }

                    seededDays += startMs / DAY_MS
                }

                refreshDailySummaries(seededDays, now)
            }
        }
    }

    suspend fun resetOnboarding() {
        if (!BuildConfig.DEBUG) return
        withContext(dispatchers.io) {
            clearLegacyOnboardingPreferences()
            markOnboardingIncomplete()
            runCatching { onboardingRepository.ensureInitialized() }
        }
    }

    suspend fun resetAllPreferences() {
        if (!BuildConfig.DEBUG) return
        withContext(dispatchers.io) {
            clearLegacyPreferences()
            resetRepositoryBackedPreferences()
            deleteKnownDataStoreFiles()
        }
    }

    suspend fun toggleHasTrackedFlag(value: Boolean) {
        if (!BuildConfig.DEBUG) return
        if (value) {
            seedSessions(count = 1, distanceKm = 1.2, profile = "walk")
        } else {
            resetCollectedTrackingData()
        }
    }

    suspend fun resetAllData() {
        if (!BuildConfig.DEBUG) return
        withContext(dispatchers.io) {
            resetCollectedTrackingData()
            clearLegacyPreferences()
            resetRepositoryBackedPreferences()
            deleteKnownDataStoreFiles()
        }
    }

    private suspend fun resetCollectedTrackingData() {
        withContext(dispatchers.io) {
            AppDatabase.deleteAllCollectedData(context)
        }
    }

    private suspend fun refreshDailySummaries(days: Set<Long>, now: Long) {
        days.forEach { day ->
            val start = day * DAY_MS
            val end = start + DAY_MS - 1
            val segments = appDatabase.sessionSegmentDao().getAllBetween(start, end)
            appDatabase.dailySummaryDao().upsert(
                dateEpochDay = day,
                totalDistanceM = segments.sumOf { it.distanceM.toDouble() }.toFloat(),
                totalSteps = segments.sumOf { it.steps ?: 0 },
                totalDurationMs = segments.sumOf { it.endTimeMs - it.startTimeMs },
                tripCount = segments.size,
                activeTrackingMs = segments.sumOf { it.endTimeMs - it.startTimeMs },
                lastUpdatedMs = now,
            )
        }
    }

    private fun clearLegacyPreferences() {
        Preferences(context).edit { clear() }
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        clearLegacyOnboardingPreferences()
    }

    private fun clearLegacyOnboardingPreferences() {
        context.getSharedPreferences(ONBOARDING_SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private suspend fun resetRepositoryBackedPreferences() {
        markOnboardingIncomplete()

        val trackerDefaults = TrackerSettingsState.DEFAULT
        trackerSettingsRepository.setAutoUnitSwitch(trackerDefaults.autoUnitSwitch)
        trackerSettingsRepository.setLengthSystem(trackerDefaults.lengthSystem)
        trackerSettingsRepository.setSpeedFormat(trackerDefaults.speedFormat)

        val mapDefaults = MapSettingsState()
        mapSettingsRepository.setQuality(mapDefaults.quality)
        mapSettingsRepository.setMaxHeatPoints(mapDefaults.maxHeatPoints)
        mapSettingsRepository.setVisitThresholdSeconds(mapDefaults.visitThresholdSeconds)

        trackingParamsRepository.update { TrackingParamsState() }
        retentionConfigStore.update { RetentionConfigState() }
    }

    private suspend fun markOnboardingIncomplete() {
        val activeDataStore = activeOnboardingDataStore()
        if (activeDataStore != null) {
            activeDataStore.updateData { current ->
                current.toBuilder()
                    .setCompleted(false)
                    .setCompletedTime(0L)
                    .setLegacyMigrated(true)
                    .build()
            }
        }
        // Note: previously a writeOnboardingIncompleteState() fallback used proto-java
        // CodedOutputStream APIs which are not on the classpath (project uses proto-lite via
        // DataStore). The DataStore path above is the only supported way; if it returns null
        // the test data hasn't been initialized yet, so there's nothing to reset.
    }

    private fun deleteKnownDataStoreFiles() {
        val dir = File(context.filesDir, DATASTORE_DIR_NAME)
        if (!dir.exists()) return

        KNOWN_DATASTORE_FILES.forEach { fileName ->
            dir.listFiles { file ->
                file.name == fileName ||
                    file.name.startsWith("$fileName.") ||
                    file.name.startsWith("$fileName-")
            }?.forEach { it.delete() }
            dataStoreFile(fileName).delete()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun activeOnboardingDataStore(): DataStore<OnboardingStateProto>? =
        runCatching {
            val delegate = dataStoreDelegate(
                className = "com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepositoryKt",
                fieldName = "onboardingDataStore\$delegate",
            )
            dataStoreInstanceField(delegate).get(delegate) as? DataStore<OnboardingStateProto>
        }.getOrNull()

    private fun dataStoreDelegate(className: String, fieldName: String): Any {
        val fileClass = Class.forName(className)
        val delegateField = fileClass.getDeclaredField(fieldName).apply { isAccessible = true }
        return delegateField.get(null)
    }

    private fun dataStoreInstanceField(delegate: Any) =
        delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }

    private fun dataStoreFile(fileName: String): File =
        File(File(context.filesDir, DATASTORE_DIR_NAME), fileName)

    private fun generateRoute(
        profile: String,
        distanceM: Double,
        sampleCount: Int,
        sessionIndex: Int,
    ): List<RoutePoint> {
        val centerLat = PRAGUE_LAT + sessionIndex * 0.0025
        val centerLon = PRAGUE_LON + sessionIndex * 0.003
        val radiusM = when (profile) {
            "drive" -> distanceM / 5.5
            "run" -> distanceM / (2 * PI)
            else -> distanceM / (2 * PI)
        }.coerceAtLeast(120.0)
        val latRadius = radiusM / METERS_PER_DEGREE_LAT
        val lonRadius = radiusM / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(centerLat)))
        val startAngle = sessionIndex * 0.41

        return List(sampleCount) { index ->
            val progress = index.toDouble() / (sampleCount - 1).coerceAtLeast(1)
            val angle = when (profile) {
                "drive" -> startAngle + progress * PI * 1.35
                else -> startAngle + progress * 2 * PI
            }
            val drift = if (profile == "drive") progress * 0.035 else 0.0
            RoutePoint(
                lat = centerLat + sin(angle) * latRadius + drift,
                lon = centerLon + cos(angle) * lonRadius + drift * 0.7,
                altitudeM = (210f + (sin(angle * 2) * 14f)).toFloat(),
            )
        }
    }

    private fun String.detectedActivity(): DetectedActivity = when (this) {
        "run", "running" -> DetectedActivity.RUNNING
        "drive", "car", "vehicle" -> DetectedActivity.IN_VEHICLE
        else -> DetectedActivity.WALKING
    }

    private fun String.speedMps(): Float = when (this) {
        "run", "running" -> 2.9f
        "drive", "car", "vehicle" -> 13.8f
        else -> 1.4f
    }

    private fun String.sampleIntervalMs(): Long = when (this) {
        "drive", "car", "vehicle" -> 30_000L
        "run", "running" -> 10_000L
        else -> 20_000L
    }

    private fun String.accuracyM(): Float = when (this) {
        "drive", "car", "vehicle" -> 7f
        else -> 5f
    }

    private fun String.stepsFor(distanceM: Float): Int = when (this) {
        "drive", "car", "vehicle" -> 0
        "run", "running" -> (distanceM / 1.15f).roundToInt()
        else -> (distanceM / 0.78f).roundToInt()
    }

    private fun Double.toE7(): Int = (this * E7).roundToInt()

    private data class RoutePoint(
        val lat: Double,
        val lon: Double,
        val altitudeM: Float,
    )

    private companion object {
        const val DEFAULT_DISTANCE_KM = 5.0
        const val DEFAULT_PROFILE = "walk"
        const val MAX_SESSIONS_PER_SEED = 50
        const val MIN_DISTANCE_KM = 0.1
        const val MAX_DISTANCE_KM = 50.0
        const val METERS_PER_KM = 1_000.0
        const val METERS_PER_DEGREE_LAT = 111_320.0
        const val MILLIS_PER_SECOND = 1_000.0
        const val NANOS_PER_MILLI = 1_000_000L
        const val E7 = 10_000_000.0
        const val MIN_SESSION_DURATION_MS = 10 * 60 * 1_000L
        const val SESSION_SPACING_MS = 4 * 60 * 60 * 1_000L
        const val DAY_MS = 24 * 60 * 60 * 1_000L
        const val MIN_SAMPLE_COUNT = 12
        const val MAX_SAMPLE_COUNT = 240
        const val PRAGUE_LAT = 50.0755
        const val PRAGUE_LON = 14.4378
        const val DATASTORE_DIR_NAME = "datastore"
        const val ONBOARDING_SHARED_PREFS_NAME = "onboarding"
        const val ONBOARDING_DATASTORE_FILE = "onboarding_state.pb"

        val KNOWN_DATASTORE_FILES = listOf(
            "map_settings.pb",
            ONBOARDING_DATASTORE_FILE,
            "retention_config.pb",
            "tracker_settings.pb",
            "tracking_params.pb",
        )

    }
}
