package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.PressureWalClosureKind
import com.adsamcik.tracker.shared.base.database.data.PressureWalPayload
import com.adsamcik.tracker.shared.base.database.data.PressureWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.PressureWalSensorAccuracy
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import org.junit.Test
import kotlin.test.assertIs

@Suppress("LongMethod", "MagicNumber")
class PressureFactProofAuthenticatorTest {
	@Test
	fun `historical revision one and two select exact members while revision three remains current`() {
		val lineage = correctionLineage()
		val walByEventId = lineage.associate { fact -> fact.sourceEventId to wal(fact) }

		val first = assertIs<PressureFactLineageProof.Authenticated>(
			authenticate(lineage, walByEventId, expectation(lineage[0])),
		)
		val second = assertIs<PressureFactLineageProof.Authenticated>(
			authenticate(lineage, walByEventId, expectation(lineage[1])),
		)
		val head = assertIs<PressureFactLineageProof.Authenticated>(
			authenticate(lineage, walByEventId, expectation(lineage[2])),
		)

		first.selected shouldBe lineage[0]
		first.current shouldBe lineage[2]
		second.selected shouldBe lineage[1]
		second.current shouldBe lineage[2]
		head.selected shouldBe lineage[2]
		head.current shouldBe lineage[2]
	}

	@Test
	fun `mismatched or nonexistent evidence admission cannot authenticate a correction member`() {
		val lineage = correctionLineage()
		val walByEventId = lineage.associate { fact -> fact.sourceEventId to wal(fact) }
		val mismatched = expectation(lineage[0]).copy(
			sourceAdmissionOrdinal = lineage[1].sourceAdmissionOrdinal,
		)
		val nonexistent = expectation(lineage[0]).copy(
			evidenceIdentity = "pressure-event-missing",
		)

		authenticate(lineage, walByEventId, mismatched) shouldBe
			PressureFactLineageProof.Rejected(
				PressureFactProofFailure.CURRENT_FACT_MISMATCH,
			)
		authenticate(lineage, walByEventId, nonexistent) shouldBe
			PressureFactLineageProof.Rejected(
				PressureFactProofFailure.CURRENT_FACT_MISMATCH,
			)
	}

	@Test
	fun `selected historical revision still requires its exact fact checksum and WAL carrier`() {
		val lineage = correctionLineage()
		val corruptSelected = lineage.toMutableList().also {
			it[0] = it[0].copy(effectChecksum = "corrupt")
		}

		authenticate(
			corruptSelected,
			lineage.associate { fact -> fact.sourceEventId to wal(fact) },
			expectation(lineage[0]),
		) shouldBe PressureFactLineageProof.Rejected(
			PressureFactProofFailure.INTEGRITY_MISMATCH,
		)
	}

	@Test
	fun `correction lineage still requires contiguous revisions and distinct admissions`() {
		val lineage = correctionLineage()
		val walByEventId = lineage.associate { fact -> fact.sourceEventId to wal(fact) }
		val revisionGap = listOf(lineage[0], lineage[2])
		val duplicateAdmission = lineage.toMutableList().also {
			it[1] = it[1].copy(sourceAdmissionOrdinal = it[0].sourceAdmissionOrdinal)
		}

		authenticate(revisionGap, walByEventId, expectation(lineage[0])) shouldBe
			PressureFactLineageProof.Rejected(PressureFactProofFailure.LINEAGE_INCOMPLETE)
		authenticate(
			duplicateAdmission,
			duplicateAdmission.associate { fact -> fact.sourceEventId to wal(fact) },
			expectation(duplicateAdmission[0]),
		) shouldBe PressureFactLineageProof.Rejected(
			PressureFactProofFailure.LINEAGE_INCOMPLETE,
		)
	}

	private fun authenticate(
		lineage: List<PressureFactRevisionEntity>,
		walByEventId: Map<String, SourceEventWalEntity>,
		expectation: PressureFactProofExpectation,
	): PressureFactLineageProof = PressureFactProofAuthenticator.authenticateStoredLineage(
		revisions = lineage,
		walByEventId = walByEventId,
		bindingsByManifestRevision = mapOf(MANIFEST_REVISION to binding()),
		expectation = expectation,
	)

