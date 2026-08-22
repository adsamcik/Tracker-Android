package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.tracker.source.ingress.AdmissionResult
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.projection.Projection
import com.adsamcik.tracker.tracker.source.projection.ProjectionContext
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingCoordinatorTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `corrupt raw event is quarantined and later healthy event is dispatched`() = runTest {
		val projection = RecordingProjection()
		val coordinator = TrackingCoordinator(
			database = database,
			ingress = PoisonThenHealthyIngress(healthyEvent(2L)),
			projections = ProjectionDispatcher(database, setOf(projection)),
		)

		val result = coordinator.drainAvailable("test-owner", batchSize = 10)
			.shouldBeInstanceOf<CoordinatorDrainResult.Complete>()

		result.lastCompletedOrdinal shouldBe 2L
		result.eventsDispatched shouldBe 1
		projection.appliedOrdinals shouldBe listOf(2L)
		database.sourceProjectionStateDao().failure("recording", 1, 1)?.also { failure ->
			failure.terminal shouldBe true
			failure.failureCode shouldBe "RAW_PAYLOAD_INTEGRITY_SOURCE_2"
		}
		database.sourceProjectionStateDao().checkpoint("recording", 1)
			?.contiguousAdmissionOrdinal shouldBe 2L
	}

	@Test
	fun `live projection generation starts strictly after a migrated v27 cutoff`() = runTest {
		database.legacyV27ProjectionDrainDao().saveDrain(
			LegacyV27ProjectionDrainEntity(
				cutoffAdmissionOrdinal = 5,
				collectedDataEpoch = 7,
				status = LegacyV27ProjectionDrainEntity.STATUS_PENDING,
				ownerBootId = null,
				ownerToken = null,
				leaseGeneration = 0,
				leaseExpiresElapsedNanos = null,
				startedAtMs = null,
				completedAtMs = null,
				suppressedOutboxCount = 0,
				failureCode = null,
			),
		)
		val projection = RecordingProjection()
		val ingress = OrderedIngress(listOf(healthyEvent(6L)))
		val coordinator = TrackingCoordinator(
			database = database,
			ingress = ingress,
			projections = ProjectionDispatcher(database, setOf(projection)),
		)

		val result = coordinator.drainAvailable("v28-owner")
			.shouldBeInstanceOf<CoordinatorDrainResult.Complete>()

		result.lastCompletedOrdinal shouldBe 6L
		projection.appliedOrdinals shouldBe listOf(6L)
		ingress.requestedAfterOrdinals.first() shouldBe 5L
		database.sourceProjectionStateDao().registration("recording", 1)
			?.activationOrdinal shouldBe 6L
		database.sourceProjectionStateDao().checkpoint("recording", 1)
			?.contiguousAdmissionOrdinal shouldBe 6L
	}

	private fun healthyEvent(ordinal: Long) = AdmittedSourceEvent(
		eventId = SourceEventId("event-$ordinal"),
		admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.ACTIVITY,
			sourceInstanceId = SourceInstanceId("activity"),
			registrationGeneration = 1L,
			sourceSequence = ordinal,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot",
			observedElapsedRealtimeNanos = ordinal,
			receivedElapsedRealtimeNanos = ordinal,
			wallTimeMs = ordinal,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			acquiredAtMs = ordinal,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = ActivityTransitionPayload(3, 1, ordinal),
		),
	)
}

private class PoisonThenHealthyIngress(
	private val healthy: AdmittedSourceEvent<out SourcePayload>,
) : DurableSourceIngress {
	private var poisonReported = false

	override suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult =
		error("Admission is not used by this test")

	override suspend fun committedBatch(
		afterOrdinal: Long,
		limit: Int,
	): List<AdmittedSourceEvent<out SourcePayload>> = when {
		afterOrdinal == 0L && !poisonReported -> {
			poisonReported = true
			throw CorruptSourceEventException(1L, SourceKind.ACTIVITY.stableCode, "RAW_PAYLOAD_INTEGRITY")
		}
		afterOrdinal == 1L -> listOf(healthy)
		else -> emptyList()
	}

	override suspend fun checkpoint(consumer: String, ordinal: Long) = Unit
}

private class OrderedIngress(
	private val events: List<AdmittedSourceEvent<out SourcePayload>>,
) : DurableSourceIngress {
	val requestedAfterOrdinals = mutableListOf<Long>()

	override suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult =
		error("Admission is not used by this test")

	override suspend fun committedBatch(
		afterOrdinal: Long,
		limit: Int,
	): List<AdmittedSourceEvent<out SourcePayload>> {
		requestedAfterOrdinals += afterOrdinal
		return events.filter { it.admissionOrdinal > afterOrdinal }.take(limit)
	}

	override suspend fun checkpoint(consumer: String, ordinal: Long) = Unit
}

private class RecordingProjection : Projection {
	override val id = "recording"
	override val version = 1
	val appliedOrdinals = mutableListOf<Long>()

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		appliedOrdinals += event.admissionOrdinal
	}
}
