package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CellCapturedFactClassifierTest {
	@Test
	fun `cache refresh request and receipt time are operational only`() {
		listOf(
			CellObservationOrigin.GET_ALL_CELL_INFO_CACHE,
			CellObservationOrigin.REFRESH_REQUEST,
			CellObservationOrigin.RECEIPT_ONLY,
		).forEach { origin ->
			val result = CellCapturedFactClassifier.classify(
				input(origin = origin),
				authority(),
			)

			assertEquals(
				CellAbsentReason.OPERATIONAL_ONLY,
				assertIs<CellCapturedFactClassification.Absent>(result).reason,
			)
		}
	}

	@Test
	fun `provider outcomes remain typed without fabricating a fact`() {
		assertEquals(
			CellAbsentReason.PROVIDER_ABSENT,
			assertIs<CellCapturedFactClassification.Absent>(
				classify(input(outcome = CellProviderOutcome.ABSENT)),
			).reason,
		)
		assertEquals(
			CellFailureReason.PROVIDER_FAILED,
			assertIs<CellCapturedFactClassification.Failed>(
				classify(input(outcome = CellProviderOutcome.FAILED)),
			).reason,
		)
		assertIs<CellCapturedFactClassification.PermissionLimited>(
			classify(input(outcome = CellProviderOutcome.PERMISSION_LIMITED)),
		)
		assertIs<CellCapturedFactClassification.OsLimited>(
			classify(input(outcome = CellProviderOutcome.OS_LIMITED)),
		)
	}

	@Test
	fun `each callback child is qualified and partial multi sim aggregate stays identity free`() {
		val exactAuthority = authority()
		val children = listOf(
			child(technology = CellRadioTechnology.LTE, quality = 1, registered = true, providerTimeNanos = FRESH_TIME),
			child(technology = CellRadioTechnology.NR, quality = null, providerTimeNanos = FRESH_TIME - 500_000_000L),
			child(technology = CellRadioTechnology.LTE, providerTimeNanos = STALE_TIME),
			child(technology = CellRadioTechnology.LTE, providerTimeNanos = FUTURE_TIME),
			child(technology = CellRadioTechnology.LTE, providerTimeNanos = null),
			child(
				technology = CellRadioTechnology.LTE,
				authority = exactAuthority.childAuthority.copy(registrationGeneration = 2L),
			),
			child(technology = null),
			child(
				technology = CellRadioTechnology.LTE,
				authority = exactAuthority.childAuthority.copy(clockDomainId = "other-boot"),
			),
		)

		val changed = assertIs<CellCapturedFactClassification.FreshChanged>(
			CellCapturedFactClassifier.classify(
				input(
					subscriptionGroupProof = CellSubscriptionGroupProof(
						expectedGroupCount = 2,
						observedGroupSizeHistogram = mapOf(children.size to 1),
					),
					children = children,
				),
				exactAuthority,
			),
		)
		val fact = changed.fact

		assertEquals(CellAvailability.AVAILABLE, fact.availability)
		assertEquals(2, fact.coverage.acceptedChildCount)
		assertEquals(1, fact.coverage.staleChildCount)
		assertEquals(1, fact.coverage.futureTimeChildCount)
		assertEquals(1, fact.coverage.missingTimeChildCount)
		assertEquals(1, fact.coverage.clockUnverifiableChildCount)
		assertEquals(1, fact.coverage.authorityMismatchChildCount)
		assertEquals(1, fact.coverage.unsupportedTechnologyChildCount)
		assertEquals(CellChildCompleteness.PARTIAL, fact.coverage.childCompleteness)
		assertTrue(fact.coverage.isPartialMultiSim)
		assertEquals(mapOf(CellRadioTechnology.LTE to 1, CellRadioTechnology.NR to 1), fact.aggregate.technologyMix.counts)
		assertEquals(1, fact.aggregate.registeredObservationCount)
		assertEquals(1, fact.aggregate.signalQuality.unknownCount)
		assertEquals(1, fact.aggregate.signalQuality.poorCount)
		assertEquals(1, fact.aggregate.weakPeriod.weakObservationCount)
		assertTrue(fact.aggregate.weakPeriod.allKnownQualityIsWeak)
		assertEquals(RECEIVED_WALL_TIME_MS - 1_000L, fact.observedWallTimeMs)
	}

	@Test
	fun `every immutable generation and epoch is enforced independently`() {
		val exact = authority()
		val mismatches = listOf(
			exact.childAuthority.copy(sourceInstanceId = SourceInstanceId("other-instance")),
			exact.childAuthority.copy(registrationGeneration = 2L),
			exact.childAuthority.copy(configurationRevision = 2L),
			exact.childAuthority.copy(authorizationRevision = 2L),
			exact.childAuthority.copy(authorizationFingerprint = "b".repeat(64)),
			exact.childAuthority.copy(sourcePolicyRevision = 2L),
			exact.childAuthority.copy(captureConsentEpoch = 2L),
			exact.childAuthority.copy(sessionManifestRevision = 2L),
			exact.childAuthority.copy(lifecycleLeaseGeneration = 2L),
			exact.childAuthority.copy(collectedDataEpoch = 2L),
			exact.childAuthority.copy(scopeDeletionGeneration = 2L),
		)

		mismatches.forEach { childAuthority ->
			val result = CellCapturedFactClassifier.classify(
				input(children = listOf(child(authority = childAuthority))),
				exact,
			)

			assertEquals(
				CellFactRejection.CHILD_AUTHORITY_MISMATCH,
				assertIs<CellCapturedFactClassification.Rejected>(result).reason,
			)
		}
	}

	@Test
	fun `delivery and child clock domains remain explicitly unverifiable`() {
		assertEquals(
			CellClockUnverifiableReason.DELIVERY_CLOCK_DOMAIN_MISSING_OR_MISMATCHED,
			assertIs<CellCapturedFactClassification.ClockUnverifiable>(
				classify(input(clockDomainId = "other-boot")),
			).reason,
		)

		val exact = authority()
		assertEquals(
			CellClockUnverifiableReason.CHILD_CLOCK_DOMAIN_MISMATCH,
			assertIs<CellCapturedFactClassification.ClockUnverifiable>(
				CellCapturedFactClassifier.classify(
					input(
						children = listOf(
							child(authority = exact.childAuthority.copy(clockDomainId = "other-boot")),
						),
					),
					exact,
				),
			).reason,
		)
	}

	@Test
	fun `all stale missing and future child sets have distinct outcomes`() {
		assertEquals(
			1,
			assertIs<CellCapturedFactClassification.Stale>(
				classify(input(children = listOf(child(providerTimeNanos = STALE_TIME)))),
			).rejectedChildCount,
		)
		assertEquals(
			CellClockUnverifiableReason.PROVIDER_TIME_MISSING,
			assertIs<CellCapturedFactClassification.ClockUnverifiable>(
				classify(input(children = listOf(child(providerTimeNanos = null)))),
			).reason,
		)
		assertEquals(
			CellClockUnverifiableReason.PROVIDER_TIME_IN_FUTURE,
			assertIs<CellCapturedFactClassification.ClockUnverifiable>(
				classify(input(children = listOf(child(providerTimeNanos = FUTURE_TIME)))),
			).reason,
		)
	}

	@Test
	fun `empty callback stays source unverifiable without immutable provider proof`() {
		val unverifiable = classify(input(children = emptyList()))
		assertEquals(
			CellSourceUnverifiableReason.PROVIDER_CONFIRMED_EMPTY_PROOF_UNAVAILABLE,
			assertIs<CellCapturedFactClassification.SourceUnverifiable>(unverifiable).reason,
		)
		val withoutWalIdentity = classify(
			input(
				walEvidence = null,
				children = emptyList(),
			),
		)
		assertEquals(
			CellSourceUnverifiableReason.PROVIDER_CONFIRMED_EMPTY_PROOF_UNAVAILABLE,
			assertIs<CellCapturedFactClassification.SourceUnverifiable>(withoutWalIdentity).reason,
		)
		val impossibleCallerProof = classify(
			input(
				subscriptionGroupProof = CellSubscriptionGroupProof(1, mapOf(2 to 1)),
				children = emptyList(),
			),
		)
		assertEquals(
			CellSourceUnverifiableReason.PROVIDER_CONFIRMED_EMPTY_PROOF_UNAVAILABLE,
			assertIs<CellCapturedFactClassification.SourceUnverifiable>(impossibleCallerProof).reason,
		)
	}

	@Test
	fun `exact replay is stable and changed delivery semantics collide`() {
		val firstInput = input(deliveryIdentity = identity('a'))
		val first = assertIs<CellCapturedFactClassification.FreshChanged>(classify(firstInput)).fact
		val reusable = first.reusable()

		val replay = assertIs<CellCapturedFactClassification.Replay>(
			classify(firstInput, priorFact = reusable),
		)
		assertEquals(CellReplayReason.EXACT_DELIVERY, replay.reason)
		assertEquals(reusable.reference, replay.reference)

		val unchanged = assertIs<CellCapturedFactClassification.FreshUnchanged>(
			classify(input(deliveryIdentity = identity('b')), priorFact = reusable),
		)
		assertEquals(reusable.reference, unchanged.fact.reusesAggregate.reference)
		assertEquals(CellAvailability.AVAILABLE, unchanged.fact.availability)

		val collisionAfterCoverageContextChanges = assertIs<CellCapturedFactClassification.Rejected>(
			classify(
				firstInput.copy(
					subscriptionGroupProof = CellSubscriptionGroupProof(2, mapOf(1 to 1)),
				),
				priorFact = reusable,
			),
		)
		assertEquals(CellFactRejection.DELIVERY_IDENTITY_COLLISION, collisionAfterCoverageContextChanges.reason)
	}

	@Test
	fun `delivery identity collision and correction chain fail closed`() {
		val firstInput = input(deliveryIdentity = identity('a'))
		val first = assertIs<CellCapturedFactClassification.FreshChanged>(classify(firstInput)).fact
		val base = first.reusable()
		assertEquals(
			CellFactRejection.DELIVERY_IDENTITY_COLLISION,
			assertIs<CellCapturedFactClassification.Rejected>(
				classify(
					firstInput.copy(children = listOf(child(quality = 4))),
					priorFact = base,
				),
			).reason,
		)

		val corrected = assertIs<CellCapturedFactClassification.FreshChanged>(
			classify(
				firstInput.copy(children = listOf(child(quality = 4))),
				semanticRevision = 2L,
				supersedesSemanticRevision = 1L,
				correctionBase = base,
			),
		).fact
		assertEquals(2L, corrected.mutation.semanticRevision)
		assertEquals(1L, corrected.mutation.supersedesSemanticRevision)

		assertEquals(
			CellReplayReason.CORRECTION_NO_OP,
			assertIs<CellCapturedFactClassification.Replay>(
				classify(
					firstInput,
					semanticRevision = 2L,
					supersedesSemanticRevision = 1L,
					correctionBase = base,
				),
			).reason,
		)
		assertEquals(
			CellFactRejection.INVALID_CORRECTION_BASE,
			assertIs<CellCapturedFactClassification.Rejected>(
				classify(
					firstInput,
					semanticRevision = 3L,
					supersedesSemanticRevision = 2L,
					correctionBase = base,
				),
			).reason,
		)
	}

	@Test
	fun `identity bearing children are rejected before a fact exists`() {
		val result = classify(
			input(children = listOf(child(containsProhibitedIdentity = true))),
		)

		assertEquals(
			CellFactRejection.IDENTITY_BEARING_INPUT,
			assertIs<CellCapturedFactClassification.Rejected>(result).reason,
		)
	}

	@Test
	fun `processing and subscription bounds are explicit`() {
		val tooManyChildren = List(MAX_CELL_CHILDREN_PER_DELIVERY + 1) { child() }
		assertEquals(
			CellFactRejection.PROCESSING_LIMIT_EXCEEDED,
			assertIs<CellCapturedFactClassification.Rejected>(
				classify(input(children = tooManyChildren)),
			).reason,
		)
		assertEquals(
			CellFactRejection.INVALID_SUBSCRIPTION_COUNTS,
			assertIs<CellCapturedFactClassification.Rejected>(
				classify(
					input(
						subscriptionGroupProof = CellSubscriptionGroupProof(1, mapOf(1 to 2)),
						children = listOf(child(), child()),
					),
				),
			).reason,
		)
	}

	@Test
	fun `subscription completeness requires a consistent nonidentifying group proof`() {
		val unknown = assertIs<CellCapturedFactClassification.FreshChanged>(
			classify(input(subscriptionGroupProof = null)),
		).fact.coverage
		assertEquals(CellSubscriptionCompleteness.UNKNOWN, unknown.subscriptionCompleteness)
		assertNull(unknown.expectedSubscriptionCount)
		assertNull(unknown.observedSubscriptionCount)

		val partial = assertIs<CellCapturedFactClassification.FreshChanged>(
			classify(
				input(
					subscriptionGroupProof = CellSubscriptionGroupProof(2, mapOf(1 to 1)),
				),
			),
		).fact.coverage
		assertEquals(CellSubscriptionCompleteness.PARTIAL, partial.subscriptionCompleteness)
		assertEquals(2, partial.expectedSubscriptionCount)
		assertEquals(1, partial.observedSubscriptionCount)

		val mismatch = classify(
			input(
				subscriptionGroupProof = CellSubscriptionGroupProof(2, mapOf(2 to 1)),
			),
		)
		assertEquals(
			CellFactRejection.INVALID_SUBSCRIPTION_COUNTS,
			assertIs<CellCapturedFactClassification.Rejected>(mismatch).reason,
		)
	}

	@Test
	fun `provider authority is half open at the exact end boundary`() {
		val boundedAuthority = authority(
			temporalAuthority = CellCaptureTemporalAuthority(
				providerAcceptance = interval(endExclusiveNanos = RECEIVED_ELAPSED_NANOS),
				authorizationEffect = interval(endExclusiveNanos = RECEIVED_ELAPSED_NANOS),
				consentEffect = interval(endExclusiveNanos = RECEIVED_ELAPSED_NANOS),
				sessionRunEffect = interval(endExclusiveNanos = RECEIVED_ELAPSED_NANOS),
				deletionEffect = interval(endExclusiveNanos = RECEIVED_ELAPSED_NANOS),
			),
			maximumObservationAgeNanos = RECEIVED_ELAPSED_NANOS,
		)
		val children = listOf(
			child(
				providerTimeNanos = boundedAuthority.temporalAuthority.capturedStartInclusiveNanos,
				authority = boundedAuthority.childAuthority,
			),
			child(
				providerTimeNanos = boundedAuthority.temporalAuthority.capturedEndExclusiveNanos,
				authority = boundedAuthority.childAuthority,
			),
		)

		val fact = assertIs<CellCapturedFactClassification.FreshChanged>(
			classify(
				input(authority = boundedAuthority, children = children),
				authority = boundedAuthority,
			),
		).fact
		assertEquals(1, fact.coverage.acceptedChildCount)
		assertEquals(1, fact.coverage.staleChildCount)
	}

	@Test
	fun `captured WAL authority mismatch and semantic identity collisions fail closed`() {
		val exactAuthority = authority()
		val firstInput = input(authority = exactAuthority, deliveryIdentity = identity('a'))
		val first = assertIs<CellCapturedFactClassification.FreshChanged>(
			classify(firstInput, authority = exactAuthority),
		).fact.reusable()
		val mismatchedWal = cellWalEvidence(
			identity('b'),
			exactAuthority.childAuthority.copy(registrationGeneration = 2L),
		)
		assertEquals(
			CellFactRejection.CAPTURE_AUTHORITY_MISMATCH,
			assertIs<CellCapturedFactClassification.Rejected>(
				classify(
					input(authority = exactAuthority, walEvidence = mismatchedWal),
					authority = exactAuthority,
				),
			).reason,
		)

		val changedTimes = firstInput.copy(
			receivedElapsedRealtimeNanos = RECEIVED_ELAPSED_NANOS + 1_000_000L,
		)
		assertEquals(
			CellFactRejection.DELIVERY_IDENTITY_COLLISION,
			assertIs<CellCapturedFactClassification.Rejected>(
				classify(changedTimes, authority = exactAuthority, priorFact = first),
			).reason,
		)
		val changedWal = firstInput.copy(
			walEvidence = requireNotNull(firstInput.walEvidence).copy(
				walIntegrityIdentity = "e".repeat(64),
			),
		)
		assertEquals(
			CellFactRejection.DELIVERY_IDENTITY_COLLISION,
			assertIs<CellCapturedFactClassification.Rejected>(
				classify(changedWal, authority = exactAuthority, priorFact = first),
			).reason,
		)
		val changedScope = authority(sessionManifestRevision = 2L)
		assertEquals(
			CellFactRejection.DELIVERY_IDENTITY_COLLISION,
			assertIs<CellCapturedFactClassification.Rejected>(
				classify(
					input(
						authority = changedScope,
						deliveryIdentity = identity('a'),
					),
					authority = changedScope,
					priorFact = first,
				),
			).reason,
		)
	}

	@Test
	fun `direct aggregate reuse crosses only compatible physical authority`() {
		val originalAuthority = authority()
		val directOwner = assertIs<CellCapturedFactClassification.FreshChanged>(
			classify(input(authority = originalAuthority), authority = originalAuthority),
		).fact.reusable()
		val replacementAuthority = authority(
			sourceInstanceId = SourceInstanceId("cell-source-replacement"),
			registrationGeneration = 2L,
			configurationRevision = 2L,
			authorizationRevision = 2L,
			authorizationFingerprint = "b".repeat(64),
			sourcePolicyRevision = 2L,
			sessionManifestRevision = 2L,
			lifecycleLeaseGeneration = 2L,
			clockDomainId = "boot-2",
		)
		val reused = assertIs<CellCapturedFactClassification.FreshUnchanged>(
			classify(
				input(authority = replacementAuthority, deliveryIdentity = identity('b')),
				authority = replacementAuthority,
				priorFact = directOwner,
			),
		).fact
		assertEquals(replacementAuthority, reused.authority)
		assertEquals(directOwner.reference, reused.reusesAggregate.reference)

		val incompatibleAuthorities = listOf(
			replacementAuthority.copy(captureConsentEpoch = 2L),
			replacementAuthority.copy(collectedDataEpoch = 2L),
			replacementAuthority.copy(scopeDeletionGeneration = 2L),
			replacementAuthority.copy(capturedSources = setOf(SourceKind.CELL, SourceKind.STEPS)),
		)
		incompatibleAuthorities.forEachIndexed { index, incompatible ->
			assertIs<CellCapturedFactClassification.FreshChanged>(
				classify(
					input(
						authority = incompatible,
						deliveryIdentity = identity(('c'.code + index).toChar()),
					),
					authority = incompatible,
					priorFact = directOwner,
				),
			)
		}

		assertIs<CellCapturedFactClassification.FreshChanged>(
			classify(
				input(authority = replacementAuthority, deliveryIdentity = identity('z')),
				authority = replacementAuthority,
				priorFact = reused.toReusableFact(),
			),
		)
	}

	private fun classify(
		input: CellObservationInput,
		authority: CellCaptureAuthority = authority(),
		priorFact: CellReusableFact? = null,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		correctionBase: CellReusableFact? = null,
	): CellCapturedFactClassification = CellCapturedFactClassifier.classify(
		input = input,
		authority = authority,
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = supersedesSemanticRevision,
		priorFact = priorFact,
		correctionBase = correctionBase,
	)

	private fun authority(
		sourceInstanceId: SourceInstanceId = SourceInstanceId("cell-source-instance"),
		registrationGeneration: Long = 1L,
		configurationRevision: Long = 1L,
		authorizationRevision: Long = 1L,
		authorizationFingerprint: String = "a".repeat(64),
		sourcePolicyRevision: Long = 1L,
		captureConsentEpoch: Long = 1L,
		sessionManifestRevision: Long = 1L,
		lifecycleLeaseGeneration: Long = 1L,
		collectedDataEpoch: Long = 1L,
		scopeDeletionGeneration: Long = 1L,
		clockDomainId: String = "boot-1",
		temporalAuthority: CellCaptureTemporalAuthority = CellCaptureTemporalAuthority(
			providerAcceptance = interval(),
			authorizationEffect = interval(),
			consentEffect = interval(),
			sessionRunEffect = interval(),
			deletionEffect = interval(),
		),
		maximumObservationAgeNanos: Long = 2_000_000_000L,
	) = CellCaptureAuthority(
		logicalTrackingId = LogicalTrackingId("logical-cell-session"),
		serviceRunId = ServiceRunId("cell-run"),
		sessionSegmentId = 71L,
		capturedSources = setOf(SourceKind.CELL),
		controlSources = emptySet(),
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
		configurationRevision = configurationRevision,
		physicalConfigurationFingerprint = "cell-change-callback-v1",
		authorizationRevision = authorizationRevision,
		authorizationFingerprint = authorizationFingerprint,
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		sessionManifestRevision = sessionManifestRevision,
		lifecycleLeaseGeneration = lifecycleLeaseGeneration,
		collectedDataEpoch = collectedDataEpoch,
		scopeDeletionGeneration = scopeDeletionGeneration,
		clockDomainId = clockDomainId,
		zoneId = "Europe/Prague",
		structuralEpochDay = 20_000L,
		temporalAuthority = temporalAuthority,
		maximumObservationAgeNanos = maximumObservationAgeNanos,
	)

	private fun interval(
		startInclusiveNanos: Long = 5_000_000_000L,
		endExclusiveNanos: Long = 20_000_000_000L,
	) = CellProviderTimeInterval(
		startInclusiveNanos = startInclusiveNanos,
		endExclusiveNanos = endExclusiveNanos,
	)

	private fun input(
		authority: CellCaptureAuthority = authority(),
		origin: CellObservationOrigin = CellObservationOrigin.CHANGE_CALLBACK,
		outcome: CellProviderOutcome = CellProviderOutcome.DELIVERED,
		deliveryIdentity: SourceDeliveryIdentity? = identity('a'),
		walEvidence: CellWalObservationEvidence? = deliveryIdentity?.let { identity ->
			cellWalEvidence(identity, authority.childAuthority)
		},
		clockDomainId: String? = authority.clockDomainId,
		subscriptionGroupProof: CellSubscriptionGroupProof? = null,
		children: List<CellProviderChild> = listOf(child(authority = authority.childAuthority)),
	) = CellObservationInput(
		origin = origin,
		outcome = outcome,
		walEvidence = walEvidence,
		clockDomainId = clockDomainId,
		receivedElapsedRealtimeNanos = RECEIVED_ELAPSED_NANOS,
		receivedWallTimeMs = RECEIVED_WALL_TIME_MS,
		wallTimeUncertaintyMs = 25L,
		subscriptionGroupProof = subscriptionGroupProof,
		children = children,
	)

	private fun child(
		containsProhibitedIdentity: Boolean = false,
		technology: CellRadioTechnology? = CellRadioTechnology.LTE,
		registered: Boolean = false,
		quality: Int? = 3,
		providerTimeNanos: Long? = FRESH_TIME,
		authority: CellChildAuthority = authority().childAuthority,
	) = CellProviderChild(
		containsProhibitedIdentity = containsProhibitedIdentity,
		radioTechnology = technology,
		registered = registered,
		signalQualityLevel = quality,
		providerTimestampNanos = providerTimeNanos,
		authority = authority,
	)

	private fun CellCapturedFact.Aggregate.reusable() =
		CellReusableFact.DirectAggregateOwner.from(this)

	private fun cellWalEvidence(
		identity: SourceDeliveryIdentity,
		capturedAuthority: CellChildAuthority,
	) = CellWalObservationEvidence(
		sourceDeliveryIdentity = identity,
		sourceAdmissionOrdinal = 1L,
		walIntegrityIdentity = "c".repeat(64),
		payloadChecksum = "d".repeat(64),
		deliveryUnitIndex = 0,
		deliveryUnitCount = 1,
		sourceSequence = 1L,
		capturedAuthority = capturedAuthority,
	)

	private fun identity(character: Char) = SourceDeliveryIdentity(character.toString().repeat(64))

	private companion object {
		const val RECEIVED_ELAPSED_NANOS = 10_000_000_000L
		const val RECEIVED_WALL_TIME_MS = 100_000L
		const val FRESH_TIME = 9_000_000_000L
		const val STALE_TIME = 7_000_000_000L
		const val FUTURE_TIME = 11_000_000_000L
	}
}
