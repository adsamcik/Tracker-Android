package com.adsamcik.tracker.tracker.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvidenceLedgerTest {
	@Test
	fun `bounded reordering emits monotonic timestamps`() {
		val ledger = EvidenceLedger(maxReorderNanos = 20L)
		ledger.offer(input(at = 20L))
		ledger.offer(input(at = 10L))
		ledger.offer(input(at = 50L))

		assertEquals(listOf(10L, 20L), ledger.drainReady().map { it.second.elapsedRealtimeNanos })
		assertEquals(listOf(50L), ledger.flush().map { it.second.elapsedRealtimeNanos })
	}

	@Test
	fun `too late evidence is surfaced rather than reordered into history`() {
		val ledger = EvidenceLedger(maxReorderNanos = 10L)
		ledger.offer(input(at = 100L))

		val offered = ledger.offer(input(at = 89L))

		assertTrue(offered is EvidenceLedger.OfferResult.Late)
		assertEquals(100L, (offered as EvidenceLedger.OfferResult.Late).lateInput.latestSeenElapsedRealtimeNanos)
	}

	@Test
	fun `duplicate location source id is surfaced without entering the buffer twice`() {
		val ledger = EvidenceLedger(maxReorderNanos = 0L)
		val first = locationInput(sourceEventId = "provider-fix-1", at = 1L)
		val duplicate = locationInput(sourceEventId = "provider-fix-1", at = 2L)

		ledger.offer(first)
		val offered = ledger.offer(duplicate)

		assertTrue(offered is EvidenceLedger.OfferResult.Duplicate)
		assertEquals(1L, (offered as EvidenceLedger.OfferResult.Duplicate).duplicateInput.originalLedgerSequence)
		assertEquals(listOf(first), ledger.flush().map { it.second })
	}
}

