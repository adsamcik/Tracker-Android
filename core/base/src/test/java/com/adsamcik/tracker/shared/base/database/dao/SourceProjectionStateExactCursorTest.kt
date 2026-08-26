package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
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
class SourceProjectionStateExactCursorTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: SourceProjectionStateDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.sourceProjectionStateDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun exactCursorCasRejectsEveryMismatchedExecutionFence() = runTest {
		val lane = lane()
		dao.installProductLane(lane)

		advance(lane, captureModeMask = lane.captureModeMask + 1L) shouldBe 0
		advance(lane, productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW) shouldBe 0
		advance(lane, activatedRolloutRevision = lane.activatedRolloutRevision + 1L) shouldBe 0
		advance(lane, activationOrdinal = lane.activationOrdinal + 1L) shouldBe 0
		advance(lane, expectedCutoffOrdinal = 9L) shouldBe 0
		advance(lane, expectedCurrentOrdinal = 1L) shouldBe 0

		advance(lane) shouldBe 1
		dao.activeProductLane(STEPS_SOURCE_KIND)?.contiguousAdmissionOrdinal shouldBe 1L
		advance(lane) shouldBe 0
	}

	@Test
	fun terminalFailureRangeReturnsTheFirstBlockingOrdinalOnly() = runTest {
		listOf(8L, 3L, 5L).forEach { ordinal ->
			dao.saveFailure(
				SourceProjectionFailureEntity(
					projectionId = WRITER_ID,
					projectionVersion = WRITER_VERSION,
					admissionOrdinal = ordinal,
					attemptCount = 1,
					failureCode = "terminal-$ordinal",
					terminal = ordinal != 5L,
					lastAttemptAtMs = ordinal,
				),
			)
		}

		dao.firstTerminalFailureAfterThrough(WRITER_ID, WRITER_VERSION, 0L, 10L)
			?.admissionOrdinal shouldBe 3L
		dao.firstTerminalFailureAfterThrough(WRITER_ID, WRITER_VERSION, 3L, 7L) shouldBe null
		dao.firstTerminalFailureAfterThrough(WRITER_ID, WRITER_VERSION, 3L, 8L)
			?.admissionOrdinal shouldBe 8L
	}

	private suspend fun advance(
		lane: SourceProductProjectionLaneEntity,
		captureModeMask: Long = lane.captureModeMask,
		productStage: String = lane.productStage,
		activatedRolloutRevision: Long = lane.activatedRolloutRevision,
		activationOrdinal: Long = lane.activationOrdinal,
		expectedCutoffOrdinal: Long? = lane.captureAdmissionCutoffOrdinal,
		expectedCurrentOrdinal: Long = lane.contiguousAdmissionOrdinal,
	): Int = dao.advanceExactProductLaneCursor(
		sourceKind = lane.sourceKind,
		bindingGeneration = lane.bindingGeneration,
		projectionId = lane.projectionId,
		projectionVersion = lane.projectionVersion,
		captureModeMask = captureModeMask,
		productStage = productStage,
		activatedRolloutRevision = activatedRolloutRevision,
		activationOrdinal = activationOrdinal,
		expectedCutoffOrdinal = expectedCutoffOrdinal,
		expectedCurrentOrdinal = expectedCurrentOrdinal,
		throughOrdinal = 1L,
		updatedAtMs = 2L,
	)

	private fun lane() = SourceProductProjectionLaneEntity(
		sourceKind = STEPS_SOURCE_KIND,
		bindingGeneration = 1L,
		projectionId = WRITER_ID,
		projectionVersion = WRITER_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = 2L,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = 0L,
		captureAdmissionCutoffOrdinal = null,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		installedAtMs = 1L,
		updatedAtMs = 1L,
	)

	private companion object {
		const val STEPS_SOURCE_KIND = 3
		const val WRITER_ID = "steps-session-facts"
		const val WRITER_VERSION = 1
	}
}
