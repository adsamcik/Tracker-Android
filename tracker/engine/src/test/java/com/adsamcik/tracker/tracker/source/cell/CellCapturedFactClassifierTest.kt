package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.cellProviderDeliveryIdentity
import io.kotest.matchers.shouldBe
import org.junit.Test

class CellCapturedFactClassifierTest {
	@Test
	fun `only callback deliveries can produce facts`() {
		CellObservationOrigin.entries.filterNot {
			it == CellObservationOrigin.CHANGE_CALLBACK ||
				it == CellObservationOrigin.REFRESH_RESULT_CALLBACK
		}.forEach { origin ->
			classify(input(origin = origin)) shouldBe
				CellCapturedFactClassification.Absent(CellAbsentReason.OPERATIONAL_ONLY)
		}
	}

	@Test
	fun `provider outcomes remain typed`() {
		classify(input(outcome = CellProviderOutcome.ABSENT)) shouldBe
			CellCapturedFactClassification.Absent(CellAbsentReason.PROVIDER_ABSENT)
		classify(input(outcome = CellProviderOutcome.FAILED)) shouldBe
			CellCapturedFactClassification.Failed(CellFailureReason.PROVIDER_FAILED)
		classify(input(outcome = CellProviderOutcome.PERMISSION_LIMITED)) shouldBe
			CellCapturedFactClassification.PermissionLimited
		classify(input(outcome = CellProviderOutcome.OS_LIMITED)) shouldBe
			CellCapturedFactClassification.OsLimited
	}

	@Test
	fun `first materialization binds complete WAL header and provider semantics`() {
		val authority = authority()
		val observations = listOf(
			observation("LTE", true, -105, 9_800_000_000L),
			observation("NR", false, -85, 9_900_000_000L),
			observation("WCDMA", false, null, 9_950_000_000L),
		)
		val input = input(authority = authority, observations = observations)
		val fact = (classify(input, authority) as CellCapturedFactClassification.FreshChanged).fact
		val wal = requireNotNull(input.walEvidence)

		fact.authority shouldBe authority
		fact.authority.capturedSources shouldBe setOf(SourceKind.CELL)
		fact.authority.controlSources shouldBe emptySet()
		fact.evidenceBinding.sourceEventId shouldBe wal.sourceEventId
		fact.evidenceBinding.sourceKind shouldBe SourceKind.CELL
		fact.evidenceBinding.sourceAdmissionOrdinal shouldBe wal.sourceAdmissionOrdinal
		fact.evidenceBinding.walIntegrityIdentity shouldBe wal.walIntegrityIdentity
		fact.evidenceBinding.payloadChecksum shouldBe wal.payloadChecksum
		fact.evidenceBinding.deliveryUnitIndex shouldBe wal.deliveryUnitIndex
		fact.evidenceBinding.deliveryUnitCount shouldBe wal.deliveryUnitCount
		fact.evidenceBinding.sourceSequence shouldBe wal.sourceSequence
		fact.evidenceBinding.planAttribution shouldBe PlanAttribution.CAPTURED_REGISTRATION
		fact.evidenceBinding.payloadVersion shouldBe wal.payloadVersion
		fact.evidenceBinding.capturedAuthority shouldBe authority
		fact.evidenceBinding.observedIntervalStartElapsedRealtimeNanos shouldBe
			wal.clock.observedIntervalStartElapsedRealtimeNanos
		fact.evidenceBinding.observedElapsedRealtimeNanos shouldBe
			wal.clock.observedElapsedRealtimeNanos
		fact.evidenceBinding.receivedElapsedRealtimeNanos shouldBe
			wal.clock.receivedElapsedRealtimeNanos
		fact.evidenceBinding.observedWallTimeMs shouldBe wal.clock.observedWallTimeMs
		fact.evidenceBinding.wallTimeUncertaintyMs shouldBe wal.clock.wallTimeUncertaintyMs
		fact.evidenceBinding.acquiredAtMs shouldBe wal.acquiredAtMs
		fact.evidenceBinding.createdAtMs shouldBe wal.createdAtMs
		fact.evidenceBinding.qualityFlags shouldBe wal.qualityFlags
		fact.evidenceBinding.qualityConfidence shouldBe wal.qualityConfidence
		fact.evidenceBinding.canonicalProviderSemanticsDigest shouldBe
			requireNotNull(wal.sourceDeliveryIdentity).value
		fact.coverage.subscriptionCompleteness shouldBe CellSubscriptionCompleteness.UNKNOWN
		fact.coverage.expectedSubscriptionCount shouldBe null
		fact.coverage.observedSubscriptionCount shouldBe null
		fact.aggregate shouldBe CellIdentityFreeAggregate(
			observationCount = 3,
			registeredObservationCount = 1,
			technologyMix = CellTechnologyMix(
				mapOf(
					CellRadioTechnology.LTE to 1,
					CellRadioTechnology.NR to 1,
					CellRadioTechnology.WCDMA to 1,
				),
			),
			signalQuality = CellSignalQualityDistribution(1, 0, 0, 1, 0, 1),
			weakPeriod = CellWeakPeriodEvidence(0, 2, false),
		)
		fact.observedWallTimeMs shouldBe OBSERVED_WALL_TIME_MS
	}

