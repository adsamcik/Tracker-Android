@file:Suppress("LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database.data

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStoredOwner
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import java.security.MessageDigest
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class SourceWalCarrierAuthenticatorTest {
	@Test
	fun `first zero-based WAL sequence authenticates for Activity Steps and Pressure`() {
		val activity = activityWal(ActivityWalPayload.Transition(1, 0, 1_000_000L))
			.resigned(sourceSequence = 0L)
		val steps = stepsWal(stepsPayload()).resigned(sourceSequence = 0L)
		val pressure = pressureWal(pressurePayload()).resigned(sourceSequence = 0L)
		assertIs<ActivityWalCarrierAuthentication.Authenticated>(ActivityWalCarrierAuthenticator.authenticate(activity))
		val stepsResult = StepsWalCarrierAuthenticator.authenticate(steps)
		assertIs<StepsWalCarrierAuthentication.Authenticated>(stepsResult, stepsResult.toString())
		assertIs<PressureWalCarrierAuthentication.Authenticated>(PressureWalCarrierAuthenticator.authenticate(pressure))
	}

	@Test
	fun `negative WAL sequence remains rejected even with recomputed integrity`() {
		val activity = activityWal(ActivityWalPayload.Transition(1, 0, 1_000_000L))
			.resigned(sourceSequence = -1L)
		val steps = stepsWal(stepsPayload()).resigned(sourceSequence = -1L)
		val pressure = pressureWal(pressurePayload()).resigned(sourceSequence = -1L)
		assertIs<ActivityWalCarrierAuthentication.Rejected>(ActivityWalCarrierAuthenticator.authenticate(activity))
		assertIs<StepsWalCarrierAuthentication.Rejected>(StepsWalCarrierAuthenticator.authenticate(steps))
		assertIs<PressureWalCarrierAuthentication.Rejected>(PressureWalCarrierAuthenticator.authenticate(pressure))
	}
}

class PressureWalCarrierAuthenticatorTest {
	@Test
	fun `Pressure fact rejects rehashed canonical WAL with different hPa payload`() {
		val binding = pressureBinding()
		val originalWal = pressureWal(pressurePayload())
		val fact = pressureFact(originalWal, binding)
		assertTrue(PressureWalCarrierAuthenticator.authenticates(fact, originalWal, binding))

		val differentPayload = pressurePayload().copy(
			meanHectopascals = 1_001.5,
			minimumHectopascals = 1_001f,
			maximumHectopascals = 1_002f,
			firstHectopascals = 1_001f,
			lastHectopascals = 1_002f,
		)
		val rehashed = pressureWal(differentPayload)

		assertIs<PressureWalCarrierAuthentication.Authenticated>(
			PressureWalCarrierAuthenticator.authenticate(rehashed),
		)
		assertFalse(PressureWalCarrierAuthenticator.authenticates(fact, rehashed, binding))
	}

	@Test
	fun `Pressure current correction remains bound and unsupported version is rejected`() {
		val binding = pressureBinding()
		val wal = pressureWal(pressurePayload())
		val first = pressureFact(wal, binding)
		val correctionWal = pressureWal(
			pressurePayload(),
			eventId = "pressure-correction",
			admissionOrdinal = 2L,
		)
		val unsignedCorrection = first.copy(
			semanticRevision = 2L,
			mutationId = "${first.logicalFactId}:2",
			sourceEventId = correctionWal.eventId,
			sourceAdmissionOrdinal = correctionWal.admissionOrdinal,
			effectChecksum = "pending",
		)
		val correction = unsignedCorrection.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(
				unsignedCorrection,
				binding,
			),
		)

		assertTrue(
			PressureWalCarrierAuthenticator.authenticates(
				correction,
				correctionWal,
				binding,
			),
		)
		assertIs<PressureWalCarrierAuthentication.Rejected>(
			PressureWalCarrierAuthenticator.authenticate(wal.resigned(payloadVersion = 3)),
		)
	}
}

