package com.adsamcik.tracker.stats.engine.place

import com.adsamcik.tracker.stats.api.TransportMode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EnrichedTripTest {

	private val sampleCluster = PlaceCluster(
		id = 1L,
		centerLatE7 = 407128000,
		centerLonE7 = -740060000,
		radiusM = 100f,
		visitCount = 5,
	)

	private fun sampleLeg(
		sequenceIndex: Int = 0,
		startTimeMs: Long = 1_000_000L,
		endTimeMs: Long = 1_600_000L,
		distanceM: Float = 1200f,
		transportMode: TransportMode = TransportMode.WALK,
	) = TripLeg(
		sequenceIndex = sequenceIndex,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		transportMode = transportMode,
	)

	private fun sampleTrip(
		startTimeMs: Long = 1_000_000L,
		endTimeMs: Long = 1_600_000L,
		distanceM: Float = 1200f,
		steps: Int? = 1500,
		primaryActivity: Int? = 7,
		transportMode: TransportMode = TransportMode.WALK,
		departurePlaceMatch: PlaceMatchResult? = null,
		arrivalPlaceMatch: PlaceMatchResult? = null,
		legs: List<TripLeg> = listOf(sampleLeg()),
		source: String = "segment_detector",
		inferenceVersion: String? = "1.0.0",
		segmentId: Long = 0L,
	) = EnrichedTrip(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		steps = steps,
		primaryActivity = primaryActivity,
		transportMode = transportMode,
		departurePlaceMatch = departurePlaceMatch,
		arrivalPlaceMatch = arrivalPlaceMatch,
		legs = legs,
		source = source,
		inferenceVersion = inferenceVersion,
		segmentId = segmentId,
	)

	// ================================================================
	// TripLeg
	// ================================================================

	@Nested
	inner class TripLegConstruction {

		@Test
		fun `stores all fields correctly`() {
			val leg = TripLeg(
				sequenceIndex = 2,
				startTimeMs = 500L,
				endTimeMs = 1500L,
				distanceM = 800f,
				transportMode = TransportMode.DRIVE,
			)

			leg.sequenceIndex shouldBe 2
			leg.startTimeMs shouldBe 500L
			leg.endTimeMs shouldBe 1500L
			leg.distanceM shouldBe 800f
			leg.transportMode shouldBe TransportMode.DRIVE
		}

		@Test
		fun `equal legs have same hashCode`() {
			val a = sampleLeg()
			val b = sampleLeg()
			a shouldBe b
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `legs with different sequence index are not equal`() {
			val a = sampleLeg(sequenceIndex = 0)
			val b = sampleLeg(sequenceIndex = 1)
			a shouldNotBe b
		}

		@Test
		fun `legs with different transport mode are not equal`() {
			val a = sampleLeg(transportMode = TransportMode.WALK)
			val b = sampleLeg(transportMode = TransportMode.CYCLE)
			a shouldNotBe b
		}

		@Test
		fun `zero distance leg is valid`() {
			val leg = sampleLeg(distanceM = 0f)
			leg.distanceM shouldBe 0f
		}

		@Test
		fun `all transport modes are representable in a leg`() {
			for (mode in TransportMode.entries) {
				val leg = sampleLeg(transportMode = mode)
				leg.transportMode shouldBe mode
			}
		}
	}

	// ================================================================
	// EnrichedTrip construction
	// ================================================================

	@Nested
	inner class Construction {

		@Test
		fun `stores all fields correctly`() {
			val departure = PlaceMatchResult.Matched(sampleCluster)
			val arrival = PlaceMatchResult.NewPlace(latE7 = 123, lonE7 = 456)
			val legs = listOf(sampleLeg(sequenceIndex = 0), sampleLeg(sequenceIndex = 1))

			val trip = sampleTrip(
				startTimeMs = 100L,
				endTimeMs = 900L,
				distanceM = 5000f,
				steps = 6000,
				primaryActivity = 2,
				transportMode = TransportMode.RUN,
				departurePlaceMatch = departure,
				arrivalPlaceMatch = arrival,
				legs = legs,
				source = "manual",
				inferenceVersion = "2.0.0",
				segmentId = 42L,
			)

			trip.startTimeMs shouldBe 100L
			trip.endTimeMs shouldBe 900L
			trip.distanceM shouldBe 5000f
			trip.steps shouldBe 6000
			trip.primaryActivity shouldBe 2
			trip.transportMode shouldBe TransportMode.RUN
			trip.departurePlaceMatch shouldBe departure
			trip.arrivalPlaceMatch shouldBe arrival
			trip.legs shouldHaveSize 2
			trip.source shouldBe "manual"
			trip.inferenceVersion shouldBe "2.0.0"
			trip.segmentId shouldBe 42L
		}

		@Test
		fun `null optional fields are preserved`() {
			val trip = sampleTrip(
				steps = null,
				primaryActivity = null,
				departurePlaceMatch = null,
				arrivalPlaceMatch = null,
				inferenceVersion = null,
			)

			trip.steps.shouldBeNull()
			trip.primaryActivity.shouldBeNull()
			trip.departurePlaceMatch.shouldBeNull()
			trip.arrivalPlaceMatch.shouldBeNull()
			trip.inferenceVersion.shouldBeNull()
		}

		@Test
		fun `empty legs list is valid`() {
			val trip = sampleTrip(legs = emptyList())
			trip.legs.shouldBeEmpty()
		}
	}

	// ================================================================
	// Place match integration
	// ================================================================

	@Nested
	inner class PlaceMatchIntegration {

		@Test
		fun `trip with Matched departure holds cluster reference`() {
			val departure = PlaceMatchResult.Matched(sampleCluster)
			val trip = sampleTrip(departurePlaceMatch = departure)

			trip.departurePlaceMatch.shouldNotBeNull()
			val matched = trip.departurePlaceMatch as PlaceMatchResult.Matched
			matched.cluster.id shouldBe sampleCluster.id
		}

		@Test
		fun `trip with NewPlace arrival holds coordinates`() {
			val arrival = PlaceMatchResult.NewPlace(latE7 = 407128000, lonE7 = -740060000)
			val trip = sampleTrip(arrivalPlaceMatch = arrival)

			trip.arrivalPlaceMatch.shouldNotBeNull()
			val newPlace = trip.arrivalPlaceMatch as PlaceMatchResult.NewPlace
			newPlace.latE7 shouldBe 407128000
			newPlace.lonE7 shouldBe -740060000
		}

		@Test
		fun `trip with mixed match types for departure and arrival`() {
			val departure = PlaceMatchResult.Matched(sampleCluster)
			val arrival = PlaceMatchResult.NewPlace(latE7 = 0, lonE7 = 0)

			val trip = sampleTrip(
				departurePlaceMatch = departure,
				arrivalPlaceMatch = arrival,
			)

			trip.departurePlaceMatch shouldBe departure
			trip.arrivalPlaceMatch shouldBe arrival
		}
	}

	// ================================================================
	// Equality
	// ================================================================

	@Nested
	inner class Equality {

		@Test
		fun `identical trips are equal`() {
			val a = sampleTrip()
			val b = sampleTrip()
			a shouldBe b
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `trips with different distance are not equal`() {
			val a = sampleTrip(distanceM = 100f)
			val b = sampleTrip(distanceM = 200f)
			a shouldNotBe b
		}

		@Test
		fun `trips with different transport mode are not equal`() {
			val a = sampleTrip(transportMode = TransportMode.WALK)
			val b = sampleTrip(transportMode = TransportMode.DRIVE)
			a shouldNotBe b
		}

		@Test
		fun `trips with different segmentId are not equal`() {
			val a = sampleTrip(segmentId = 1L)
			val b = sampleTrip(segmentId = 2L)
			a shouldNotBe b
		}

		@Test
		fun `trips with different source are not equal`() {
			val a = sampleTrip(source = "manual")
			val b = sampleTrip(source = "auto")
			a shouldNotBe b
		}
	}

	// ================================================================
	// Copy
	// ================================================================

	@Nested
	inner class Copy {

		@Test
		fun `copy with updated distance preserves other fields`() {
			val original = sampleTrip(segmentId = 99L)
			val updated = original.copy(distanceM = 9999f)

			updated.distanceM shouldBe 9999f
			updated.segmentId shouldBe 99L
			updated.startTimeMs shouldBe original.startTimeMs
			updated.source shouldBe original.source
		}

		@Test
		fun `copy with updated place match preserves trip data`() {
			val original = sampleTrip(departurePlaceMatch = null)
			val newMatch = PlaceMatchResult.Matched(sampleCluster)
			val updated = original.copy(departurePlaceMatch = newMatch)

			updated.departurePlaceMatch shouldBe newMatch
			updated.distanceM shouldBe original.distanceM
			updated.legs shouldBe original.legs
		}
	}

	// ================================================================
	// Multi-leg
	// ================================================================

	@Nested
	inner class MultiLeg {

		@Test
		fun `trip with multiple legs preserves order`() {
			val legs = listOf(
				sampleLeg(sequenceIndex = 0, transportMode = TransportMode.WALK),
				sampleLeg(sequenceIndex = 1, transportMode = TransportMode.TRANSIT),
				sampleLeg(sequenceIndex = 2, transportMode = TransportMode.WALK),
			)
			val trip = sampleTrip(legs = legs)

			trip.legs shouldHaveSize 3
			trip.legs[0].sequenceIndex shouldBe 0
			trip.legs[1].sequenceIndex shouldBe 1
			trip.legs[2].sequenceIndex shouldBe 2
			trip.legs[0].transportMode shouldBe TransportMode.WALK
			trip.legs[1].transportMode shouldBe TransportMode.TRANSIT
			trip.legs[2].transportMode shouldBe TransportMode.WALK
		}

		@Test
		fun `leg distances can sum to trip distance`() {
			val legs = listOf(
				sampleLeg(sequenceIndex = 0, distanceM = 400f),
				sampleLeg(sequenceIndex = 1, distanceM = 600f),
				sampleLeg(sequenceIndex = 2, distanceM = 200f),
			)
			val trip = sampleTrip(distanceM = 1200f, legs = legs)

			val legSum = trip.legs.sumOf { it.distanceM.toDouble() }.toFloat()
			legSum shouldBe trip.distanceM
		}
	}
}
