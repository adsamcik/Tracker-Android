package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrackingEnrichmentContractTest {
	@Test
	fun `enrichment cannot request acquisition or provider demand`() {
		val request = request(setOf(HistorySource.LOCATION))
		val rejected = TrackingEnrichmentSourceResult.Rejected(
			HistorySource.LOCATION,
			TrackingEnrichmentRejectionReason.ACQUISITION_REQUIRED,
		)
		val snapshot = TrackingEnrichmentSnapshot(
			request,
			request.primary.readSnapshot,
			listOf(rejected),
		)

		assertEquals(
			listOf(TrackingEnrichmentAccess.ALREADY_COLLECTED_COMPATIBLE_FACTS_ONLY),
			TrackingEnrichmentAccess.entries,
		)
		assertEquals(TrackingEnrichmentAccess.ALREADY_COLLECTED_COMPATIBLE_FACTS_ONLY, request.access)
		assertEquals(TrackingEnrichmentRejectionReason.ACQUISITION_REQUIRED, rejected.reason)
		assertEquals(listOf(rejected), snapshot.results)
	}

	@Test
	fun `control is excluded from enrichment eligibility`() {
		assertEquals(
			listOf(
				TrackingEnrichmentPurpose.SESSION_CAPTURE,
				TrackingEnrichmentPurpose.AMBIENT_PRODUCT,
			),
			TrackingEnrichmentPurpose.entries,
		)
		assertFailsWith<IllegalArgumentException> {
			TrackingEnrichmentEligibility(
				purpose = TrackingEnrichmentPurpose.SESSION_CAPTURE,
				consentEpoch = 5L,
			)
		}
	}

	@Test
	fun `enrichment source sets remain isolated from the primary and each other`() {
		assertFailsWith<IllegalArgumentException> {
			request(setOf(HistorySource.WIFI))
		}
		val locationRequest = request(setOf(HistorySource.LOCATION))
		assertFailsWith<IllegalArgumentException> {
			TrackingEnrichmentSnapshot(
				locationRequest,
				TrackingProductReadSnapshot(3L, 8L),
				listOf(TrackingEnrichmentSourceResult.NoCompatibleFacts(HistorySource.LOCATION)),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingEnrichmentSnapshot(
				locationRequest,
				locationRequest.primary.readSnapshot,
				listOf(TrackingEnrichmentSourceResult.NoCompatibleFacts(HistorySource.CELL)),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingEnrichmentSnapshot(
				locationRequest,
				locationRequest.primary.readSnapshot,
				listOf(
					TrackingEnrichmentSourceResult.NoCompatibleFacts(HistorySource.LOCATION),
					TrackingEnrichmentSourceResult.Rejected(
						HistorySource.LOCATION,
						TrackingEnrichmentRejectionReason.CONFLICT,
					),
				),
			)
		}
	}

	@Test
	fun `only compatible same-session facts enter the immutable snapshot`() {
		val request = request(setOf(HistorySource.LOCATION))
		val compatible = locationFact(
			TrackingEnrichmentEligibility(
				purpose = TrackingEnrichmentPurpose.SESSION_CAPTURE,
				consentEpoch = 5L,
				logicalSessionIdentity = identity("session"),
			),
		)
		val result = TrackingEnrichmentSourceResult.Location(
			facts = listOf(compatible),
			metadata = LocationEnrichmentResultMetadata(1, EpochMs(120L)),
		)

		assertEquals(
			listOf(result),
			TrackingEnrichmentSnapshot(
				request,
				request.primary.readSnapshot,
				listOf(result),
			).results,
		)

		val incompatible = locationFact(
			TrackingEnrichmentEligibility(
				purpose = TrackingEnrichmentPurpose.SESSION_CAPTURE,
				consentEpoch = 5L,
				logicalSessionIdentity = identity("other-session"),
			),
		)
		assertFailsWith<IllegalArgumentException> {
			TrackingEnrichmentSnapshot(
				request,
				request.primary.readSnapshot,
				listOf(
					TrackingEnrichmentSourceResult.Location(
						listOf(incompatible),
						LocationEnrichmentResultMetadata(1, EpochMs(120L)),
					),
				),
			)
		}
		val rejected = TrackingEnrichmentSourceResult.Rejected(
			HistorySource.LOCATION,
			TrackingEnrichmentRejectionReason.OUTSIDE_AUTHORITY,
		)
		assertEquals(
			rejected,
			TrackingEnrichmentSnapshot(
				request,
				request.primary.readSnapshot,
				listOf(rejected),
			).results.single(),
		)
	}

	@Test
	fun `ambient compatibility retains exact structural zone authority`() {
		val day = TrackingProductStructuralDay(10L, "Europe/Prague")
		val eligibility = TrackingEnrichmentEligibility(
			purpose = TrackingEnrichmentPurpose.AMBIENT_PRODUCT,
			consentEpoch = 9L,
			structuralDay = day,
		)
		val identity = identity("ambient")
		val matching = TrackingProductOrigin.Ambient(day, identity, 9L)
		val currentZoneSubstitution = TrackingProductOrigin.Ambient(
			TrackingProductStructuralDay(10L, "UTC"),
			identity,
			9L,
		)

		assertEquals(true, eligibility.isCompatibleWith(matching))
		assertEquals(false, eligibility.isCompatibleWith(currentZoneSubstitution))
	}

	private fun request(
		requestedSources: Set<HistorySource>,
	): TrackingEnrichmentRequest {
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		return TrackingEnrichmentRequest(
			primary = TrackingProductSelectionAuthority(
				source = HistorySource.WIFI,
				origin = TrackingProductOrigin.Session(identity("session"), 5L),
				selection = TestSelection("wifi-session"),
				producerRevision = 11L,
				contentChecksum = checksum("wifi"),
				readSnapshot = snapshot,
			),
			observedRange = TrackingProductQueryScope.WallRange(EpochMs(100L), EpochMs(200L)),
			requestedSources = requestedSources,
		)
	}

	private fun locationFact(
		eligibility: TrackingEnrichmentEligibility,
	) = LocationEnrichmentFact(
		authority = TrackingEnrichmentFactAuthority(
			identity = identity("location-fact"),
			observedFromInclusive = EpochMs(120L),
			observedToExclusive = EpochMs(121L),
			eligibility = eligibility,
		),
		location = Location(
			time = 120L,
			latitude = 50.0,
			longitude = 14.0,
			altitude = null,
			horizontalAccuracy = 5f,
			verticalAccuracy = null,
			speed = null,
			speedAccuracy = null,
		),
	)

	private fun identity(seed: String) =
		TrackingProductIdentity("sha256:" + opaqueDigest(seed))

	private fun checksum(seed: String) =
		TrackingProductChecksum("sha256:" + opaqueDigest(seed))

	private data class TestSelection(val value: String) : TrackingProductSelection

	private fun opaqueDigest(seed: String): String {
		val hexadecimal = "0123456789abcdef"
		val encoded = seed.flatMap { character ->
			listOf(
				hexadecimal[(character.code ushr 4) and 0xf],
				hexadecimal[character.code and 0xf],
			)
		}.joinToString(separator = "")
		return encoded.padEnd(64, '0').take(64)
	}
}
