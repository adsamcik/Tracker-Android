package com.adsamcik.tracker.stats.api.research

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResearchEvidenceTest {
	@Test
	fun `only a successful conversion carries an MSL value`() {
		val success = AltitudeLocationObservationRecord(
			sourceSequence = 1,
			eventEpochMs = 10,
			acquisitionElapsedNanos = 20,
			ellipsoidAltitudeM = 250.0,
			conversion = AltitudeConversionKind.SUCCESS_MSL,
			convertedMslAltitudeM = 203.0,
		)
		success.convertedMslAltitudeM?.let { assertTrue(it < success.ellipsoidAltitudeM!!) }

		val absent = AltitudeLocationObservationRecord(
			sourceSequence = 2,
			eventEpochMs = 11,
			acquisitionElapsedNanos = 21,
			ellipsoidAltitudeM = 250.0,
			conversion = AltitudeConversionKind.NO_MSL_OUTPUT,
		)
		assertNull(absent.convertedMslAltitudeM)

		assertFailsWith<IllegalArgumentException> {
			absent.copy(convertedMslAltitudeM = 250.0)
		}
	}

	@Test
	fun `canonical location cannot carry a half coordinate`() {
		assertFailsWith<IllegalArgumentException> {
			CanonicalLocationEvidence(
				latitudeE7 = 500_000_000,
				longitudeE7 = null,
				horizontalAccuracyM = null,
				speedMps = null,
			)
		}
	}

	@Test
	fun `loss count is stable at ordinary and maximal sequence values`() {
		assertEquals(3L, ResearchLossRange(4, 6, ResearchLossRange.Reason.UNKNOWN).count)
		assertEquals(1L, ResearchLossRange(Long.MAX_VALUE, Long.MAX_VALUE, ResearchLossRange.Reason.UNKNOWN).count)
	}

	@Test
	fun `schema wire codec round trips every record and preserves declared contract metadata`() {
		val identity = ResearchTraceIdentity("trace", "run", "session")
		val domain = ResearchClockDomain("boot", ResearchClockDomain.Kind.ANDROID_ELAPSED_REALTIME, "boot")
		val capabilities = ResearchEvidenceCapabilities(
			rawPressureEvents = true,
			pressureAggregateWindows = true,
			altitudeConversionOutcomes = true,
			altitudeEstimatorDecisions = true,
			canonicalSegmentationObservations = true,
			segmentationReducerOutputs = true,
			truthMarkers = true,
		)
		val loss = ResearchLossRange(8, 9, ResearchLossRange.Reason.BUFFER_OVERFLOW)
		val records: List<ResearchEvidenceRecord> = listOf(
			PressureSensorDescriptorRecord("TYPE_PRESSURE", "sensor", "continuous"),
			RawPressureEventRecord(1, 10, 11, 12, 1000.0, PressureValidityDecision.ACCEPTED),
			PressureAggregateWindowRecord(1, 2, 10, 20, 20, 2, 2, 0, 0, 1000.0, 999.0, 1001.0, 1.0, "aggregate-v1"),
			AltitudeLocationObservationRecord(1, 10, 20, 21, 22, "gps", 250.0, 3.0, AltitudeConversionKind.SUCCESS_MSL, 203.0, "geoid-v1", "provider->geoid"),
			AltitudeCalibrationRecord(CalibrationDecision.ACCEPTED, 1, 2, "cal-v1", "lineage", null, 100.0, 101.0, "boot", "stable"),
			AltitudeFilterRecord(AltitudeFilterDecision.GPS_UPDATE, "filter-v1", 10, 20, 1, 203.0, 0.1, listOf(1.0), listOf(2.0), listOf(1.0), listOf(1.0), listOf(0.1), listOf(2.0), 1.0, 2.0, 1, 2, "accepted"),
			ResearchLifecycleRecord(ResearchLifecycleBoundary.PAUSE, "user"),
			ResearchTraceLossRecord(listOf(loss)),
			ResearchGapRecord(10, 20, "boot", "gap"),
			ResearchTruthMarkerRecord("marker", "arrival", 20),
			ResearchManualCorrectionRecord("correction", 10, 20, "walk", "reviewed"),
			CanonicalSegmentationObservation(
				eventEpochMs = 10,
				acquisitionElapsedNanos = 20,
				receiptEpochMs = 11,
				receiptElapsedNanos = 21,
				sourceSequence = 1,
				clockDomainId = "boot",
				identityScopes = setOf("session"),
				policyTier = PolicyTier.ACTIVE,
				capabilityFlags = setOf("gps"),
				curatedLocation = CanonicalLocationEvidence(500_000_000, 140_000_000, 5f, 1f, 0.5f, 2f, "gps", 0, 1, "active", "high", "fine"),
				locationDecision = CanonicalLocationDecision(CanonicalLocationDecisionKind.ACCEPTED, "valid", "source", 12, "decision-v1"),
				stepEvidence = CanonicalStepEvidence(1, 1, 1, 100),
				activityEvidence = CanonicalActivityEvidence(DetectedActivityType.WALKING, 90, 10, true, SourceTimeCapability.PRESENT),
			),
			SegmentationReducerOutputRecord(10, 20, 21, "WALK", "low", "complete", "rule", "replay", "v1", "config-v1"),
			ResearchTerminalIntegrityRecord(1, 0, 0, 0, 0, true),
		)

		records.forEachIndexed { index, record ->
			val envelope = ResearchEvidenceEnvelope(
				identity = identity,
				sequence = index.toLong(),
				clockDomain = domain,
				privacyClass = ResearchPrivacyClass.ENCRYPTED_RESEARCH,
				algorithmVersions = mapOf("replay" to "v1", "altitude" to "v1"),
				capabilities = capabilities,
				lossRanges = if (record is ResearchTraceLossRecord) emptyList() else listOf(loss),
				record = record,
			)
			assertEquals(envelope, ResearchEvidenceCodec.decode(ResearchEvidenceCodec.encode(envelope)))
		}
		assertEquals("tools/research-trace/RESEARCH_EVIDENCE_SCHEMA.md", RESEARCH_EVIDENCE_SCHEMA_DOCUMENT)
		assertEquals(1, RESEARCH_EVIDENCE_SCHEMA_VERSION)
	}
}
