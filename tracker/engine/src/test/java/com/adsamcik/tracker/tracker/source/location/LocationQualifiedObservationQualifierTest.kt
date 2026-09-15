package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class LocationQualifiedObservationQualifierTest {
	@Test
	fun `location-only capture retains immutable WAL clock quality and mock provenance`() {
		val authority = authority(
			capturedSources = setOf(SourceKind.LOCATION),
			controlSources = emptySet(),
		)
		val evidence = evidence(
			authority = authority,
			quality = SourceQuality(
				confidence = 0.8f,
				flags = setOf(SourceQualityFlag.PROVIDER_DEGRADED),
			),
			isMock = true,
		)

		val command = assertIs<LocationObservationQualification.Qualified>(
			qualify(input(authority, evidence), authority),
		).command

		assertEquals(setOf(SourceKind.LOCATION), command.authority.capturedSources)
		assertEquals(emptySet(), command.authority.controlSources)
		assertEquals(SourceEventId("location-event-1"), command.mutation.identity.sourceEventId)
		assertEquals(7L, command.mutation.identity.sourceAdmissionOrdinal)
		assertEquals("b".repeat(64), command.mutation.identity.walIntegrityIdentity)
		assertEquals(1L, command.authority.capturedCollectedDataEpoch)
		assertEquals(500L, command.productEffect.deliveryAgeNanos)
		assertEquals(evidence.quality, command.productEffect.quality)
		assertTrue(command.productEffect.isMock)
	}

	@Test
	fun `control-only or source-overlapping authority cannot claim captured Location`() {
		assertFailsWith<IllegalArgumentException> {
			authority(capturedSources = setOf(SourceKind.STEPS))
		}
		assertFailsWith<IllegalArgumentException> {
			authority(controlSources = setOf(SourceKind.LOCATION))
		}
	}

	@Test
	fun `provider time must be positive nonfuture fresh and inside every authority`() {
		val authority = authority()

		assertRejected(
			input(authority, evidence(authority, observedNanos = 0L)),
			authority,
			LocationFactRejection.NON_POSITIVE_PROVIDER_TIME,
		)
		assertRejected(
			input(authority, evidence(authority, observedNanos = 5_501L)),
			authority,
			LocationFactRejection.FUTURE_PROVIDER_TIME,
		)
		assertEquals(
			LocationObservationQualification.Stale(LocationStaleReason.PRE_EFFECTIVE),
			qualify(
				input(
					authority,
					evidence(authority, observedNanos = 999L, receivedNanos = 1_100L),
				),
				authority,
			),
		)
		assertRejected(
			input(
				authority,
				evidence(authority, observedNanos = 10_000L, receivedNanos = 10_000L),
			),
			authority,
			LocationFactRejection.AFTER_AUTHORITY,
		)
		assertEquals(
			LocationObservationQualification.Stale(LocationStaleReason.TOO_OLD),
			qualify(
				input(authority, evidence(authority, receivedNanos = 6_001L)),
				authority,
			),
		)
	}

	@Test
	fun `captured epoch is immutable while current deletion epoch and floor fence eligibility`() {
		val authority = authority()
		val observation = input(authority)

		assertRejected(
			observation,
			authority,
			LocationFactRejection.DELETION_AUTHORITY_MISMATCH,
			deletionAuthority = deletion(epoch = 2L),
		)
		assertEquals(
			LocationObservationQualification.Stale(LocationStaleReason.BEFORE_DELETION_FLOOR),
			qualify(
				observation,
				authority,
				deletionAuthority = deletion(retainedFromWallTimeMs = 91L),
			),
		)

		val accepted = assertIs<LocationObservationQualification.Qualified>(
			qualify(
				observation,
				authority,
				deletionAuthority = deletion(retainedFromWallTimeMs = 90L),
			),
		)
		assertEquals(1L, accepted.command.authority.capturedCollectedDataEpoch)
		assertEquals(90L, accepted.command.productEffect.derivedQualification.earliestPossibleWallTimeMs)
	}

	@Test
	fun `clock authority and complete uncertainty interval must be verifiable`() {
		val authority = authority()
		val baseline = evidence(authority)
		val clocks = listOf(
			baseline.clockAuthority.copy(clockDomainId = "boot-2"),
			baseline.clockAuthority.copy(observedWallTimeMs = -1L),
			baseline.clockAuthority.copy(wallTimeUncertaintyMs = -1L),
			baseline.clockAuthority.copy(observedWallTimeMs = 5L, wallTimeUncertaintyMs = 6L),
			baseline.clockAuthority.copy(
				observedWallTimeMs = Long.MAX_VALUE,
				wallTimeUncertaintyMs = 1L,
			),
		)

		clocks.forEach { clock ->
			assertRejected(
				input(authority, baseline.copy(clockAuthority = clock)),
				authority,
				LocationFactRejection.CLOCK_UNVERIFIABLE,
			)
		}
	}

	@Test
	fun `coordinates accuracy and every optional payload field are validated`() {
		val authority = authority()
		val cases = listOf(
			payload().copy(latitudeDegrees = 90.01) to LocationFactRejection.INVALID_COORDINATE,
			payload().copy(horizontalAccuracyMeters = Float.NaN) to
				LocationFactRejection.INVALID_ACCURACY,
			payload().copy(horizontalAccuracyMeters = 25.01f) to
				LocationFactRejection.INSUFFICIENT_ACCURACY,
			payload().copy(altitudeMeters = Double.NaN) to LocationFactRejection.INVALID_ALTITUDE,
			payload().copy(verticalAccuracyMeters = -1f) to
				LocationFactRejection.INVALID_VERTICAL_ACCURACY,
			payload().copy(speedMetersPerSecond = Float.POSITIVE_INFINITY) to
				LocationFactRejection.INVALID_SPEED,
			payload().copy(bearingDegrees = 360f) to LocationFactRejection.INVALID_BEARING,
			payload().copy(provider = "") to LocationFactRejection.INVALID_PROVIDER,
		)

		cases.forEach { (payload, rejection) ->
			assertRejected(
				input(authority, evidence(authority, payload = payload)),
				authority,
				rejection,
			)
		}
	}

	@Test
	fun `cached evidence is rejected and permission precision must match quality`() {
		val precise = authority()
		assertRejected(
			input(
				precise,
				evidence(precise, quality = SourceQuality(flags = setOf(SourceQualityFlag.CACHED))),
			),
			precise,
			LocationFactRejection.CACHED_EVIDENCE,
		)
		assertRejected(
			input(
				precise,
				evidence(precise, quality = SourceQuality(flags = setOf(SourceQualityFlag.APPROXIMATE))),
			),
			precise,
			LocationFactRejection.QUALITY_AUTHORITY_MISMATCH,
		)

		val approximate = authority(permissionPrecision = LocationPermissionPrecision.APPROXIMATE)
		assertIs<LocationObservationQualification.Qualified>(
			qualify(
				input(
					approximate,
					evidence(
						approximate,
						quality = SourceQuality(flags = setOf(SourceQualityFlag.APPROXIMATE)),
					),
				),
				approximate,
			),
		)
	}

	@Test
	fun `non-fix outcomes remain typed unavailable but cannot carry evidence`() {
		val authority = authority()
		val cases = listOf(
			LocationProviderOutcome.NO_FIX to LocationUnavailableReason.NO_FIX,
			LocationProviderOutcome.PROVIDER_UNAVAILABLE to
				LocationUnavailableReason.PROVIDER_UNAVAILABLE,
			LocationProviderOutcome.PERMISSION_UNAVAILABLE to
				LocationUnavailableReason.PERMISSION_UNAVAILABLE,
			LocationProviderOutcome.FAILED to LocationUnavailableReason.PROVIDER_FAILURE,
		)

		cases.forEach { (outcome, expected) ->
			assertEquals(
				LocationObservationQualification.Unavailable(expected),
				qualify(input(authority, durableEvidence = null, outcome = outcome), authority),
			)
		}
		assertRejected(
			input(authority, outcome = LocationProviderOutcome.NO_FIX),
			authority,
			LocationFactRejection.INCONSISTENT_PROVIDER_RESULT,
		)
	}

	@Test
	fun `cache timer and request attempts cannot manufacture an observation`() {
		val authority = authority()

		LocationObservationOrigin.entries
			.filterNot { it == LocationObservationOrigin.PROVIDER_CALLBACK }
			.forEach { origin ->
				assertRejected(
					input(authority).copy(origin = origin),
					authority,
					LocationFactRejection.NOT_PROVIDER_OBSERVATION,
				)
			}
	}

	@Test
	fun `every captured ownership privacy clock and zone field uses historical authority`() {
		val expected = authority()
		val mutations = listOf<Pair<String, (LocationCaptureAuthority) -> LocationCaptureAuthority>>(
			"logical tracking" to { it.copy(logicalTrackingId = LogicalTrackingId("logical-2")) },
			"service run" to { it.copy(serviceRunId = ServiceRunId("run-2")) },
			"segment" to { it.copy(sessionSegmentId = 2L) },
			"capture set" to { it.copy(capturedSources = setOf(SourceKind.LOCATION, SourceKind.STEPS)) },
			"control set" to { it.copy(controlSources = emptySet()) },
			"source instance" to { it.copy(sourceInstanceId = SourceInstanceId("location-2")) },
			"registration generation" to { it.copy(registrationGeneration = 2L) },
			"configuration revision" to { it.copy(configurationRevision = 2L) },
			"physical fingerprint" to { it.copy(physicalConfigurationFingerprint = "physical-2") },
			"authorization revision" to { it.copy(authorizationRevision = 2L) },
			"authorization fingerprint" to { it.copy(authorizationFingerprint = "auth-2") },
			"purpose mask" to {
				it.copy(
					purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE or
						SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
				)
			},
			"policy" to { it.copy(sourcePolicyRevision = 2L) },
			"consent" to { it.copy(captureConsentEpoch = 2L) },
			"manifest" to { it.copy(sessionManifestRevision = 2L) },
			"lease" to { it.copy(lifecycleLeaseGeneration = 2L) },
			"captured epoch" to { it.copy(capturedCollectedDataEpoch = 2L) },
			"boot clock" to { it.copy(clockDomainId = "boot-2") },
			"stored zone" to { it.copy(zoneId = "Europe/London") },
			"permission" to { it.copy(permissionPrecision = LocationPermissionPrecision.APPROXIMATE) },
			"temporal authority" to {
				it.copy(
					temporalAuthority = it.temporalAuthority.copy(
						authorization = LocationProviderTimeInterval(1_001L, 10_000L),
					),
				)
			},
			"acquisition limits" to {
				it.copy(
					acquisitionConfiguration = it.acquisitionConfiguration.copy(
						maximumHorizontalAccuracyMeters = 30f,
					),
				)
			},
		)

		mutations.forEach { (name, mutation) ->
			val captured = mutation(expected)
			assertEquals(
				LocationObservationQualification.Rejected(LocationFactRejection.AUTHORITY_MISMATCH),
				qualify(input(captured), expected),
				name,
			)
		}
	}

	@Test
	fun `WAL admission payload delivery position and clock binding must be explicit`() {
		val authority = authority()
		val baseline = evidence(authority)
		val cases = listOf(
			baseline.copy(sourceAdmissionOrdinal = 0L) to
				LocationFactRejection.INCOMPLETE_DURABLE_EVIDENCE,
			baseline.copy(walIntegrityIdentity = "B".repeat(64)) to
				LocationFactRejection.WAL_INTEGRITY_UNVERIFIABLE,
			baseline.copy(sourceDeliveryIdentity = null) to
				LocationFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE,
			baseline.copy(payloadVersion = 1) to LocationFactRejection.UNSUPPORTED_PAYLOAD_VERSION,
			baseline.copy(payload = baseline.payload.copy(isMock = null)) to
				LocationFactRejection.MOCK_PROVENANCE_MISMATCH,
			baseline.copy(isMock = !baseline.isMock) to
				LocationFactRejection.MOCK_PROVENANCE_MISMATCH,
			baseline.copy(deliveryUnitIndex = 1, deliveryUnitCount = 1) to
				LocationFactRejection.INVALID_DELIVERY_POSITION,
		)

		cases.forEach { (candidate, rejection) ->
			assertRejected(input(authority, candidate), authority, rejection)
		}
	}

	@Test
	fun `exact replay is duplicate while changed evidence under delivery identity collides`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = qualified(originalInput, authority)

		val replay = assertIs<LocationObservationQualification.Duplicate>(
			qualify(originalInput, authority, priorDeliveryFact = original),
		)
		assertSame(original, replay.existing)
		val changed = originalInput.copy(
			durableEvidence = requireNotNull(originalInput.durableEvidence).copy(
				payload = payload().copy(latitudeDegrees = 50.0001),
			),
		)
		assertRejected(
			changed,
			authority,
			LocationFactRejection.DELIVERY_IDENTITY_COLLISION,
			priorDeliveryFact = original,
		)
	}

	@Test
	fun `corrections are derived-only and retain exact raw evidence and captured authority`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = qualified(originalInput, authority)
		val rawChanges = listOf(
			requireNotNull(originalInput.durableEvidence).copy(
				payload = payload().copy(latitudeDegrees = 50.0001),
			),
			requireNotNull(originalInput.durableEvidence).copy(
				clockAuthority = requireNotNull(originalInput.durableEvidence).clockAuthority.copy(
					observedWallTimeMs = 101L,
				),
			),
			requireNotNull(originalInput.durableEvidence).copy(
				quality = SourceQuality(flags = setOf(SourceQualityFlag.PROVIDER_DEGRADED)),
			),
			requireNotNull(originalInput.durableEvidence).copy(
				payload = requireNotNull(originalInput.durableEvidence).payload.copy(isMock = true),
				isMock = true,
			),
		)

		rawChanges.forEach { changedEvidence ->
			assertRejected(
				input(authority, changedEvidence),
				authority,
				LocationFactRejection.RAW_EVIDENCE_CHANGED,
				semanticRevision = 2L,
				supersedesSemanticRevision = 1L,
				correctionBase = original,
			)
		}

		val corrected = assertIs<LocationObservationQualification.Qualified>(
			qualify(
				originalInput,
				authority,
				qualifierVersion = 2,
				semanticRevision = 2L,
				supersedesSemanticRevision = 1L,
				correctionBase = original,
			),
		).command
		assertEquals(original.mutation.identity, corrected.mutation.identity)
		assertEquals(original.productEffect.durableEvidence, corrected.productEffect.durableEvidence)
		assertEquals(2, corrected.productEffect.derivedQualification.qualifierVersion)

		val noOp = assertIs<LocationObservationQualification.Duplicate>(
			qualify(
				originalInput,
				authority,
				semanticRevision = 2L,
				supersedesSemanticRevision = 1L,
				correctionBase = original,
			),
		)
		assertSame(original, noOp.existing)
	}

	@Test
	fun `missing noncontiguous or authority-rotated correction base is rejected`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = qualified(originalInput, authority)

		assertRejected(
			originalInput,
			authority,
			LocationFactRejection.INVALID_CORRECTION_BASE,
			qualifierVersion = 2,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
		)
		assertRejected(
			originalInput,
			authority,
			LocationFactRejection.INVALID_CORRECTION_BASE,
			qualifierVersion = 2,
			semanticRevision = 3L,
			supersedesSemanticRevision = 2L,
			correctionBase = original,
		)

		val rotated = authority.copy(
			authorizationRevision = 2L,
			authorizationFingerprint = "authorization-2",
		)
		assertRejected(
			input(rotated),
			rotated,
			LocationFactRejection.INVALID_CORRECTION_BASE,
			qualifierVersion = 2,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			correctionBase = original,
		)
	}

	private fun qualified(
		input: LocationObservationInput,
		authority: LocationCaptureAuthority,
	): LocationCapturedFactCommand = assertIs<LocationObservationQualification.Qualified>(
		qualify(input, authority),
	).command

	private fun qualify(
		input: LocationObservationInput,
		authority: LocationCaptureAuthority,
		deletionAuthority: LocationDeletionAuthority = deletion(),
		qualifierVersion: Int = 1,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		priorDeliveryFact: LocationCapturedFactCommand? = null,
		correctionBase: LocationCapturedFactCommand? = null,
	): LocationObservationQualification = LocationQualifiedObservationQualifier.qualify(
		input = input,
		expectedAuthority = authority,
		currentDeletionAuthority = deletionAuthority,
		qualifierVersion = qualifierVersion,
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = supersedesSemanticRevision,
		priorDeliveryFact = priorDeliveryFact,
		correctionBase = correctionBase,
	)

	private fun assertRejected(
		input: LocationObservationInput,
		authority: LocationCaptureAuthority,
		reason: LocationFactRejection,
		deletionAuthority: LocationDeletionAuthority = deletion(),
		qualifierVersion: Int = 1,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		priorDeliveryFact: LocationCapturedFactCommand? = null,
		correctionBase: LocationCapturedFactCommand? = null,
	) {
		assertEquals(
			LocationObservationQualification.Rejected(reason),
			qualify(
				input = input,
				authority = authority,
				deletionAuthority = deletionAuthority,
				qualifierVersion = qualifierVersion,
				semanticRevision = semanticRevision,
				supersedesSemanticRevision = supersedesSemanticRevision,
				priorDeliveryFact = priorDeliveryFact,
				correctionBase = correctionBase,
			),
		)
	}

	private fun authority(
		capturedSources: Set<SourceKind> = setOf(SourceKind.LOCATION),
		controlSources: Set<SourceKind> = setOf(SourceKind.ACTIVITY),
		permissionPrecision: LocationPermissionPrecision = LocationPermissionPrecision.PRECISE,
	): LocationCaptureAuthority = LocationCaptureAuthority(
		logicalTrackingId = LogicalTrackingId("logical-1"),
		serviceRunId = ServiceRunId("run-1"),
		sessionSegmentId = 1L,
		capturedSources = capturedSources,
		controlSources = controlSources,
		sourceInstanceId = SourceInstanceId("location-1"),
		registrationGeneration = 1L,
		configurationRevision = 1L,
		physicalConfigurationFingerprint = "physical-1",
		authorizationRevision = 1L,
		authorizationFingerprint = "authorization-1",
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		sessionManifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		capturedCollectedDataEpoch = 1L,
		clockDomainId = "boot-1",
		zoneId = "Europe/Prague",
		permissionPrecision = permissionPrecision,
		temporalAuthority = LocationCaptureTemporalAuthority(
			providerRegistration = LocationProviderTimeInterval(1_000L, 10_000L),
			authorization = LocationProviderTimeInterval(900L, 11_000L),
			sourcePolicy = LocationProviderTimeInterval(800L, 12_000L),
			captureConsent = LocationProviderTimeInterval(700L, 13_000L),
			sessionManifest = LocationProviderTimeInterval(600L, 14_000L),
			lifecycleLease = LocationProviderTimeInterval(500L, 15_000L),
		),
		acquisitionConfiguration = LocationHistoricalAcquisitionConfiguration(
			maximumObservationAgeNanos = 1_000L,
			maximumHorizontalAccuracyMeters = 25f,
		),
	)

	private fun deletion(
		epoch: Long = 1L,
		retainedFromWallTimeMs: Long? = 90L,
	): LocationDeletionAuthority = LocationDeletionAuthority(
		currentCollectedDataEpoch = epoch,
		retainedFromWallTimeMs = retainedFromWallTimeMs,
	)

	private fun input(
		authority: LocationCaptureAuthority,
		durableEvidence: LocationDurableObservationEvidence? = evidence(authority),
		outcome: LocationProviderOutcome = LocationProviderOutcome.FIX,
	): LocationObservationInput = LocationObservationInput(
		origin = LocationObservationOrigin.PROVIDER_CALLBACK,
		outcome = outcome,
		attemptedAuthority = authority,
		durableEvidence = durableEvidence,
	)

	private fun evidence(
		authority: LocationCaptureAuthority,
		payload: LocationFixPayload = payload(),
		quality: SourceQuality = SourceQuality(),
		isMock: Boolean = false,
		observedNanos: Long = 5_000L,
		receivedNanos: Long = 5_500L,
		observedWallTimeMs: Long = 100L,
		wallTimeUncertaintyMs: Long = 10L,
	): LocationDurableObservationEvidence = LocationDurableObservationEvidence(
		sourceEventId = SourceEventId("location-event-1"),
		sourceAdmissionOrdinal = 7L,
		walIntegrityIdentity = "b".repeat(64),
		sourceDeliveryIdentity = SourceDeliveryIdentity("a".repeat(64)),
		deliveryUnitIndex = 0,
		deliveryUnitCount = 1,
		capturedAuthority = authority,
		clockAuthority = LocationDurableClockAuthority(
			clockDomainId = authority.clockDomainId,
			observedElapsedRealtimeNanos = observedNanos,
			receivedElapsedRealtimeNanos = receivedNanos,
			observedWallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		),
		payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
		payload = payload.copy(isMock = isMock),
		quality = quality,
		isMock = isMock,
	)

	private fun payload(): LocationFixPayload = LocationFixPayload(
		latitudeDegrees = 50.0,
		longitudeDegrees = 14.0,
		horizontalAccuracyMeters = 10f,
		altitudeMeters = 250.0,
		verticalAccuracyMeters = 3f,
		speedMetersPerSecond = 1f,
		bearingDegrees = 90f,
		provider = "fused",
	)
}
