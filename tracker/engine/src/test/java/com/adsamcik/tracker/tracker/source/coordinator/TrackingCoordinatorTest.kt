package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
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
	fun `global registration excludes exact background consumers until independent registration`() = runTest {
		val dispatcher = ProjectionDispatcher(database, setOf(
			RecordingProjection(), FailingBackgroundProjection(SourceKind.WIFI), FailingBackgroundProjection(SourceKind.CELL),
		))
		dispatcher.registerAll(1L)
		database.sourceProjectionStateDao().registration(AmbientWifiFactRevisionEntity.WRITER_ID, 1) shouldBe null
		database.sourceProjectionStateDao().registration(AmbientCellFactRevisionEntity.WRITER_ID, 1) shouldBe null
		dispatcher.registerAmbientRadioProjections()
		database.sourceProjectionStateDao().registration(AmbientWifiFactRevisionEntity.WRITER_ID, 1)
			?.activationOrdinal shouldBe 1L
		database.sourceProjectionStateDao().registration(AmbientCellFactRevisionEntity.WRITER_ID, 1)
			?.activationOrdinal shouldBe 1L
	}

	@Test
	fun `pending background retries cannot join global dispatch or rewind its continuation`() = runTest {
		val global = RecordingProjection()
		val wifi = FailingBackgroundProjection(SourceKind.WIFI)
		val cell = FailingBackgroundProjection(SourceKind.CELL)
		val dispatcher = ProjectionDispatcher(database, setOf(global, wifi, cell))
		dispatcher.registerAll(1L)
		dispatcher.registerAmbientRadioProjections()
		dispatcher.dispatch(healthyEvent(1L))
		database.sourceProjectionStateDao().saveFailure(SourceProjectionFailureEntity(
			wifi.id, wifi.version, 2L, 1, "INJECTED_PENDING_STORAGE", false, 1L,
		))
		val ingress = OrderedIngress(listOf(sourceEvent(2L, SourceKind.WIFI), sourceEvent(3L, SourceKind.STEPS)))
		val coordinator = TrackingCoordinator(database, ingress, dispatcher)

		coordinator.drainAvailable("independent-global") shouldBe CoordinatorDrainResult.Complete(3L, 2)
		coordinator.drainAvailable("continued-global") shouldBe CoordinatorDrainResult.Complete(3L, 0)
		ingress.requestedAfterOrdinals shouldBe listOf(1L, 3L)
		global.appliedOrdinals shouldBe listOf(1L, 2L, 3L)
		wifi.attempts shouldBe 0
		cell.attempts shouldBe 0
		database.sourceProjectionStateDao().checkpoint(wifi.id, 1)?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().checkpoint(cell.id, 1)?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(wifi.id, 1, 2L)?.attemptCount shouldBe 1
	}

	@Test
	fun `global raw quarantine cannot retire a pending source-local background reading`() = runTest {
		val global = RecordingProjection()
		val wifi = FailingBackgroundProjection(SourceKind.WIFI)
		val cell = FailingBackgroundProjection(SourceKind.CELL)
		val dispatcher = ProjectionDispatcher(database, setOf(global, wifi, cell))
		dispatcher.registerAmbientRadioProjections()
		val coordinator = TrackingCoordinator(database, PoisonThenHealthyIngress(healthyEvent(2L)), dispatcher)

		coordinator.drainAvailable("quarantine-global") shouldBe CoordinatorDrainResult.Complete(2L, 1)
		database.sourceProjectionStateDao().checkpoint(wifi.id, 1)?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().checkpoint(cell.id, 1)?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(wifi.id, 1, 1L) shouldBe null
		database.sourceProjectionStateDao().failure(cell.id, 1, 1L) shouldBe null
		wifi.attempts shouldBe 0
		cell.attempts shouldBe 0
	}

	@Test
	fun `unknown background writer version and unknown registration remain global prerequisites`() = runTest {
		val unknownVersion = FailingBackgroundProjection(SourceKind.WIFI, version = 2)
		val dispatcher = ProjectionDispatcher(database, setOf(unknownVersion))
		dispatcher.registerAll(1L)
		dispatcher.minimumGlobalCheckpoint() shouldBe 0L
		val coordinator = TrackingCoordinator(database, OrderedIngress(listOf(sourceEvent(1L, SourceKind.WIFI))), dispatcher)
		coordinator.drainAvailable("unknown-version-global") shouldBe
			CoordinatorDrainResult.ProjectionFailed(0L, 0, unknownVersion.id, 1L)
		unknownVersion.attempts shouldBe 1
		database.sourceProjectionStateDao().register(SourceProjectionRegistrationEntity(
			"unknown-reader", 1, 1L, true, "ACTIVE", 1L,
		))
		database.sourceProjectionStateDao().saveCheckpoint(SourceProjectionCheckpointEntity(
			unknownVersion.id, 2, 1L, 1, 1L,
		))
		dispatcher.minimumGlobalCheckpoint() shouldBe 0L
	}

	private class FailingBackgroundProjection(source: SourceKind, override val version: Int = 1) : Projection {
		override val id = if (source == SourceKind.WIFI) AmbientWifiFactRevisionEntity.WRITER_ID else AmbientCellFactRevisionEntity.WRITER_ID
		var attempts = 0
		override suspend fun apply(event: AdmittedSourceEvent<out SourcePayload>, context: ProjectionContext) {
			attempts++
			throw java.io.IOException("Injected pending background storage")
		}
	}

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

	@Test
	fun `deletion high water rebases strict projection and each source is applied once`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = 2L,
				deletedSourceEventHighWaterOrdinal = 9L,
			),
		)
		val events = SourceKind.values().mapIndexed { index, source ->
			sourceEvent(ordinal = 10L + index, source = source)
		}
		val projection = RecordingProjection()
		val ingress = OrderedIngress(events)
		val coordinator = TrackingCoordinator(
			database = database,
			ingress = ingress,
			projections = ProjectionDispatcher(database, setOf(projection)),
		)

		coordinator.drainAvailable("post-delete-owner") shouldBe
			CoordinatorDrainResult.Complete(lastCompletedOrdinal = 15L, eventsDispatched = 6)
		coordinator.drainAvailable("post-delete-owner") shouldBe
			CoordinatorDrainResult.Complete(lastCompletedOrdinal = 15L, eventsDispatched = 0)

		ingress.requestedAfterOrdinals.first() shouldBe 9L
		projection.appliedOrdinals shouldBe (10L..15L).toList()
		projection.appliedSources shouldBe SourceKind.values().toList()
		database.sourceProjectionStateDao().registration("recording", 1)
			?.activationOrdinal shouldBe 10L
		database.sourceProjectionStateDao().checkpoint("recording", 1)
			?.contiguousAdmissionOrdinal shouldBe 15L
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

	private fun sourceEvent(ordinal: Long, source: SourceKind) = AdmittedSourceEvent(
		eventId = SourceEventId("${source.name.lowercase()}-$ordinal"),
		admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = source,
			sourceInstanceId = SourceInstanceId(source.name.lowercase()),
			registrationGeneration = 1L,
			sourceSequence = ordinal,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot",
			observedElapsedRealtimeNanos = ordinal,
			receivedElapsedRealtimeNanos = ordinal,
			wallTimeMs = ordinal,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 2L,
			acquiredAtMs = ordinal,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = TestSourcePayload(source),
		),
	)
}

private data class TestSourcePayload(override val source: SourceKind) : SourcePayload

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

	override suspend fun committedSourceBatch(
		source: SourceKind,
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<AdmittedSourceEvent<out SourcePayload>> = error(
		"Source-scoped reads are not used by this test",
	)

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

	override suspend fun committedSourceBatch(
		source: SourceKind,
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<AdmittedSourceEvent<out SourcePayload>> = error(
		"Source-scoped reads are not used by this test",
	)

	override suspend fun checkpoint(consumer: String, ordinal: Long) = Unit
}

private class RecordingProjection : Projection {
	override val id = "recording"
	override val version = 1
	val appliedOrdinals = mutableListOf<Long>()
	val appliedSources = mutableListOf<SourceKind>()

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		appliedOrdinals += event.admissionOrdinal
		appliedSources += event.evidence.source
	}
}
