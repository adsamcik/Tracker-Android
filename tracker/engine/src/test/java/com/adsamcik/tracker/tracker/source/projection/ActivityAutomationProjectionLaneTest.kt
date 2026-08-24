package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityAutomationProjectionLaneTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `callback lane projects activity in source order without reading unrelated wal rows`() = runTest {
		val ingress = mockk<DurableSourceIngress>()
		val events = listOf(activityEvent(2L), activityEvent(5L))
		coEvery {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 0L, 5L, 64)
		} returns events
		val lane = ActivityAutomationProjectionLane(
			database = database,
			ingress = ingress,
			projections = ProjectionDispatcher(database, setOf(ActivityAutomationProjection())),
		)

		lane.drainThrough(5L) shouldBe CoordinatorDrainResult.Complete(5L, 2)

		database.sourceProjectionStateDao().checkpoint(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.contiguousAdmissionOrdinal shouldBe 5L
		database.sourceProjectionStateDao().pendingOutbox(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
			ActivityAutomationProjection.OUTBOX_KIND,
			10,
		).map { it.stableId } shouldContainExactly listOf(
			"${ActivityAutomationProjection.ID}:activity-2",
			"${ActivityAutomationProjection.ID}:activity-5",
		)
		coVerify(exactly = 1) {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 0L, 5L, 64)
		}
		coVerify(exactly = 0) { ingress.committedBatch(any(), any()) }
	}

	@Test
	fun `callback lane starts after deletion high water and applies first activity once`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = 2L,
				deletedSourceEventHighWaterOrdinal = 5L,
			),
		)
		val ingress = mockk<DurableSourceIngress>()
		coEvery {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 5L, 8L, 64)
		} returns listOf(activityEvent(8L, collectedDataEpoch = 2L))
		val lane = ActivityAutomationProjectionLane(
			database = database,
			ingress = ingress,
			projections = ProjectionDispatcher(database, setOf(ActivityAutomationProjection())),
		)

		lane.drainThrough(8L) shouldBe CoordinatorDrainResult.Complete(8L, 1)
		lane.drainThrough(8L) shouldBe CoordinatorDrainResult.Complete(8L, 0)

		database.sourceProjectionStateDao().registration(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.activationOrdinal shouldBe 6L
		database.sourceProjectionStateDao().pendingOutbox(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
			ActivityAutomationProjection.OUTBOX_KIND,
			10,
		).map { it.stableId } shouldContainExactly listOf(
			"${ActivityAutomationProjection.ID}:activity-8",
		)
		coVerify(exactly = 1) {
			ingress.committedSourceBatch(SourceKind.ACTIVITY, 5L, 8L, 64)
		}
		coVerify(exactly = 0) { ingress.committedBatch(any(), any()) }
	}

	private fun activityEvent(ordinal: Long, collectedDataEpoch: Long = 1L) = AdmittedSourceEvent(
		eventId = SourceEventId("activity-$ordinal"),
		admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = "activity-$ordinal",
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.ACTIVITY,
			sourceInstanceId = SourceInstanceId("activity-provider"),
			registrationGeneration = 1L,
			authorizationRevision = 7L,
			registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			registrationEligibilityFingerprint = "activity-control",
			sourceSequence = ordinal,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot-1",
			observedElapsedRealtimeNanos = ordinal * 1_000L,
			receivedElapsedRealtimeNanos = ordinal * 1_000L + 10L,
			wallTimeMs = ordinal * 1_000L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = collectedDataEpoch,
			activityAutomationEpoch = 17L,
			acquiredAtMs = ordinal * 1_000L,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = ActivityRecognitionPayload(1, 80, ordinal * 1_000L),
		),
	)
}
