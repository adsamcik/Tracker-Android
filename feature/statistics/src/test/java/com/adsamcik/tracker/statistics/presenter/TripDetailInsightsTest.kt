package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Tests the pure logic functions in TripDetailPresenterViewModel.kt:
 * buildInsights, resolveActivityType, resolveSourceLabel, normalizeProviderLabel.
 *
 * These are package-private top-level functions, accessed via reflection.
 */
@DisplayName("TripDetail Insights Logic")
class TripDetailInsightsTest {

	// Use reflection to access package-private functions
	private val buildInsightsFn: Method = Class.forName(
		"com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModelKt",
	).getDeclaredMethod(
		"buildInsights",
		TripSummary::class.java,
		Trip::class.java,
		List::class.java,
	).also { it.isAccessible = true }

	private val resolveActivityTypeFn: Method = Class.forName(
		"com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModelKt",
	).getDeclaredMethod(
		"resolveActivityType",
		Trip::class.java,
		TripSummary::class.java,
	).also { it.isAccessible = true }

	private val resolveSourceLabelFn: Method = Class.forName(
		"com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModelKt",
	).getDeclaredMethod(
		"resolveSourceLabel",
		List::class.java,
		Trip::class.java,
	).also { it.isAccessible = true }

	private val normalizeProviderLabelFn: Method = Class.forName(
		"com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModelKt",
	).getDeclaredMethod(
		"normalizeProviderLabel",
		String::class.java,
	).also { it.isAccessible = true }

	private fun buildInsights(trip: TripSummary, projection: Trip?, samples: List<LocationSample>): TripDetailInsights {
		return buildInsightsFn.invoke(null, trip, projection, samples) as TripDetailInsights
	}

	private fun resolveActivityType(projection: Trip?, trip: TripSummary): String {
		return resolveActivityTypeFn.invoke(null, projection, trip) as String
	}

	private fun resolveSourceLabel(samples: List<LocationSample>, projection: Trip?): String {
		return resolveSourceLabelFn.invoke(null, samples, projection) as String
	}

	private fun normalizeProviderLabel(provider: String): String {
		return normalizeProviderLabelFn.invoke(null, provider) as String
	}

	@Nested
	@DisplayName("normalizeProviderLabel")
	inner class NormalizeProviderTest {
		@Test
		fun `gps normalizes to GPS`() {
			normalizeProviderLabel("gps") shouldBe "GPS"
		}

		@Test
		fun `GPS uppercase also normalizes to GPS`() {
			normalizeProviderLabel("GPS") shouldBe "GPS"
		}

		@Test
		fun `network normalizes to Network`() {
			normalizeProviderLabel("network") shouldBe "Network"
		}

		@Test
		fun `fused normalizes to Fused`() {
			normalizeProviderLabel("fused") shouldBe "Fused"
		}

		@Test
		fun `flp normalizes to Fused`() {
			normalizeProviderLabel("flp") shouldBe "Fused"
		}

		@Test
		fun `passive normalizes to Passive`() {
			normalizeProviderLabel("passive") shouldBe "Passive"
		}

		@Test
		fun `unknown provider gets capitalized first letter`() {
			normalizeProviderLabel("custom_provider") shouldBe "Custom_provider"
		}
	}

	@Nested
	@DisplayName("resolveActivityType")
	inner class ResolveActivityTypeTest {
		@Test
		fun `uses projection primaryActivity when available`() {
			val projection = makeTrip(primaryActivity = 7) // Walking
			val trip = makeTripSummary(primaryMode = TransportMode.UNKNOWN)
			resolveActivityType(projection, trip) shouldBe "Walk"
		}

		@Test
		fun `falls back to trip primaryMode when no projection`() {
			val trip = makeTripSummary(primaryMode = TransportMode.CYCLE)
			resolveActivityType(null, trip) shouldBe "Cycle"
		}

		@Test
		fun `UNKNOWN mode falls back to Trip`() {
			val trip = makeTripSummary(primaryMode = TransportMode.UNKNOWN)
			resolveActivityType(null, trip) shouldBe "Trip"
		}

		@Test
		fun `underscore modes format correctly`() {
			val trip = makeTripSummary(primaryMode = TransportMode.HIGH_SPEED_RAIL)
			resolveActivityType(null, trip) shouldBe "High speed rail"
		}

		@Test
		fun `null projection activity falls back to mode name`() {
			val projection = makeTrip(primaryActivity = null)
			val trip = makeTripSummary(primaryMode = TransportMode.RUN)
			resolveActivityType(projection, trip) shouldBe "Run"
		}
	}

