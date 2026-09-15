package com.adsamcik.tracker.tracker.reconstruction

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CompletedTrackerSession
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.TrackerStateEvent
import com.adsamcik.tracker.shared.base.database.data.TrajectoryReconstructionRunEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalTrajectoryReconstructionRunnerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runBlocking {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
	}

	@After
	fun tearDown() {
		if (::database.isInitialized && database.isOpen) {
			database.close()
		}
	}

	@Test
	fun `legacy step intervals cannot influence or link trajectory reconstruction`() = runBlocking {
		database.locationObservationDao().insert(locationObservation())
		val runner = HistoricalTrajectoryReconstructionRunner(database)
		val baseline = runner.reconstruct(session())
		val baselineStates = database.trajectoryReconstructionDao().states(baseline.runId)
			.map { state -> state.copy(id = 0L, runId = "normalized") }

		database.withTransaction {
			database.stepIntervalDao().insert(legacyStepInterval())
			check(database.sourceEvidenceStateDao().incrementRevision(LOCATION_WALL_MS) == 1)
		}
		val outcome = runner.reconstruct(session())
		val statesWithLegacyStep = database.trajectoryReconstructionDao().states(outcome.runId)
			.map { state -> state.copy(id = 0L, runId = "normalized") }

		outcome.stateCount shouldBe 1
		outcome.sourceRevision shouldBe 1L
		statesWithLegacyStep shouldBe baselineStates
		database.openHelper.readableDatabase.query(
			"""
			SELECT source_start_ms, source_end_ms, configuration_version
			FROM trajectory_reconstruction_run
			WHERE run_id = ?
			""".trimIndent(),
			arrayOf<Any>(outcome.runId),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe SESSION_START_WALL_MS
			cursor.getLong(1) shouldBe SESSION_END_WALL_MS
			cursor.getString(2) shouldBe runner.configurationVersion
		}
		database.openHelper.readableDatabase.query(
			"SELECT step_interval_id FROM trajectory_source_link WHERE run_id = ?",
			arrayOf<Any>(outcome.runId),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.isNull(0) shouldBe true
		}
	}

	@Test
	fun `step influenced v1 run does not suppress location activity composition`() = runBlocking {
		val runner = HistoricalTrajectoryReconstructionRunner(database)
		database.trackerStateEventDao().insert(
			trackerStateEvent(TrackerStateEvent.START, SESSION_START_ELAPSED_NANOS, SESSION_START_WALL_MS),
		)
		database.trackerStateEventDao().insert(
			trackerStateEvent(TrackerStateEvent.STOP, SESSION_END_ELAPSED_NANOS, SESSION_END_WALL_MS),
		)
		database.trajectoryReconstructionDao().insertRun(
			TrajectoryReconstructionRunEntity(
				runId = "legacy-step-influenced-run",
				sourceStartMs = SESSION_START_WALL_MS,
				sourceEndMs = SESSION_END_WALL_MS,
				sourceClockDomainId = CLOCK_DOMAIN_ID,
				sourceStartElapsedRealtimeNanos = SESSION_START_ELAPSED_NANOS,
				sourceEndElapsedRealtimeNanos = SESSION_END_ELAPSED_NANOS,
				sourceRevision = 0L,
				algorithmVersion = runner.algorithmVersion,
				configurationVersion = "default_v1",
				permissionBranch = "PRECISE",
				status = "COMPLETED",
				createdAtMs = SESSION_END_WALL_MS,
				completedAtMs = SESSION_END_WALL_MS,
			),
		)

		val pending = database.trackerStateEventDao().getCompletedSessionsAwaitingReconstruction(
			algorithmVersion = runner.algorithmVersion,
			configurationVersion = runner.configurationVersion,
		)

		runner.configurationVersion shouldBe "default_v1+location_activity_v2"
		pending.single().clockDomainId shouldBe CLOCK_DOMAIN_ID
	}

	private fun session() = CompletedTrackerSession(
		clockDomainId = CLOCK_DOMAIN_ID,
		startElapsedRealtimeNanos = SESSION_START_ELAPSED_NANOS,
		endElapsedRealtimeNanos = SESSION_END_ELAPSED_NANOS,
		startWallTimeMs = SESSION_START_WALL_MS,
		endWallTimeMs = SESSION_END_WALL_MS,
	)

	private fun locationObservation() = LocationObservation(
		fixTimeMs = LOCATION_WALL_MS,
		fixElapsedRealtimeNanos = LOCATION_ELAPSED_NANOS,
		receivedAtMs = LOCATION_WALL_MS,
		receivedElapsedRealtimeNanos = LOCATION_ELAPSED_NANOS,
		deliveryAgeMs = 0L,
		latE7 = 500_000_000,
		lonE7 = 140_000_000,
		rawAltitudeM = null,
		hAccM = 5f,
		vAccM = null,
		speedMps = 0f,
		speedAccuracyMps = 0.5f,
		provider = "gps",
		acquisitionMode = "BALANCED",
		requestPriority = "BALANCED_POWER_ACCURACY",
		permissionPrecision = "PRECISE",
		batchIndex = 0,
		batchSize = 1,
		isMock = false,
		ingressDisposition = "DELIVERED_VALID",
		estimatorVersion = 1,
		calibrationVersion = 1,
		createdAt = LOCATION_WALL_MS,
		sourceSignalId = "location-signal",
		sourceEventId = "location-event",
		callbackId = "location-callback",
		clockDomainId = CLOCK_DOMAIN_ID,
		bootClockDomainId = BOOT_CLOCK_DOMAIN_ID,
	)

	private fun legacyStepInterval() = StepInterval(
		startTimeMs = LEGACY_STEP_START_WALL_MS,
		endTimeMs = LEGACY_STEP_END_WALL_MS,
		stepCount = 42,
		sensorValueStart = 100,
		sensorValueEnd = 142,
		sensorReset = false,
		createdAt = LOCATION_WALL_MS,
		sourceSignalId = "legacy-step-signal",
		observationStamp = ObservationStampColumns(
			sourceFirstElapsedRealtimeNanos = LOCATION_ELAPSED_NANOS - 100_000_000L,
			sourceElapsedRealtimeNanos = LOCATION_ELAPSED_NANOS + 100_000_000L,
			receivedElapsedRealtimeNanos = LOCATION_ELAPSED_NANOS + 100_000_000L,
			clockDomainId = CLOCK_DOMAIN_ID,
			bootClockDomainId = BOOT_CLOCK_DOMAIN_ID,
		),
	)

	private fun trackerStateEvent(state: String, elapsedNanos: Long, wallTimeMs: Long) =
		TrackerStateEvent(
			clockDomainId = CLOCK_DOMAIN_ID,
			elapsedRealtimeNanos = elapsedNanos,
			wallTimeMs = wallTimeMs,
			state = state,
			policy = "BALANCED",
			createdAtMs = wallTimeMs,
		)

	private companion object {
		const val CLOCK_DOMAIN_ID = "clock-1"
		const val BOOT_CLOCK_DOMAIN_ID = "boot-1"
		const val SESSION_START_ELAPSED_NANOS = 1_000_000_000L
		const val LOCATION_ELAPSED_NANOS = 2_000_000_000L
		const val SESSION_END_ELAPSED_NANOS = 3_000_000_000L
		const val SESSION_START_WALL_MS = 10_000L
		const val LOCATION_WALL_MS = 11_000L
		const val SESSION_END_WALL_MS = 12_000L
		const val LEGACY_STEP_START_WALL_MS = 1L
		const val LEGACY_STEP_END_WALL_MS = 50_000L
	}
}