	@Test
	fun `complete captured header mismatches fail before first materialization`() {
		val authority = authority()
		val original = input(authority = authority)
		val wal = requireNotNull(original.walEvidence)
		val mismatches = listOf(
			wal.copy(logicalTrackingId = LogicalTrackingId("other-logical")),
			wal.copy(serviceRunId = ServiceRunId("other-run")),
			wal.copy(sourceInstanceId = SourceInstanceId("other-source")),
			wal.copy(registrationGeneration = 2L),
			wal.copy(configurationRevision = 2L),
			wal.copy(physicalConfigurationFingerprint = "other-plan"),
			wal.copy(authorizationRevision = 2L),
			wal.copy(authorizationFingerprint = "b".repeat(64)),
			wal.copy(purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART),
			wal.copy(sourcePolicyRevision = 2L),
			wal.copy(captureConsentEpoch = 2L),
			wal.copy(sessionManifestRevision = 2L),
			wal.copy(lifecycleLeaseGeneration = 2L),
			wal.copy(capturedCollectedDataEpoch = 2L),
			wal.copy(capturedAuthority = authority.copy(sessionSegmentId = 72L)),
			wal.copy(
				capturedAuthority = authority.copy(
					capturedSources = setOf(SourceKind.CELL, SourceKind.STEPS),
				),
			),
			wal.copy(capturedAuthority = authority.copy(zoneId = "UTC")),
			wal.copy(capturedAuthority = authority.copy(structuralEpochDay = 1L)),
		)
		mismatches.forEach { mismatch ->
			classify(
				original.copy(walEvidence = mismatch.withValidWalIntegrity()),
				authority,
			) shouldBe rejected(CellFactRejection.CAPTURE_AUTHORITY_MISMATCH)
		}
	}

	@Test
	fun `WAL and payload integrity are recomputed instead of trusted`() {
		val authority = authority()
		val original = input(authority = authority)
		val wal = requireNotNull(original.walEvidence)
		classify(
			original.copy(walEvidence = wal.copy(sourceSequence = wal.sourceSequence + 1L)),
			authority,
		) shouldBe rejected(CellFactRejection.WAL_INTEGRITY_UNVERIFIABLE)
		classify(
			original.copy(walEvidence = wal.copy(payloadChecksum = "f".repeat(64))),
			authority,
		) shouldBe rejected(CellFactRejection.PAYLOAD_INTEGRITY_UNVERIFIABLE)
		classify(
			original.copy(
				walEvidence = wal.copy(planAttribution = PlanAttribution.RECEIVE_TIME_ONLY)
					.withValidWalIntegrity(),
			),
			authority,
		) shouldBe rejected(CellFactRejection.WAL_PROVENANCE_UNVERIFIABLE)
		classify(
			original.copy(
				walEvidence = wal.copy(sourceKind = SourceKind.WIFI).withValidWalIntegrity(),
			),
			authority,
		) shouldBe rejected(CellFactRejection.WAL_PROVENANCE_UNVERIFIABLE)
	}