class TrackingDecisionEngineTest {
	@Test
	fun `user initiated session ignores automatic stop evidence`() {
		val engine = engine()
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 1L, ControlEvidence.AutomaticStopEvidence("IN_VEHICLE", 100)))
		engine.accept(event(at = 2_000_000_000L, ControlEvidence.Tick()))

		assertEquals(LogicalLifecycleState.ACTIVE, engine.snapshot().lifecycle)
	}

	@Test
	fun `automatic session uses grace and can recover on movement`() {
		val engine = engine(automaticStopGraceNanos = 1_000L)
		engine.accept(start(origin = TrackingSessionOrigin.AUTOMATIC))
		engine.accept(event(at = 10L, ControlEvidence.AutomaticStopEvidence("IN_VEHICLE", 90)))
		assertEquals(LogicalLifecycleState.STOP_CANDIDATE, engine.snapshot().lifecycle)

		engine.accept(event(at = 500L, ControlEvidence.ActivityEvidence(ActivityCategory.MOVING, 90)))
		assertEquals(LogicalLifecycleState.ACTIVE, engine.snapshot().lifecycle)

		engine.accept(event(at = 1_000L, ControlEvidence.AutomaticStopEvidence("IN_VEHICLE", 90)))
		engine.accept(event(at = 2_001L, ControlEvidence.Tick()))
		assertEquals(LogicalLifecycleState.FINISHED, engine.snapshot().lifecycle)
	}

	@Test
	fun `moving and unavailable location produces a bounded probe`() {
		val engine = engine(
			observabilityTimeoutNanos = 100L,
			probeCooldownNanos = 0L,
			minimumAcquisitionDwellNanos = 0L,
		)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 1L, ControlEvidence.ActivityEvidence(ActivityCategory.MOVING, 90)))
		engine.accept(event(at = 101L, ControlEvidence.Tick()))

		val request = engine.snapshot().acquisition
		assertEquals(LocationAcquisitionMode.PROBE, request.mode)
		assertNotNull(request.probeDurationMs)
	}

	@Test
	fun `provider loss is observability evidence and requests a bounded probe while moving`() {
		val engine = engine(minimumAcquisitionDwellNanos = 0L)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 1L, ControlEvidence.ActivityEvidence(ActivityCategory.MOVING, 90)))

		engine.accept(
			event(
				at = 2L,
				payload = ControlEvidence.ProviderAvailability(
					available = false,
					reason = "TEST_PROVIDER_DISABLED",
				),
			),
		)

		assertEquals(LocationObservabilityState.DEGRADED, engine.snapshot().observability)
		assertEquals(LocationAcquisitionMode.PROBE, engine.snapshot().acquisition.mode)
	}

	@Test
	fun `failed probe stays passive during cooldown and retries after cooldown`() {
		val engine = engine(
			observabilityTimeoutNanos = 10L,
			probeDurationNanos = 10L,
			probeCooldownNanos = 100L,
			minimumAcquisitionDwellNanos = 0L,
		)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 1L, ControlEvidence.ActivityEvidence(ActivityCategory.MOVING, 90)))
		engine.accept(event(at = 10L, ControlEvidence.Tick()))
		assertEquals(LocationAcquisitionMode.PROBE, engine.snapshot().acquisition.mode)

		engine.accept(event(at = 20L, ControlEvidence.Tick()))
		assertEquals(LocationAcquisitionMode.PASSIVE, engine.snapshot().acquisition.mode)
		assertEquals("PROBE_COOLDOWN", engine.snapshot().acquisition.reason)

		engine.accept(event(at = 119L, ControlEvidence.Tick()))
		assertEquals(LocationAcquisitionMode.PASSIVE, engine.snapshot().acquisition.mode)

		engine.accept(event(at = 120L, ControlEvidence.Tick()))
		assertEquals(LocationAcquisitionMode.PROBE, engine.snapshot().acquisition.mode)
	}

	@Test
	fun `duty budget deescalates an active request`() {
		val engine = engine(
			minimumAcquisitionDwellNanos = 0L,
			dutyWindowNanos = 10_000L,
			maximumActiveDutyNanos = 100L,
		)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 1L, ControlEvidence.ActivityEvidence(ActivityCategory.MOVING, 90)))
		engine.accept(
			event(
				at = 2L,
				payload = ControlEvidence.PolicyIntent(
					locationEnabled = true,
					preferredMode = LocationAcquisitionMode.HIGH_ACCURACY,
					reason = "TEST",
				),
			),
		)
		assertEquals(LocationAcquisitionMode.HIGH_ACCURACY, engine.snapshot().acquisition.mode)

		engine.accept(event(at = 102L, ControlEvidence.Tick()))
		assertEquals(LocationAcquisitionMode.PASSIVE, engine.snapshot().acquisition.mode)
	}

	@Test
	fun `preferred probe uses the configured bounded duration`() {
		val engine = engine(
			minimumAcquisitionDwellNanos = 0L,
			probeDurationNanos = 37_000_000L,
			probeCooldownNanos = 100L,
		)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 1L, ControlEvidence.ActivityEvidence(ActivityCategory.MOVING, 90)))
		engine.accept(
			event(
				at = 2L,
				payload = ControlEvidence.PolicyIntent(
					locationEnabled = true,
					preferredMode = LocationAcquisitionMode.PROBE,
					reason = "TEST",
				),
			),
		)

		assertEquals(37L, engine.snapshot().acquisition.probeDurationMs)

		engine.accept(event(at = 37_000_002L, ControlEvidence.Tick()))
		assertEquals(LocationAcquisitionMode.PASSIVE, engine.snapshot().acquisition.mode)
		assertEquals("PROBE_COOLDOWN", engine.snapshot().acquisition.reason)

		engine.accept(event(at = 37_000_102L, ControlEvidence.Tick()))
		assertEquals(LocationAcquisitionMode.PROBE, engine.snapshot().acquisition.mode)
	}

	@Test
	fun `rejected estimator observation does not conceal a continuity gap`() {
		val engine = engine(
			minimumAcquisitionDwellNanos = 0L,
			continuityGapNanos = 100L,
			estimatorGapNanos = 10_000L,
		)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 0L, location("initial", 50.0, 14.0)))
		engine.accept(event(at = 50L, location("teleport", 51.0, 15.0)))

		val outputs = engine.accept(event(at = 101L, location("recovered", 50.0, 14.0)))

		assertTrue(
			outputs.flatMap { it.transitions }.any {
				it.kind == ControlTransitionKind.CONTINUITY && it.to == TrackingContinuity.GAP_OPEN.name
			},
		)
	}

	@Test
	fun `rejected outlier neither closes an open gap nor supplies motion evidence`() {
		val engine = engine(
			minimumAcquisitionDwellNanos = 0L,
			continuityGapNanos = 100L,
			estimatorGapNanos = 10_000L,
		)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 0L, location("initial", 50.0, 14.0)))

		engine.accept(
			event(
				at = 101L,
				payload = location("teleport", 51.0, 15.0).copy(speedMetersPerSecond = 30.0),
			),
		)

		assertEquals(TrackingContinuity.GAP_OPEN, engine.snapshot().continuity)
		assertEquals(MotionState.UNKNOWN, engine.snapshot().motion)
	}

	@Test
	fun `new logical session resets policy estimator and provider deduplication state`() {
		val engine = engine(minimumAcquisitionDwellNanos = 0L)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(
			event(
				at = 1L,
				payload = ControlEvidence.PolicyIntent(
					locationEnabled = false,
					preferredMode = LocationAcquisitionMode.DISABLED,
					reason = "FIRST_SESSION",
				),
			),
		)
		engine.accept(event(at = 2L, location("provider-fix", 50.0, 14.0)))
		engine.accept(event(at = 3L, ControlEvidence.FinishRequested("TEST")))

		engine.accept(
			event(
				at = 4L,
				payload = ControlEvidence.SessionStarted(
					logicalTrackingId = LogicalTrackingId("logical-session-2"),
					origin = TrackingSessionOrigin.USER,
				),
			),
		)
		assertEquals(LocationAcquisitionMode.LOW_POWER, engine.snapshot().acquisition.mode)

		val outputs = engine.accept(event(at = 5L, location("provider-fix", 51.0, 15.0)))
		assertEquals(HorizontalEstimatorDecision.INITIALIZED, outputs.single().estimator?.decision)
		assertFalse(
			outputs.single().transitions.any {
				it.kind == ControlTransitionKind.DUPLICATE_INPUT_REJECTED
			},
		)
	}

	@Test
	fun `duplicate provider observation is replay visible and does not update state`() {
		val engine = engine(minimumAcquisitionDwellNanos = 0L)
		engine.accept(start(origin = TrackingSessionOrigin.USER))
		engine.accept(event(at = 1L, location("provider-fix-1", 50.0, 14.0)))

		val outputs = engine.accept(event(at = 2L, location("provider-fix-1", 51.0, 15.0)))

		assertTrue(
			outputs.single().transitions.any {
				it.kind == ControlTransitionKind.DUPLICATE_INPUT_REJECTED &&
					it.reason == "DUPLICATE_LOCATION_SOURCE_EVENT_ID"
			},
		)
	}

	@Test
	fun `clock-domain change opens an explicit continuity boundary`() {
		val engine = engine()
		engine.accept(start(origin = TrackingSessionOrigin.USER, domain = "boot-a"))
		val outputs = engine.accept(
			event(
				at = 10L,
				domain = "boot-b",
				payload = ControlEvidence.Tick("PROCESS_RESTART"),
			),
		)

		assertTrue(
			outputs.flatMap { it.transitions }.any {
				it.kind == ControlTransitionKind.CONTINUITY && it.reason == "CLOCK_DOMAIN_CHANGED"
			},
		)
	}

	private fun engine(
		automaticStopGraceNanos: Long = 1_000_000_000L,
		observabilityTimeoutNanos: Long = 90_000_000_000L,
		probeCooldownNanos: Long = 120_000_000_000L,
		minimumAcquisitionDwellNanos: Long = 0L,
		dutyWindowNanos: Long = 900_000_000_000L,
		maximumActiveDutyNanos: Long = 300_000_000_000L,
		continuityGapNanos: Long = 120_000_000_000L,
		estimatorGapNanos: Long = 120_000_000_000L,
		probeDurationNanos: Long = 20_000_000_000L,
	): TrackingDecisionEngine = TrackingDecisionEngine(
		TrackingDecisionConfig(
			maxReorderNanos = 0L,
			automaticStopGraceNanos = automaticStopGraceNanos,
			observabilityTimeoutNanos = observabilityTimeoutNanos,
			probeCooldownNanos = probeCooldownNanos,
			minimumAcquisitionDwellNanos = minimumAcquisitionDwellNanos,
			dutyWindowNanos = dutyWindowNanos,
			maximumActiveDutyNanos = maximumActiveDutyNanos,
			continuityGapNanos = continuityGapNanos,
			estimatorGapNanos = estimatorGapNanos,
			probeDurationNanos = probeDurationNanos,
		),
	)

	private fun start(
		origin: TrackingSessionOrigin,
		domain: String = "boot-a",
	): ControlInput = event(
		at = 0L,
		domain = domain,
		payload = ControlEvidence.SessionStarted(LogicalTrackingId("logical-session"), origin),
	)
}

