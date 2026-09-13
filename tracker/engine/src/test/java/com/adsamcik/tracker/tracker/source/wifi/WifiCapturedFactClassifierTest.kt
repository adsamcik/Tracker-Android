package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class WifiCapturedFactClassifierTest {
	@Test
	fun `fresh callback creates an identity-free aggregate with exact authority`() {
		val authority = authority(capturedSources = setOf(SourceKind.WIFI))
		val result = WifiCapturedFactClassifier.classify(
			input(
				accessPoints = listOf(
					accessPoint(2_412, -48, 1_000L),
					accessPoint(5_180, -62, 1_010L),
					accessPoint(5_955, -70, 1_020L),
				),
			),
			authority,
		)

		val fact = (result as WifiCapturedFactClassification.FreshChanged).fact
		fact.authority shouldBe authority
		fact.availability shouldBe WifiAvailability.AVAILABLE
		fact.aggregate shouldBe WifiIdentityFreeAggregate(
			observationCount = 3,
			bandMix = WifiBandMix(
				twoPointFourGhzCount = 1,
				fiveGhzCount = 1,
				sixGhzCount = 1,
				otherCount = 0,
			),
			signalQuality = WifiSignalQualitySummary(
				observationCount = 3,
				strongestSignalLevelDbm = -48,
				weakestSignalLevelDbm = -70,
				signalLevelSumDbm = -180L,
			),
		)
		fact.coverage shouldBe WifiCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos = 1_000L,
			providerIntervalEndElapsedRealtimeNanos = 1_020L,
			submittedResultCount = 3,
			acceptedResultCount = 3,
			staleResultCount = 0,
			clockUnverifiableResultCount = 0,
			completeness = WifiCoverageCompleteness.COMPLETE,
		)
		fact.mutation.identity shouldBe WifiCapturedFactIdentity(
			sourceDeliveryIdentity = identity('a'),
			logicalTrackingId = LogicalTrackingId("tracking-1"),
			serviceRunId = ServiceRunId("run-1"),
			sessionSegmentId = 41L,
			sessionManifestRevision = 17L,
			collectedDataEpoch = 23L,
		)
	}

	@Test
	fun `source-only manifest is sufficient and never requires Location`() {
		val result = WifiCapturedFactClassifier.classify(
			input(accessPoints = listOf(accessPoint(2_412, -50, 1_000L))),
			authority(
				capturedSources = setOf(SourceKind.WIFI),
				controlSources = emptySet(),
			),
		)

		val fact = (result as WifiCapturedFactClassification.FreshChanged).fact
		fact.authority.capturedSources shouldBe setOf(SourceKind.WIFI)
		fact.authority.controlSources shouldBe emptySet()
	}

	@Test
	fun `fresh callback with the same aggregate stores coverage and reuses one prior fact`() {
		val authority = authority()
		val firstResult = WifiCapturedFactClassifier.classify(
			input(accessPoints = listOf(accessPoint(2_412, -50, 1_000L))),
			authority,
		) as WifiCapturedFactClassification.FreshChanged
		val prior = firstResult.fact.reusable()

		val result = WifiCapturedFactClassifier.classify(
			input(
				deliveryIdentity = identity('b'),
				accessPoints = listOf(accessPoint(2_412, -50, 1_100L)),
				receivedElapsedRealtimeNanos = 1_150L,
			),
			authority,
			priorFact = prior,
		)

		val fact = (result as WifiCapturedFactClassification.FreshUnchanged).fact
		fact.reusesAggregate shouldBe prior.reference
		fact.coverage.providerIntervalStartElapsedRealtimeNanos shouldBe 1_100L
		fact.mutation.identity.sourceDeliveryIdentity shouldBe identity('b')
	}

	@Test
	fun `same aggregate cannot reuse content across a changed historical authority`() {
		val priorAuthority = authority()
		val prior = (
			WifiCapturedFactClassifier.classify(
				input(accessPoints = listOf(accessPoint(2_412, -50, 1_000L))),
				priorAuthority,
			) as WifiCapturedFactClassification.FreshChanged
			).fact.reusable()
		val changedAuthority = priorAuthority.copy(
			authorizationRevision = priorAuthority.authorizationRevision + 1L,
			authorizationFingerprint = "wifi-authorization-2",
		)

		val result = WifiCapturedFactClassifier.classify(
			input(
				deliveryIdentity = identity('b'),
				accessPoints = listOf(accessPoint(2_412, -50, 1_100L)),
				receivedElapsedRealtimeNanos = 1_150L,
			),
			changedAuthority,
			priorFact = prior,
		)

		result::class shouldBe WifiCapturedFactClassification.FreshChanged::class
	}

	@Test
	fun `provider-confirmed fresh empty is a durable zero coverage fact`() {
		val result = WifiCapturedFactClassifier.classify(
			input(
				accessPoints = emptyList(),
				providerObservationElapsedRealtimeNanos = 1_000L,
			),
			authority(),
		)

		val fact = (result as WifiCapturedFactClassification.FreshConfirmedEmpty).fact
		fact.availability shouldBe WifiAvailability.CONFIRMED_EMPTY
		fact.reusesAggregate shouldBe null
		fact.coverage.acceptedResultCount shouldBe 0
		fact.coverage.submittedResultCount shouldBe 0
		fact.coverage.completeness shouldBe WifiCoverageCompleteness.COMPLETE
	}

	@Test
	fun `empty result without source-native observation time is clock unverifiable`() {
		val result = WifiCapturedFactClassifier.classify(
			input(
				accessPoints = emptyList(),
				providerObservationElapsedRealtimeNanos = null,
			),
			authority(),
		)

		result shouldBe WifiCapturedFactClassification.ClockUnverifiable
	}

	@Test
	fun `mixed-age callback retains only fresh aggregate evidence and marks partial coverage`() {
		val result = WifiCapturedFactClassifier.classify(
			input(
				accessPoints = listOf(
					accessPoint(2_412, -50, 1_000L),
					accessPoint(5_180, -60, 400L),
					accessPoint(5_955, -70, null),
				),
			),
			authority(maximumObservationAgeNanos = 500L),
		)

		val fact = (result as WifiCapturedFactClassification.FreshChanged).fact
		fact.aggregate.observationCount shouldBe 1
		fact.aggregate.bandMix shouldBe WifiBandMix(1, 0, 0, 0)
		fact.coverage shouldBe WifiCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos = 1_000L,
			providerIntervalEndElapsedRealtimeNanos = 1_000L,
			submittedResultCount = 3,
			acceptedResultCount = 1,
			staleResultCount = 1,
			clockUnverifiableResultCount = 1,
			completeness = WifiCoverageCompleteness.PARTIAL,
		)
	}

	@Test
	fun `all stale provider items have no product or coverage fact`() {
		val result = WifiCapturedFactClassifier.classify(
			input(
				accessPoints = listOf(
					accessPoint(2_412, -50, 400L),
					accessPoint(5_180, -60, 499L),
				),
			),
			authority(maximumObservationAgeNanos = 500L),
		)

		result shouldBe WifiCapturedFactClassification.Stale
	}

	@Test
	fun `pre-effective and end-boundary observations are stale`() {
		val authority = authority().copy(
			temporalAuthority = WifiCaptureTemporalAuthority(
				providerAcceptance = WifiProviderTimeInterval(900L, 1_100L),
				authorizationEffect = WifiProviderTimeInterval(950L, 1_050L),
				sessionRunEffect = WifiProviderTimeInterval(980L, 1_020L),
			),
		)

		listOf(979L, 1_020L).forEach { providerTime ->
			WifiCapturedFactClassifier.classify(
				input(accessPoints = listOf(accessPoint(2_412, -50, providerTime))),
				authority,
			) shouldBe WifiCapturedFactClassification.Stale
		}
	}

	@Test
	fun `missing and future provider clocks fail closed as clock unverifiable`() {
		listOf(
			accessPoint(2_412, -50, null),
			accessPoint(2_412, -50, 1_101L),
		).forEach { accessPoint ->
			WifiCapturedFactClassifier.classify(
				input(accessPoints = listOf(accessPoint)),
				authority(),
			) shouldBe WifiCapturedFactClassification.ClockUnverifiable
		}
	}

	@Test
	fun `wrong boot domain and missing wall authority are clock unverifiable`() {
		val authority = authority()
		WifiCapturedFactClassifier.classify(
			input(
				clockDomainId = "another-boot",
				accessPoints = listOf(accessPoint(2_412, -50, 1_000L)),
			),
			authority,
		) shouldBe WifiCapturedFactClassification.ClockUnverifiable
		WifiCapturedFactClassifier.classify(
			input(
				observedWallTimeMs = null,
				accessPoints = listOf(accessPoint(2_412, -50, 1_000L)),
			),
			authority,
		) shouldBe WifiCapturedFactClassification.ClockUnverifiable
	}

	@Test
	fun `cache timer and request attempt never create an observation`() {
		listOf(
			WifiObservationOrigin.CACHE_READ,
			WifiObservationOrigin.TIMER,
			WifiObservationOrigin.REQUEST_ATTEMPT,
		).forEach { origin ->
			WifiCapturedFactClassifier.classify(
				input(
					origin = origin,
					accessPoints = listOf(accessPoint(2_412, -50, 1_000L)),
				),
				authority(),
			) shouldBe WifiCapturedFactClassification.Absent
		}
	}

	@Test
	fun `provider operational outcomes remain typed and payload free`() {
		val expected = mapOf(
			WifiProviderOutcome.ABSENT to WifiCapturedFactClassification.Absent,
			WifiProviderOutcome.FAILED to WifiCapturedFactClassification.Failed,
			WifiProviderOutcome.RESULTS_NOT_UPDATED to WifiCapturedFactClassification.Failed,
			WifiProviderOutcome.PERMISSION_LIMITED to
				WifiCapturedFactClassification.PermissionLimited,
			WifiProviderOutcome.OS_THROTTLED to WifiCapturedFactClassification.OsThrottled,
		)

		expected.forEach { (outcome, classification) ->
			WifiCapturedFactClassifier.classify(
				input(
					outcome = outcome,
					deliveryIdentity = null,
					clockDomainId = null,
					observedWallTimeMs = null,
					wallTimeUncertaintyMs = null,
					accessPoints = emptyList(),
				),
				authority(),
			) shouldBe classification
		}
	}

	@Test
	fun `identity-bearing input is rejected before aggregate persistence`() {
		val result = WifiCapturedFactClassifier.classify(
			input(
				accessPoints = listOf(
					WifiAccessPointEvidence(
						identifierToken = "aa:bb:cc:dd:ee:ff",
						frequencyMhz = 2_412,
						signalLevelDbm = -50,
						providerTimestampNanos = 1_000L,
					),
				),
			),
			authority(),
		)

		result shouldBe WifiCapturedFactClassification.Rejected(
			WifiFactRejection.IDENTITY_BEARING_INPUT,
		)
	}

	@Test
	fun `missing stable provider delivery identity cannot create a fact`() {
		val result = WifiCapturedFactClassifier.classify(
			input(
				deliveryIdentity = null,
				accessPoints = listOf(accessPoint(2_412, -50, 1_000L)),
			),
			authority(),
		)

		result shouldBe WifiCapturedFactClassification.Rejected(
			WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `band mix uses explicit Android Wi-Fi frequency ranges and retains unknown bands`() {
		val result = WifiCapturedFactClassifier.classify(
			input(
				accessPoints = listOf(
					accessPoint(2_400, -50, 1_000L),
					accessPoint(2_500, -50, 1_000L),
					accessPoint(4_900, -50, 1_000L),
					accessPoint(5_900, -50, 1_000L),
					accessPoint(5_925, -50, 1_000L),
					accessPoint(7_125, -50, 1_000L),
					accessPoint(5_901, -50, 1_000L),
				),
			),
			authority(),
		)

		val aggregate = (result as WifiCapturedFactClassification.FreshChanged).fact.aggregate
		aggregate.bandMix shouldBe WifiBandMix(2, 2, 2, 1)
	}

	@Test
	fun `correction keeps stable fact identity and explicitly supersedes prior revision`() {
		val authority = authority()
		val initial = (
			WifiCapturedFactClassifier.classify(
				input(accessPoints = listOf(accessPoint(2_412, -50, 1_000L))),
				authority,
			) as WifiCapturedFactClassification.FreshChanged
			).fact
		val correctionBase = initial.reusable()

		val result = WifiCapturedFactClassifier.classify(
			input(accessPoints = listOf(accessPoint(2_412, -49, 1_000L))),
			authority,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			correctionBase = correctionBase,
		)

		val corrected = (result as WifiCapturedFactClassification.FreshChanged).fact
		corrected.mutation.identity shouldBe initial.mutation.identity
		corrected.mutation.semanticRevision shouldBe 2L
		corrected.mutation.supersedesSemanticRevision shouldBe 1L
	}

	@Test
	fun `correction requires the exact prior identity and revision`() {
		val result = WifiCapturedFactClassifier.classify(
			input(accessPoints = listOf(accessPoint(2_412, -49, 1_000L))),
			authority(),
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			correctionBase = null,
		)

		result shouldBe WifiCapturedFactClassification.Rejected(
			WifiFactRejection.INVALID_CORRECTION_BASE,
		)
	}

	@Test
	fun `semantic no-op correction has zero product and coverage effect`() {
		val authority = authority()
		val initial = (
			WifiCapturedFactClassifier.classify(
				input(accessPoints = listOf(accessPoint(2_412, -50, 1_000L))),
				authority,
			) as WifiCapturedFactClassification.FreshChanged
			).fact

		val result = WifiCapturedFactClassifier.classify(
			input(accessPoints = listOf(accessPoint(2_412, -50, 1_000L))),
			authority,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			correctionBase = initial.reusable(),
		)

		result shouldBe WifiCapturedFactClassification.Absent
	}

	@Test
	fun `exact provider replay has zero product and coverage effect`() {
		val authority = authority()
		val initial = (
			WifiCapturedFactClassifier.classify(
				input(accessPoints = listOf(accessPoint(2_412, -50, 1_000L))),
				authority,
			) as WifiCapturedFactClassification.FreshChanged
			).fact

		val replay = WifiCapturedFactClassifier.classify(
			input(accessPoints = listOf(accessPoint(2_412, -50, 1_000L))),
			authority,
			priorFact = initial.reusable(),
		)

		replay shouldBe WifiCapturedFactClassification.Absent
	}

	@Test
	fun `control-only or overlapping manifest source authority cannot construct a captured fact`() {
		shouldThrow<IllegalArgumentException> {
			authority(
				purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			)
		}
		shouldThrow<IllegalArgumentException> {
			authority(
				capturedSources = setOf(SourceKind.WIFI),
				controlSources = setOf(SourceKind.WIFI),
			)
		}
	}

	private fun WifiCapturedFact.Aggregate.reusable() = WifiReusableFact(
		reference = WifiAggregateFactReference(mutation.identity, mutation.semanticRevision),
		authority = authority,
		availability = availability,
		aggregate = aggregate,
	)

	private fun input(
		origin: WifiObservationOrigin = WifiObservationOrigin.PROVIDER_RESULTS_CALLBACK,
		outcome: WifiProviderOutcome = WifiProviderOutcome.RESULTS_UPDATED,
		deliveryIdentity: SourceDeliveryIdentity? = identity('a'),
		clockDomainId: String? = "boot-1",
		providerObservationElapsedRealtimeNanos: Long? = null,
		receivedElapsedRealtimeNanos: Long = 1_100L,
		observedWallTimeMs: Long? = 2_000L,
		wallTimeUncertaintyMs: Long? = 5L,
		accessPoints: List<WifiAccessPointEvidence>,
	) = WifiObservationInput(
		origin = origin,
		outcome = outcome,
		sourceDeliveryIdentity = deliveryIdentity,
		clockDomainId = clockDomainId,
		providerObservationElapsedRealtimeNanos = providerObservationElapsedRealtimeNanos,
		receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
		observedWallTimeMs = observedWallTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		accessPoints = accessPoints,
	)

	private fun accessPoint(
		frequencyMhz: Int,
		signalLevelDbm: Int,
		providerTimestampNanos: Long?,
	) = WifiAccessPointEvidence(
		identifierToken = "",
		frequencyMhz = frequencyMhz,
		signalLevelDbm = signalLevelDbm,
		providerTimestampNanos = providerTimestampNanos,
	)

	private fun authority(
		capturedSources: Set<SourceKind> = setOf(SourceKind.WIFI),
		controlSources: Set<SourceKind> = emptySet(),
		purposeEligibilityMask: Long = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		maximumObservationAgeNanos: Long = 500L,
	) = WifiCaptureAuthority(
		logicalTrackingId = LogicalTrackingId("tracking-1"),
		serviceRunId = ServiceRunId("run-1"),
		sessionSegmentId = 41L,
		capturedSources = capturedSources,
		controlSources = controlSources,
		sourceInstanceId = SourceInstanceId("wifi-provider"),
		registrationGeneration = 3L,
		configurationRevision = 5L,
		physicalConfigurationFingerprint = "wifi-physical",
		authorizationRevision = 7L,
		authorizationFingerprint = "wifi-authorization",
		purposeEligibilityMask = purposeEligibilityMask,
		sourcePolicyRevision = 11L,
		captureConsentEpoch = 13L,
		sessionManifestRevision = 17L,
		lifecycleLeaseGeneration = 19L,
		collectedDataEpoch = 23L,
		clockDomainId = "boot-1",
		zoneId = "Europe/Prague",
		temporalAuthority = WifiCaptureTemporalAuthority(
			providerAcceptance = WifiProviderTimeInterval(0L, 10_000L),
			authorizationEffect = WifiProviderTimeInterval(0L, 10_000L),
			sessionRunEffect = WifiProviderTimeInterval(0L, 10_000L),
		),
		maximumObservationAgeNanos = maximumObservationAgeNanos,
	)

	private fun identity(character: Char) = SourceDeliveryIdentity(character.toString().repeat(64))
}