	@Test
	fun `same delivery identity with changed canonical provider semantics is a collision`() {
		val authority = authority()
		val firstInput = input(authority = authority)
		val first = (classify(firstInput, authority) as CellCapturedFactClassification.FreshChanged)
			.fact.toReusableFact()
		val identity = requireNotNull(requireNotNull(firstInput.walEvidence).sourceDeliveryIdentity)
		val changed = input(
			authority = authority,
			observations = listOf(observation("NR", true, -85, 9_900_000_000L)),
			deliveryIdentityOverride = identity,
		)

		classify(changed, authority, priorFact = first) shouldBe
			rejected(CellFactRejection.DELIVERY_IDENTITY_COLLISION)
		classify(changed, authority) shouldBe rejected(CellFactRejection.PROVIDER_SEMANTICS_MISMATCH)

		val originalWal = requireNotNull(firstInput.walEvidence)
		val changedReceipt = firstInput.copy(
			walEvidence = originalWal.copy(
				clock = originalWal.clock.copy(
					receivedElapsedRealtimeNanos =
						originalWal.clock.receivedElapsedRealtimeNanos + 1L,
				),
			).withValidWalIntegrity(),
		)
		classify(changedReceipt, authority, priorFact = first) shouldBe
			rejected(CellFactRejection.DELIVERY_IDENTITY_COLLISION)
	}

	@Test
	fun `exact replay and one hop unchanged reuse are stable`() {
		val authority = authority()
		val firstInput = input(authority = authority)
		val first = (classify(firstInput, authority) as CellCapturedFactClassification.FreshChanged)
			.fact.toReusableFact()
		val replay = classify(firstInput, authority, priorFact = first)
		(replay as CellCapturedFactClassification.Replay).reason shouldBe CellReplayReason.EXACT_DELIVERY

		val nextInput = input(
			authority = authority,
			observations = listOf(observation("LTE", true, -105, 9_950_000_000L)),
		)
		val unchanged = classify(nextInput, authority, priorFact = first) as
			CellCapturedFactClassification.FreshUnchanged
		unchanged.fact.reusesAggregate.reference shouldBe first.reference
		classify(
			input(
				authority = authority,
				observations = listOf(observation("LTE", true, -105, 9_975_000_000L)),
			),
			authority,
			priorFact = unchanged.fact.toReusableFact(),
		)::class shouldBe CellCapturedFactClassification.FreshChanged::class
	}

	@Test
	fun `empty and identity bearing provider payloads cannot become product facts`() {
		val authority = authority()
		classify(input(authority = authority, observations = emptyList()), authority) shouldBe
			CellCapturedFactClassification.SourceUnverifiable(
				CellSourceUnverifiableReason.PROVIDER_CONFIRMED_EMPTY_PROOF_UNAVAILABLE,
			)
		classify(
			input(
				authority = authority,
				observations = listOf(observation("LTE", true, -105, 9_900_000_000L, "tower")),
			),
			authority,
		) shouldBe rejected(CellFactRejection.IDENTITY_BEARING_INPUT)
	}

	@Test
	fun `provider authority is half open and receipt clock is exact`() {
		val authority = authority(
			temporalAuthority = temporalAuthority(start = 9_000_000_000L, end = 10_000_000_000L),
			maximumObservationAgeNanos = 1_000_000_000L,
		)
		val fact = classify(
			input(
				authority = authority,
				observations = listOf(
					observation("LTE", true, -105, 9_000_000_000L),
					observation("LTE", true, -105, 10_000_000_000L),
				),
			),
			authority,
		) as CellCapturedFactClassification.FreshChanged
		fact.fact.coverage.acceptedChildCount shouldBe 1
		fact.fact.coverage.staleChildCount shouldBe 1

		val future = input(
			authority = authority,
			observations = listOf(observation("LTE", true, -105, 10_100_000_000L)),
		)
		(classify(future, authority) as CellCapturedFactClassification.ClockUnverifiable).reason shouldBe
			CellClockUnverifiableReason.DELIVERY_CLOCK_DOMAIN_MISSING_OR_MISMATCHED
	}