	private fun expectation(fact: PressureFactRevisionEntity) = PressureFactProofExpectation(
		evidenceIdentity = fact.sourceEventId,
		sourcePolicyRevision = fact.sourcePolicyRevision,
		captureConsentEpoch = fact.captureConsentEpoch,
		collectedDataEpoch = fact.collectedDataEpoch,
		logicalTrackingId = fact.logicalTrackingId,
		serviceRunId = fact.serviceRunId,
		logicalFactId = fact.logicalFactId,
		semanticRevision = fact.semanticRevision,
		sourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
		writerProjectionId = fact.writerProjectionId,
		writerProjectionVersion = fact.writerProjectionVersion,
		writerBindingGeneration = fact.writerBindingGeneration,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
	)

	private fun correctionLineage(): List<PressureFactRevisionEntity> {
		val first = fact(
			semanticRevision = 1L,
			eventId = "pressure-event-1",
			admissionOrdinal = 1L,
			windowStartNanos = 1_000_000_000L,
			firstProviderSequence = 1L,
			endTimeMs = 1_150L,
		)
		return listOf(
			first,
			fact(
				semanticRevision = 2L,
				eventId = "pressure-event-2",
				admissionOrdinal = 2L,
				windowStartNanos = 2_000_000_000L,
				firstProviderSequence = 5L,
				endTimeMs = 2_150L,
			),
			fact(
				semanticRevision = 3L,
				eventId = "pressure-event-3",
				admissionOrdinal = 3L,
				windowStartNanos = 3_000_000_000L,
				firstProviderSequence = 9L,
				endTimeMs = 3_150L,
			),
		)
	}

