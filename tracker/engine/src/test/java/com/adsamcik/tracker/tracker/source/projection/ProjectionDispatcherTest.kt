package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
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
	fun `failed projection leaves checkpoint behind the failed ordinal`() = runTest {
		val subject = ProjectionDispatcher(database, setOf(FailingProjection()))
		subject.registerAll(1L)

		subject.dispatch(event(1L)).complete shouldBe false

		database.sourceProjectionStateDao().checkpoint("failing", 1)?.contiguousAdmissionOrdinal shouldBe 0L
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
		database.sourceProjectionStateDao().checkpoint("failing", 1)?.contiguousAdmissionOrdinal shouldBe 1L
		database.sourceProjectionStateDao().failure("failing", 1, 1)?.terminal shouldBe true
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

	private fun event(ordinal: Long) = AdmittedSourceEvent(
		eventId = SourceEventId("event-$ordinal"),
		admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.ACTIVITY,
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
			payload = ActivityTransitionPayload(3, 1, ordinal),
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

private class FailingProjection : Projection {
	override val id = "failing"
	override val version = 1
	override suspend fun apply(
		event: AdmittedSourceEvent<out com.adsamcik.tracker.tracker.source.model.SourcePayload>,
		context: ProjectionContext,
	): Unit = error("expected")
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