class ActivityWalCarrierAuthenticatorTest {
	@Test
	fun `Activity evidence rejects control and rehashed payload mismatch`() {
		val payload = ActivityWalPayload.Transition(
			activityType = 1,
			transitionType = 0,
			providerElapsedRealtimeNanos = 1_000_000L,
		)
		val wal = activityWal(payload)
		val revision = activityRevision()
		val evidence = activityEvidence()
		assertTrue(ActivityWalCarrierAuthenticator.authenticates(revision, evidence, wal))

		val changed = activityWal(payload.copy(activityType = 2))
		assertIs<ActivityWalCarrierAuthentication.Authenticated>(
			ActivityWalCarrierAuthenticator.authenticate(changed),
		)
		assertFalse(ActivityWalCarrierAuthenticator.authenticates(revision, evidence, changed))

		val control = wal.resigned(
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			logicalTrackingId = null,
			serviceRunId = null,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			sessionManifestRevision = null,
			lifecycleLeaseGeneration = null,
		)
		val rejection = assertIs<ActivityWalCarrierAuthentication.Rejected>(
			ActivityWalCarrierAuthenticator.authenticate(control),
		)
		assertTrue(rejection.reason == ActivityWalCarrierFailure.CONTROL_ONLY)
	}
}

class StepsWalCarrierAuthenticatorTest {
	@Test
	fun `Steps fact binds positive delta token and effects to exact WAL`() {
		val payload = stepsPayload()
		val wal = stepsWal(payload)
		val fact = stepsFact(wal, payload)
		val owners = stepsOwners(wal, fact, requireNotNull(payload.counterDomainToken))

		val valid = StepsWalCarrierAuthenticator.authenticate(
			fact,
			wal,
			owners.first,
			owners.second,
		)
		assertIs<StepsWalCarrierAuthentication.Authenticated>(valid)
		assertTrue(valid.carrier.isPositiveCoveredWindow)

		val changedPayload = payload.copy(
			lastCumulativeCount = 15L,
			deltaCount = 5L,
		)
		val rehashed = stepsWal(changedPayload)
		assertFalse(
			StepsWalCarrierAuthenticator.authenticates(
				fact,
				rehashed,
				owners.first,
				owners.second,
			),
		)
	}

	@Test
	fun `Steps baseline and unsupported version cannot prove positive recording`() {
		val baselinePayload = stepsPayload().copy(
			lastCumulativeCount = 10L,
			deltaCount = 0L,
			windowEndElapsedRealtimeNanos = 1_000_000L,
			lastProviderSequence = 1L,
			boundaryKind = StepsWalBoundaryKind.BASELINE,
		)
		val baselineWal = stepsWal(baselinePayload).resigned(
			observedElapsedNanos = baselinePayload.windowEndElapsedRealtimeNanos,
			sourceSequence = baselinePayload.lastProviderSequence,
		)
		val owner = stepsWalOwner(
			baselineWal,
			requireNotNull(baselinePayload.counterDomainToken),
		)
		val authenticated = assertIs<StepsWalCarrierAuthentication.Authenticated>(
			StepsWalCarrierAuthenticator.authenticate(baselineWal, owner),
		)
		assertFalse(authenticated.carrier.isPositiveCoveredWindow)
		assertIs<StepsWalCarrierAuthentication.Rejected>(
			StepsWalCarrierAuthenticator.authenticate(
				baselineWal.resigned(payloadVersion = STEPS_WAL_PAYLOAD_VERSION + 1),
			),
		)
	}
}

private fun pressurePayload() = PressureWalPayload(
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
	sensorAccuracy = PressureWalSensorAccuracy.HIGH,
	effectiveSamplePeriodMicros = 1_000,
	effectiveMaximumReportLatencyMicros = 0,
	targetWindowDurationNanos = 2_000_000L,
	expectedSampleCount = 2,
	maximumInterSampleGapNanos = 1_000_000L,
	closureKind = PressureWalClosureKind.TARGET_ELAPSED,
)

private fun pressureWal(
	payload: PressureWalPayload,
	eventId: String = PRESSURE_EVENT,
	admissionOrdinal: Long = 1L,
): SourceEventWalEntity {
	val bytes = PressureWalPayloadCodec.encode(payload)
	return signedWal(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		eventId = eventId,
		payloadVersion = PRESSURE_WAL_PAYLOAD_VERSION,
		payload = bytes,
		sourceSequence = payload.lastProviderSequence,
		observedStart = payload.windowStartElapsedRealtimeNanos,
		observedEnd = payload.windowEndElapsedRealtimeNanos,
		providerDedupKey = null,
		deliveryIdentity = pressureDeliveryIdentity(payload),
		deliveryUnitIndex = 0,
		deliveryUnitCount = 1,
	).resigned(admissionOrdinal = admissionOrdinal)
}