	@Test
	fun `aggregate reuse crosses only compatible physical authority`() {
		val originalAuthority = authority()
		val original = (classify(input(authority = originalAuthority), originalAuthority) as
			CellCapturedFactClassification.FreshChanged).fact.toReusableFact()
		val replacement = originalAuthority.copy(
			sourceInstanceId = SourceInstanceId("replacement"),
			registrationGeneration = 2L,
			configurationRevision = 2L,
			physicalConfigurationFingerprint = "cell-replacement-plan",
			authorizationRevision = 2L,
			authorizationFingerprint = "b".repeat(64),
			sourcePolicyRevision = 2L,
			sessionManifestRevision = 2L,
			lifecycleLeaseGeneration = 2L,
			clockDomainId = "boot-2",
		)
		val reused = classify(input(authority = replacement), replacement, priorFact = original)
			as CellCapturedFactClassification.FreshUnchanged
		reused.fact.authority shouldBe replacement
		reused.fact.reusesAggregate.reference shouldBe original.reference
		val incompatible = replacement.copy(captureConsentEpoch = 2L)
		classify(input(authority = incompatible), incompatible, priorFact = original)::class shouldBe
			CellCapturedFactClassification.FreshChanged::class
	}

	private fun classify(
		input: CellObservationInput,
		authority: CellCaptureAuthority = authority(),
		priorFact: CellReusableFact? = null,
	): CellCapturedFactClassification = CellCapturedFactClassifier.classify(
		input = input,
		authority = authority,
		priorFact = priorFact,
	)

	private fun authority(
		temporalAuthority: CellCaptureTemporalAuthority = temporalAuthority(),
		maximumObservationAgeNanos: Long = 2_000_000_000L,
	) = CellCaptureAuthority(
		logicalTrackingId = LogicalTrackingId("logical-cell-session"),
		serviceRunId = ServiceRunId("cell-run"),
		sessionSegmentId = 71L,
		capturedSources = setOf(SourceKind.CELL),
		controlSources = emptySet(),
		sourceInstanceId = SourceInstanceId("cell-source-instance"),
		registrationGeneration = 1L,
		configurationRevision = 1L,
		physicalConfigurationFingerprint = "cell-change-callback-v1",
		authorizationRevision = 1L,
		authorizationFingerprint = "a".repeat(64),
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		sessionManifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		collectedDataEpoch = 1L,
		scopeDeletionGeneration = 1L,
		clockDomainId = "boot-1",
		zoneId = "Europe/Prague",
		structuralEpochDay = 0L,
		temporalAuthority = temporalAuthority,
		maximumObservationAgeNanos = maximumObservationAgeNanos,
	)

	private fun temporalAuthority(
		start: Long = 5_000_000_000L,
		end: Long = 20_000_000_000L,
	) = CellCaptureTemporalAuthority(
		providerAcceptance = CellProviderTimeInterval(start, end),
		authorizationEffect = CellProviderTimeInterval(start, end),
		consentEffect = CellProviderTimeInterval(start, end),
		sessionRunEffect = CellProviderTimeInterval(start, end),
		deletionEffect = CellProviderTimeInterval(start, end),
	)

