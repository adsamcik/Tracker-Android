package com.adsamcik.tracker.tracker.source.ingress

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDurableSourceIngressTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: FakeLifecycleStore
	private lateinit var subject: RoomDurableSourceIngress

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		lifecycle = FakeLifecycleStore(CollectedDataLifecycleSnapshot(0L, null))
		subject = RoomDurableSourceIngress(database, lifecycle, DefaultSourcePayloadCodec())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `admission is durable ordered replayable and idempotent by source sequence`() = runTest {
		val candidate = candidate(sequence = 1L)

		val admitted = subject.admit(candidate).shouldBeInstanceOf<AdmissionResult.Admitted>()
		val duplicate = subject.admit(candidate).shouldBeInstanceOf<AdmissionResult.Duplicate>()

		duplicate.eventId shouldBe admitted.eventId
		duplicate.existingAdmissionOrdinal shouldBe admitted.admissionOrdinal
		subject.committedBatch(0L, 1).single().eventId shouldBe admitted.eventId
	}

	@Test
	fun `first admission initializes a missing lifecycle guard from durable lifecycle`() = runTest {
		database.clearAllTables()

		val admitted = subject.admit(candidate(sequence = 1L))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()

		admitted.admissionOrdinal shouldBe 1L
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 0L
	}

	@Test
	fun `batch collision rolls back every event in the provider delivery`() = runTest {
		val results = subject.admitBatch(
			listOf(
				candidate(sequence = 1L, activityType = 3),
				candidate(sequence = 1L, activityType = 7),
			),
		)

		results.forEach {
			it.shouldBeInstanceOf<AdmissionResult.PermanentFailure>().code shouldBe
				AdmissionFailureCode.IDENTITY_COLLISION
		}
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `same source sequence with different evidence is rejected as collision`() = runTest {
		subject.admit(candidate(sequence = 1L, activityType = 3))

		val collision = subject.admit(candidate(sequence = 1L, activityType = 7))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		collision.code shouldBe AdmissionFailureCode.IDENTITY_COLLISION
	}

	@Test
	fun `provider retry with a newly allocated local sequence remains idempotent`() = runTest {
		val admitted = subject.admit(candidate(sequence = 1L, providerDedupKey = "provider-event"))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()

		val duplicate = subject.admit(candidate(sequence = 2L, providerDedupKey = "provider-event"))
			.shouldBeInstanceOf<AdmissionResult.Duplicate>()

		duplicate.eventId shouldBe admitted.eventId
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `stale collected-data epoch is rejected before database admission`() = runTest {
		lifecycle.update(CollectedDataLifecycleSnapshot(2L, 500L))

		val result = subject.admit(candidate(sequence = 1L, epoch = 1L, acquiredAtMs = 600L))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `new epoch waits until Room deletion barrier catches up`() = runTest {
		lifecycle.update(CollectedDataLifecycleSnapshot(1L, 500L))

		val result = subject.admit(candidate(sequence = 1L, epoch = 1L, acquiredAtMs = 600L))
			.shouldBeInstanceOf<AdmissionResult.RetryableFailure>()

		result.code shouldBe AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS
	}

	private fun candidate(
		sequence: Long,
		activityType: Int = 3,
		epoch: Long = 0L,
		acquiredAtMs: Long = 100L,
		providerDedupKey: String? = null,
	) = SourceEvidenceCandidate(
		providerDedupKey = providerDedupKey,
		logicalTrackingId = null,
		serviceRunId = null,
		source = SourceKind.ACTIVITY,
		sourceInstanceId = SourceInstanceId("activity-instance"),
		registrationGeneration = 1L,
		sourceSequence = sequence,
		configRevision = 1L,
		planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
		clockDomainId = "boot",
		observedElapsedRealtimeNanos = 100L,
		receivedElapsedRealtimeNanos = 110L,
		wallTimeMs = acquiredAtMs,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = epoch,
		acquiredAtMs = acquiredAtMs,
		quality = SourceQuality(),
		payloadVersion = 1,
		payload = ActivityTransitionPayload(activityType, 1, 100L),
	)
}

private class FakeLifecycleStore(initial: CollectedDataLifecycleSnapshot) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot {
		val updated = state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs)
		state.emit(updated)
		return updated
	}
	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot {
		val updated = state.value.copy(retainedFromMs = retainedFromMs)
		state.emit(updated)
		return updated
	}
	suspend fun update(value: CollectedDataLifecycleSnapshot) = state.emit(value)
}