	@Nested
	@DisplayName("resolveSourceLabel")
	inner class ResolveSourceLabelTest {
		@Test
		fun `groups providers and returns dominant provider`() {
			val samples = listOf(
				makeSample(provider = "gps"),
				makeSample(provider = "gps"),
				makeSample(provider = "network"),
			)
			resolveSourceLabel(samples, null) shouldBe "GPS"
		}

		@Test
		fun `falls back to projection source when no samples`() {
			val projection = makeTrip(source = SegmentSource.USER_CREATED)
			resolveSourceLabel(emptyList(), projection) shouldBe "Manual"
		}

		@Test
		fun `INFERRED_HIGH_CONFIDENCE returns Inferred`() {
			val projection = makeTrip(source = SegmentSource.INFERRED_HIGH_CONFIDENCE)
			resolveSourceLabel(emptyList(), projection) shouldBe "Inferred"
		}

		@Test
		fun `LEGACY_MIGRATION returns Legacy`() {
			val projection = makeTrip(source = SegmentSource.LEGACY_MIGRATION)
			resolveSourceLabel(emptyList(), projection) shouldBe "Legacy"
		}

		@Test
		fun `null projection and no samples returns dash`() {
			resolveSourceLabel(emptyList(), null) shouldBe "—"
		}
	}