private fun pressureFact(
	wal: SourceEventWalEntity,
	binding: SessionManifestSourceEntity,
): PressureFactRevisionEntity {
	val payload = pressurePayload()
	val logicalId = "${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:${wal.eventId}"
	val unsigned = PressureFactRevisionEntity(
		logicalFactId = logicalId,
		semanticRevision = 1L,
		mutationId = "$logicalId:1",
		sourceEventId = wal.eventId,
		sourceAdmissionOrdinal = wal.admissionOrdinal,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration =
			SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		payloadVersion = wal.payloadVersion,
		intervalStartTimeMs = 9_999L,
		intervalEndTimeMs = 10_000L,
		windowStartElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos,
		windowEndElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
		clockDomainId = BOOT,
		wallTimeUncertaintyMs = 1L,
		sampleCount = payload.sampleCount,
		meanHectopascals = payload.meanHectopascals,
		sumSquaredDeviations = payload.sumSquaredDeviations,
		minimumHectopascals = payload.minimumHectopascals,
		maximumHectopascals = payload.maximumHectopascals,
		firstProviderSequence = payload.firstProviderSequence,
		lastProviderSequence = payload.lastProviderSequence,
		firstHectopascals = payload.firstHectopascals,
		lastHectopascals = payload.lastHectopascals,
		slopeHectopascalsPerSecond = payload.slopeHectopascalsPerSecond,
		rSquared = payload.rSquared,
		sensorAccuracy = payload.sensorAccuracy.name,
		effectiveSamplePeriodMicros = payload.effectiveSamplePeriodMicros,
		effectiveMaximumReportLatencyMicros =
			payload.effectiveMaximumReportLatencyMicros,
		targetWindowDurationNanos = payload.targetWindowDurationNanos,
		expectedSampleCount = payload.expectedSampleCount,
		maximumInterSampleGapNanos = payload.maximumInterSampleGapNanos,
		closureKind = payload.closureKind.name,
		qualification = payload.qualification,
		sourceQualityFlags = wal.qualityFlags,
		sourceQualityConfidence = wal.qualityConfidence,
		logicalTrackingId = LOGICAL,
		serviceRunId = RUN,
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = MANIFEST,
		sourcePolicyRevision = POLICY,
		captureConsentEpoch = CONSENT,
		collectedDataEpoch = EPOCH,
		effectChecksum = "pending",
		appliedAtMs = 10_000L,
	)
	return unsigned.copy(
		effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, binding),
	)
}

private fun pressureBinding() = SessionManifestSourceEntity(
	logicalTrackingId = LOGICAL,
	manifestRevision = MANIFEST,
	sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
	purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
	consentEpoch = CONSENT,
	persistenceEligible = true,
	qosCode = 1,
	outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
	writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
	writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
	writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
	writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
	writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
)

private fun activityWal(payload: ActivityWalPayload): SourceEventWalEntity =
	signedWal(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		eventId = ACTIVITY_EVENT,
		payloadVersion = ACTIVITY_WAL_PAYLOAD_VERSION,
		payload = ActivityWalPayloadCodec.encode(payload),
		sourceSequence = 1L,
		observedStart = requireNotNull(payload.providerElapsedRealtimeNanos),
		observedEnd = requireNotNull(payload.providerElapsedRealtimeNanos),
		providerDedupKey = null,
		deliveryIdentity = "d".repeat(64),
		deliveryUnitIndex = 0,
		deliveryUnitCount = 1,
	)

