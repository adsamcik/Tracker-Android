package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrajectoryReconstructionRunEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectoryStateEntity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrajectoryReconstructionDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: TrajectoryReconstructionDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.trajectoryReconstructionDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `raw retention removes every derived row whose source range crosses cutoff`() = runTest {
		dao.insertRun(run("crossing", sourceStartMs = 100L, sourceEndMs = 1_100L))
		dao.insertRun(run("retained", sourceStartMs = 1_000L, sourceEndMs = 1_100L))
		dao.insertStates(listOf(state("crossing"), state("retained")))

		dao.deleteWithSourceBefore(beforeMs = 500L) shouldBe 1

		dao.states("crossing").shouldBeEmpty()
		dao.states("retained").size shouldBe 1
	}

	private fun run(
		runId: String,
		sourceStartMs: Long,
		sourceEndMs: Long,
	) = TrajectoryReconstructionRunEntity(
		runId = runId,
		sourceStartMs = sourceStartMs,
		sourceEndMs = sourceEndMs,
		sourceRevision = 1L,
		algorithmVersion = "algorithm",
		configurationVersion = "configuration",
		permissionBranch = "PRECISE",
		status = "COMPLETED",
		createdAtMs = 1L,
		completedAtMs = 2L,
	)

	private fun state(runId: String) = TrajectoryStateEntity(
		runId = runId,
		stateIndex = 0,
		estimateKind = "SMOOTHED",
		sourceEventId = "event-$runId",
		timeMs = 1L,
		elapsedRealtimeNanos = 1L,
		clockDomainId = "session",
		bootClockDomainId = "boot",
		latE7 = 0,
		lonE7 = 0,
		velocityEastMps = 0.0,
		velocityNorthMps = 0.0,
		covarianceEastEastM2 = 1.0,
		covarianceEastNorthM2 = 0.0,
		covarianceNorthNorthM2 = 1.0,
		stationaryProbability = 1.0,
		observationWeight = 1.0,
		observationHealth = "HEALTHY",
	)
}
