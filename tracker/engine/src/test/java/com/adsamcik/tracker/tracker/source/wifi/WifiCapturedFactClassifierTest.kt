package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.security.MessageDigest

class WifiCapturedFactClassifierTest {
	@Test
	fun `fresh callback is identity free and bound to WAL plan registration and clock`() {
		val authority = authority()
		val input = input(
			authority = authority,
			accessPoints = listOf(
				accessPoint(2_412, -48, 1_000_000_000L),
				accessPoint(5_180, -62, 1_010_000_000L),
				accessPoint(5_955, -70, 1_020_000_000L),
			),
		)

		val fact = (classify(input, authority) as WifiCapturedFactClassification.FreshChanged).fact
		val wal = requireNotNull(input.walEvidence)
		fact.authority.capturedSources shouldBe setOf(SourceKind.WIFI)
		fact.authority.controlSources shouldBe emptySet()
		fact.aggregate shouldBe WifiIdentityFreeAggregate(
			observationCount = 3,
			bandMix = WifiBandMix(1, 1, 1, 0),
			signalQuality = WifiSignalQualitySummary(3, -48, -70, -180L),
		)
		fact.mutation.identity.sourceEventId shouldBe wal.sourceEventId
		fact.mutation.identity.payloadChecksum shouldBe wal.payloadChecksum
		fact.evidenceBinding.acquisitionPlanChecksum shouldBe
			authority.serializedAcquisitionPlan.payloadChecksum
		fact.evidenceBinding.observedElapsedRealtimeNanos shouldBe 1_020_000_000L
		fact.observedWallTimeMs shouldBe DEFAULT_WALL_TIME_MS
		fact.wallTimeUncertaintyMs shouldBe DEFAULT_UNCERTAINTY_MS
	}

	@Test
	fun `unchanged state reuses only a direct aggregate owner`() {
		val authority = authority()
		val original = freshFact(authority)
		val owner = original.toReusableFact() as WifiReusableFact.DirectAggregateOwner
		val unchanged = classify(
			input(
				authority = authority,
				delivery = 'b',
				accessPoints = listOf(accessPoint(2_412, -50, 1_030_000_000L)),
			),
			authority,
			priorFact = owner,
		) as WifiCapturedFactClassification.FreshUnchanged

		unchanged.fact.reusesAggregate shouldBe owner
		val coverageOnly = unchanged.fact.toReusableFact()
		val next = classify(
			input(
				authority = authority,
				delivery = 'c',
				accessPoints = listOf(accessPoint(2_412, -50, 1_040_000_000L)),
			),
			authority,
			priorFact = coverageOnly,
		)
		next::class shouldBe WifiCapturedFactClassification.FreshChanged::class
	}

	@Test
	fun `confirmed empty requires exact provider proof bound to WAL`() {
		val authority = authority()
		val noProof = input(authority, accessPoints = emptyList())
		classify(noProof, authority) shouldBe WifiCapturedFactClassification.ClockUnverifiable

		val provedInput = input(
			authority = authority,
			accessPoints = emptyList(),
			confirmedEmptyProviderTimeNanos = 1_000_000_000L,
		)
		val result = classify(provedInput, authority)
		val fact = (result as WifiCapturedFactClassification.FreshConfirmedEmpty).fact
		fact.availability shouldBe WifiAvailability.CONFIRMED_EMPTY
		fact.coverage.submittedResultCount shouldBe 0
		fact.coverage.completeness shouldBe WifiCoverageCompleteness.COMPLETE

		val foreignWal = input(
			authority = authority,
			delivery = 'b',
			accessPoints = emptyList(),
			confirmedEmptyProviderTimeNanos = 1_000_000_000L,
		).walEvidence!!
		classify(
			provedInput.copy(
				confirmedEmptyProof = WifiConfirmedEmptyProviderProof.fromProviderCallback(
					foreignWal,
					1_000_000_000L,
				),
			),
			authority,
		) shouldBe rejected(WifiFactRejection.CONFIRMED_EMPTY_PROOF_MISMATCH)
	}