private fun activityRevision() = ActivityCapturedWindowRevisionEntity(
	writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
	writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
	writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
	writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
	logicalWindowId = "activity-window",
	semanticRevision = 1L,
	supersedesSemanticRevision = null,
	mutationId = "activity-mutation",
	logicalTrackingId = LOGICAL,
	serviceRunId = RUN,
	sessionSegmentId = 1L,
	purpose = SourceBrokerPurpose.SESSION_CAPTURE,
	sourceInstanceId = INSTANCE,
	registrationGeneration = REGISTRATION,
	configurationRevision = CONFIGURATION,
	physicalConfigurationFingerprint = FINGERPRINT,
	authorizationRevision = AUTHORIZATION,
	authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
	purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
	sourcePolicyRevision = POLICY,
	captureConsentEpoch = CONSENT,
	manifestRevision = MANIFEST,
	lifecycleLeaseGeneration = LEASE,
	collectedDataEpoch = EPOCH,
	clockDomainId = BOOT,
	storedZoneId = "UTC",
	providerAcceptanceStartNanos = 1L,
	providerAcceptanceEndNanos = 2_000_000L,
	authorizationEffectStartNanos = 1L,
	authorizationEffectEndNanos = 2_000_000L,
	sessionRunEffectStartNanos = 1L,
	sessionRunEffectEndNanos = 2_000_000L,
	windowStartElapsedRealtimeNanos = 500_000L,
	windowEndElapsedRealtimeNanos = 1_500_000L,
	coverage = "COMPLETE",
	knownActiveDurationNanos = 1_000_000L,
	knownInactiveDurationNanos = 0L,
	unknownActivityDurationNanos = 0L,
	unobservedDurationNanos = 0L,
	exactDuplicateCount = 0,
	semanticDuplicateCount = 0,
	unchangedEvidenceCount = 0,
	scopeDeletionGeneration = 0L,
	effectChecksum = "activity-effect",
	appliedAtMs = 1_000L,
)

private fun activityEvidence() = ActivityCapturedEvidenceEntity(
	writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
	writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
	logicalWindowId = "activity-window",
	semanticRevision = 1L,
	fragmentOrdinal = 0,
	evidenceOrdinal = 0,
	sourceEventId = ACTIVITY_EVENT,
	sourceAdmissionOrdinal = 1L,
	sourceSequence = 1L,
	providerElapsedRealtimeNanos = 1_000_000L,
	receivedElapsedRealtimeNanos = 1_000_001L,
	observationKind = ActivityCapturedEvidenceEntity.KIND_TRANSITION,
	observedActivity = "WALKING",
	transitionChange = "ENTER",
	confidencePercent = null,
	coverageEndExclusiveElapsedRealtimeNanos = null,
)

private fun stepsPayload() = StepsWalPayload(
	bootClockDomainId = BOOT,
	firstCumulativeCount = 10L,
	lastCumulativeCount = 14L,
	deltaCount = 4L,
	windowStartElapsedRealtimeNanos = 1_000_000L,
	windowEndElapsedRealtimeNanos = 2_000_000L,
	firstProviderSequence = 1L,
	lastProviderSequence = 2L,
	boundaryKind = StepsWalBoundaryKind.COVERED,
	counterEpochGeneration = 1L,
	counterDomainToken = StepsCounterDomainToken.opaque("sha256:${"e".repeat(64)}"),
)

private fun stepsWal(payload: StepsWalPayload) = signedWal(
	sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
	eventId = STEPS_EVENT,
	payloadVersion = STEPS_WAL_PAYLOAD_VERSION,
	payload = StepsWalPayloadCodec.encode(payload),
	sourceSequence = payload.lastProviderSequence,
	observedStart = payload.windowStartElapsedRealtimeNanos,
	observedEnd = payload.windowEndElapsedRealtimeNanos,
	providerDedupKey = "steps-dedup",
	deliveryIdentity = null,
	deliveryUnitIndex = null,
	deliveryUnitCount = null,
)

private fun stepsFact(
	wal: SourceEventWalEntity,
	payload: StepsWalPayload,
): StepFactRevisionEntity {
	val logicalId = "${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:${wal.eventId}"
	val unsigned = StepFactRevisionEntity(
		logicalFactId = logicalId,
		semanticRevision = 1L,
		mutationId = "$logicalId:1:UPSERT",
		stepIntervalId = null,
		sourceEventId = wal.eventId,
		sourceAdmissionOrdinal = wal.admissionOrdinal,
		originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
		originIdentity = wal.eventId,
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = 9_999L,
		intervalEndTimeMs = 10_000L,
		intervalStartElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos,
		intervalEndElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
		clockDomainId = BOOT,
		bootClockDomainId = BOOT,
		cumulativeStepCountStart = payload.firstCumulativeCount,
		cumulativeStepCountEnd = payload.lastCumulativeCount,
		wallTimeUncertaintyMs = 1L,
		coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
		effectiveStepCount = payload.deltaCount,
		logicalTrackingId = LOGICAL,
		serviceRunId = RUN,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = MANIFEST,
		sourcePolicyRevision = POLICY,
		captureConsentEpoch = CONSENT,
		collectedDataEpoch = EPOCH,
		scopeDeletionGeneration = 0L,
		effectChecksum = "pending",
		appliedAtMs = 10_000L,
	)
	return unsigned.copy(
		effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
	)
}

