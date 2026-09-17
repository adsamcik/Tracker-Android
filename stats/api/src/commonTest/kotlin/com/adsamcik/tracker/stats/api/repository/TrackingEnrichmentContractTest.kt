package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingEnrichmentContractTest {
	@Test
	fun `enrichment exposes no acquisition or control authority`() {
		val request = request(setOf(HistorySource.LOCATION))
		val rejected = TrackingEnrichmentSourceResult.Rejected(
			HistorySource.LOCATION,
			TrackingEnrichmentRejectionReason.ACQUISITION_REQUIRED,
		)

		assertEquals(
			listOf(TrackingEnrichmentAccess.ALREADY_COLLECTED_COMPATIBLE_FACTS_ONLY),
			TrackingEnrichmentAccess.entries,
		)
		assertEquals(
			listOf(
				TrackingEnrichmentPurpose.SESSION_CAPTURE,
				TrackingEnrichmentPurpose.AMBIENT_PRODUCT,
			),
			TrackingEnrichmentPurpose.entries,
		)
		assertEquals(
			TrackingEnrichmentRejectionReason.ACQUISITION_REQUIRED,
			TrackingEnrichmentSnapshot(
				request,
				request.primary.readSnapshot,
				listOf(rejected),
			).results.single().let { it as TrackingEnrichmentSourceResult.Rejected }.reason,
		)
	}

	@Test
	fun `request derives exact selection scope and widening is outside authority`() {
		val request = request(setOf(HistorySource.LOCATION))
		assertEquals(request.primary.authorityScope, request.authorityScope)

		val widened = locationFact(
			from = 90L,
			to = 110L,
			sessionIdentity = request.primary.origin.identity,
		)
		assertEquals(
			TrackingEnrichmentRejectionReason.OUTSIDE_AUTHORITY,
			widened.authority.rejectionAgainst(request.primary),
		)
		assertFailsWith<IllegalArgumentException> {
			TrackingEnrichmentSnapshot(
				request,
				request.primary.readSnapshot,
				listOf(
					TrackingEnrichmentSourceResult.Location(
						listOf(widened),
						LocationEnrichmentResultMetadata(1, EpochMs(100L)),
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
	fun `source sets and snapshot collections are defensively immutable`() {
		val sourceInput = mutableSetOf(HistorySource.LOCATION, HistorySource.CELL)
		val request = request(sourceInput)
		sourceInput.clear()
		assertEquals(setOf(HistorySource.LOCATION, HistorySource.CELL), request.requestedSources)
		(request.requestedSources as MutableSet<*>).clear()
		assertEquals(setOf(HistorySource.LOCATION, HistorySource.CELL), request.requestedSources)

		val resultInput = mutableListOf<TrackingEnrichmentSourceResult>(
			TrackingEnrichmentSourceResult.NoCompatibleFacts(HistorySource.LOCATION),
			TrackingEnrichmentSourceResult.NoCompatibleFacts(HistorySource.CELL),
		)
		val snapshot = TrackingEnrichmentSnapshot(
			request,
			request.primary.readSnapshot,
			resultInput,
		)
		resultInput.clear()
		assertEquals(2, snapshot.results.size)
		(snapshot.results as MutableList<*>).clear()
		assertEquals(2, snapshot.results.size)

		val factsInput = mutableListOf(
			locationFact(110L, 111L, request.primary.origin.identity, "first"),
			locationFact(120L, 121L, request.primary.origin.identity, "second"),
		)
		val locationResult = TrackingEnrichmentSourceResult.Location(
			factsInput,
			LocationEnrichmentResultMetadata(2, EpochMs(120L)),
		)
		factsInput.clear()
		assertEquals(2, locationResult.facts.size)
		(locationResult.facts as MutableList<*>).clear()
		assertEquals(2, locationResult.facts.size)

		val bandInput = mutableMapOf(
			WifiHistoryBand.TWO_POINT_FOUR_GHZ to 1,
			WifiHistoryBand.FIVE_GHZ to 1,
		)
		val wifi = WifiEnrichmentObservation(
			intervalStartTime = EpochMs(110L),
			observedTime = EpochMs(120L),
			wallTimeUncertaintyMs = 0L,
			resultCompleteness = WifiHistoryResultCompleteness.COMPLETE,
			observationCount = 2,
			bandMix = bandInput,
			signalQuality = WifiHistorySignalQuality(-40, -60, -50.0, 2),
			sourceQualityFlags = 0L,
			sourceQualityConfidence = 1f,
			storedZoneId = "UTC",
		)
		bandInput.clear()
		assertEquals(2, wifi.bandMix.size)
		(wifi.bandMix as MutableMap<*, *>).clear()
		assertEquals(2, wifi.bandMix.size)

		val technologyInput = mutableMapOf(
			CellHistoryTechnology.LTE to 1,
			CellHistoryTechnology.NR to 1,
		)
		val cell = CellEnrichmentObservation(
			intervalStartTime = EpochMs(110L),
			observedTime = EpochMs(120L),
			wallTimeUncertaintyMs = 0L,
			childCompleteness = CellHistoryChildCompleteness.COMPLETE,
			observationCount = 2,
			technologyMix = technologyInput,
			signalQuality = CellHistorySignalQuality(0, 0, 0, 0, 1, 1),
			sourceQualityFlags = 0L,
			sourceQualityConfidence = 1f,
			storedZoneId = "UTC",
		)
		technologyInput.clear()
		assertEquals(2, cell.technologyMix.size)
		(cell.technologyMix as MutableMap<*, *>).clear()
		assertEquals(2, cell.technologyMix.size)
	}

	@Test
	fun `enrichment source results remain isolated from primary and each other`() {
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
	fun `same-session and ambient structural-zone compatibility fail closed`() {
		val sessionRequest = request(setOf(HistorySource.LOCATION))
		val compatible = locationFact(
			110L,
			111L,
			sessionRequest.primary.origin.identity,
		)
		val otherSession = locationFact(
			110L,
			111L,
			identity("other-session"),
		)
		assertEquals(null, compatible.authority.rejectionAgainst(sessionRequest.primary))
		assertEquals(
			TrackingEnrichmentRejectionReason.OUTSIDE_AUTHORITY,
			otherSession.authority.rejectionAgainst(sessionRequest.primary),
		)

		val day = TrackingProductStructuralDay(10L, "Europe/Prague")
		val ambientPrimary = primary(
			origin = TrackingProductTrustedCapabilities.ambientOrigin(
				identity("ambient"),
				9L,
				day,
			),
			scope = TrackingProductAuthorityScope(wall(100L, 200L), setOf(day)),
		)
		val matching = TrackingEnrichmentEligibility(
			TrackingEnrichmentPurpose.AMBIENT_PRODUCT,
			9L,
			structuralDay = day,
		)
		val currentZoneSubstitution = TrackingEnrichmentEligibility(
			TrackingEnrichmentPurpose.AMBIENT_PRODUCT,
			9L,
			structuralDay = TrackingProductStructuralDay(10L, "UTC"),
		)
		assertTrue(matching.isCompatibleWith(ambientPrimary.origin))
		assertFalse(currentZoneSubstitution.isCompatibleWith(ambientPrimary.origin))
	}

	@Test
	fun `sparse individually complete Steps facts produce aggregate gap`() {
		val request = request(setOf(HistorySource.STEPS))
		val sessionIdentity = request.primary.origin.identity
		val facts = listOf(
			stepsFact(100L, 125L, sessionIdentity, "steps-first"),
			stepsFact(175L, 200L, sessionIdentity, "steps-second"),
		)
		val range = wall(100L, 200L)
		assertEquals(
			TrackingEnrichmentIntervalUnionResult.Covered(
				TrackingEnrichmentAggregateCoverage.GAP,
			),
			trackingEnrichmentIntervalUnionCoverage(
				range,
				facts.map {
					TrackingEnrichmentInterval(
						it.authority.observedFromInclusive,
						it.authority.observedToExclusive,
					)
				},
			),
		)
		assertEquals(
			TrackingEnrichmentRejectionReason.CONFLICT,
			(TrackingEnrichmentSourceResult.Steps.create(
				facts,
				StepsEnrichmentResultMetadata(
					2,
					range,
					TrackingEnrichmentAggregateCoverage.COMPLETE,
				),
			) as TrackingEnrichmentSourceResult.Rejected).reason,
		)
		val result = TrackingEnrichmentSourceResult.Steps.create(
			facts,
			StepsEnrichmentResultMetadata(
				2,
				range,
				TrackingEnrichmentAggregateCoverage.GAP,
			),
		) as TrackingEnrichmentSourceResult.Steps
		assertEquals(
			TrackingEnrichmentAggregateCoverage.GAP,
			result.metadata.aggregateCoverage,
		)
		TrackingEnrichmentSnapshot(
			request,
			request.primary.readSnapshot,
			listOf(result),
		)
	}

	@Test
	fun `sparse individually complete Pressure windows produce aggregate gap`() {
		val request = request(setOf(HistorySource.PRESSURE))
		val sessionIdentity = request.primary.origin.identity
		val facts = listOf(
			pressureFact(100L, 125L, sessionIdentity, "pressure-first"),
			pressureFact(175L, 200L, sessionIdentity, "pressure-second"),
		)
		val range = wall(100L, 200L)
		assertEquals(
			TrackingEnrichmentRejectionReason.CONFLICT,
			(TrackingEnrichmentSourceResult.Pressure.create(
				facts,
				PressureEnrichmentResultMetadata(
					2,
					range,
					TrackingEnrichmentAggregateCoverage.COMPLETE,
				),
			) as TrackingEnrichmentSourceResult.Rejected).reason,
		)
		val result = TrackingEnrichmentSourceResult.Pressure.create(
			facts,
			PressureEnrichmentResultMetadata(
				2,
				range,
				TrackingEnrichmentAggregateCoverage.GAP,
			),
		) as TrackingEnrichmentSourceResult.Pressure
		assertEquals(
			TrackingEnrichmentAggregateCoverage.GAP,
			result.metadata.aggregateCoverage,
		)
		TrackingEnrichmentSnapshot(
			request,
			request.primary.readSnapshot,
			listOf(result),
		)
	}

	private fun request(
		requestedSources: Set<HistorySource>,
	): TrackingEnrichmentRequest =
		TrackingEnrichmentRequest(
			primary = primary(
				origin = TrackingProductTrustedCapabilities.sessionOrigin(
					identity("session"),
					5L,
				),
				scope = TrackingProductAuthorityScope(wall(100L, 200L), emptySet()),
			),
			requestedSources = requestedSources,
		)

	private fun primary(
		origin: TrackingProductOrigin,
		scope: TrackingProductAuthorityScope,
	): TrackingProductSelectionAuthority {
		val issuer = TrackingProductTrustedCapabilities.issuer("wifi-producer")
		return TrackingProductTrustedCapabilities.selectionAuthority(
			issuer = issuer,
			source = HistorySource.WIFI,
			productIdentity = identity("wifi-product"),
			origin = origin,
			authorityScope = scope,
			selection = TrackingProductTrustedCapabilities.selection(
				issuer,
				"wifi-selection",
			),
			producerRevision = 11L,
			contentChecksum = checksum("wifi"),
			readSnapshot = TrackingProductReadSnapshot(3L, 7L),
		)
	}

	private fun locationFact(
		from: Long,
		to: Long,
		sessionIdentity: TrackingProductIdentity,
		seed: String = "location",
	): LocationEnrichmentFact =
		LocationEnrichmentFact(
			authority = factAuthority(from, to, sessionIdentity, seed),
			location = Location(
				time = from,
				latitude = 50.0,
				longitude = 14.0,
				altitude = null,
				horizontalAccuracy = 5f,
				verticalAccuracy = null,
				speed = null,
				speedAccuracy = null,
			),
		)

	private fun stepsFact(
		from: Long,
		to: Long,
		sessionIdentity: TrackingProductIdentity,
		seed: String,
	): StepsEnrichmentFact =
		StepsEnrichmentFact(
			factAuthority(from, to, sessionIdentity, seed),
			count = 1L,
			coverage = StepsHistoryCoverage.COMPLETE,
		)

	private fun pressureFact(
		from: Long,
		to: Long,
		sessionIdentity: TrackingProductIdentity,
		seed: String,
	): PressureEnrichmentFact =
		PressureEnrichmentFact(
			factAuthority(from, to, sessionIdentity, seed),
			pressureWindow(from, to),
		)

	private fun factAuthority(
		from: Long,
		to: Long,
		sessionIdentity: TrackingProductIdentity,
		seed: String,
	): TrackingEnrichmentFactAuthority =
		TrackingEnrichmentFactAuthority(
			identity(seed),
			EpochMs(from),
			EpochMs(to),
			TrackingEnrichmentEligibility(
				TrackingEnrichmentPurpose.SESSION_CAPTURE,
				5L,
				logicalSessionIdentity = sessionIdentity,
			),
		)

	@Suppress("LongParameterList")
	private fun pressureWindow(from: Long, to: Long): PressureHistoryWindow =
		PressureHistoryWindow(
			intervalStartTime = EpochMs(from),
			intervalEndTime = EpochMs(to),
			sampleCount = 1,
			expectedSampleCount = 1,
			meanHectopascals = 1_000.0,
			sumSquaredDeviations = 0.0,
			minimumHectopascals = 1_000f,
			maximumHectopascals = 1_000f,
			firstHectopascals = 1_000f,
			latestHectopascals = 1_000f,
			slopeHectopascalsPerSecond = null,
			rSquared = null,
			sensorAccuracy = PressureSensorAccuracy.HIGH,
			effectiveSamplePeriodMicros = 1,
			effectiveMaximumReportLatencyMicros = 0,
			targetWindowDurationNanos = 1L,
			maximumInterSampleGapNanos = 0L,
			closure = PressureWindowClosure.TARGET_ELAPSED,
			qualification = PressureWindowQualification.COMPLETE,
			sourceQualityFlags = 0L,
			sourceQualityConfidence = 1f,
			zoneId = "UTC",
		)

	private fun wall(from: Long, to: Long) =
		TrackingProductQueryScope.WallRange(EpochMs(from), EpochMs(to))

	private fun identity(seed: String): TrackingProductIdentity =
		TrackingProductTrustedCapabilities.identity("sha256:" + opaqueDigest(seed))

	private fun checksum(seed: String): TrackingProductChecksum =
		TrackingProductTrustedCapabilities.checksum("sha256:" + opaqueDigest(seed))

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
