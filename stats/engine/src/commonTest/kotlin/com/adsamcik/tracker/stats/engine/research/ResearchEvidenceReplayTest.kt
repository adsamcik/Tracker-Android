package com.adsamcik.tracker.stats.engine.research

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.SegmentEvent
import com.adsamcik.tracker.stats.api.research.AltitudeConversionKind
import com.adsamcik.tracker.stats.api.research.AltitudeLocationObservationRecord
import com.adsamcik.tracker.stats.api.research.CanonicalLocationDecision
import com.adsamcik.tracker.stats.api.research.CanonicalLocationDecisionKind
import com.adsamcik.tracker.stats.api.research.CanonicalLocationEvidence
import com.adsamcik.tracker.stats.api.research.CanonicalSegmentationObservation
import com.adsamcik.tracker.stats.api.research.CanonicalStepEvidence
import com.adsamcik.tracker.stats.api.research.RawPressureEventRecord
import com.adsamcik.tracker.stats.api.research.PressureSensorDescriptorRecord
import com.adsamcik.tracker.stats.api.research.PressureValidityDecision
import com.adsamcik.tracker.stats.api.research.ResearchClockDomain
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceEnvelope
import com.adsamcik.tracker.stats.api.research.ResearchLifecycleBoundary
import com.adsamcik.tracker.stats.api.research.ResearchLossRange
import com.adsamcik.tracker.stats.api.research.ResearchPrivacyClass
import com.adsamcik.tracker.stats.api.research.ResearchTraceIdentity
import com.adsamcik.tracker.stats.api.research.ResearchTerminalIntegrityRecord
import com.adsamcik.tracker.stats.api.research.ResearchTraceLossRecord
import com.adsamcik.tracker.stats.engine.segment.SegmentDetectorConfig
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResearchEvidenceReplayTest {
	private val identity = ResearchTraceIdentity("trace-1", "run-1", "session-1")
	private val domain = ResearchClockDomain("boot-a", ResearchClockDomain.Kind.ANDROID_ELAPSED_REALTIME, "boot-a")

	@Test
	fun `recorder requires one identity and contiguous increasing evidence`() {
		val recorder = InMemoryResearchEvidenceRecorder(identity)
		recorder.append(envelope(0, PressureSensorDescriptorRecord("pressure")))
		recorder.append(
			envelope(
				3,
				PressureSensorDescriptorRecord("pressure"),
				lossRanges = listOf(ResearchLossRange(1, 2, ResearchLossRange.Reason.BUFFER_OVERFLOW)),
			),
		)
		assertEquals(2, recorder.snapshot().size)
		assertEquals(2L, recorder.close().lossCount)
		assertEquals(1L, recorder.terminalIntegrity().lossRangeCount)
		assertEquals(
			ResearchTerminalIntegrityRecord(2, 0, 3, 1, 2, true),
			recorder.terminalRecord(),
		)
		assertTrue(recorder.terminalIntegrity().complete)
		assertFailsWith<IllegalStateException> { recorder.append(envelope(4, PressureSensorDescriptorRecord("pressure"))) }

		val unaccounted = InMemoryResearchEvidenceRecorder(identity)
		unaccounted.append(envelope(0, PressureSensorDescriptorRecord("pressure")))
		assertFailsWith<IllegalArgumentException> {
			unaccounted.append(envelope(2, PressureSensorDescriptorRecord("pressure")))
		}
	}

	@Test
	fun `recorder validates terminal envelope accounting and disallows overlapping loss`() {
		val recorder = InMemoryResearchEvidenceRecorder(identity)
		recorder.append(envelope(0, PressureSensorDescriptorRecord("pressure")))
		val terminal = ResearchTerminalIntegrityRecord(2, 0, 1, 0, 0, true)
		recorder.append(envelope(1, PressureSensorDescriptorRecord("pressure"), terminalIntegrity = terminal))
		assertTrue(recorder.terminalIntegrity().complete)

		val overlapping = InMemoryResearchEvidenceRecorder(identity)
		overlapping.append(envelope(0, ResearchTraceLossRecord(listOf(ResearchLossRange(4, 5, ResearchLossRange.Reason.UNKNOWN)))))
		assertFailsWith<IllegalArgumentException> {
			overlapping.append(
				envelope(1, ResearchTraceLossRecord(listOf(ResearchLossRange(5, 6, ResearchLossRange.Reason.UNKNOWN)))),
			)
		}
	}

	@Test
	fun `altitude replay classifies deterministic invalid ordering instead of reading host time`() {
		val otherDomain = domain.copy(id = "boot-b", bootId = "boot-b")
		val records = listOf(
			envelope(1, rawPressure(1, 10)),
			envelope(2, rawPressure(1, 20)), // duplicate source sequence
			envelope(3, rawPressure(2, 5)), // out of order source time
			envelope(4, rawPressure(3, 30), clockDomain = otherDomain),
			envelope(5, rawPressure(4, 40), lifecycle = ResearchLifecycleBoundary.REBOOT),
			envelope(6, rawPressure(5, 99)), // supplied replay horizon makes this future
		)

		val result = AltitudeEvidenceReplay.classify(records, domain, replayHorizonNanos = 50)
		assertEquals(AltitudeReplayRejection.DUPLICATE_SOURCE_SEQUENCE, result.rejected[0].reason)
		assertEquals(AltitudeReplayRejection.OUT_OF_ORDER_SOURCE_TIME, result.rejected[1].reason)
		assertEquals(AltitudeReplayRejection.CROSS_CLOCK_DOMAIN, result.rejected[2].reason)
		assertEquals(AltitudeReplayRejection.CROSS_REBOOT, result.rejected[3].reason)
		assertEquals(AltitudeReplayRejection.FUTURE_EVENT, result.rejected[4].reason)
		assertEquals(listOf(10L), result.accepted.map { it.sourceTimeNanos })
		assertEquals(listOf(10L), result.acceptedBySourceTime.map { it.sourceTimeNanos })
	}

	@Test
	fun `altitude replay rejects source decisions and trace identity while keeping source namespaces distinct`() {
		val otherIdentity = identity.copy(traceId = "other")
		val aggregate = com.adsamcik.tracker.stats.api.research.PressureAggregateWindowRecord(
			sourceFirstSequence = 1,
			sourceLastSequence = 1,
			windowStartElapsedNanos = 10,
			windowEndElapsedNanos = 20,
			aggregatePressureHpa = 1000.0,
		)
		val rejectedRaw = rawPressure(2, 30).copy(validity = PressureValidityDecision.REJECTED_NON_FINITE)
		val result = AltitudeEvidenceReplay.classify(
			listOf(
				envelope(0, rawPressure(1, 10)),
				envelope(1, aggregate),
				envelope(2, rejectedRaw),
				envelope(3, rawPressure(3, 40)).copy(identity = otherIdentity),
			),
			domain,
			traceIdentity = identity,
		)
		assertEquals(2, result.accepted.size)
		assertEquals(AltitudeReplayRejection.REJECTED_BY_SOURCE, result.rejected[0].reason)
		assertEquals(AltitudeReplayRejection.CROSS_TRACE_IDENTITY, result.rejected[1].reason)
	}

	@Test
	fun `canonical V1 projection preserves nullable evidence and drops location only in legacy adapter`() {
		val locationless = observation(sequence = 1, location = null)
		assertNull(LegacyV1SegmentationProjection.project(locationless))
		assertNull(
			LegacyV1SegmentationProjection.project(
				observation(sequence = 2).copy(
					locationDecision = CanonicalLocationDecision(CanonicalLocationDecisionKind.REJECTED, "accuracy"),
				),
			),
		)

		val projected = LegacyV1SegmentationProjection.project(observation(sequence = 3))
		assertEquals(1, projected?.stepDelta)
		assertEquals(DetectedActivityType.WALKING, projected?.activityType)
	}

	@Test
	fun `V1 replay is repeatable and event-time ordering never crosses a clock-domain boundary`() {
		val input = listOf(
			observation(sequence = 0, epoch = 100, location = canonicalLocation(500_000_000)),
			observation(sequence = 2, epoch = 300, location = canonicalLocation(500_003_000)),
			observation(sequence = 1, epoch = 200, location = canonicalLocation(500_001_000)),
			observation(sequence = 3, epoch = 50, domainId = "boot-b", location = canonicalLocation(500_005_000)),
			observation(sequence = 4, epoch = 1, domainId = "boot-a", location = canonicalLocation(500_007_000)),
		)
		val runner = V1SegmentationReplay(
			SegmentDetectorConfig(departureConfirmationMs = 0, departureMinSteps = 1),
		)
		val first = runner.replay(input)
		val second = runner.replay(input)
		assertEquals(first, second)
		assertTrue(first.events.any { it is SegmentEvent.TripStarted })

		val ordered = runner.replay(input, SegmentationReplayOrder.EVENT_TIME_WITHIN_DOMAIN)
		assertEquals(2, ordered.clockDomainBoundaries)
		assertEquals(SegmentationReplayOrder.EVENT_TIME_WITHIN_DOMAIN, ordered.order)
	}

	@Test
	fun `envelope V1 replay retains identity capability loss and representation metadata`() {
		val loss = ResearchLossRange(1, 1, ResearchLossRange.Reason.SOURCE_UNAVAILABLE)
		val evidence = listOf(
			envelope(0, observation(0).copy(identityScopes = setOf("session"), capabilityFlags = setOf("gps"))),
			envelope(
				2,
				observation(2),
				lossRanges = listOf(loss),
			).copy(
				capabilities = com.adsamcik.tracker.stats.api.research.ResearchEvidenceCapabilities(
					canonicalSegmentationObservations = true,
				),
			),
		)
		val result = V1SegmentationReplay(SegmentDetectorConfig(departureConfirmationMs = 0, departureMinSteps = 1))
			.replayEvidence(evidence)
		assertEquals(identity, result.traceIdentity)
		assertEquals(2, result.envelopeCount)
		assertEquals(listOf(loss), result.lossRanges)
		assertEquals(1L, result.sensitivity.lossRangeCount)
		assertEquals(1L, result.sensitivity.lostEventCount)
		assertEquals(setOf("session"), result.sensitivity.representationMetadata.first().identityScopes)
		assertEquals(setOf("gps"), result.sensitivity.representationMetadata.first().capabilityFlags)
		assertTrue(result.sensitivity.representationMetadata.last().envelopeCapabilities?.canonicalSegmentationObservations == true)
	}

	@Test
	fun `transforms expose loss and refuse fabricated densification`() {
		val input = listOf(observation(1), observation(2), observation(3))
		val thinned = SegmentationEvidenceTransformations.thin(input, keep = { it.sourceSequence != 2L })
		assertEquals(listOf(2L), thinned.lossRanges.map { it.firstSequence })

		val densified = SegmentationEvidenceTransformations.densifyNonInformative(
			input,
			mapOf(1L to listOf(observation(10, location = null, step = null).copy(activityEvidence = null))),
		)
		assertEquals(4, densified.observations.size)

		assertFailsWith<IllegalArgumentException> {
			SegmentationEvidenceTransformations.densifyNonInformative(
				input,
				mapOf(1L to listOf(observation(11))),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			SegmentationEvidenceTransformations.densifyNonInformative(
				input,
				mapOf(99L to listOf(observation(12, location = null, step = null).copy(activityEvidence = null))),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			SegmentationEvidenceTransformations.densifyNonInformative(
				input,
				mapOf(1L to listOf(
					observation(12, location = null, step = null).copy(
						activityEvidence = null,
						locationDecision = CanonicalLocationDecision(CanonicalLocationDecisionKind.ACCEPTED),
					),
				)),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			SegmentationEvidenceTransformations.permuteWithinBatches(
				listOf(input.first(), input.first(), input.last()),
				permutation = { listOf(input.first(), input.last(), input.last()) },
			)
		}
		val cleared = SegmentationEvidenceTransformations.clearChannels(
			listOf(observation(20).copy(locationDecision = CanonicalLocationDecision(CanonicalLocationDecisionKind.ACCEPTED))),
			clearLocation = true,
		)
		assertNull(cleared.observations.single().locationDecision)
	}

	@Test
	fun `altitude numerical diagnostics reject non-finite asymmetric and indefinite covariance`() {
		val valid = AltitudeNumericalDiagnostics.inspect(
			state = listOf(100.0, -0.2),
			covariance = listOf(4.0, 1.0, 1.0, 2.0),
			dimension = 2,
		)
		assertTrue(valid.finiteState)
		assertTrue(valid.symmetricCovariance)
		assertTrue(valid.positiveSemidefiniteCovariance)

		val asymmetric = AltitudeNumericalDiagnostics.inspect(
			state = listOf(1.0),
			covariance = listOf(1.0, 0.0, 2.0, 1.0),
			dimension = 2,
		)
		assertTrue(!asymmetric.symmetricCovariance)
		assertTrue(!asymmetric.positiveSemidefiniteCovariance)

		val symmetricIndefinite = AltitudeNumericalDiagnostics.inspect(
			state = listOf(1.0, 2.0),
			covariance = listOf(1.0, 2.0, 2.0, 1.0),
			dimension = 2,
		)
		assertTrue(symmetricIndefinite.symmetricCovariance)
		assertTrue(!symmetricIndefinite.positiveSemidefiniteCovariance)

		val nonFinite = AltitudeNumericalDiagnostics.inspect(
			state = listOf(Double.NaN),
			covariance = listOf(1.0),
			dimension = 1,
		)
		assertTrue(!nonFinite.finiteState)
	}

	@Test
	fun `altitude numerical diagnostics are deterministic across seeded finite covariance matrices`() {
		val random = Random(0x510)
		repeat(200) {
			val a = random.nextDouble(-10.0, 10.0)
			val b = random.nextDouble(-10.0, 10.0)
			// A diagonal positive matrix gives an exact PSD oracle while still varying finite inputs.
			val covariance = listOf(
				a * a + 1.0, 0.0,
				0.0, b * b + 1.0,
			)
			val state = listOf(random.nextDouble(), random.nextDouble())
			val first = AltitudeNumericalDiagnostics.inspect(state, covariance, 2)
			val second = AltitudeNumericalDiagnostics.inspect(state, covariance, 2)
			assertEquals(first, second)
			assertTrue(first.finiteState)
			assertTrue(first.symmetricCovariance)
			assertTrue(first.positiveSemidefiniteCovariance)
		}
	}

	@Test
	fun `altitude replay keeps seeded monotonic timing deterministic`() {
		val random = Random(0xA17)
		var time = 0L
		val evidence = (0L until 100L).map { sequence ->
			time += random.nextLong(1L, 1_000L)
			envelope(sequence, rawPressure(sequence, time))
		}
		val first = AltitudeEvidenceReplay.classify(evidence, domain, traceIdentity = identity)
		val second = AltitudeEvidenceReplay.classify(evidence, domain, traceIdentity = identity)
		assertEquals(first, second)
		assertTrue(first.rejected.isEmpty())
		assertEquals(first.accepted.map { it.sourceTimeNanos }, first.acceptedBySourceTime.map { it.sourceTimeNanos })
	}

	@Test
	fun `authored V1 corpus has a versioned output snapshot and transformations are characterization`() {
		val corpus = AuthoredV1Corpus(
			version = "v1-authored-2026-07-22",
			observations = listOf(
			observation(0, epoch = 1_000, location = canonicalLocation(500_000_000)),
			observation(1, epoch = 2_000, location = canonicalLocation(500_003_000)),
			observation(2, epoch = 3_000, location = canonicalLocation(500_006_000)),
			),
		)
		val runner = V1SegmentationReplay(
			SegmentDetectorConfig(departureConfirmationMs = 0, departureMinSteps = 1),
		)
		val snapshot = snapshot(corpus.version, runner.replay(corpus.observations))
		assertEquals(
			V1Snapshot(
				corpusVersion = "v1-authored-2026-07-22",
				eventTypes = listOf("TripStarted"),
				projectedCount = 3,
				locationlessCount = 0,
				diagnostics = V1DiagnosticSnapshot(
					eventSequence = listOf("TripStarted"),
					stateDurationMs = emptyMap(),
					distanceM = 0f,
					stepCount = 0,
					boundaryDeltasMs = emptyList(),
					inferredModes = emptyList(),
					unresolvedCount = 0,
					lossRangeCount = 0,
					lostEventCount = 0,
					representationMetadata = List(3) {
						V1RepresentationMetadata(
							traceIdentity = null,
							envelopeSequence = null,
							clockDomainId = "boot-a",
							identityScopes = emptySet(),
							policyTier = null,
							capabilityFlags = emptySet(),
							envelopeCapabilities = null,
							algorithmVersions = emptyMap(),
							privacyClass = null,
						)
					},
				),
			),
			snapshot,
		)

		val nonInformative = observation(100, epoch = 2_500, location = null, step = null)
			.copy(activityEvidence = null)
		val transformations = listOf(
			SegmentationEvidenceTransformations.densifyNonInformative(corpus.observations, mapOf(1L to listOf(nonInformative))),
			SegmentationEvidenceTransformations.thin(corpus.observations, keep = { it.sourceSequence != 1L }),
			SegmentationEvidenceTransformations.jitterEpoch(corpus.observations, offsetMs = { 10L }),
			SegmentationEvidenceTransformations.changeWallClock(corpus.observations, offsetMs = { -10L }),
			SegmentationEvidenceTransformations.regroupBatches(corpus.observations, batch = { 0 to 1 }),
			SegmentationEvidenceTransformations.permuteWithinBatches(corpus.observations, permutation = { it.reversed() }),
			SegmentationEvidenceTransformations.duplicateExact(corpus.observations, listOf(1)),
			SegmentationEvidenceTransformations.addLongGap(corpus.observations, 3_000L, 10_000L, "boot-a"),
			SegmentationEvidenceTransformations.degradeAccuracy(corpus.observations, accuracy = { 500f }),
			SegmentationEvidenceTransformations.introduceJumps(corpus.observations, location = { 510_000_000 to 140_000_000 }),
			SegmentationEvidenceTransformations.clearChannels(corpus.observations, clearLocation = true, clearSteps = true, clearActivity = true),
			SegmentationEvidenceTransformations.resetSteps(corpus.observations, 1L),
			SegmentationEvidenceTransformations.remapClockDomain(corpus.observations, newDomain = { "replay-${it.clockDomainId}" }),
		)
		transformations.forEach { transformed ->
			val replay = runner.replay(transformed)
			assertEquals(transformed.observations.size, replay.inputCount)
			assertTrue(transformed.provenance.isNotBlank())
			assertEquals(transformed.lossRanges.size.toLong(), replay.sensitivity.lossRangeCount)
			assertEquals(replay.events.map { eventName(it) }, replay.sensitivity.eventSequence)
		}
	}

	private fun snapshot(corpusVersion: String, result: V1SegmentationReplayResult): V1Snapshot = V1Snapshot(
		corpusVersion = corpusVersion,
		eventTypes = result.events.map { event ->
			when (event) {
				is SegmentEvent.TripStarted -> "TripStarted"
				is SegmentEvent.TripUpdated -> "TripUpdated"
				is SegmentEvent.TripEnded -> "TripEnded"
				is SegmentEvent.DepartureCancelled -> "DepartureCancelled"
			}
		},
		projectedCount = result.projectedCount,
		locationlessCount = result.locationlessCount,
		diagnostics = V1DiagnosticSnapshot(
			eventSequence = result.sensitivity.eventSequence,
			stateDurationMs = result.sensitivity.stateDurationMs,
			distanceM = result.sensitivity.distanceM,
			stepCount = result.sensitivity.stepCount,
			boundaryDeltasMs = result.sensitivity.boundaryDeltasMs,
			inferredModes = result.sensitivity.inferredModes,
			unresolvedCount = result.sensitivity.unresolvedCount,
			lossRangeCount = result.sensitivity.lossRangeCount,
			lostEventCount = result.sensitivity.lostEventCount,
			representationMetadata = result.sensitivity.representationMetadata,
		),
	)

	private fun eventName(event: SegmentEvent): String = when (event) {
		is SegmentEvent.TripStarted -> "TripStarted"
		is SegmentEvent.TripUpdated -> "TripUpdated:${event.currentState.name}"
		is SegmentEvent.TripEnded -> "TripEnded:${event.inferredTransportMode.name}"
		is SegmentEvent.DepartureCancelled -> "DepartureCancelled"
	}

	private data class AuthoredV1Corpus(
		val version: String,
		val observations: List<CanonicalSegmentationObservation>,
	)

	private data class V1Snapshot(
		val corpusVersion: String,
		val eventTypes: List<String>,
		val projectedCount: Int,
		val locationlessCount: Int,
		val diagnostics: V1DiagnosticSnapshot,
	)

	private data class V1DiagnosticSnapshot(
		val eventSequence: List<String>,
		val stateDurationMs: Map<String, Long>,
		val distanceM: Float,
		val stepCount: Long,
		val boundaryDeltasMs: List<Long>,
		val inferredModes: List<String>,
		val unresolvedCount: Int,
		val lossRangeCount: Long,
		val lostEventCount: Long,
		val representationMetadata: List<V1RepresentationMetadata>,
	)

	private fun envelope(
		sequence: Long,
		record: com.adsamcik.tracker.stats.api.research.ResearchEvidenceRecord,
		clockDomain: ResearchClockDomain = domain,
		lifecycle: ResearchLifecycleBoundary? = null,
		lossRanges: List<ResearchLossRange> = emptyList(),
		terminalIntegrity: ResearchTerminalIntegrityRecord? = null,
	): ResearchEvidenceEnvelope = ResearchEvidenceEnvelope(
		identity = identity,
		sequence = sequence,
		clockDomain = clockDomain,
		privacyClass = ResearchPrivacyClass.ENCRYPTED_RESEARCH,
		lifecycleBoundary = lifecycle,
		lossRanges = lossRanges,
		terminalIntegrity = terminalIntegrity,
		record = record,
	)

	private fun rawPressure(sequence: Long, elapsed: Long) = RawPressureEventRecord(
		sourceSequence = sequence,
		sourceElapsedNanos = elapsed,
		pressureHpa = 1000.0,
		validity = PressureValidityDecision.ACCEPTED,
	)

	private fun canonicalLocation(latitude: Int) = CanonicalLocationEvidence(
		latitudeE7 = latitude,
		longitudeE7 = 140_000_000,
		horizontalAccuracyM = 5f,
		speedMps = 1.5f,
	)

	private fun observation(
		sequence: Long,
		epoch: Long = sequence * 1_000L,
		domainId: String = "boot-a",
		location: CanonicalLocationEvidence? = canonicalLocation(500_000_000 + sequence.toInt() * 1_000),
		step: CanonicalStepEvidence? = CanonicalStepEvidence(delta = 1),
	): CanonicalSegmentationObservation = CanonicalSegmentationObservation(
		eventEpochMs = epoch,
		acquisitionElapsedNanos = epoch * 1_000_000L,
		receiptEpochMs = epoch,
		receiptElapsedNanos = epoch * 1_000_000L,
		sourceSequence = sequence,
		clockDomainId = domainId,
		curatedLocation = location,
		stepEvidence = step,
		activityEvidence = com.adsamcik.tracker.stats.api.research.CanonicalActivityEvidence(
			type = DetectedActivityType.WALKING,
			confidence = 90,
		),
	)
}