class HorizontalKalmanEstimatorTest {
	@Test
	fun `outlier does not become the next estimator anchor`() {
		val estimator = HorizontalKalmanEstimator(gapNanos = 10_000_000_000L, nisThreshold = 16.0)
		val origin = observation("a", 50.0, 14.0)
		assertEquals(HorizontalEstimatorDecision.INITIALIZED, estimator.observe(origin, 0L).decision)
		assertEquals(HorizontalEstimatorDecision.ACCEPTED, estimator.observe(observation("b", 50.00001, 14.0), 1_000_000_000L).decision)

		val rejected = estimator.observe(observation("teleport", 51.0, 15.0), 2_000_000_000L)
		assertEquals(HorizontalEstimatorDecision.REJECTED_OUTLIER, rejected.decision)

		val recovered = estimator.observe(observation("c", 50.00002, 14.0), 3_000_000_000L)
		assertEquals(HorizontalEstimatorDecision.ACCEPTED, recovered.decision)
		assertNotNull(recovered.position)
		assertTrue(absDifference(recovered.position.latitude, 50.0) < 0.001)
	}

	@Test
	fun `long measurement gap resets rather than bridges`() {
		val estimator = HorizontalKalmanEstimator(gapNanos = 100L, nisThreshold = 16.0)
		estimator.observe(observation("a", 50.0, 14.0), 0L)

		val result = estimator.observe(observation("b", 51.0, 15.0), 101L)

		assertEquals(HorizontalEstimatorDecision.GAP_RESET, result.decision)
		assertTrue(result.gapOpened)
		assertEquals(51.0, result.position?.latitude)
	}