	@Nested
	@DisplayName("buildInsights")
	inner class BuildInsightsTest {
		@Test
		fun `elevation gain and loss calculated from altitude deltas`() {
			val samples = listOf(
				makeSample(altitudeM = 100f),
				makeSample(altitudeM = 110f),  // +10
				makeSample(altitudeM = 105f),  // -5
				makeSample(altitudeM = 120f),  // +15
			)
			val trip = makeTripSummary(distanceM = 1000f, durationMs = 3600_000L)
			val insights = buildInsights(trip, null, samples)

			insights.elevationGainM.shouldNotBeNull()
			insights.elevationGainM!! shouldBe 25.0 // 10 + 15
			insights.elevationLossM.shouldNotBeNull()
			insights.elevationLossM!! shouldBe 5.0
		}

		@Test
		fun `small altitude changes under 1m threshold are ignored`() {
			val samples = listOf(
				makeSample(altitudeM = 100f),
				makeSample(altitudeM = 100.5f),  // +0.5, below threshold
				makeSample(altitudeM = 100.3f),  // -0.2, below threshold
			)
			val trip = makeTripSummary(distanceM = 500f, durationMs = 1800_000L)
			val insights = buildInsights(trip, null, samples)

			insights.elevationGainM.shouldBeNull()
			insights.elevationLossM.shouldBeNull()
		}

		@Test
		fun `max speed from samples`() {
			val samples = listOf(
				makeSample(speedMps = 2.0f),
				makeSample(speedMps = 5.5f),
				makeSample(speedMps = 3.0f),
			)
			val trip = makeTripSummary(distanceM = 1000f, durationMs = 600_000L)
			val insights = buildInsights(trip, null, samples)

			insights.maxSpeedMps.shouldNotBeNull()
			insights.maxSpeedMps!! shouldBe 5.5
		}

		@Test
		fun `low quality sample speed is excluded from max speed`() {
			val samples = listOf(
				makeSample(speedMps = 5.0f, quality = SampleQuality.HIGH),
				// A single degraded fix reporting an implausible spike shouldn't set the record.
				makeSample(speedMps = 90.0f, quality = SampleQuality.LOW),
			)
			val trip = makeTripSummary()
			val insights = buildInsights(trip, null, samples)

			insights.maxSpeedMps.shouldNotBeNull()
			insights.maxSpeedMps!! shouldBe 5.0
		}

		@Test
		fun `coarse quality sample speed is excluded from max speed`() {
			val samples = listOf(
				makeSample(speedMps = 4.0f, quality = SampleQuality.MEDIUM),
				makeSample(speedMps = 120.0f, quality = SampleQuality.COARSE),
			)
			val trip = makeTripSummary()
			val insights = buildInsights(trip, null, samples)

			insights.maxSpeedMps.shouldNotBeNull()
			insights.maxSpeedMps!! shouldBe 4.0
		}

		@Test
		fun `high speed-accuracy uncertainty excludes sample from max speed`() {
			val samples = listOf(
				makeSample(speedMps = 6.0f, speedAccuracyMps = 0.5f),
				// Accurate position but the device itself flags this speed reading as unreliable.
				makeSample(speedMps = 50.0f, speedAccuracyMps = 15.0f),
			)
			val trip = makeTripSummary()
			val insights = buildInsights(trip, null, samples)

			insights.maxSpeedMps.shouldNotBeNull()
			insights.maxSpeedMps!! shouldBe 6.0
		}

		@Test
		fun `all samples untrustworthy yields null max speed`() {
			val samples = listOf(
				makeSample(speedMps = 90.0f, quality = SampleQuality.LOW),
				makeSample(speedMps = 120.0f, quality = SampleQuality.COARSE),
			)
			val trip = makeTripSummary()
			val insights = buildInsights(trip, null, samples)

			insights.maxSpeedMps.shouldBeNull()
		}

		@Test
		fun `average speed from trip distance and duration`() {
			val trip = makeTripSummary(distanceM = 3600f, durationMs = 3600_000L) // 1 m/s
			val insights = buildInsights(trip, null, emptyList())

			insights.averageSpeedMps.shouldNotBeNull()
			insights.averageSpeedMps!! shouldBe 1.0
		}

		@Test
		fun `zero duration yields null average speed`() {
			val trip = makeTripSummary(distanceM = 100f, durationMs = 0L)
			val insights = buildInsights(trip, null, emptyList())

			insights.averageSpeedMps.shouldBeNull()
		}

		@Test
		fun `zero distance yields null average speed`() {
			val trip = makeTripSummary(distanceM = 0f, durationMs = 5000L)
			val insights = buildInsights(trip, null, emptyList())

			insights.averageSpeedMps.shouldBeNull()
		}

		@Test
		fun `missing altitude resets elevation baseline`() {
			val samples = listOf(
				makeSample(altitudeM = 100f),
				makeSample(altitudeM = null),
				makeSample(altitudeM = 115f),
			)
			val trip = makeTripSummary(distanceM = 500f, durationMs = 1800_000L)
			val insights = buildInsights(trip, null, samples)

			// No delta may bridge an unknown/missing altitude boundary.
			insights.elevationGainM.shouldBeNull()
		}

		@Test
		fun `maxAltitude is set from samples`() {
			val samples = listOf(
				makeSample(altitudeM = 100f),
				makeSample(altitudeM = 250f),
				makeSample(altitudeM = 200f),
			)
			val trip = makeTripSummary()
			val insights = buildInsights(trip, null, samples)

			insights.maxAltitudeM.shouldNotBeNull()
			insights.maxAltitudeM!! shouldBe 250.0
		}

		@Test
		fun `elevation deltas reset across datum and clock boundaries`() {
			val samples = listOf(
				makeSample(altitudeM = 100f, clockDomainId = "boot-a"),
				makeSample(
					altitudeM = 1_000f,
					altitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
					clockDomainId = "boot-a",
				),
				makeSample(altitudeM = 120f, clockDomainId = "boot-a"),
				makeSample(
					altitudeM = 140f,
					altitudeDatum = AltitudeDatum.RELATIVE_BAROMETRIC,
					clockDomainId = "boot-a",
				),
				makeSample(altitudeM = 160f, clockDomainId = "boot-b"),
			)

			val insights = buildInsights(makeTripSummary(), null, samples)

			insights.elevationGainM.shouldBeNull()
			insights.elevationLossM.shouldBeNull()
		}

		@Test
		fun `max altitude excludes unknown and relative datum values`() {
			val samples = listOf(
				makeSample(altitudeM = 250f),
				makeSample(
					altitudeM = 1_000f,
					altitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
				),
				makeSample(
					altitudeM = 900f,
					altitudeDatum = AltitudeDatum.RELATIVE_BAROMETRIC,
				),
			)

			buildInsights(makeTripSummary(), null, samples).maxAltitudeM shouldBe 250.0
		}

		@Test
		fun `route points filtered to samples with coordinates`() {
			val samples = listOf(
				makeSample(latE7 = 500000000, lonE7 = 140000000),
				makeSample(latE7 = null, lonE7 = null), // no coords
				makeSample(latE7 = 500100000, lonE7 = 140100000),
			)
			val trip = makeTripSummary()
			val insights = buildInsights(trip, null, samples)

			insights.routePoints.size shouldBe 2
		}

		@Test
		fun `large route preview is simplified before exposure`() {
			val samples = (0 until 3_000).map { index ->
				makeSample(
					latE7 = 500000000 + index * 120,
					lonE7 = 140000000 + if (index % 2 == 0) 0 else 1_200,
				)
			}
			val trip = makeTripSummary()
			val insights = buildInsights(trip, null, samples)

			insights.routePoints.size.shouldBeLessThanOrEqual(1_500)
			insights.routePoints.first().lat shouldBe 50.0
			insights.routePoints.last().lat.shouldBeGreaterThan(50.0)
		}

		@Test
		fun `zero-speed samples are excluded from maxSpeed`() {
			val samples = listOf(
				makeSample(speedMps = 0.0f),
				makeSample(speedMps = 0.0f),
			)
			val trip = makeTripSummary()
			val insights = buildInsights(trip, null, samples)

			insights.maxSpeedMps.shouldBeNull()
		}
	}

