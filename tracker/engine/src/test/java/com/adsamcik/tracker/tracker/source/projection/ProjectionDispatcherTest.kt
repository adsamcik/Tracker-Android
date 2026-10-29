package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.DefaultRetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecovery
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecoveryResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import kotlinx.coroutines.CancellationException
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectionDispatcherTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `fresh startup establishes lifecycle guard before retaining projection scaffolding`() = runBlocking<Unit> {
		try {
			val projection = BatchAmbientProjection()
			val dispatcher = ProjectionDispatcher(database, setOf(projection))
			val retention = startupRetention()
			database.sourceEvidenceStateDao().get() shouldBe null
			startupRecovery(dispatcher, retention, this).recoverStartupAuthority() shouldBe
				LegacyV27ProjectionRecoveryResult.NotRequired
			val guard = requireNotNull(database.sourceEvidenceStateDao().get())
			guard.collectedDataEpoch shouldBe 4L
			guard.retainedFromMs shouldBe 123L
			database.sourceProjectionStateDao().registration(projection.id, projection.version)?.status shouldBe "ACTIVE"
			database.sourceProjectionStateDao().checkpoint(projection.id, projection.version)
				?.contiguousAdmissionOrdinal shouldBe 0L
			retention.prepareSourceEvidence() shouldBe null
			database.sourceEvidenceStateDao().get() shouldBe guard
			database.sourceEventWalDao().countAll() shouldBe 0L
		} finally {
			coroutineContext.cancelChildren()
		}
	}

	@Test
	fun `startup cannot reconstruct a missing guard over recorded observations`() = runBlocking<Unit> {
		try {
			database.stepIntervalDao().insert(StepInterval(startTimeMs = 1L, endTimeMs = 2L, stepCount = 1,
				sensorValueStart = 1, sensorValueEnd = 2, sensorReset = false, createdAt = 2L))
			val projection = BatchAmbientProjection()
			val dispatcher = ProjectionDispatcher(database, setOf(projection))
			val retention = startupRetention()
			retention.prepareSourceEvidence() shouldBe
				com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
			shouldThrow<IllegalStateException> {
				startupRecovery(dispatcher, retention, this).recoverStartupAuthority()
			}
			database.sourceEvidenceStateDao().get() shouldBe null
			database.sourceProjectionStateDao().registration(projection.id, projection.version) shouldBe null
			database.sourceProjectionStateDao().checkpoint(projection.id, projection.version) shouldBe null
			database.stepIntervalDao().getAllBetween(0L, 10L).size shouldBe 1
		} finally {
			coroutineContext.cancelChildren()
		}
	}

	@Test
	fun `startup evidence preparation cannot replace another epoch or retention floor`() = runBlocking<Unit> {
		startupRetention().prepareSourceEvidence() shouldBe null
		val original = database.sourceEvidenceStateDao().get()
		startupRetention(CollectedDataLifecycleSnapshot(5L, 123L)).prepareSourceEvidence() shouldBe
			com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
		startupRetention(CollectedDataLifecycleSnapshot(4L, 124L)).prepareSourceEvidence() shouldBe
			com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED
		database.sourceEvidenceStateDao().get() shouldBe original
	}

	private fun startupRetention(snapshot: CollectedDataLifecycleSnapshot = CollectedDataLifecycleSnapshot(4L, 123L)):
		DefaultRetentionAuthorityProducer {
		val lifecycle = mockk<CollectedDataLifecycleStore>()
		coEvery { lifecycle.snapshot() } returns snapshot
		return DefaultRetentionAuthorityProducer(database, mockk(), mockk(), lifecycle,
			{ SourcePolicyEffectiveTime("boot", 10L, 100L) })
	}

	private fun startupRecovery(dispatcher: ProjectionDispatcher, retention: DefaultRetentionAuthorityProducer,
		scope: CoroutineScope): SourcePipelineRecovery {
		val legacy = mockk<LegacyV27ProjectionRecovery>()
		coEvery { legacy.recover() } returns LegacyV27ProjectionRecoveryResult.NotRequired
		return SourcePipelineRecovery(legacy, mockk(), mockk(), mockk(), mockk(relaxed = true),
			mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), scope,
			ambientProjections = dispatcher, retentionAuthority = retention)
	}

	@Test
	fun `ambient page commits all effects once and resumes after source ordinal gaps`() = runTest {
		val projection = BatchAmbientProjection()
		val subject = ProjectionDispatcher(database, setOf(projection))
		subject.registerProjection(projection.id, projection.version, 1L)
		val page = listOf(ambientEvent(2L), ambientEvent(5L), ambientEvent(9L))
		subject.dispatchAmbientBatch(SourceKind.WIFI, page) shouldBe CoordinatorDrainResult.Complete(9L, 3)
		subject.dispatchAmbientBatch(SourceKind.WIFI, page) shouldBe CoordinatorDrainResult.Complete(9L, 0)
		database.sourceProjectionStateDao().pendingOutbox(10).map { it.admissionOrdinal } shouldBe
			listOf(2L, 5L, 9L)
	}

	@Test
	fun `failed ambient page rolls back before committing good prefix and leaves suffix retryable`() = runTest {
		val projection = BatchAmbientProjection(failedOrdinal = 5L)
		val subject = ProjectionDispatcher(database, setOf(projection))
		subject.registerProjection(projection.id, projection.version, 1L)
		val page = listOf(ambientEvent(2L), ambientEvent(5L), ambientEvent(9L))
		val failed = subject.dispatchAmbientBatch(SourceKind.WIFI, page) as CoordinatorDrainResult.ProjectionFailed
		failed.lastCompletedOrdinal shouldBe 2L
		failed.failedOrdinal shouldBe 5L
		database.sourceProjectionStateDao().pendingOutbox(10).map { it.admissionOrdinal } shouldBe listOf(2L)
		projection.failedOrdinal = null
		subject.dispatchAmbientBatch(SourceKind.WIFI, page) shouldBe CoordinatorDrainResult.Complete(9L, 2)
		database.sourceProjectionStateDao().failure(projection.id, projection.version, 5L) shouldBe null
		database.sourceProjectionStateDao().pendingOutbox(10).map { it.admissionOrdinal } shouldBe
			listOf(2L, 5L, 9L)
	}

	@Test
	fun `cancelled ambient page acknowledges neither prefix nor suffix`() = runTest {
		val projection = BatchAmbientProjection(failedOrdinal = 5L, cancel = true)
		val subject = ProjectionDispatcher(database, setOf(projection))
		subject.registerProjection(projection.id, projection.version, 1L)
		shouldThrow<CancellationException> {
			subject.dispatchAmbientBatch(SourceKind.WIFI, listOf(ambientEvent(2L), ambientEvent(5L)))
		}
		database.sourceProjectionStateDao().checkpoint(projection.id, projection.version)
			?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().pendingOutbox(10) shouldBe emptyList()
	}

	private fun ambientEvent(ordinal: Long) = event(ordinal, SourceKind.WIFI)

	@Test
	fun `projection outbox and checkpoint commit atomically and retry is idempotent`() = runTest {
		val projection = OutboxProjection()
		val subject = ProjectionDispatcher(database, setOf(projection))
		subject.registerAll(1L)

		subject.dispatch(event(1L)).complete shouldBe true
		subject.dispatch(event(1L)).complete shouldBe true

		database.sourceProjectionStateDao().checkpoint("outbox", 1)?.contiguousAdmissionOrdinal shouldBe 1L
		database.sourceProjectionStateDao().pendingOutbox(10).size shouldBe 1
		projection.applyCount shouldBe 1
	}

	@Test
	fun `global dispatcher cannot claim a source local output contract generation`() = runTest {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				bindingGeneration = 1L,
				projectionId = "outbox",
				projectionVersion = 1,
				captureModeMask = CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = 3,
				activationOrdinal = 1,
				contiguousAdmissionOrdinal = 0,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1,
				updatedAtMs = 1,
			),
		)

		shouldThrow<IllegalStateException> {
			ProjectionDispatcher(database, setOf(OutboxProjection())).registerAll(1L)
		}
		database.sourceProjectionStateDao().registration("outbox", 1) shouldBe null
	}

	@Test
	fun `outbox identity collision never advances or becomes poison quarantine`() = runTest {
		val projection = OutboxProjection()
		val subject = ProjectionDispatcher(database, setOf(projection))
		subject.registerAll(1L)
		database.sourceProjectionStateDao().insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "effect-event-1",
				projectionId = "foreign-owner",
				projectionVersion = 9,
				admissionOrdinal = 99L,
				effectKind = "different",
				payloadVersion = 2,
				payload = byteArrayOf(9),
				createdAtMs = 1L,
				deliveredAtMs = null,
			),
		)

		repeat(4) {
			val result = subject.dispatch(event(1L))
			result.complete shouldBe false
			result.failures.single().cause::class shouldBe
				ProjectionOutboxIdentityCollisionException::class
			result.quarantined shouldBe emptyList()
		}

		database.sourceProjectionStateDao().checkpoint("outbox", 1)
			?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure("outbox", 1, 1)?.also { failure ->
			failure.terminal shouldBe false
			failure.attemptCount shouldBe 4
		}
	}

	@Test
	fun `failed projection leaves checkpoint behind the failed ordinal`() = runTest {
		val subject = ProjectionDispatcher(database, setOf(FailingProjection()))
		subject.registerAll(1L)

		subject.dispatch(event(1L)).complete shouldBe false

		database.sourceProjectionStateDao().checkpoint("failing", 1)?.contiguousAdmissionOrdinal shouldBe 0L
	}

	@Test
	fun `transient Activity projection failure retries despite non-retention and attempt limit`() = runTest {
		val projection = TransientActivityProjection()
		val subject = ProjectionDispatcher(database, setOf(projection))
		subject.registerAll(1L)

		val failed = subject.dispatch(event(1L))

		failed.complete shouldBe false
		failed.quarantined shouldBe emptyList()
		failed.failures.single().cause::class shouldBe IllegalStateException::class
		database.sourceProjectionStateDao().checkpoint(projection.id, projection.version)
			?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(projection.id, projection.version, 1L)?.also { failure ->
			failure.attemptCount shouldBe 1
			failure.terminal shouldBe false
		}
		database.sourceProjectionStateDao().pendingOutbox(10) shouldBe emptyList()

		subject.dispatch(event(1L)).complete shouldBe true
		subject.dispatch(event(1L)).complete shouldBe true

		projection.applyCount shouldBe 2
		database.sourceProjectionStateDao().checkpoint(projection.id, projection.version)
			?.contiguousAdmissionOrdinal shouldBe 1L
		database.sourceProjectionStateDao().failure(projection.id, projection.version, 1L) shouldBe null
		database.sourceProjectionStateDao().pendingOutbox(10).map { it.stableId } shouldBe
			listOf("transient-effect-event-1")
	}

	@Test
	fun `generic projection remains strict when an ordinal is missing`() = runTest {
		val projection = OutboxProjection()
		val subject = ProjectionDispatcher(database, setOf(projection))
		subject.registerAll(6L)

		val result = subject.dispatch(event(8L))

		result.complete shouldBe false
		result.failures.single().cause::class shouldBe IllegalStateException::class
		projection.applyCount shouldBe 0
		database.sourceProjectionStateDao().checkpoint("outbox", 1)
			?.contiguousAdmissionOrdinal shouldBe 5L
	}

	@Test
	fun `poison event is quarantined after bounded attempts and unblocks replay`() = runTest {
		val subject = ProjectionDispatcher(database, setOf(FailingProjection()))
		subject.registerAll(1L)

		subject.dispatch(event(1L)).complete shouldBe false
		subject.dispatch(event(1L)).complete shouldBe false
		val terminal = subject.dispatch(event(1L))

		terminal.complete shouldBe true
		terminal.quarantined.single().attemptCount shouldBe 3
		terminal.quarantined.single().failureCode shouldBe "TEST_DETERMINISTIC_POISON"
		database.sourceProjectionStateDao().checkpoint("failing", 1)?.contiguousAdmissionOrdinal shouldBe 1L
		database.sourceProjectionStateDao().failure("failing", 1, 1)?.also { failure ->
			failure.terminal shouldBe true
			failure.failureCode shouldBe "TEST_DETERMINISTIC_POISON"
		}
	}

	@Test
	fun `raw integrity quarantine advances without invoking projection code`() = runTest {
		val projection = OutboxProjection()
		val subject = ProjectionDispatcher(database, setOf(projection))
		subject.registerAll(1L)

		subject.quarantineRawEvent(1L, "RAW_PAYLOAD_INTEGRITY_SOURCE_2")
		subject.dispatch(event(2L)).complete shouldBe true

		projection.applyCount shouldBe 1
		database.sourceProjectionStateDao().failure("outbox", 1, 1)?.also { failure ->
			failure.terminal shouldBe true
			failure.failureCode shouldBe "RAW_PAYLOAD_INTEGRITY_SOURCE_2"
		}
		database.sourceProjectionStateDao().checkpoint("outbox", 1)
			?.contiguousAdmissionOrdinal shouldBe 2L
	}

	@Test
	fun `durable join state retains the oldest buffered admission ordinal`() = runTest {
		val subject = ProjectionDispatcher(database, setOf(RetentionProjection()))
		subject.registerAll(1L)

		subject.dispatch(event(1L)).complete shouldBe true
		subject.dispatch(event(2L)).complete shouldBe true

		val state = database.sourceProjectionStateDao().joinState("retention", 1, "pending")
		state?.minimumRequiredOrdinal shouldBe 1L
		state?.payload?.toList() shouldBe listOf(1.toByte(), 2.toByte())
	}

	private fun event(ordinal: Long, source: SourceKind = SourceKind.ACTIVITY) = AdmittedSourceEvent(
		eventId = SourceEventId("event-$ordinal"),
		admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = source,
			sourceInstanceId = SourceInstanceId("activity"),
			registrationGeneration = 1,
			sourceSequence = ordinal,
			configRevision = 1,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot",
			observedElapsedRealtimeNanos = ordinal,
			receivedElapsedRealtimeNanos = ordinal,
			wallTimeMs = ordinal,
			wallTimeUncertaintyMs = 0,
			capturedCollectedDataEpoch = 0,
			acquiredAtMs = ordinal,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = if (source == SourceKind.WIFI) {
				WifiResultSnapshotPayload(emptyList(), ordinal, 0L)
			} else {
				ActivityTransitionPayload(3, 1, ordinal)
			},
		),
	)
}

