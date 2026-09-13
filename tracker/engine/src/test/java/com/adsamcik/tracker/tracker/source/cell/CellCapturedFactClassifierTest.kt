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
					expectedSubscriptionCount = 2,
					observedSubscriptionCount = 1,
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
	fun `confirmed empty requires stable source identity and provider time`() {
		val unverifiable = classify(
			input(
				sourceDeliveryIdentity = null,
				children = emptyList(),
				providerEmptyObservationElapsedRealtimeNanos = FRESH_TIME,
			),
		)
		assertEquals(
			CellSourceUnverifiableReason.CONFIRMED_EMPTY_IDENTITY_MISSING,
			assertIs<CellCapturedFactClassification.SourceUnverifiable>(unverifiable).reason,
		)

		val empty = assertIs<CellCapturedFactClassification.FreshConfirmedEmpty>(
			classify(
				input(
					children = emptyList(),
					providerEmptyObservationElapsedRealtimeNanos = FRESH_TIME,
					expectedSubscriptionCount = 2,
					observedSubscriptionCount = 1,
				),
			),
		)
		assertEquals(CellAvailability.CONFIRMED_EMPTY, empty.fact.availability)
		assertNull(empty.fact.reusesAggregate)
		assertTrue(empty.fact.coverage.isPartialMultiSim)
	}

	@Test
	fun `exact replay is stable and a new delivery with the same aggregate is unchanged`() {
		val firstInput = input(sourceDeliveryIdentity = identity('a'))
		val first = assertIs<CellCapturedFactClassification.FreshChanged>(classify(firstInput)).fact
		val reusable = first.reusable()

		val replay = assertIs<CellCapturedFactClassification.Replay>(
			classify(firstInput, priorFact = reusable),
		)
		assertEquals(CellReplayReason.EXACT_DELIVERY, replay.reason)
		assertEquals(reusable.reference, replay.reference)

		val unchanged = assertIs<CellCapturedFactClassification.FreshUnchanged>(
			classify(input(sourceDeliveryIdentity = identity('b')), priorFact = reusable),
		)
		assertEquals(reusable.aggregateOwnerReference, unchanged.fact.reusesAggregate)
		assertEquals(CellAvailability.AVAILABLE, unchanged.fact.availability)

		val replayAfterCoverageContextChanges = assertIs<CellCapturedFactClassification.Replay>(
			classify(
				firstInput.copy(expectedSubscriptionCount = 2, observedSubscriptionCount = 1),
				priorFact = reusable,
			),
		)
		assertEquals(CellReplayReason.EXACT_DELIVERY, replayAfterCoverageContextChanges.reason)
	}

	@Test
	fun `delivery identity collision and correction chain fail closed`() {
		val firstInput = input(sourceDeliveryIdentity = identity('a'))
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
				classify(input(expectedSubscriptionCount = 1, observedSubscriptionCount = 2)),
			).reason,
		)
	}

	private fun classify(
		input: CellObservationInput,
		priorFact: CellReusableFact? = null,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		correctionBase: CellReusableFact? = null,
	): CellCapturedFactClassification = CellCapturedFactClassifier.classify(
		input = input,
		authority = authority(),
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = supersedesSemanticRevision,
		priorFact = priorFact,
		correctionBase = correctionBase,
	)

	private fun authority() = CellCaptureAuthority(
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
		structuralEpochDay = 20_000L,
		temporalAuthority = CellCaptureTemporalAuthority(
			providerAcceptance = interval(),
			authorizationEffect = interval(),
			consentEffect = interval(),
			sessionRunEffect = interval(),
			deletionEffect = interval(),
		),
		maximumObservationAgeNanos = 2_000_000_000L,
	)

	private fun interval() = CellProviderTimeInterval(
		startInclusiveNanos = 5_000_000_000L,
		endExclusiveNanos = 20_000_000_000L,
	)

	private fun input(
		origin: CellObservationOrigin = CellObservationOrigin.CHANGE_CALLBACK,
		outcome: CellProviderOutcome = CellProviderOutcome.DELIVERED,
		sourceDeliveryIdentity: SourceDeliveryIdentity? = identity('a'),
		clockDomainId: String? = "boot-1",
		providerEmptyObservationElapsedRealtimeNanos: Long? = null,
		expectedSubscriptionCount: Int? = 1,
		observedSubscriptionCount: Int = 1,
		children: List<CellProviderChild> = listOf(child()),
	) = CellObservationInput(
		origin = origin,
		outcome = outcome,
		sourceDeliveryIdentity = sourceDeliveryIdentity,
		clockDomainId = clockDomainId,
		providerEmptyObservationElapsedRealtimeNanos = providerEmptyObservationElapsedRealtimeNanos,
		receivedElapsedRealtimeNanos = RECEIVED_ELAPSED_NANOS,
		receivedWallTimeMs = RECEIVED_WALL_TIME_MS,
		wallTimeUncertaintyMs = 25L,
		expectedSubscriptionCount = expectedSubscriptionCount,
		observedSubscriptionCount = observedSubscriptionCount,
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

	private fun CellCapturedFact.Aggregate.reusable() = CellReusableFact(
		reference = CellAggregateFactReference(mutation.identity, mutation.semanticRevision),
		authority = authority,
		availability = availability,
		coverage = coverage,
		aggregate = aggregate,
		aggregateOwnerReference = CellAggregateFactReference(
			mutation.identity,
			mutation.semanticRevision,
		),
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