	@Test
	fun `dateline crossing uses the short longitude delta`() {
		val estimator = HorizontalKalmanEstimator(gapNanos = 10_000_000_000L, nisThreshold = 16.0)
		estimator.observe(observation("west", 0.0, 179.999999), 0L)

		val result = estimator.observe(
			observation("east", 0.0, -179.999999),
			1_000_000_000L,
		)

		assertEquals(HorizontalEstimatorDecision.ACCEPTED, result.decision)
		assertNotNull(result.position)
		assertTrue(result.position.longitude in -180.0..180.0)
	}

	private fun observation(id: String, latitude: Double, longitude: Double) =
		ControlEvidence.LocationObservation(
			sourceEventId = id,
			position = GeoPoint(latitude, longitude),
			horizontalAccuracyMeters = 5.0,
			ingressAccepted = true,
		)
}

private fun input(at: Long): ControlInput = event(at, ControlEvidence.Tick())

private fun event(
	at: Long,
	payload: ControlEvidence,
	domain: String = "boot-a",
	): ControlInput = ControlInput(
	wallTimeMs = at,
	elapsedRealtimeNanos = at,
	clockDomainId = domain,
	payload = payload,
)

private fun location(
	id: String,
	latitude: Double,
	longitude: Double,
): ControlEvidence.LocationObservation = ControlEvidence.LocationObservation(
	sourceEventId = id,
	position = GeoPoint(latitude, longitude),
	horizontalAccuracyMeters = 5.0,
	ingressAccepted = true,
)

private fun locationInput(sourceEventId: String, at: Long): ControlInput = event(
	at = at,
	payload = location(sourceEventId, 50.0, 14.0),
)

private fun absDifference(first: Double, second: Double): Double = if (first >= second) first - second else second - first