	private fun fact(
		semanticRevision: Long,
		eventId: String,
		admissionOrdinal: Long,
		windowStartNanos: Long,
		firstProviderSequence: Long,
		endTimeMs: Long,
	): PressureFactRevisionEntity {
		val unsigned = PressureFactRevisionEntity(
			logicalFactId = LOGICAL_FACT_ID,
			semanticRevision = semanticRevision,
			mutationId = "$LOGICAL_FACT_ID:$semanticRevision",
			sourceEventId = eventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			writerBindingGeneration =
				SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
			intervalStartTimeMs = endTimeMs - WINDOW_DURATION_MS,
			intervalEndTimeMs = endTimeMs,
			windowStartElapsedRealtimeNanos = windowStartNanos,
			windowEndElapsedRealtimeNanos = windowStartNanos + WINDOW_DURATION_NANOS,
			clockDomainId = CLOCK_DOMAIN_ID,
			wallTimeUncertaintyMs = 1L,
			sampleCount = 4,
			meanHectopascals = 1_001.5,
			sumSquaredDeviations = 5.0,
			minimumHectopascals = 1_000f,
			maximumHectopascals = 1_003f,
			firstProviderSequence = firstProviderSequence,
			lastProviderSequence = firstProviderSequence + 3L,
			firstHectopascals = 1_000f,
			lastHectopascals = 1_003f,
			slopeHectopascalsPerSecond = 20.0,
			rSquared = 1.0,
			sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
			effectiveSamplePeriodMicros = 50_000,
			effectiveMaximumReportLatencyMicros = 200_000,
			targetWindowDurationNanos = 200_000_000L,
			expectedSampleCount = 4,
			maximumInterSampleGapNanos = 50_000_000L,
			closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
			qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
			sourceQualityFlags = 0L,
			sourceQualityConfidence = null,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			manifestRevision = MANIFEST_REVISION,
			sourcePolicyRevision = SOURCE_POLICY_REVISION,
			captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			effectChecksum = "pending",
			appliedAtMs = endTimeMs,
		)
		return unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, binding()),
		)
	}

	private fun wal(fact: PressureFactRevisionEntity): SourceEventWalEntity {
		val payload = PressureWalPayload(
			sampleCount = fact.sampleCount,
			meanHectopascals = fact.meanHectopascals,
			sumSquaredDeviations = fact.sumSquaredDeviations,
			minimumHectopascals = fact.minimumHectopascals,
			maximumHectopascals = fact.maximumHectopascals,
			windowStartElapsedRealtimeNanos = fact.windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = fact.windowEndElapsedRealtimeNanos,
			firstProviderSequence = fact.firstProviderSequence,
			lastProviderSequence = fact.lastProviderSequence,
			firstHectopascals = fact.firstHectopascals,
			lastHectopascals = fact.lastHectopascals,
			slopeHectopascalsPerSecond = fact.slopeHectopascalsPerSecond,
			rSquared = fact.rSquared,
			sensorAccuracy = PressureWalSensorAccuracy.valueOf(fact.sensorAccuracy),
			effectiveSamplePeriodMicros = fact.effectiveSamplePeriodMicros,
			effectiveMaximumReportLatencyMicros = fact.effectiveMaximumReportLatencyMicros,
			targetWindowDurationNanos = fact.targetWindowDurationNanos,
			expectedSampleCount = fact.expectedSampleCount,
			maximumInterSampleGapNanos = fact.maximumInterSampleGapNanos,
			closureKind = PressureWalClosureKind.valueOf(fact.closureKind),
		)
		val bytes = PressureWalPayloadCodec.encode(payload)
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = fact.sourceAdmissionOrdinal,
			eventId = fact.sourceEventId,
			providerDedupKey = null,
			deliveryIdentity = pressureDeliveryIdentity(fact.clockDomainId, payload),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = fact.logicalTrackingId,
			serviceRunId = fact.serviceRunId,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			sourceInstanceId = "pressure-provider",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "pressure-config",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = fact.lastProviderSequence,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = fact.clockDomainId,
			observedElapsedNanos = fact.windowEndElapsedRealtimeNanos,
			observedIntervalStartNanos = fact.windowStartElapsedRealtimeNanos,
			receivedElapsedNanos = fact.windowEndElapsedRealtimeNanos + 1L,
			receivedWallTimeMs = fact.intervalEndTimeMs + 1L,
			wallTimeMs = fact.intervalEndTimeMs,
			wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
			capturedCollectedDataEpoch = fact.collectedDataEpoch,
			sourcePolicyRevision = fact.sourcePolicyRevision,
			captureConsentEpoch = fact.captureConsentEpoch,
			sessionManifestRevision = fact.manifestRevision,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = fact.intervalEndTimeMs,
			qualityFlags = fact.sourceQualityFlags,
			qualityConfidence = fact.sourceQualityConfidence,
			payloadVersion = fact.payloadVersion,
			payload = bytes,
			payloadChecksum = "",
			createdAtMs = fact.intervalEndTimeMs + 1L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
	}

	private fun binding() = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		manifestRevision = MANIFEST_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = CAPTURE_CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = 2,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
	)

	private fun pressureDeliveryIdentity(
		clockDomainId: String,
		payload: PressureWalPayload,
	): String {
		val canonical = buildString {
			append("pressure-window-v1|")
			append(clockDomainId.length)
			append(':')
			append(clockDomainId)
			append("|start:")
			append(payload.windowStartElapsedRealtimeNanos)
			append("|end:")
			append(payload.windowEndElapsedRealtimeNanos)
			append("|count:")
			append(payload.sampleCount)
			append("|mean-bits:")
			append(payload.meanHectopascals.toRawBits())
			append("|m2-bits:")
			append(payload.sumSquaredDeviations.toRawBits())
			append("|min-bits:")
			append(payload.minimumHectopascals.toRawBits())
			append("|max-bits:")
			append(payload.maximumHectopascals.toRawBits())
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	private companion object {
		const val LOGICAL_FACT_ID =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:pressure-event-1"
		const val LOGICAL_TRACKING_ID = "logical-pressure"
		const val SERVICE_RUN_ID = "run-pressure"
		const val CLOCK_DOMAIN_ID = "boot-pressure"
		const val MANIFEST_REVISION = 1L
		const val SOURCE_POLICY_REVISION = 3L
		const val CAPTURE_CONSENT_EPOCH = 4L
		const val COLLECTED_DATA_EPOCH = 7L
		const val WINDOW_DURATION_MS = 150L
		const val WINDOW_DURATION_NANOS = 150_000_000L
	}
}