	private fun input(
		authority: CellCaptureAuthority = authority(),
		origin: CellObservationOrigin = CellObservationOrigin.CHANGE_CALLBACK,
		outcome: CellProviderOutcome = CellProviderOutcome.DELIVERED,
		observations: List<CellObservationEvidence> = listOf(
			observation("LTE", true, -105, 9_900_000_000L),
		),
		deliveryIdentityOverride: SourceDeliveryIdentity? = null,
	): CellObservationInput {
		if (outcome != CellProviderOutcome.DELIVERED) return CellObservationInput(origin, outcome, null)
		val payload = CellSnapshotPayload(null, observations, CellRefreshOutcome.CALLBACK)
		val encoded = DefaultSourcePayloadCodec().encode(payload, CELL_PAYLOAD_VERSION)
		val providerTimes = observations.mapNotNull(CellObservationEvidence::providerTimestampNanos)
		val observed = providerTimes.maxOrNull() ?: RECEIVED_ELAPSED_NANOS
		val observedStart = providerTimes.minOrNull() ?: observed
		val canonicalIdentity = runCatching {
			cellProviderDeliveryIdentity(authority.clockDomainId, observations)
		}.getOrNull()
		val raw = CellWalObservationEvidence(
			sourceEventId = SourceEventId("cell-event-${observed}-${observations.size}"),
			sourceKind = SourceKind.CELL,
			sourceAdmissionOrdinal = 7L,
			walIntegrityIdentity = "0".repeat(64),
			providerDedupKey = null,
			sourceDeliveryIdentity = deliveryIdentityOverride ?: canonicalIdentity,
			payloadChecksum = encoded.checksum,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = authority.logicalTrackingId,
			serviceRunId = authority.serviceRunId,
			sourceInstanceId = authority.sourceInstanceId,
			registrationGeneration = authority.registrationGeneration,
			configurationRevision = authority.configurationRevision,
			physicalConfigurationFingerprint = authority.physicalConfigurationFingerprint,
			authorizationRevision = authority.authorizationRevision,
			authorizationFingerprint = authority.authorizationFingerprint,
			purposeEligibilityMask = authority.purposeEligibilityMask,
			sourceSequence = 1L,
			sourcePolicyRevision = authority.sourcePolicyRevision,
			captureConsentEpoch = authority.captureConsentEpoch,
			sessionManifestRevision = authority.sessionManifestRevision,
			lifecycleLeaseGeneration = authority.lifecycleLeaseGeneration,
			capturedCollectedDataEpoch = authority.collectedDataEpoch,
			activityAutomationEpoch = null,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clock = CellDurableClockEvidence(
				clockDomainId = authority.clockDomainId,
				observedIntervalStartElapsedRealtimeNanos = observedStart,
				observedElapsedRealtimeNanos = observed,
				receivedElapsedRealtimeNanos = RECEIVED_ELAPSED_NANOS,
				observedWallTimeMs = OBSERVED_WALL_TIME_MS,
				wallTimeUncertaintyMs = 25L,
			),
			acquiredAtMs = OBSERVED_WALL_TIME_MS,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = CELL_PAYLOAD_VERSION,
			payloadBytes = encoded.bytes.toList(),
			createdAtMs = OBSERVED_WALL_TIME_MS,
			capturedAuthority = authority,
		)
		return CellObservationInput(origin, outcome, raw.withValidWalIntegrity())
	}

	private fun CellWalObservationEvidence.withValidWalIntegrity(): CellWalObservationEvidence =
		copy(walIntegrityIdentity = calculatedWalIntegrityIdentity())

	private fun observation(
		radioType: String,
		registered: Boolean,
		signalLevelDbm: Int?,
		providerTimeNanos: Long?,
		identifierToken: String = "",
	) = CellObservationEvidence(
		identifierToken = identifierToken,
		radioType = radioType,
		registered = registered,
		signalLevelDbm = signalLevelDbm,
		providerTimestampNanos = providerTimeNanos,
	)

	private fun rejected(reason: CellFactRejection) = CellCapturedFactClassification.Rejected(reason)

	private companion object {
		const val CELL_PAYLOAD_VERSION = 1
		const val RECEIVED_ELAPSED_NANOS = 10_000_000_000L
		const val OBSERVED_WALL_TIME_MS = 100_000L
	}
}