	@Test
	fun `mixed callback retains fresh children and reports incomplete coverage`() {
		val authority = authority(maximumObservationAgeMs = 500L)
		val result = classify(
			input(
				authority = authority,
				receivedElapsedRealtimeNanos = 1_100_000_000L,
				accessPoints = listOf(
					accessPoint(2_412, -50, 1_000_000_000L),
					accessPoint(5_180, -60, 400_000_000L),
					accessPoint(5_955, -70, null),
					accessPoint(0, -55, 1_010_000_000L),
				),
			),
			authority,
		)

		val fact = (result as WifiCapturedFactClassification.FreshChanged).fact
		fact.aggregate.observationCount shouldBe 1
		fact.coverage shouldBe WifiCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos = 1_000_000_000L,
			providerIntervalEndElapsedRealtimeNanos = 1_000_000_000L,
			receivedElapsedRealtimeNanos = 1_100_000_000L,
			submittedResultCount = 4,
			acceptedResultCount = 1,
			staleResultCount = 1,
			clockUnverifiableResultCount = 1,
			malformedResultCount = 1,
			completeness = WifiCoverageCompleteness.PARTIAL,
		)
	}

	@Test
	fun `historical plan bounds count frequency and signal`() {
		val bounded = authority(maximumAccessPointCount = 1)
		classify(
			input(
				bounded,
				accessPoints = listOf(
					accessPoint(2_412, -50, 1_000_000_000L),
					accessPoint(5_180, -60, 1_010_000_000L),
				),
			),
			bounded,
		) shouldBe rejected(WifiFactRejection.TOO_MANY_ACCESS_POINTS)

		listOf(accessPoint(2_399, -50, 1_000_000_000L), accessPoint(2_412, -201, 1_000_000_000L))
			.forEach { malformed ->
				classify(
					input(bounded, accessPoints = listOf(malformed)),
					bounded,
				) shouldBe rejected(WifiFactRejection.MALFORMED_ACCESS_POINT)
			}
	}

	@Test
	fun `stale future missing and nonprovider observations do not become facts`() {
		val authority = authority(maximumObservationAgeMs = 500L)
		classify(
			input(
				authority,
				receivedElapsedRealtimeNanos = 2_000_000_000L,
				accessPoints = listOf(accessPoint(2_412, -50, 1_000_000_000L)),
			),
			authority,
		) shouldBe WifiCapturedFactClassification.Stale
		listOf(2_100_000_000L, null).forEach { providerTime ->
			classify(
				input(
					authority,
					receivedElapsedRealtimeNanos = 2_000_000_000L,
					accessPoints = listOf(accessPoint(2_412, -50, providerTime)),
				),
				authority,
			) shouldBe WifiCapturedFactClassification.ClockUnverifiable
		}
		classify(
			input(
				authority,
				origin = WifiObservationOrigin.CACHE_READ,
				accessPoints = listOf(accessPoint(2_412, -50, 1_000_000_000L)),
			),
			authority,
		) shouldBe WifiCapturedFactClassification.Absent
	}

	@Test
	fun `provider operational outcomes remain typed without manufacturing observations`() {
		val authority = authority()
		mapOf(
			WifiProviderOutcome.ABSENT to WifiCapturedFactClassification.Absent,
			WifiProviderOutcome.RESULTS_NOT_UPDATED to WifiCapturedFactClassification.Failed,
			WifiProviderOutcome.FAILED to WifiCapturedFactClassification.Failed,
			WifiProviderOutcome.PERMISSION_LIMITED to WifiCapturedFactClassification.PermissionLimited,
			WifiProviderOutcome.OS_THROTTLED to WifiCapturedFactClassification.OsThrottled,
		).forEach { (outcome, expected) ->
			classify(input(authority, outcome = outcome), authority) shouldBe expected
		}
	}

	@Test
	fun `identity bearing payload and missing delivery identity are rejected`() {
		val authority = authority()
		classify(
			input(
				authority,
				accessPoints = listOf(accessPoint(2_412, -50, 1_000_000_000L, "ssid-or-bssid")),
			),
			authority,
		) shouldBe rejected(WifiFactRejection.IDENTITY_BEARING_INPUT)
		val noDelivery = input(authority).let {
			it.copy(walEvidence = it.walEvidence!!.copy(sourceDeliveryIdentity = null))
		}
		classify(noDelivery, authority) shouldBe
			rejected(WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)
	}

	@Test
	fun `same delivery cannot replay changed raw AP evidence`() {
		val authority = authority()
		val original = freshFact(authority)
		val changedRaw = input(
			authority,
			accessPoints = listOf(accessPoint(5_180, -50, 1_000_000_000L)),
		)
		classify(
			changedRaw,
			authority,
			priorFact = original.toReusableFact(),
		) shouldBe rejected(WifiFactRejection.DELIVERY_IDENTITY_COLLISION)
		classify(
			changedRaw,
			authority,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			correctionBase = original.toReusableFact(),
		) shouldBe rejected(WifiFactRejection.RAW_EVIDENCE_CHANGED)
	}

	@Test
	fun `same delivery cannot correct immutable clock evidence`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = (
			classify(originalInput, authority) as WifiCapturedFactClassification.FreshChanged
			).fact
		val wal = requireNotNull(originalInput.walEvidence)
		val changedClock = originalInput.copy(
			walEvidence = wal.copy(
				clock = wal.clock.copy(wallTimeUncertaintyMs = DEFAULT_UNCERTAINTY_MS + 1L),
			),
		)

		classify(
			changedClock,
			authority,
			priorFact = original.toReusableFact(),
		) shouldBe rejected(WifiFactRejection.DELIVERY_IDENTITY_COLLISION)
		classify(
			changedClock,
			authority,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			correctionBase = original.toReusableFact(),
		) shouldBe rejected(WifiFactRejection.RAW_EVIDENCE_CHANGED)
	}

	@Test
	fun `exact replay and product no-op correction do not create revisions`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = (
			classify(originalInput, authority) as WifiCapturedFactClassification.FreshChanged
			).fact
		classify(
			originalInput,
			authority,
			priorFact = original.toReusableFact(),
		) shouldBe WifiCapturedFactClassification.Absent
		classify(
			originalInput,
			authority,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			correctionBase = original.toReusableFact(),
		) shouldBe WifiCapturedFactClassification.Absent
	}

	@Test
	fun `captured authority is immutable and current deletion epoch is separate`() {
		val authority = authority()
		val original = input(authority)
		val wal = requireNotNull(original.walEvidence)
		val mismatches = listOf(
			wal.copy(sourceAdmissionOrdinal = 0L),
			wal.copy(logicalTrackingId = LogicalTrackingId("other-tracking")),
			wal.copy(serviceRunId = ServiceRunId("other-run")),
			wal.copy(sourceInstanceId = SourceInstanceId("other-instance")),
			wal.copy(registrationGeneration = 10L),
			wal.copy(configurationRevision = 6L),
			wal.copy(physicalConfigurationFingerprint = "other-physical"),
			wal.copy(authorizationRevision = 4L),
			wal.copy(authorizationFingerprint = "other-authorization"),
			wal.copy(purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART),
			wal.copy(sourcePolicyRevision = 12L),
			wal.copy(captureConsentEpoch = 8L),
			wal.copy(sessionManifestRevision = 18L),
			wal.copy(lifecycleLeaseGeneration = 20L),
			wal.copy(capturedCollectedDataEpoch = 24L),
		)
		classify(
			original.copy(walEvidence = mismatches.first()),
			authority,
		) shouldBe rejected(WifiFactRejection.WAL_PROVENANCE_UNVERIFIABLE)
		mismatches.drop(1).forEach { wal ->
			classify(original.copy(walEvidence = wal), authority) shouldBe
				rejected(WifiFactRejection.CAPTURE_AUTHORITY_MISMATCH)
		}
		classify(
			original,
			authority,
			deletionAuthority = WifiDeletionAuthority(24L, null),
		) shouldBe rejected(WifiFactRejection.DELETION_AUTHORITY_MISMATCH)
	}

	@Test
	fun `serialized acquisition plan and applied registration are exact historical evidence`() {
		val authority = authority()
		val corruptPlan = authority.copy(
			serializedAcquisitionPlan = authority.serializedAcquisitionPlan.copy(
				payloadChecksum = "f".repeat(64),
			),
		)
		classify(
			input(corruptPlan),
			corruptPlan,
		) shouldBe rejected(WifiFactRejection.ACQUISITION_PLAN_UNVERIFIABLE)

		val mismatchedAge = authority.copy(
			acquisitionConfiguration = authority.acquisitionConfiguration.copy(
				maximumObservationAgeNanos = 999L,
			),
		)
		classify(input(mismatchedAge), mismatchedAge) shouldBe
			rejected(WifiFactRejection.ACQUISITION_PLAN_UNVERIFIABLE)
		val mismatchedFingerprint = authority.copy(
			physicalConfigurationFingerprint = "other-physical",
		)
		classify(input(mismatchedFingerprint), mismatchedFingerprint) shouldBe
			rejected(WifiFactRejection.ACQUISITION_PLAN_UNVERIFIABLE)

		val mismatchedRegistration = authority.copy(
			appliedRegistration = authority.appliedRegistration.copy(appliedRevision = 4L),
		)
		classify(input(mismatchedRegistration), mismatchedRegistration) shouldBe
			rejected(WifiFactRejection.APPLIED_REGISTRATION_MISMATCH)
	}

	@Test
	fun `payload checksum and WAL integrity are independently rejected`() {
		val authority = authority()
		val original = input(authority)
		val wal = requireNotNull(original.walEvidence)
		classify(
			original.copy(
				walEvidence = wal.copy(payloadChecksum = "f".repeat(64)),
			),
			authority,
		) shouldBe rejected(WifiFactRejection.PAYLOAD_INTEGRITY_UNVERIFIABLE)
		classify(
			original.copy(
				walEvidence = wal.copy(walIntegrityIdentity = "not-a-digest"),
			),
			authority,
		) shouldBe rejected(WifiFactRejection.WAL_INTEGRITY_UNVERIFIABLE)
	}

	@Test
	fun `wall time is derived with uncertainty and full interval obeys retention`() {
		val authority = authority()
		val input = input(
			authority = authority,
			observedWallTimeMs = 100L,
			wallTimeUncertaintyMs = 5L,
			accessPoints = listOf(
				accessPoint(2_412, -50, 999_500_000L),
				accessPoint(0, -60, 1_000_000_000L),
			),
		)
		val accepted = classify(
			input,
			authority,
			deletionAuthority = WifiDeletionAuthority(CAPTURED_EPOCH, 94L),
		) as WifiCapturedFactClassification.FreshChanged
		accepted.fact.observedWallTimeMs shouldBe 100L
		accepted.fact.wallTimeUncertaintyMs shouldBe 6L
		classify(
			input,
			authority,
			deletionAuthority = WifiDeletionAuthority(CAPTURED_EPOCH, 95L),
		) shouldBe WifiCapturedFactClassification.Stale
	}

	@Test
	fun `clock timeline and zone authority must be verifiable`() {
		val authority = authority()
		val original = input(authority)
		val wal = requireNotNull(original.walEvidence)
		val wrongTimeline = original.copy(
			walEvidence = wal.copy(
				clock = wal.clock.copy(
					observedElapsedRealtimeNanos = 1_100_000_000L,
				),
			),
		)
		classify(wrongTimeline, authority) shouldBe WifiCapturedFactClassification.ClockUnverifiable
		shouldThrow<IllegalArgumentException> { authority(zoneId = "Not/A_Zone") }
	}

	private fun classify(
		input: WifiObservationInput,
		authority: WifiCaptureAuthority,
		deletionAuthority: WifiDeletionAuthority = WifiDeletionAuthority(CAPTURED_EPOCH, null),
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		priorFact: WifiReusableFact? = null,
		correctionBase: WifiReusableFact? = null,
	): WifiCapturedFactClassification = WifiCapturedFactClassifier.classify(
		input = input,
		expectedAuthority = authority,
		currentDeletionAuthority = deletionAuthority,
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = supersedesSemanticRevision,
		priorFact = priorFact,
		correctionBase = correctionBase,
	)

	private fun freshFact(authority: WifiCaptureAuthority): WifiCapturedFact.Aggregate =
		(
			classify(input(authority), authority) as WifiCapturedFactClassification.FreshChanged
			).fact

	private fun authority(
		maximumObservationAgeMs: Long = 500L,
		maximumAccessPointCount: Int = 64,
		zoneId: String = "Europe/Prague",
	): WifiCaptureAuthority {
		val plan = WifiPlan(
			revision = 5L,
			mode = WifiMode.BROADCAST_DRIVEN,
			minimumAttemptIntervalMs = 300_000L,
			maximumAcceptableResultAgeMs = maximumObservationAgeMs,
			unchangedResultDedupeWindowMs = 600_000L,
			backoff = RetryBackoff(1_000L, 60_000L),
		)
		val encodedPlan = SourcePlanCodec().encode(plan)
		val fingerprint = plan.physicalConfigurationFingerprint()
		return WifiCaptureAuthority(
			logicalTrackingId = LogicalTrackingId("tracking-1"),
			serviceRunId = ServiceRunId("run-1"),
			sessionSegmentId = 41L,
			capturedSources = setOf(SourceKind.WIFI),
			controlSources = emptySet(),
			sourceInstanceId = SourceInstanceId("wifi-instance-1"),
			registrationGeneration = 9L,
			configurationRevision = plan.revision,
			physicalConfigurationFingerprint = fingerprint,
			authorizationRevision = 3L,
			authorizationFingerprint = "wifi-authorization-1",
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = 11L,
			captureConsentEpoch = 7L,
			sessionManifestRevision = 17L,
			lifecycleLeaseGeneration = 19L,
			collectedDataEpoch = CAPTURED_EPOCH,
			clockDomainId = CLOCK_DOMAIN,
			zoneId = zoneId,
			temporalAuthority = WifiCaptureTemporalAuthority(
				providerAcceptance = WifiProviderTimeInterval(1L, 10_000_000_000L),
				authorizationEffect = WifiProviderTimeInterval(1L, 10_000_000_000L),
				sessionRunEffect = WifiProviderTimeInterval(1L, 10_000_000_000L),
			),
			acquisitionConfiguration = WifiHistoricalAcquisitionConfiguration(
				maximumObservationAgeNanos = Math.multiplyExact(
					maximumObservationAgeMs,
					NANOS_PER_MILLISECOND,
				),
				maximumAccessPointCount = maximumAccessPointCount,
				minimumFrequencyMhz = 2_400,
				maximumFrequencyMhz = 7_125,
				minimumSignalLevelDbm = -200,
				maximumSignalLevelDbm = 0,
			),
			serializedAcquisitionPlan = WifiSerializedAcquisitionPlanEvidence(
				payloadVersion = 1,
				payloadBytes = encodedPlan.bytes.toList(),
				payloadChecksum = encodedPlan.checksum,
			),
			appliedRegistration = WifiAppliedRegistrationEvidence(
				desiredRevision = plan.revision,
				appliedRevision = plan.revision,
				sourceInstanceId = SourceInstanceId("wifi-instance-1"),
				registrationGeneration = 9L,
				appliedAtElapsedRealtimeNanos = 0L,
				physicalConfigurationFingerprint = fingerprint,
				clockDomainId = CLOCK_DOMAIN,
				capturedCollectedDataEpoch = CAPTURED_EPOCH,
			),
		)
	}

	private fun input(
		authority: WifiCaptureAuthority,
		delivery: Char = 'a',
		origin: WifiObservationOrigin = WifiObservationOrigin.PROVIDER_RESULTS_CALLBACK,
		outcome: WifiProviderOutcome = WifiProviderOutcome.RESULTS_UPDATED,
		accessPoints: List<WifiAccessPointEvidence> = listOf(
			accessPoint(2_412, -50, 1_000_000_000L),
		),
		receivedElapsedRealtimeNanos: Long = 1_100_000_000L,
		observedWallTimeMs: Long = DEFAULT_WALL_TIME_MS,
		wallTimeUncertaintyMs: Long = DEFAULT_UNCERTAINTY_MS,
		confirmedEmptyProviderTimeNanos: Long? = null,
	): WifiObservationInput {
		if (outcome != WifiProviderOutcome.RESULTS_UPDATED) {
			return WifiObservationInput(origin, outcome, null, null)
		}
		val payload = WifiResultSnapshotPayload(accessPoints, null, null)
		val encoded = DefaultSourcePayloadCodec().encode(payload, 2)
		val providerTimes = accessPoints.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos)
		val observed = providerTimes.maxOrNull() ?: confirmedEmptyProviderTimeNanos ?: 1_000_000_000L
		val observedStart = providerTimes.minOrNull() ?: observed
		val deliveryIdentity = identity(delivery)
		val wal = WifiWalObservationEvidence(
			sourceEventId = SourceEventId("wifi-event-$delivery"),
			sourceAdmissionOrdinal = 7L + (delivery - 'a').toLong(),
			walIntegrityIdentity = sha256("wifi-wal-$delivery-${encoded.checksum}"),
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			capturedAuthority = authority,
			logicalTrackingId = authority.logicalTrackingId,
			serviceRunId = authority.serviceRunId,
			sourceInstanceId = authority.sourceInstanceId,
			registrationGeneration = authority.registrationGeneration,
			configurationRevision = authority.configurationRevision,
			physicalConfigurationFingerprint = authority.physicalConfigurationFingerprint,
			authorizationRevision = authority.authorizationRevision,
			authorizationFingerprint = authority.authorizationFingerprint,
			purposeEligibilityMask = authority.purposeEligibilityMask,
			sourcePolicyRevision = authority.sourcePolicyRevision,
			captureConsentEpoch = authority.captureConsentEpoch,
			sessionManifestRevision = authority.sessionManifestRevision,
			lifecycleLeaseGeneration = authority.lifecycleLeaseGeneration,
			capturedCollectedDataEpoch = authority.collectedDataEpoch,
			clock = WifiDurableClockEvidence(
				clockDomainId = authority.clockDomainId,
				observedIntervalStartElapsedRealtimeNanos = observedStart,
				observedElapsedRealtimeNanos = observed,
				receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
				observedWallTimeMs = observedWallTimeMs,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			),
			payloadVersion = 2,
			payloadBytes = encoded.bytes.toList(),
			payloadChecksum = encoded.checksum,
		)
		return WifiObservationInput(
			origin = origin,
			outcome = outcome,
			walEvidence = wal,
			confirmedEmptyProof = confirmedEmptyProviderTimeNanos?.let {
				WifiConfirmedEmptyProviderProof.fromProviderCallback(wal, it)
			},
		)
	}

	private fun accessPoint(
		frequencyMhz: Int,
		signalLevelDbm: Int,
		providerTimestampNanos: Long?,
		identifierToken: String = "",
	) = WifiAccessPointEvidence(identifierToken, frequencyMhz, signalLevelDbm, providerTimestampNanos)

	private fun identity(character: Char) = SourceDeliveryIdentity(character.toString().repeat(64))

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(Charsets.UTF_8))
		.joinToString(separator = "") { byte -> "%02x".format(byte) }

	private fun rejected(reason: WifiFactRejection) = WifiCapturedFactClassification.Rejected(reason)

	private companion object {
		const val CAPTURED_EPOCH = 23L
		const val CLOCK_DOMAIN = "boot-1"
		const val DEFAULT_WALL_TIME_MS = 2_000L
		const val DEFAULT_UNCERTAINTY_MS = 5L
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
