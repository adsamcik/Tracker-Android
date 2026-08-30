package com.adsamcik.tracker.tracker.presentation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import io.kotest.assertions.throwables.shouldThrow
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
class SessionPresentationLifecycleTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: SessionPresentationLifecycle

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		lifecycle = SessionPresentationLifecycle(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `open atomically binds one segment and same-run retry resumes it`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)

		val opened = lifecycle.openOrResumeExact(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ONE,
			requestedSessionSegmentId = null,
			newSegment = segment(LOGICAL_ID, RUN_ONE),
		)

		opened.created shouldBe true
		opened.binding.sessionSegmentId shouldBe opened.segment.id
		val boundRun = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ONE))
		boundRun.sessionSegmentId shouldBe opened.segment.id
		boundRun.runRevision shouldBe 7L

		val retried = lifecycle.openOrResumeExact(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ONE,
			requestedSessionSegmentId = opened.segment.id,
			newSegment = segment(LOGICAL_ID, RUN_ONE),
		)

		retried.created shouldBe false
		retried.binding shouldBe opened.binding
		database.sessionSegmentDao().countTotal() shouldBe 1L
	}

	@Test
	fun `unbound Room owner rejects a descriptor supplied segment without inserting`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)

		shouldThrow<IllegalStateException> {
			lifecycle.openOrResumeExact(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ONE,
				requestedSessionSegmentId = 99L,
				newSegment = segment(LOGICAL_ID, RUN_ONE),
			)
		}

		database.sessionSegmentDao().countTotal() shouldBe 0L
		database.sourceSessionDao().serviceRun(RUN_ONE)?.sessionSegmentId shouldBe null
	}

	@Test
	fun `replacement physical run receives another segment under the same logical entry`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		val first = lifecycle.openOrResumeExact(
			LOGICAL_ID,
			RUN_ONE,
			null,
			segment(LOGICAL_ID, RUN_ONE),
		)
		val dao = database.sourceSessionDao()
		val firstRun = requireNotNull(dao.serviceRun(RUN_ONE))
		dao.updateServiceRun(
			firstRun.copy(
				state = SessionLifecycleState.FINALIZED.name,
				completedAtMs = 2_000L,
				completionReason = "PROCESS_REPLACED",
			),
		) shouldBe 1
		dao.insertServiceRun(serviceRun(LOGICAL_ID, RUN_TWO))
		val logical = requireNotNull(dao.session(LOGICAL_ID))
		dao.updateSession(
			logical.copy(
				state = SessionLifecycleState.ACTIVE.name,
				currentServiceRunId = RUN_TWO,
				lifecycleRevision = logical.lifecycleRevision + 1L,
			),
		) shouldBe 1

		val second = lifecycle.openOrResumeExact(
			LOGICAL_ID,
			RUN_TWO,
			null,
			segment(LOGICAL_ID, RUN_TWO),
		)

		second.binding.sessionSegmentId shouldBe second.segment.id
		(second.segment.id == first.segment.id) shouldBe false
		first.segment.logicalTrackingId shouldBe second.segment.logicalTrackingId
		database.sessionSegmentDao().countTotal() shouldBe 2L
	}

	@Test
	fun `quiescence requires a terminal exact owner and is idempotent after row cleanup`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		val opened = lifecycle.openOrResumeExact(
			LOGICAL_ID,
			RUN_ONE,
			null,
			segment(LOGICAL_ID, RUN_ONE),
		)

		lifecycle.acknowledgeQuiescedExact(opened.binding, 2_000L) shouldBe
			PresentationQuiescenceResult.NOT_TERMINAL

		val dao = database.sourceSessionDao()
		val activeRun = requireNotNull(dao.serviceRun(RUN_ONE))
		dao.updateServiceRun(
			activeRun.copy(
				state = SessionLifecycleState.FINALIZED.name,
				completedAtMs = 2_000L,
				completionReason = "STOPPED",
			),
		) shouldBe 1

		lifecycle.acknowledgeQuiescedExact(opened.binding, 2_100L) shouldBe
			PresentationQuiescenceResult.ACKNOWLEDGED
		val quiesced = requireNotNull(dao.serviceRun(RUN_ONE))
		quiesced.presentationAcknowledgement shouldBe SourceServiceRunEntity.PRESENTATION_QUIESCED
		quiesced.presentationAcknowledgedAtMs shouldBe 2_100L
		quiesced.runRevision shouldBe 7L
		database.sessionSegmentDao().getById(opened.segment.id) shouldBe opened.segment

		database.sessionSegmentDao().deleteById(opened.segment.id)
		lifecycle.acknowledgeQuiescedExact(opened.binding, 2_200L) shouldBe
			PresentationQuiescenceResult.ALREADY_ACKNOWLEDGED
	}

	@Test
	fun `completed failed run may acknowledge exact writer quiescence`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		val opened = lifecycle.openOrResumeExact(
			LOGICAL_ID,
			RUN_ONE,
			null,
			segment(LOGICAL_ID, RUN_ONE),
		)
		val dao = database.sourceSessionDao()
		val activeRun = requireNotNull(dao.serviceRun(RUN_ONE))
		dao.updateServiceRun(
			activeRun.copy(
				state = SessionLifecycleState.FAILED.name,
				completedAtMs = 2_000L,
				completionReason = "TERMINAL_FAILURE",
			),
		) shouldBe 1

		lifecycle.acknowledgeQuiescedExact(opened.binding, 2_100L) shouldBe
			PresentationQuiescenceResult.ACKNOWLEDGED
	}

	private suspend fun insertActiveOwner(logicalTrackingId: String, serviceRunId: String) {
		val dao = database.sourceSessionDao()
		dao.insertSession(logicalSession(logicalTrackingId, serviceRunId))
		dao.insertServiceRun(serviceRun(logicalTrackingId, serviceRunId))
	}

	private fun logicalSession(
		logicalTrackingId: String,
		serviceRunId: String,
	) = LogicalTrackingSessionEntity(
		logicalTrackingId = logicalTrackingId,
		state = SessionLifecycleState.ACTIVE.name,
		lifecycleRevision = 3L,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = "boot-test",
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		cutoffAtMs = null,
		cutoffElapsedNanos = null,
		completedAtMs = null,
		finalAdmissionOrdinal = null,
		failureCode = null,
		currentServiceRunId = serviceRunId,
	)

	private fun serviceRun(
		logicalTrackingId: String,
		serviceRunId: String,
	) = SourceServiceRunEntity(
		serviceRunId = serviceRunId,
		logicalTrackingId = logicalTrackingId,
		state = SessionLifecycleState.ACTIVE.name,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		completedAtMs = null,
		completionReason = null,
		bootId = "boot-test",
		runRevision = 7L,
	)

	private fun segment(
		logicalTrackingId: String,
		serviceRunId: String,
	) = SessionSegment(
		startTimeMs = 1_000L,
		endTimeMs = 1_000L,
		distanceM = 0f,
		steps = 0,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = 1_000L,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	)

	private companion object {
		const val LOGICAL_ID = "logical-test"
		const val RUN_ONE = "run-one"
		const val RUN_TWO = "run-two"
	}
}
