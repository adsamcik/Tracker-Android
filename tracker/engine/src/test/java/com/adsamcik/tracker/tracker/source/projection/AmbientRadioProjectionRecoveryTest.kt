package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellFactProjector
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellProjectionResult
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiFactProjector
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiProjectionResult
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiProjectionUnverifiableReason
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientRadioProjectionRecoveryTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `an unverifiable background group does not suppress a qualified sibling with callback mask zero`() = runTest {
		val projector = mockk<AmbientWifiFactProjector>()
		coEvery { projector.project(1L) } returns AmbientWifiProjectionResult.Groups(listOf(
			AmbientWifiProjectionResult.Unverifiable(AmbientWifiProjectionUnverifiableReason.WRITE_CONFLICT),
			AmbientWifiProjectionResult.FactWritten("qualified-sibling"),
		))
		AmbientWifiProjection(projector).apply(event(SourceKind.WIFI, 1L), mockk())
		coVerify(exactly = 1) { projector.project(1L) }
	}

	@Test
	fun `transient failure in a background group leaves the complete callback for retry`() = runTest {
		val projector = mockk<AmbientCellFactProjector>()
		coEvery { projector.project(1L) } returns AmbientCellProjectionResult.Groups(listOf(
			AmbientCellProjectionResult.FactWritten("first-group"), AmbientCellProjectionResult.RetryableFailure,
		))
		shouldThrow<AmbientRadioProjectionDeferredException> {
			AmbientCellProjection(projector).apply(event(SourceKind.CELL, 1L), mockk())
		}
	}

	@Test
	fun `background projection ignores sibling sources and preserves callback cancellation`() = runTest {
		val projector = mockk<AmbientWifiFactProjector>()
		val projection = AmbientWifiProjection(projector)
		projection.apply(event(SourceKind.CELL, 1L), mockk())
		coVerify(exactly = 0) { projector.project(any()) }
		coEvery { projector.project(2L) } throws CancellationException("cancelled")
		shouldThrow<CancellationException> { projection.apply(event(SourceKind.WIFI, 2L), mockk()) }
	}

	@Test
	fun `first consumer registration recovers retained callbacks using bounded source-only reads`() = runTest {
		seedHighWater(5L)
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.committedSourceBatch(SourceKind.WIFI, 0L, 5L, 64) } returns
			listOf(event(SourceKind.WIFI, 2L), event(SourceKind.WIFI, 5L))
		val wifi = RecordingProjection(SourceKind.WIFI)
		val dispatcher = ProjectionDispatcher(database, setOf(wifi))

		dispatcher.drainAmbientRadioProjection(SourceKind.WIFI, ingress) shouldBe CoordinatorDrainResult.Complete(5L, 2)
		wifi.applied shouldBe listOf(2L, 5L)
		database.sourceProjectionStateDao().registration(wifi.id, wifi.version)?.activationOrdinal shouldBe 1L
		dispatcher.drainAmbientRadioProjection(SourceKind.WIFI, ingress) shouldBe CoordinatorDrainResult.Complete(5L, 0)
		coVerify(exactly = 1) { ingress.committedSourceBatch(SourceKind.WIFI, 0L, 5L, 64) }
		coVerify(exactly = 0) { ingress.committedBatch(any(), any()) }
	}

	@Test
	fun `Cell retry leaves its checkpoint pending while Wi-Fi advances and a new dispatcher resumes Cell`() = runTest {
		seedHighWater(2L)
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.committedSourceBatch(SourceKind.CELL, 0L, 2L, 64) } returns listOf(event(SourceKind.CELL, 2L))
		coEvery { ingress.committedSourceBatch(SourceKind.WIFI, 0L, 2L, 64) } returns listOf(event(SourceKind.WIFI, 1L))
		coEvery { ingress.committedSourceBatch(SourceKind.WIFI, 1L, 2L, 64) } returns emptyList()
		val wifi = RecordingProjection(SourceKind.WIFI)
		val cell = RecordingProjection(SourceKind.CELL).apply { fail = true }
		val dispatcher = ProjectionDispatcher(database, setOf(wifi, cell))

		dispatcher.drainAmbientRadioProjection(SourceKind.CELL, ingress) shouldBe
			CoordinatorDrainResult.ProjectionFailed(0L, 0, cell.id, 2L)
		dispatcher.drainAmbientRadioProjection(SourceKind.WIFI, ingress) shouldBe CoordinatorDrainResult.Complete(2L, 1)
		database.sourceProjectionStateDao().checkpoint(cell.id, cell.version)?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(cell.id, cell.version, 2L)?.terminal shouldBe false
		cell.fail = false
		val restarted = ProjectionDispatcher(database, setOf(wifi, cell))
		restarted.drainAmbientRadioProjection(SourceKind.CELL, ingress) shouldBe CoordinatorDrainResult.Complete(2L, 1)
		cell.applied shouldBe listOf(2L)
		wifi.applied shouldBe listOf(1L)
		database.sourceProjectionStateDao().failure(cell.id, cell.version, 2L) shouldBe null
	}

	@Test
	fun `valid Wi-Fi prefix is saved before corruption quarantines only its consumer and later input continues`() = runTest {
		seedHighWater(3L)
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.committedSourceBatch(SourceKind.WIFI, 0L, 3L, 64) } returns listOf(event(SourceKind.WIFI, 1L))
		coEvery { ingress.committedSourceBatch(SourceKind.WIFI, 1L, 3L, 64) } throws
			CorruptSourceEventException(2L, SourceKind.WIFI.stableCode, "RAW_PAYLOAD_INTEGRITY")
		coEvery { ingress.committedSourceBatch(SourceKind.WIFI, 2L, 3L, 64) } returns listOf(event(SourceKind.WIFI, 3L))
		val wifi = RecordingProjection(SourceKind.WIFI)
		val cell = RecordingProjection(SourceKind.CELL)
		val dispatcher = ProjectionDispatcher(database, setOf(wifi, cell))
		dispatcher.registerAmbientRadioProjections()

		dispatcher.drainAmbientRadioProjection(SourceKind.WIFI, ingress) shouldBe CoordinatorDrainResult.Complete(3L, 2)
		wifi.applied shouldBe listOf(1L, 3L)
		database.sourceProjectionStateDao().failure(wifi.id, wifi.version, 2L)?.terminal shouldBe true
		database.sourceProjectionStateDao().checkpoint(cell.id, cell.version)?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(cell.id, cell.version, 2L) shouldBe null
	}

	@Test
	fun `new consumer registration after clear starts strictly after the surviving deletion fence`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(
			collectedDataEpoch = 2L, deletedSourceEventHighWaterOrdinal = 4L,
		))
		seedHighWater(8L)
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.committedSourceBatch(SourceKind.WIFI, 4L, 8L, 64) } returns listOf(event(SourceKind.WIFI, 8L))
		val wifi = RecordingProjection(SourceKind.WIFI)
		val dispatcher = ProjectionDispatcher(database, setOf(wifi))

		dispatcher.drainAmbientRadioProjection(SourceKind.WIFI, ingress) shouldBe CoordinatorDrainResult.Complete(8L, 1)
		database.sourceProjectionStateDao().registration(wifi.id, wifi.version)?.activationOrdinal shouldBe 5L
		wifi.applied shouldBe listOf(8L)
		coVerify(exactly = 0) { ingress.committedSourceBatch(any(), 0L, any(), any()) }
	}

	@Test
	fun `cancelled radio projection keeps its checkpoint and propagates cancellation`() = runTest {
		seedHighWater(1L)
		val ingress = mockk<DurableSourceIngress>()
		coEvery { ingress.committedSourceBatch(SourceKind.WIFI, 0L, 1L, 64) } returns listOf(event(SourceKind.WIFI, 1L))
		val wifi = RecordingProjection(SourceKind.WIFI).apply { cancel = true }
		val dispatcher = ProjectionDispatcher(database, setOf(wifi))

		shouldThrow<CancellationException> { dispatcher.drainAmbientRadioProjection(SourceKind.WIFI, ingress) }
		database.sourceProjectionStateDao().checkpoint(wifi.id, wifi.version)?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(wifi.id, wifi.version, 1L) shouldBe null
	}

	private class RecordingProjection(source: SourceKind) : Projection {
		override val id = if (source == SourceKind.WIFI) {
			AmbientWifiFactRevisionEntity.WRITER_ID
		} else {
			AmbientCellFactRevisionEntity.WRITER_ID
		}
		override val version = 1
		val applied = mutableListOf<Long>()
		var fail = false
		var cancel = false
		override suspend fun apply(event: AdmittedSourceEvent<out SourcePayload>, context: ProjectionContext) {
			if (cancel) {
				throw CancellationException("cancelled")
			}
			if (fail) {
				throw AmbientRadioProjectionDeferredException()
			}
			applied += event.admissionOrdinal
		}
	}

	private suspend fun seedHighWater(ordinal: Long) {
		val row = SourceEventWalEntity(
			admissionOrdinal = ordinal, eventId = "high-water-$ordinal", providerDedupKey = null,
			logicalTrackingId = null, serviceRunId = null, sourceKind = SourceKind.WIFI.stableCode,
			sourceInstanceId = "radio-provider", registrationGeneration = 1L, sourceSequence = ordinal,
			configRevision = 1L, planAttribution = 0, clockDomainId = "boot-1", observedElapsedNanos = ordinal,
			receivedElapsedNanos = ordinal, wallTimeMs = ordinal, wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 2L, acquiredAtMs = ordinal, qualityFlags = 0L, qualityConfidence = null,
			payloadVersion = 1, payload = byteArrayOf(1), payloadChecksum = "not-decoded-in-this-fixture", createdAtMs = ordinal,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(row) shouldBe ordinal
	}

	private fun event(source: SourceKind, ordinal: Long): AdmittedSourceEvent<SourcePayload> = AdmittedSourceEvent(
		eventId = SourceEventId("${source.name}-$ordinal"), admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = null, logicalTrackingId = null, serviceRunId = null, source = source,
			sourceInstanceId = SourceInstanceId("radio-provider"), registrationGeneration = 1L,
			sourceSequence = ordinal, configRevision = 1L, planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot-1", observedElapsedRealtimeNanos = ordinal, receivedElapsedRealtimeNanos = ordinal,
			wallTimeMs = ordinal, wallTimeUncertaintyMs = 0L, capturedCollectedDataEpoch = 2L, acquiredAtMs = ordinal,
			quality = SourceQuality(), payloadVersion = 1,
			payload = if (source == SourceKind.WIFI) {
				WifiResultSnapshotPayload(emptyList(), null, null)
			}
				else {
				CellSnapshotPayload(null, emptyList(), CellRefreshOutcome.CALLBACK)
			},
		),
	)
}