private fun stepsOwners(
	wal: SourceEventWalEntity,
	fact: StepFactRevisionEntity,
	token: StepsCounterDomainToken,
): Pair<StepsCountDomainStoredOwner, StepsCountDomainStoredOwner> {
	val walOwner = stepsWalOwner(wal, token)
	val walReceipt = requireNotNull(walOwner.receipt)
	val scopeIdentity = walOwner.owner.scopeIdentity
	val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
		fact.writerProjectionId,
		fact.writerProjectionVersion,
		fact.logicalFactId,
	)
	val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
		domainIdentity = walReceipt.domainIdentity,
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = fact.semanticRevision,
		registrationGeneration = walReceipt.registrationGeneration,
		collectedDataEpoch = walReceipt.collectedDataEpoch,
		authorityRevision = walReceipt.authorityRevision,
		authorityFingerprint = walReceipt.authorityFingerprint,
		coverageKind = StepsCountDomainReceiptEntity.COVERAGE_COVERED,
		coverageVersion = fact.writerProjectionVersion,
		countDomainVersion = walReceipt.countDomainVersion,
		effectChecksum = fact.effectChecksum,
		completionEvidenceChecksum = null,
	)
	val receipt = StepsCountDomainReceiptEntity(
		receiptIdentity = receiptIdentity,
		domainIdentity = walReceipt.domainIdentity,
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = fact.semanticRevision,
		registrationGeneration = walReceipt.registrationGeneration,
		collectedDataEpoch = walReceipt.collectedDataEpoch,
		authorityRevision = walReceipt.authorityRevision,
		authorityFingerprint = walReceipt.authorityFingerprint,
		coverageKind = StepsCountDomainReceiptEntity.COVERAGE_COVERED,
		coverageVersion = fact.writerProjectionVersion,
		countDomainVersion = walReceipt.countDomainVersion,
		effectChecksum = fact.effectChecksum,
		completionEvidenceChecksum = null,
	)
	return walOwner to StepsCountDomainStoredOwner(
		owner = StepsCountDomainOwnerRevisionEntity(
			ownerKind = receipt.ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
			receiptIdentity = receipt.receiptIdentity,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		),
		receipt = receipt,
	)
}

private fun stepsWalOwner(
	wal: SourceEventWalEntity,
	token: StepsCounterDomainToken,
): StepsCountDomainStoredOwner {
	val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(LOGICAL, RUN)
	val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
		wal.admissionOrdinal,
		wal.eventId,
	)
	val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
		domainIdentity = StepsCountDomainReceiptIntegrity.counterDomainIdentity(token),
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = 1L,
		registrationGeneration = wal.registrationGeneration,
		collectedDataEpoch = wal.capturedCollectedDataEpoch,
		authorityRevision = requireNotNull(wal.authorizationRevision),
		authorityFingerprint = requireNotNull(wal.authorizationFingerprint),
		coverageKind = StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW,
		coverageVersion = wal.payloadVersion,
		countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
		effectChecksum = wal.integrityIdentity,
		completionEvidenceChecksum = null,
	)
	val receipt = StepsCountDomainReceiptEntity(
		receiptIdentity = receiptIdentity,
		domainIdentity = StepsCountDomainReceiptIntegrity.counterDomainIdentity(token),
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = 1L,
		registrationGeneration = wal.registrationGeneration,
		collectedDataEpoch = wal.capturedCollectedDataEpoch,
		authorityRevision = requireNotNull(wal.authorizationRevision),
		authorityFingerprint = requireNotNull(wal.authorizationFingerprint),
		coverageKind = StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW,
		coverageVersion = wal.payloadVersion,
		countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
		effectChecksum = wal.integrityIdentity,
		completionEvidenceChecksum = null,
	)
	return StepsCountDomainStoredOwner(
		owner = StepsCountDomainOwnerRevisionEntity(
			ownerKind = receipt.ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
			receiptIdentity = receipt.receiptIdentity,
			ownerEffectChecksum = wal.integrityIdentity,
			linkedAtMs = wal.createdAtMs,
		),
		receipt = receipt,
	)
}

