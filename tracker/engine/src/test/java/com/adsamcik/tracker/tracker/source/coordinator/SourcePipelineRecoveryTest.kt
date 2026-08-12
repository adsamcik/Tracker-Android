package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceProjectionStateDao
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameOutboxDispatcher
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourcePipelineRecoveryTest {
	@Test
	fun `complete coordinator drain empties every available outbox batch`() = runTest {
		val projectionStateDao = mockk<SourceProjectionStateDao>()
		val database = mockk<AppDatabase>()
		val coordinator = mockk<TrackingCoordinator>()
		val activityEffects = mockk<ActivityAutomationOutboxDispatcher>()
		val trackingFrameEffects = mockk<EventTrackingFrameOutboxDispatcher>()
		every { database.sourceProjectionStateDao() } returns projectionStateDao
		coEvery { projectionStateDao.completeOutboxKinds(any(), any()) } returns 3
		coEvery { trackingFrameEffects.completeTerminalLegacyEffects() } returns 2
		coEvery { coordinator.drainAvailable(any()) } returns CoordinatorDrainResult.Complete(20, 5)
		coEvery { activityEffects.drain(100) } returnsMany listOf(100, 2)
		coEvery { trackingFrameEffects.drain(100) } returnsMany listOf(100, 100, 1)

		val result = SourcePipelineRecovery(
			database,
			coordinator,
			activityEffects,
			trackingFrameEffects,
		).drainCommittedWork()

		result.activityEffectsDelivered shouldBe 102
		result.trackingFramesDelivered shouldBe 201
		result.completedProjectionRecords shouldBe 5
		coVerify(exactly = 2) { activityEffects.drain(100) }
		coVerify(exactly = 3) { trackingFrameEffects.drain(100) }
	}
}