	companion object {
		private var sampleIdCounter = 0L

		fun makeSample(
			provider: String = "gps",
			altitudeM: Float? = null,
			speedMps: Float? = null,
			latE7: Int? = null,
			lonE7: Int? = null,
			quality: SampleQuality = SampleQuality.HIGH,
			speedAccuracyMps: Float? = null,
			altitudeDatum: AltitudeDatum = if (altitudeM != null) {
				AltitudeDatum.ANDROID_MODEL_MSL
			} else {
				AltitudeDatum.UNKNOWN_LEGACY
			},
			clockDomainId: String? = "test-clock",
		) = LocationSample(
			id = ++sampleIdCounter,
			timeMs = 1_700_000_000_000L + sampleIdCounter * 1000,
			elapsedRealtimeNanos = sampleIdCounter * 1_000_000_000,
			latE7 = latE7,
			lonE7 = lonE7,
			altitudeM = altitudeM,
			rawGpsAltitudeM = altitudeM,
			hAccM = 5.0f,
			vAccM = 3.0f,
			speedMps = speedMps,
			speedAccuracyMps = speedAccuracyMps,
			provider = provider,
			quality = quality,
			motionState = MotionState.MOVING,
			policy = null,
			bucketId = null,
			createdAt = 1_700_000_000_000L + sampleIdCounter * 1000,
			altitudeDatum = altitudeDatum,
			altitudeSource = if (altitudeM != null) {
				AltitudeSource.GPS_CONVERSION
			} else {
				AltitudeSource.UNKNOWN_LEGACY
			},
			altitudeConversionStatus = if (altitudeM != null) {
				AltitudeConversionStatus.SUCCESS
			} else {
				AltitudeConversionStatus.UNKNOWN_LEGACY
			},
			rawGpsAltitudeDatum = if (altitudeM != null) {
				AltitudeDatum.WGS84_ELLIPSOID
			} else {
				AltitudeDatum.UNKNOWN_LEGACY
			},
			altitudeModelVersion = if (altitudeM != null) 1 else 0,
			clockDomainId = clockDomainId,
		)

		fun makeTrip(
			id: Long = 1L,
			primaryActivity: Int? = 7,
			source: SegmentSource = SegmentSource.USER_CREATED,
		) = Trip(
			id = id,
			startTimeMs = 1_700_000_000_000L,
			endTimeMs = 1_700_003_600_000L,
			distanceM = 1000f,
			steps = 500,
			primaryActivity = primaryActivity,
			activityConfidence = 90,
			sampleCount = 100,
			source = source,
			createdAt = 1_700_000_000_000L,
		)

		fun makeTripSummary(
			primaryMode: TransportMode = TransportMode.WALK,
			distanceM: Float = 1000f,
			durationMs: Long = 3600_000L,
		) = TripSummary(
			id = 1L,
			startTimeMs = EpochMs(1_700_000_000_000L),
			endTimeMs = EpochMs(1_700_000_000_000L + durationMs),
			distance = DistanceM(distanceM),
			steps = StepCount(500),
			duration = DurationMs(durationMs),
			primaryMode = primaryMode,
			sampleCount = 100,
		)
	}
}