@Suppress("LongParameterList")
private fun signedWal(
	sourceKind: Int,
	eventId: String,
	payloadVersion: Int,
	payload: ByteArray,
	sourceSequence: Long,
	observedStart: Long,
	observedEnd: Long,
	providerDedupKey: String?,
	deliveryIdentity: String?,
	deliveryUnitIndex: Int?,
	deliveryUnitCount: Int?,
): SourceEventWalEntity {
	val unsigned = SourceEventWalEntity(
		admissionOrdinal = 1L,
		eventId = eventId,
		providerDedupKey = providerDedupKey,
		deliveryIdentity = deliveryIdentity,
		deliveryUnitIndex = deliveryUnitIndex,
		deliveryUnitCount = deliveryUnitCount,
		logicalTrackingId = LOGICAL,
		serviceRunId = RUN,
		sourceKind = sourceKind,
		sourceInstanceId = INSTANCE,
		registrationGeneration = REGISTRATION,
		physicalConfigurationFingerprint = FINGERPRINT,
		authorizationRevision = AUTHORIZATION,
		authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
		sourceSequence = sourceSequence,
		configRevision = CONFIGURATION,
		planAttribution = 0,
		clockDomainId = BOOT,
		observedElapsedNanos = observedEnd,
		observedIntervalStartNanos = observedStart,
		receivedElapsedNanos = observedEnd + 1L,
		receivedWallTimeMs = 10_001L,
		wallTimeMs = 10_000L,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = EPOCH,
		activityAutomationEpoch = null,
		sourcePolicyRevision = POLICY,
		captureConsentEpoch = CONSENT,
		sessionManifestRevision = MANIFEST,
		lifecycleLeaseGeneration = LEASE,
		acquiredAtMs = 10_000L,
		qualityFlags = 0L,
		qualityConfidence = null,
		payloadVersion = payloadVersion,
		payload = payload,
		payloadChecksum = "",
		integrityIdentity = "pending",
		createdAtMs = 10_001L,
	)
	val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
	return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
}

private fun SourceEventWalEntity.resigned(
	admissionOrdinal: Long = this.admissionOrdinal,
	payloadVersion: Int = this.payloadVersion,
	authorizationPurposeEligibilityMask: Long = this.authorizationPurposeEligibilityMask,
	logicalTrackingId: String? = this.logicalTrackingId,
	serviceRunId: String? = this.serviceRunId,
	sourcePolicyRevision: Long? = this.sourcePolicyRevision,
	captureConsentEpoch: Long? = this.captureConsentEpoch,
	sessionManifestRevision: Long? = this.sessionManifestRevision,
	lifecycleLeaseGeneration: Long? = this.lifecycleLeaseGeneration,
	observedElapsedNanos: Long = this.observedElapsedNanos,
	sourceSequence: Long = this.sourceSequence,
): SourceEventWalEntity {
	val unsigned = copy(
		admissionOrdinal = admissionOrdinal,
		payloadVersion = payloadVersion,
		authorizationPurposeEligibilityMask = authorizationPurposeEligibilityMask,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		sessionManifestRevision = sessionManifestRevision,
		lifecycleLeaseGeneration = lifecycleLeaseGeneration,
		observedElapsedNanos = observedElapsedNanos,
		sourceSequence = sourceSequence,
		integrityIdentity = "pending",
	)
	return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
}

private fun pressureDeliveryIdentity(payload: PressureWalPayload): String {
	val canonical = buildString {
		append("pressure-window-v1|")
		append(BOOT.length)
		append(':')
		append(BOOT)
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
		.joinToString("") { "%02x".format(it) }
}

private const val LOGICAL = "logical"
private const val RUN = "run"
private const val INSTANCE = "provider"
private const val BOOT = "boot"
private const val REGISTRATION = 1L
private const val CONFIGURATION = 1L
private const val AUTHORIZATION = 1L
private const val POLICY = 1L
private const val CONSENT = 1L
private const val MANIFEST = 1L
private const val LEASE = 1L
private const val EPOCH = 1L
private const val PRESSURE_EVENT = "pressure-event"
private const val ACTIVITY_EVENT = "activity-event"
private const val STEPS_EVENT = "steps-event"
private val FINGERPRINT = "f".repeat(64)
private val AUTHORIZATION_FINGERPRINT = "a".repeat(64)
