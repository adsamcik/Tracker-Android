package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.Test

class LocationQualifiedObservationQualifierTest {
	@Test
	fun `location-only capture qualifies without another source or sample-count evidence`() {
		val authority = authority(
			capturedSources = setOf(SourceKind.LOCATION),
			controlSources = emptySet(),
		)

		val result = assertIs<LocationObservationQualification.Qualified>(
			LocationQualifiedObservationQualifier.qualify(input(authority), authority),
		)

		assertEquals(setOf(SourceKind.LOCATION), result.command.authority.capturedSources)
		assertEquals(emptySet(), result.command.authority.controlSources)
		assertEquals(LocationPermissionPrecision.PRECISE, result.command.authority.permissionPrecision)
		assertEquals(500L, result.command.productEffect.deliveryAgeNanos)
		assertEquals(10f, result.command.productEffect.payload.horizontalAccuracyMeters)
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
	fun `provider time must be positive nonfuture and inside every effective authority`() {
		val authority = authority()

		assertRejected(
			input(authority).copy(observedElapsedRealtimeNanos = 0L),
			authority,
			LocationFactRejection.NON_POSITIVE_PROVIDER_TIME,
		)
		assertRejected(
			input(authority).copy(
				observedElapsedRealtimeNanos = 5_501L,
				receivedElapsedRealtimeNanos = 5_500L,
			),
			authority,
			LocationFactRejection.FUTURE_PROVIDER_TIME,
		)
		assertEquals(
			LocationObservationQualification.Stale(LocationStaleReason.PRE_EFFECTIVE),
			LocationQualifiedObservationQualifier.qualify(
				input(authority).copy(
					observedElapsedRealtimeNanos = 999L,
					receivedElapsedRealtimeNanos = 1_100L,
				),
				authority,
			),
		)
		assertRejected(
			input(authority).copy(
				observedElapsedRealtimeNanos = 10_000L,
				receivedElapsedRealtimeNanos = 10_000L,
			),
			authority,
			LocationFactRejection.AFTER_AUTHORITY,
		)
	}

	@Test
	fun `old observations and fixes before the deletion floor are typed stale`() {
		val authority = authority()

		assertEquals(
			LocationObservationQualification.Stale(LocationStaleReason.TOO_OLD),
			LocationQualifiedObservationQualifier.qualify(
				input(authority).copy(receivedElapsedRealtimeNanos = 6_001L),
				authority,
			),
		)
		assertEquals(
			LocationObservationQualification.Stale(LocationStaleReason.BEFORE_DELETION_FLOOR),
			LocationQualifiedObservationQualifier.qualify(
				input(authority).copy(observedWallTimeMs = 99L),
				authority,
			),
		)
	}

	@Test
	fun `accuracy and coordinates are qualified against captured acquisition limits`() {
		val authority = authority()

		assertRejected(
			input(authority, payload = payload().copy(horizontalAccuracyMeters = Float.NaN)),
			authority,
			LocationFactRejection.INVALID_ACCURACY,
		)
		assertRejected(
			input(authority, payload = payload().copy(horizontalAccuracyMeters = 25.01f)),
			authority,
			LocationFactRejection.INSUFFICIENT_ACCURACY,
		)
		assertRejected(
			input(authority, payload = payload().copy(latitudeDegrees = 90.01)),
			authority,
			LocationFactRejection.INVALID_COORDINATE,
		)
	}

	@Test
	fun `no-fix provider permission and failure outcomes remain typed unavailable`() {
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
				LocationQualifiedObservationQualifier.qualify(
					input(authority).copy(outcome = outcome, payload = null),
					authority,
				),
			)
		}

