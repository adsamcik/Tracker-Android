package com.adsamcik.tracker.tracker.source.lifecycle

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.PressureFactLineageProof
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.SourceProofLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionAdapter
import com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.wifi.WifiWalQualificationAdapter
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceRecordingQualificationTest {
	@Test
	fun `steps baseline registration and zero delta never prove recording`() {
		val wal = stepWal()

		stepsPayload(StepBoundaryKind.BASELINE, 0L).recordingDisqualification(wal) shouldBe
			SourceProofReason.BASELINE_ONLY
		stepsPayload(StepBoundaryKind.COUNTER_RESET, 0L).recordingDisqualification(wal) shouldBe
			SourceProofReason.BASELINE_ONLY
		stepsPayload(StepBoundaryKind.COVERED, 0L).recordingDisqualification(wal) shouldBe
			SourceProofReason.ZERO_DELTA
		stepsPayload(StepBoundaryKind.COVERED, 4L).recordingDisqualification(wal) shouldBe null
	}

	@Test
	fun `pressure accepts only qualified homogeneous committed windows`() {
		val completeWal = pressureWal(qualityFlags = 0L)
		val partialWal = pressureWal(qualityFlags = SourceQualityFlag.INCOMPLETE_WINDOW.bit)

		pressurePayload(PressureWindowClosureKind.TARGET_ELAPSED)
			.recordingCompletenessOrNull(completeWal) shouldBe SourceProofCompleteness.COMPLETE
		pressurePayload(PressureWindowClosureKind.SOURCE_BOUNDARY)
			.recordingCompletenessOrNull(partialWal) shouldBe SourceProofCompleteness.PARTIAL
		pressurePayload(PressureWindowClosureKind.SOURCE_BOUNDARY)
			.recordingCompletenessOrNull(completeWal) shouldBe null
	}

	@Test
	fun `Pressure materialization rejects selected admission before lane activation`() {
		val lane = SourceProductProjectionLaneEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			bindingGeneration = 1L,
			projectionId = "pressure",
			projectionVersion = 1,
			captureModeMask = 1L,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			activatedRolloutRevision = 1L,
			activationOrdinal = 2L,
			contiguousAdmissionOrdinal = 3L,
			retentionRequired = true,
			status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
			installedAtMs = 1L,
			updatedAtMs = 1L,
		)

		lane.materializationAdmissionFailure(listOf(3L, 1L)) shouldBe
			SourceProofReason.MATERIALIZATION_PENDING
		lane.materializationAdmissionFailure(listOf(3L, 2L)) shouldBe null
	}

	@Test
	fun `Pressure materialization coverage and completeness belong to selected revision`() {
		val selected = mockk<PressureFactRevisionEntity>()
		val current = mockk<PressureFactRevisionEntity>()
		every { selected.windowStartElapsedRealtimeNanos } returns 10L
		every { selected.windowEndElapsedRealtimeNanos } returns 20L
		every { selected.qualification } returns PressureFactRevisionEntity.QUALIFICATION_PARTIAL
		every { current.windowStartElapsedRealtimeNanos } returns 30L
		every { current.windowEndElapsedRealtimeNanos } returns 40L
		every { current.qualification } returns PressureFactRevisionEntity.QUALIFICATION_COMPLETE
		val proof = PressureFactLineageProof.Authenticated(
			current = current,
			revisions = listOf(selected, current),
			selected = selected,
		)

		proof.selectedPressureProofCoverage() shouldBe
			SourceProofCoverage.ProviderInterval(10L, 20L)
		proof.selectedPressureProofCompleteness() shouldBe SourceProofCompleteness.PARTIAL
	}

	@Test
	fun `Pressure materialization keeps complete selected evidence under partial current head`() {
		val selected = mockk<PressureFactRevisionEntity>()
		val current = mockk<PressureFactRevisionEntity>()
		every { selected.qualification } returns PressureFactRevisionEntity.QUALIFICATION_COMPLETE
		every { current.qualification } returns PressureFactRevisionEntity.QUALIFICATION_PARTIAL
		val proof = PressureFactLineageProof.Authenticated(
			current = current,
			revisions = listOf(selected, current),
			selected = selected,
		)

		proof.selectedPressureProofCompleteness() shouldBe SourceProofCompleteness.COMPLETE
	}

	@Test
	fun `Pressure materialization retention uses old revision under newer head`() {
		val selected = mockk<PressureFactRevisionEntity>()
		val current = mockk<PressureFactRevisionEntity>()
		every { selected.intervalStartTimeMs } returns 1_000L
		every { selected.wallTimeUncertaintyMs } returns 25L
		every { current.intervalStartTimeMs } returns 2_000L
		every { current.wallTimeUncertaintyMs } returns 5L
		val proof = PressureFactLineageProof.Authenticated(
			current = current,
			revisions = listOf(selected, current),
			selected = selected,
		)

		CanonicalRetentionBounds.pressureLineage(proof.revisions) shouldBe 975L
		CanonicalRetentionBounds.crossesFloor(
			CanonicalRetentionBounds.pressureLineage(proof.revisions),
			976L,
		) shouldBe true
	}

	@Test
	fun `projection failures retain terminal versus retryable materialization type`() {
		val lineage = SourceProofLineage(
			evidenceIdentity = SourceEvidenceIdentity("event"),
			sourceAdmissionOrdinal = 7L,
			sourceEvidenceRevision = 3L,
		)
		val retryable = projectionFailure(terminal = false)
			.toLifecycleMaterializationResult(lineage)
		val terminal = projectionFailure(terminal = true)
			.toLifecycleMaterializationResult(lineage)

		(retryable as SourceProofResult.Pending).reason shouldBe
			SourceProofReason.PROJECTION_RETRYABLE_FAILURE
		retryable.lineage shouldBe lineage
		(terminal as SourceProofResult.Failed).reason shouldBe
			SourceProofReason.PROJECTION_TERMINAL_FAILURE
		terminal.lineage shouldBe lineage
	}

	@Test
	fun `canonical location rejection never becomes materialized proof`() {
		val lineage = SourceProofLineage(
			evidenceIdentity = SourceEvidenceIdentity("location-event"),
			sourceAdmissionOrdinal = 3L,
			sourceEvidenceRevision = 2L,
		)

		val stale = canonicalLocationRejectionResult("STALE_FIX", lineage)
		val malformed = canonicalLocationRejectionResult("MOCK_LOCATION_REJECTED", lineage)

		(stale as SourceProofResult.Unavailable).reason shouldBe SourceProofReason.STALE
		stale.lineage shouldBe lineage
		(malformed as SourceProofResult.Failed).reason shouldBe
			SourceProofReason.MALFORMED_EVIDENCE
		malformed.lineage shouldBe lineage
	}

	@Test
	fun `activity control key is rejected before any provider database or writer access`() = runTest {
		val database = mockk<AppDatabase>()
		val codec = mockk<SourcePayloadCodec>()
		val planCodec = mockk<SourcePlanCodec>()
		val location = mockk<LocationWalQualificationAdapter>()
		val activity = mockk<ActivityCapturedWalAdmissionAdapter>()
		val wifi = mockk<WifiWalQualificationAdapter>()
		val cell = mockk<CellWalQualificationAdapter>()
		val adapter = DefaultSourceLifecycleProofAdapterFactory(
			database,
			codec,
			planCodec,
			location,
			activity,
			wifi,
			cell,
			BootClockDomainProvider { "test-boot" },
		).create(TrackingSource.ACTIVITY)

		val result = adapter.recording(
			SourceProofKey(
				source = TrackingSource.ACTIVITY,
				purpose = TrackingPurpose.CONTROL,
				evidenceIdentity = SourceEvidenceIdentity("control-event"),
				sourcePolicyRevision = 1L,
				captureConsentEpoch = 1L,
				collectedDataEpoch = 1L,
				scope = SourceProofScope.Run("logical", "run"),
			),
		)

		(result as SourceProofResult.Unavailable).reason shouldBe
			SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE
		verify(exactly = 0) {
			database.sourceEventWalDao()
		}
		coVerify(exactly = 0) {
			activity.admit(any())
			activity.admitHistorical(any())
			location.qualify(any())
			wifi.qualify(any())
			cell.qualify(any())
		}
	}

	private fun stepsPayload(
		boundary: StepBoundaryKind,
		delta: Long,
	) = StepCounterWindowPayload(
		bootClockDomainId = "boot",
		firstCumulativeCount = 10L,
		lastCumulativeCount = 10L + delta,
		deltaCount = delta,
		windowStartElapsedRealtimeNanos = 1_000L,
		windowEndElapsedRealtimeNanos = 2_000L,
		firstProviderSequence = 1L,
		lastProviderSequence = 2L,
		boundaryKind = boundary,
	)

	private fun pressurePayload(
		closure: PressureWindowClosureKind,
	) = PressureWindowPayload(
		sampleCount = 2,
		meanHectopascals = 1_000.5,
		sumSquaredDeviations = 0.5,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_001f,
		windowStartElapsedRealtimeNanos = 1_000_000L,
		windowEndElapsedRealtimeNanos = 2_000_000L,
		firstProviderSequence = 1L,
		lastProviderSequence = 2L,
		firstHectopascals = 1_000f,
		lastHectopascals = 1_001f,
		slopeHectopascalsPerSecond = 1_000.0,
		rSquared = 1.0,
		sensorAccuracy = PressureSensorAccuracy.HIGH,
		effectiveSamplePeriodMicros = 1_000,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 2_000_000L,
		expectedSampleCount = 2,
		maximumInterSampleGapNanos = 1_000_000L,
		closureKind = closure,
	)

	private fun stepWal(): SourceEventWalEntity {
		val wal = mockk<SourceEventWalEntity>()
		every { wal.observedElapsedNanos } returns 2_000L
		every { wal.clockDomainId } returns "boot"
		every { wal.sourceSequence } returns 2L
		return wal
	}

	private fun pressureWal(qualityFlags: Long): SourceEventWalEntity {
		val wal = mockk<SourceEventWalEntity>()
		every { wal.payloadVersion } returns
			PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION
		every { wal.observedElapsedNanos } returns 2_000_000L
		every { wal.qualityFlags } returns qualityFlags
		every { wal.sourceSequence } returns 2L
		return wal
	}

	private fun projectionFailure(terminal: Boolean) = SourceProjectionFailureEntity(
		projectionId = "writer",
		projectionVersion = 1,
		admissionOrdinal = 7L,
		attemptCount = 1,
		failureCode = "failure",
		terminal = terminal,
		lastAttemptAtMs = 1L,
	)
}