private class OutboxProjection : Projection {
	override val id = "outbox"
	override val version = 1
	var applyCount = 0
	override suspend fun apply(
		event: AdmittedSourceEvent<out com.adsamcik.tracker.tracker.source.model.SourcePayload>,
		context: ProjectionContext,
	) {
		applyCount++
		context.recordOutbox(ProjectionOutboxEffect("effect-${event.eventId.value}", "test", 1, byteArrayOf(1)))
	}
}

private class BatchAmbientProjection(var failedOrdinal: Long? = null, private val cancel: Boolean = false) : Projection {
	override val id = AmbientWifiFactRevisionEntity.WRITER_ID
	override val version = AmbientWifiFactRevisionEntity.WRITER_VERSION
	override suspend fun apply(event: AdmittedSourceEvent<out com.adsamcik.tracker.tracker.source.model.SourcePayload>,
		context: ProjectionContext) {
		context.recordOutbox(ProjectionOutboxEffect("batch-${event.admissionOrdinal}", "test", 1, byteArrayOf(1)))
		if (event.admissionOrdinal == failedOrdinal) {
			if (cancel) {
				throw CancellationException("Cancelled page")
			}
			throw java.io.IOException("Transient page failure")
		}
	}
}

private class FailingProjection : Projection {
	override val id = "failing"
	override val version = 1
	override suspend fun apply(
		event: AdmittedSourceEvent<out com.adsamcik.tracker.tracker.source.model.SourcePayload>,
		context: ProjectionContext,
	): Unit = throw ProjectionPoisonException("TEST_DETERMINISTIC_POISON")
}

private class TransientActivityProjection : Projection {
	override val id = "activity-transient"
	override val version = 1
	override val retentionRequired = false
	override val maximumAttemptsPerEvent = 1
	var applyCount = 0

	override suspend fun apply(
		event: AdmittedSourceEvent<out com.adsamcik.tracker.tracker.source.model.SourcePayload>,
		context: ProjectionContext,
	) {
		applyCount++
		if (applyCount == 1) error("transient")
		context.recordOutbox(
			ProjectionOutboxEffect(
				stableId = "transient-effect-${event.eventId.value}",
				kind = "test",
				payloadVersion = 1,
				payload = byteArrayOf(1),
			),
		)
	}
}

private class RetentionProjection : Projection {
	override val id = "retention"
	override val version = 1
	private val ordinals = mutableListOf<Byte>()

	override suspend fun apply(
		event: AdmittedSourceEvent<out com.adsamcik.tracker.tracker.source.model.SourcePayload>,
		context: ProjectionContext,
	) {
		ordinals += event.admissionOrdinal.toByte()
		context.saveJoinState(
			key = "pending",
			payload = ordinals.toByteArray(),
			minimumRequiredOrdinal = 1L,
		)
	}

}
