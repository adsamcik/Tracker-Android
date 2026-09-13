package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
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
import com.adsamcik.tracker.tracker.source.runtime.wifiProviderDeliveryIdentity
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
		wal.configurationRevision shouldBe null
		wal.sourceDeliveryIdentity shouldBe wifiProviderDeliveryIdentity(
			CLOCK_DOMAIN,
			listOf(
				accessPoint(2_412, -48, 1_000_000_000L),
				accessPoint(5_180, -62, 1_010_000_000L),
				accessPoint(5_955, -70, 1_020_000_000L),
			),
		)
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
	fun `direct aggregate owner proof binds exact owner identity evidence and authority`() {
		val authority = authority()
		val original = freshFact(authority)
		val owner = original.toReusableFact() as WifiReusableFact.DirectAggregateOwner

		owner.reference shouldBe WifiAggregateFactReference(
			original.mutation.identity,
			original.mutation.semanticRevision,
		)
		owner.productEffect.evidenceBinding shouldBe original.evidenceBinding
		shouldThrow<IllegalArgumentException> {
			WifiReusableFact.CoverageOnly(
				reference = owner.reference.copy(
					identity = owner.reference.identity.copy(
						sourceAdmissionOrdinal = owner.reference.identity.sourceAdmissionOrdinal + 1L,
					),
				),
				authority = authority,
				productEffect = owner.productEffect,
				directAggregateOwner = owner,
			)
		}
	}

	@Test
	fun `fact constructors bind accepted coverage to exact aggregate semantics`() {
		val authority = authority()
		val original = freshFact(authority)
		val owner = original.toReusableFact() as WifiReusableFact.DirectAggregateOwner
		shouldThrow<IllegalArgumentException> {
			original.copy(
				coverage = original.coverage.copy(
					acceptedResultCount = 0,
					staleResultCount = 1,
					completeness = WifiCoverageCompleteness.PARTIAL,
				),
			)
		}

		val unchanged = classify(
			input(
				authority = authority,
				delivery = 'b',
				accessPoints = listOf(accessPoint(2_412, -50, 1_030_000_000L)),
			),
			authority,
			priorFact = owner,
		) as WifiCapturedFactClassification.FreshUnchanged
		val fact = unchanged.fact
		shouldThrow<IllegalArgumentException> {
			WifiCapturedFact.CoverageOnly.fromExactAggregate(
				mutation = fact.mutation,
				authority = fact.authority,
				evidenceBinding = fact.evidenceBinding,
				observedWallTimeMs = fact.observedWallTimeMs,
				wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
				availability = fact.availability,
				coverage = fact.coverage,
				observedAggregate = requireNotNull(owner.productEffect.aggregate).copy(
					bandMix = WifiBandMix(0, 1, 0, 0),
				),
				reusesAggregate = owner,
			)
		}
		shouldThrow<IllegalArgumentException> {
			WifiCapturedFact.CoverageOnly.fromExactAggregate(
				mutation = fact.mutation,
				authority = fact.authority,
				evidenceBinding = fact.evidenceBinding,
				observedWallTimeMs = fact.observedWallTimeMs,
				wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
				availability = fact.availability,
				coverage = fact.coverage.copy(
					acceptedResultCount = 0,
					staleResultCount = 1,
					completeness = WifiCoverageCompleteness.PARTIAL,
				),
				observedAggregate = requireNotNull(owner.productEffect.aggregate),
				reusesAggregate = owner,
			)
		}
	}

	@Test
	fun `empty callback stays clock unverifiable until provider origin proof exists`() {
		val authority = authority()
		classify(
			input(authority, accessPoints = emptyList()),
			authority,
		) shouldBe WifiCapturedFactClassification.ClockUnverifiable
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
			submittedResultCount = 3,
			acceptedResultCount = 1,
			staleResultCount = 1,
			clockUnverifiableResultCount = 0,
			malformedResultCount = 1,
			completeness = WifiCoverageCompleteness.PARTIAL,
		)
	}

	@Test
	fun `immutable result contract bounds count frequency and signal`() {
		val bounded = authority()
		classify(
			input(
				bounded,
				accessPoints = List(65) { index ->
					accessPoint(2_412, -50, 1_000_000_000L + index)
				},
			),
			bounded,
		) shouldBe rejected(WifiFactRejection.PAYLOAD_TOO_LARGE)

		listOf(accessPoint(2_399, -50, 1_000_000_000L), accessPoint(2_412, -201, 1_000_000_000L))
			.forEach { malformed ->
				classify(
					input(bounded, accessPoints = listOf(malformed)),
					bounded,
				) shouldBe rejected(WifiFactRejection.MALFORMED_ACCESS_POINT)
			}
	}

	@Test
	fun `only captured registration plan attribution may produce facts`() {
		val authority = authority()
		PlanAttribution.entries.filterNot { it == PlanAttribution.CAPTURED_REGISTRATION }
			.forEach { attribution ->
				val original = input(authority)
				val wal = requireNotNull(original.walEvidence)
				val changed = wal.copy(planAttribution = attribution).withValidWalIntegrity()
				classify(
					original.copy(walEvidence = changed),
					authority,
				) shouldBe rejected(WifiFactRejection.PLAN_ATTRIBUTION_UNVERIFIABLE)
			}

		classify(input(authority), authority)::class shouldBe
			WifiCapturedFactClassification.FreshChanged::class
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
		classify(
			input(
				authority,
				receivedElapsedRealtimeNanos = 2_000_000_000L,
				accessPoints = listOf(accessPoint(2_412, -50, 2_100_000_000L)),
			),
			authority,
		) shouldBe WifiCapturedFactClassification.ClockUnverifiable
		classify(
			input(
				authority,
				receivedElapsedRealtimeNanos = 2_000_000_000L,
				accessPoints = listOf(accessPoint(2_412, -50, null)),
			),
			authority,
		) shouldBe rejected(WifiFactRejection.PROVIDER_DELIVERY_SHAPE_UNVERIFIABLE)
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
			it.copy(
				walEvidence = it.walEvidence!!.copy(sourceDeliveryIdentity = null).withValidWalIntegrity(),
			)
		}
		classify(noDelivery, authority) shouldBe
			rejected(WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)
		val legacyDedupIdentity = input(authority).let {
			it.copy(
				walEvidence = it.walEvidence!!.copy(providerDedupKey = "raw-radio-identity")
					.withValidWalIntegrity(),
			)
		}
		classify(legacyDedupIdentity, authority) shouldBe
			rejected(WifiFactRejection.WAL_PROVENANCE_UNVERIFIABLE)
	}

	@Test
	fun `same delivery cannot replay changed raw AP evidence`() {
		val authority = authority()
		val original = freshFact(authority)
		val changedRawInput = input(
			authority,
			accessPoints = listOf(accessPoint(5_180, -50, 1_000_000_000L)),
		)
		val changedWal = requireNotNull(changedRawInput.walEvidence).copy(
			sourceDeliveryIdentity = original.mutation.identity.sourceDeliveryIdentity,
		).withValidWalIntegrity()
		val changedRaw = changedRawInput.copy(walEvidence = changedWal)
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
			).withValidWalIntegrity(),
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
		val passiveWal = requireNotNull(input(authority).walEvidence)
		passiveWal.configurationRevision shouldBe null
		classify(input(authority), authority)::class shouldBe
			WifiCapturedFactClassification.FreshChanged::class
		val redundantMatchingHint = passiveWal.copy(
			configurationRevision = authority.configurationRevision,
		).withValidWalIntegrity()
		classify(
			input(authority).copy(walEvidence = redundantMatchingHint),
			authority,
		)::class shouldBe WifiCapturedFactClassification.FreshChanged::class

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
	fun `provider delivery identity and exact one-unit shape are independently authenticated`() {
		val authority = authority()
		val original = input(
			authority,
			accessPoints = listOf(
				accessPoint(2_412, -50, 1_000_000_000L),
				accessPoint(5_180, -60, 1_010_000_000L),
			),
		)
		val wal = requireNotNull(original.walEvidence)

		val forgedIdentity = wal.copy(sourceDeliveryIdentity = identity('f')).withValidWalIntegrity()
		classify(original.copy(walEvidence = forgedIdentity), authority) shouldBe
			rejected(WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)

		listOf(
			wal.copy(deliveryUnitCount = 2),
			wal.copy(deliveryUnitIndex = 1),
		).forEach { malformedUnit ->
			classify(
				original.copy(walEvidence = malformedUnit.withValidWalIntegrity()),
				authority,
			) shouldBe rejected(WifiFactRejection.WAL_PROVENANCE_UNVERIFIABLE)
		}

		val providerItems = listOf(
			accessPoint(2_412, -50, 1_000_000_000L),
			accessPoint(5_180, -60, 1_010_000_000L),
		)
		val unsorted = WifiResultSnapshotPayload(
			accessPoints = providerItems.reversed(),
			platformTimestampMs = 1_010L,
			resultAgeMs = null,
		)
		val wrongPlatformTime = WifiResultSnapshotPayload(
			accessPoints = providerItems,
			platformTimestampMs = 1_009L,
			resultAgeMs = null,
		)
		val receiptRelativeAge = WifiResultSnapshotPayload(
			accessPoints = providerItems,
			platformTimestampMs = 1_010L,
			resultAgeMs = 90L,
		)
		listOf(unsorted, wrongPlatformTime, receiptRelativeAge).forEach { malformedPayload ->
			classify(
				original.copy(
					walEvidence = wal.withPayload(authority, malformedPayload),
				),
				authority,
			) shouldBe rejected(WifiFactRejection.PROVIDER_DELIVERY_SHAPE_UNVERIFIABLE)
		}
	}

	@Test
	fun `v2 payload must be canonical EOF complete and source-size bounded before decode`() {
		val authority = authority()
		val original = input(authority)
		val wal = requireNotNull(original.walEvidence)
		val trailingBytes = wal.payloadBytes.toByteArray() + byteArrayOf(0)
		val withTrailingBytes = wal.copy(
			payloadBytes = trailingBytes.toList(),
			payloadChecksum = trailingBytes.sha256(),
		).withValidWalIntegrity()
		classify(original.copy(walEvidence = withTrailingBytes), authority) shouldBe
			rejected(WifiFactRejection.PAYLOAD_DECODE_FAILED)

		val oversizedBytes = ByteArray(2_000)
		val oversized = wal.copy(
			payloadBytes = oversizedBytes.toList(),
			payloadChecksum = oversizedBytes.sha256(),
		).withValidWalIntegrity()
		classify(original.copy(walEvidence = oversized), authority) shouldBe
			rejected(WifiFactRejection.PAYLOAD_TOO_LARGE)
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
	fun `WAL integrity binds sequence acquisition and quality evidence`() {
		val authority = authority()
		val original = input(authority)
		val wal = requireNotNull(original.walEvidence)
		listOf(
			wal.copy(sourceSequence = wal.sourceSequence + 1L),
			wal.copy(acquiredAtMs = wal.acquiredAtMs + 1L),
			wal.copy(qualityFlags = 1L),
			wal.copy(qualityConfidence = 0.8f),
		).forEach { changedWal ->
			classify(
				original.copy(walEvidence = changedWal),
				authority,
			) shouldBe rejected(WifiFactRejection.WAL_INTEGRITY_UNVERIFIABLE)
		}
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
	fun `retention rejects a batch when any accepted child interval crosses the floor`() {
		val authority = authority()
		val input = input(
			authority = authority,
			receivedElapsedRealtimeNanos = 1_100_000_000L,
			observedWallTimeMs = 1_000L,
			wallTimeUncertaintyMs = 0L,
			accessPoints = listOf(
				accessPoint(2_412, -50, 1_000_000_000L),
				accessPoint(5_180, -60, 1_100_000_000L),
			),
		)

		classify(
			input,
			authority,
			deletionAuthority = WifiDeletionAuthority(CAPTURED_EPOCH, 950L),
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
			).withValidWalIntegrity(),
		)
		classify(wrongTimeline, authority) shouldBe WifiCapturedFactClassification.ClockUnverifiable
		shouldThrow<IllegalArgumentException> { authority(zoneId = "Not/A_Zone") }
		shouldThrow<IllegalArgumentException> {
			authority.copy(authorizationFingerprint = "A".repeat(64))
		}
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
			authorizationFingerprint = sha256("wifi-authorization-1"),
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
				resultContract = WifiIdentityFreeResultContract.ANDROID_SCAN_RESULTS_V1,
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
	): WifiObservationInput {
		if (outcome != WifiProviderOutcome.RESULTS_UPDATED) {
			return WifiObservationInput(origin, outcome, null)
		}
		val canonicalAccessPoints = accessPoints.sortedWith(
			compareBy<WifiAccessPointEvidence>(
				WifiAccessPointEvidence::frequencyMhz,
				WifiAccessPointEvidence::signalLevelDbm,
				WifiAccessPointEvidence::providerTimestampNanos,
			),
		)
		val providerTimes = canonicalAccessPoints.mapNotNull(
			WifiAccessPointEvidence::providerTimestampNanos,
		)
		val observed = providerTimes.maxOrNull() ?: 1_000_000_000L
		val observedStart = providerTimes.minOrNull() ?: observed
		val payload = WifiResultSnapshotPayload(
			accessPoints = canonicalAccessPoints,
			platformTimestampMs = providerTimes.maxOrNull()?.div(NANOS_PER_MILLISECOND),
			resultAgeMs = null,
		)
		val encoded = DefaultSourcePayloadCodec().encode(payload, 2)
		val deliveryIdentity = runCatching {
			wifiProviderDeliveryIdentity(authority.clockDomainId, canonicalAccessPoints)
		}.getOrElse { identity(delivery) }
		val wal = WifiWalObservationEvidence(
			sourceEventId = SourceEventId("wifi-event-$delivery"),
			sourceAdmissionOrdinal = 7L + (delivery - 'a').toLong(),
			walIntegrityIdentity = "0".repeat(64),
			providerDedupKey = null,
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			capturedAuthority = authority,
			logicalTrackingId = authority.logicalTrackingId,
			serviceRunId = authority.serviceRunId,
			sourceInstanceId = authority.sourceInstanceId,
			registrationGeneration = authority.registrationGeneration,
			configurationRevision = null,
			physicalConfigurationFingerprint = authority.physicalConfigurationFingerprint,
			authorizationRevision = authority.authorizationRevision,
			authorizationFingerprint = authority.authorizationFingerprint,
			purposeEligibilityMask = authority.purposeEligibilityMask,
			sourceSequence = 0L,
			sourcePolicyRevision = authority.sourcePolicyRevision,
			captureConsentEpoch = authority.captureConsentEpoch,
			sessionManifestRevision = authority.sessionManifestRevision,
			lifecycleLeaseGeneration = authority.lifecycleLeaseGeneration,
			capturedCollectedDataEpoch = authority.collectedDataEpoch,
			activityAutomationEpoch = null,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clock = WifiDurableClockEvidence(
				clockDomainId = authority.clockDomainId,
				observedIntervalStartElapsedRealtimeNanos = observedStart,
				observedElapsedRealtimeNanos = observed,
				receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
				observedWallTimeMs = observedWallTimeMs,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			),
			acquiredAtMs = observedWallTimeMs,
			qualityFlags = 0L,
			qualityConfidence = 0.9f,
			payloadVersion = 2,
			payloadBytes = encoded.bytes.toList(),
			payloadChecksum = encoded.checksum,
		)
		val qualifiedWal = wal.copy(walIntegrityIdentity = wal.calculatedWalIntegrityIdentity())
		return WifiObservationInput(
			origin = origin,
			outcome = outcome,
			walEvidence = qualifiedWal,
		)
	}

	private fun WifiWalObservationEvidence.withValidWalIntegrity(): WifiWalObservationEvidence =
		copy(walIntegrityIdentity = calculatedWalIntegrityIdentity())

	private fun WifiWalObservationEvidence.withPayload(
		authority: WifiCaptureAuthority,
		payload: WifiResultSnapshotPayload,
	): WifiWalObservationEvidence {
		val encoded = DefaultSourcePayloadCodec().encode(payload, 2)
		val identity = runCatching {
			wifiProviderDeliveryIdentity(authority.clockDomainId, payload.accessPoints)
		}.getOrNull()
		return copy(
			sourceDeliveryIdentity = identity,
			payloadBytes = encoded.bytes.toList(),
			payloadChecksum = encoded.checksum,
		).withValidWalIntegrity()
	}

	private fun accessPoint(
		frequencyMhz: Int,
		signalLevelDbm: Int,
		providerTimestampNanos: Long?,
		identifierToken: String = "",
	) = WifiAccessPointEvidence(identifierToken, frequencyMhz, signalLevelDbm, providerTimestampNanos)

	private fun identity(character: Char) = SourceDeliveryIdentity(character.toString().repeat(64))

	private fun sha256(value: String): String = value.toByteArray(Charsets.UTF_8).sha256()

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this)
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