		val staleGeneration = authority.copy(registrationGeneration = 2L)
		assertRejected(
			input(staleGeneration).copy(
				outcome = LocationProviderOutcome.PERMISSION_UNAVAILABLE,
				payload = null,
			),
			authority,
			LocationFactRejection.AUTHORITY_MISMATCH,
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
	fun `every captured ownership and privacy field must match exact historical authority`() {
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
			"authorization fingerprint" to { it.copy(authorizationFingerprint = "authorization-2") },
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
			"deletion epoch" to {
				it.copy(deletion = it.deletion.copy(collectedDataEpoch = 2L))
			},
			"deletion floor" to {
				it.copy(deletion = it.deletion.copy(retainedFromWallTimeMs = 200L))
			},
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
				LocationQualifiedObservationQualifier.qualify(input(captured), expected),
				name,
			)
		}
	}

	@Test
	fun `fact identity clock and delivery position must be explicit`() {
		val authority = authority()

		assertRejected(
			input(authority).copy(sourceDeliveryIdentity = null),
			authority,
			LocationFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE,
		)
		assertRejected(
			input(authority).copy(deliveryUnitIndex = 1, deliveryUnitCount = 1),
			authority,
			LocationFactRejection.INVALID_DELIVERY_POSITION,
		)
		assertRejected(
			input(authority).copy(observedWallTimeMs = null),
			authority,
			LocationFactRejection.CLOCK_UNVERIFIABLE,
		)
		assertRejected(
			input(authority, payload = payload().copy(provider = "")),
			authority,
			LocationFactRejection.INVALID_PROVIDER,
		)
	}

	@Test
	fun `exact replay is duplicate while changed full product effect is an identity collision`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = qualified(originalInput, authority)

		val replay = assertIs<LocationObservationQualification.Duplicate>(
			LocationQualifiedObservationQualifier.qualify(
				originalInput,
				authority,
				priorDeliveryFact = original,
			),
		)
		assertSame(original, replay.existing)
		val originalWallTimeMs = requireNotNull(originalInput.observedWallTimeMs)

		assertRejected(
			input = originalInput.copy(observedWallTimeMs = originalWallTimeMs + 1L),
			authority = authority,
			reason = LocationFactRejection.DELIVERY_IDENTITY_COLLISION,
			priorDeliveryFact = original,
		)
	}

	@Test
	fun `correction keeps stable identity and exact immutable authority`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = qualified(originalInput, authority)
		val correctedInput = originalInput.copy(
			payload = requireNotNull(originalInput.payload).copy(latitudeDegrees = 50.0001),
		)

		val correction = assertIs<LocationObservationQualification.Qualified>(
			LocationQualifiedObservationQualifier.qualify(
				input = correctedInput,
				expectedAuthority = authority,
				semanticRevision = 2L,
				supersedesSemanticRevision = 1L,
				correctionBase = original,
			),
		).command

		assertEquals(original.mutation.identity, correction.mutation.identity)
		assertEquals(2L, correction.mutation.semanticRevision)
		assertEquals(1L, correction.mutation.supersedesSemanticRevision)
		assertEquals(authority, correction.authority)

		val rotated = authority.copy(
			authorizationRevision = 2L,
			authorizationFingerprint = "authorization-2",
		)
		assertRejected(
			input = correctedInput.copy(capturedAuthority = rotated),
			authority = rotated,
			reason = LocationFactRejection.INVALID_CORRECTION_BASE,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			correctionBase = original,
		)
	}

	@Test
	fun `semantic no-op correction produces no new command`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = qualified(originalInput, authority)

		val duplicate = assertIs<LocationObservationQualification.Duplicate>(
			LocationQualifiedObservationQualifier.qualify(
				input = originalInput,
				expectedAuthority = authority,
				semanticRevision = 2L,
				supersedesSemanticRevision = 1L,
				correctionBase = original,
			),
		)

		assertSame(original, duplicate.existing)
	}

	@Test
	fun `missing or noncontiguous correction base is rejected`() {
		val authority = authority()
		val originalInput = input(authority)
		val original = qualified(originalInput, authority)

		assertRejected(
			input = originalInput.copy(payload = payload().copy(latitudeDegrees = 50.1)),
			authority = authority,
			reason = LocationFactRejection.INVALID_CORRECTION_BASE,
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
		)
		assertRejected(
			input = originalInput.copy(payload = payload().copy(latitudeDegrees = 50.2)),
			authority = authority,
			reason = LocationFactRejection.INVALID_CORRECTION_BASE,
			semanticRevision = 3L,
			supersedesSemanticRevision = 2L,
			correctionBase = original,
		)
	}

	private fun qualified(
		input: LocationObservationInput,
		authority: LocationCaptureAuthority,
	): LocationCapturedFactCommand =
		assertIs<LocationObservationQualification.Qualified>(
			LocationQualifiedObservationQualifier.qualify(input, authority),
		).command

	private fun assertRejected(
		input: LocationObservationInput,
		authority: LocationCaptureAuthority,
		reason: LocationFactRejection,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		priorDeliveryFact: LocationCapturedFactCommand? = null,
		correctionBase: LocationCapturedFactCommand? = null,
	) {
		assertEquals(
			LocationObservationQualification.Rejected(reason),
			LocationQualifiedObservationQualifier.qualify(
				input = input,
				expectedAuthority = authority,
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
		deletion = LocationDeletionAuthority(
			collectedDataEpoch = 1L,
			retainedFromWallTimeMs = 100L,
		),
		clockDomainId = "boot-1",
		zoneId = "Europe/Prague",
		permissionPrecision = LocationPermissionPrecision.PRECISE,
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

	private fun input(
		authority: LocationCaptureAuthority,
		payload: LocationFixPayload = payload(),
	): LocationObservationInput = LocationObservationInput(
		origin = LocationObservationOrigin.PROVIDER_CALLBACK,
		outcome = LocationProviderOutcome.FIX,
		capturedAuthority = authority,
		sourceDeliveryIdentity = SourceDeliveryIdentity("a".repeat(64)),
		deliveryUnitIndex = 0,
		deliveryUnitCount = 1,
		observedElapsedRealtimeNanos = 5_000L,
		receivedElapsedRealtimeNanos = 5_500L,
		observedWallTimeMs = 1_000L,
		wallTimeUncertaintyMs = 10L,
		payload = payload,
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
