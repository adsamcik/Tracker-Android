package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrackerStateEvent
import com.adsamcik.tracker.shared.base.database.data.TrajectoryReconstructionRunEntity
import io.kotest.matchers.collections.shouldContainExactly
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
class TrackerStateEventDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: TrackerStateEventDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.trackerStateEventDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `completed session spans policy transitions and uses monotonic lifecycle bounds`() = runTest {
		dao.insert(event("session-1", 1_000L, 10_000L, TrackerStateEvent.START))
		dao.insert(event("session-1", 2_000L, 11_000L, TrackerStateEvent.POLICY_TRANSITION))
		dao.insert(event("session-1", 5_000L, 4_000L, TrackerStateEvent.STOP))

		val session = dao.getLatestCompletedSession()

		requireNotNull(session)
		session.clockDomainId shouldBe "session-1"
		session.startElapsedRealtimeNanos shouldBe 1_000L
		session.endElapsedRealtimeNanos shouldBe 5_000L
		session.startWallTimeMs shouldBe 10_000L
		session.endWallTimeMs shouldBe 4_000L
	}

	@Test
	fun `pending query skips only the current completed algorithm version`() = runTest {
		dao.insert(event("session-1", 1_000L, 1_000L, TrackerStateEvent.START))
		dao.insert(event("session-1", 2_000L, 2_000L, TrackerStateEvent.STOP))
		dao.insert(event("session-2", 3_000L, 3_000L, TrackerStateEvent.START))
		dao.insert(event("session-2", 4_000L, 4_000L, TrackerStateEvent.STOP))
		database.trajectoryReconstructionDao().insertRun(
			reconstructionRun(
				clockDomainId = "session-1",
				algorithmVersion = "current",
				configurationVersion = "configuration",
			),
		)
		database.trajectoryReconstructionDao().insertRun(
			reconstructionRun(
				clockDomainId = "session-2",
				algorithmVersion = "old",
				configurationVersion = "configuration",
			),
		)

		val pending = dao.getCompletedSessionsAwaitingReconstruction(
			algorithmVersion = "current",
			configurationVersion = "configuration",
		)

		pending.map { it.clockDomainId } shouldContainExactly listOf("session-2")
	}

	private fun event(
		clockDomainId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		state: String,
	) = TrackerStateEvent(
		clockDomainId = clockDomainId,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = wallTimeMs,
		state = state,
		policy = "PASSIVE_LOW",
		createdAtMs = wallTimeMs,
	)

	private fun reconstructionRun(
		clockDomainId: String,
		algorithmVersion: String,
		configurationVersion: String,
	) = TrajectoryReconstructionRunEntity(
		runId = "$clockDomainId-$algorithmVersion",
		sourceStartMs = 0L,
		sourceEndMs = 10_000L,
		sourceClockDomainId = clockDomainId,
		sourceStartElapsedRealtimeNanos = 0L,
		sourceEndElapsedRealtimeNanos = 10_000L,
		sourceRevision = 1L,
		algorithmVersion = algorithmVersion,
		configurationVersion = configurationVersion,
		permissionBranch = "PRECISE",
		status = "COMPLETED",
		createdAtMs = 1L,
		completedAtMs = 2L,
	)
}
