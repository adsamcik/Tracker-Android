package com.adsamcik.tracker.stats.engine.place

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.SegmentEvent
import com.adsamcik.tracker.stats.api.TransportMode
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TripEnricher")
class TripEnricherTest {

	private lateinit var enricher: TripEnricher

	/** NYC area base coordinates in E7 format (~40.7128N, 74.0060W). */
	private val baseLat = 407128000
	private val baseLon = -740060000

	/** Offset ~50m north of base (approximately 450 E7 units at NYC latitude). */
	private val nearbyLat = baseLat + 450
	private val nearbyLon = baseLon

	/** Offset ~500m north of base (far beyond default 150m match radius). */
	private val farLat = baseLat + 45000
	private val farLon = baseLon

	private val defaultSource = "segment_detector"
	private val defaultInferenceVersion = "1.0.0"

	@BeforeEach
	fun setUp() {
		enricher = TripEnricher()
	}

	// ---- Helper factories ----

	private fun tripEndedEvent(
		startTimeMs: Long = 1_000_000L,
		endTimeMs: Long = 1_600_000L,
		totalDistanceM: Float = 1200f,
		totalSteps: Int = 1500,
		sampleCount: Int = 60,
		primaryActivity: DetectedActivityType? = DetectedActivityType.WALKING,
		averageActivityConfidence: Int? = 75,
		inferredTransportMode: TransportMode = TransportMode.WALK,
	): SegmentEvent.TripEnded = SegmentEvent.TripEnded(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		totalDistanceM = totalDistanceM,
		totalSteps = totalSteps,
		sampleCount = sampleCount,
		primaryActivity = primaryActivity,
		averageActivityConfidence = averageActivityConfidence,
		inferredTransportMode = inferredTransportMode,
	)

	private fun cluster(
		id: Long = 1L,
		centerLatE7: Int = baseLat,
		centerLonE7: Int = baseLon,
		radiusM: Float = 100f,
		visitCount: Int = 5,
	): PlaceCluster = PlaceCluster(
		id = id,
		centerLatE7 = centerLatE7,
		centerLonE7 = centerLonE7,
		radiusM = radiusM,
		visitCount = visitCount,
	)

	// ================================================================
	// BasicEnrichment
	// ================================================================

	@Nested
	@DisplayName("BasicEnrichment")
	inner class BasicEnrichment {

		@Test
		@DisplayName("enriches trip with correct time fields from event")
		fun enrichCopiesTimeFields() {
			val event = tripEndedEvent(startTimeMs = 100L, endTimeMs = 900L)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.startTimeMs shouldBe 100L
			result.endTimeMs shouldBe 900L
		}

		@Test
		@DisplayName("enriches trip with correct distance from event")
		fun enrichCopiesDistance() {
			val event = tripEndedEvent(totalDistanceM = 4567.89f)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.distanceM shouldBe 4567.89f
		}

		@Test
		@DisplayName("enriches trip with step count when steps are positive")
		fun enrichCopiesPositiveSteps() {
			val event = tripEndedEvent(totalSteps = 2000)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.steps shouldBe 2000
		}

		@Test
		@DisplayName("sets steps to null when event has zero steps")
		fun enrichNullsZeroSteps() {
			val event = tripEndedEvent(totalSteps = 0)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.steps.shouldBeNull()
		}

		@Test
		@DisplayName("copies transport mode from event")
		fun enrichCopiesTransportMode() {
			val event = tripEndedEvent(inferredTransportMode = TransportMode.CYCLE)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.transportMode shouldBe TransportMode.CYCLE
		}

		@Test
		@DisplayName("copies source and inference version into result")
		fun enrichCopiesSourceAndVersion() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = "manual",
				inferenceVersion = "2.1.0",
			)

			result.source shouldBe "manual"
			result.inferenceVersion shouldBe "2.1.0"
		}

		@Test
		@DisplayName("uses provided segmentId")
		fun enrichUsesSegmentId() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
				segmentId = 42L,
			)

			result.segmentId shouldBe 42L
		}

		@Test
		@DisplayName("segmentId defaults to zero when not specified")
		fun enrichDefaultsSegmentIdToZero() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = null,
			)

			result.segmentId shouldBe 0L
		}

		@Test
		@DisplayName("creates exactly one leg for a single trip")
		fun enrichCreatesSingleLeg() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.legs shouldHaveSize 1
		}

		@Test
		@DisplayName("single leg has correct time, distance, and transport mode from event")
		fun enrichLegFieldsMatchEvent() {
			val event = tripEndedEvent(
				startTimeMs = 500L,
				endTimeMs = 1500L,
				totalDistanceM = 800f,
				inferredTransportMode = TransportMode.DRIVE,
			)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			val leg = result.legs.first()
			leg.sequenceIndex shouldBe 0
			leg.startTimeMs shouldBe 500L
			leg.endTimeMs shouldBe 1500L
			leg.distanceM shouldBe 800f
			leg.transportMode shouldBe TransportMode.DRIVE
		}

		@Test
		@DisplayName("null inference version is preserved in result")
		fun enrichPreservesNullInferenceVersion() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = null,
			)

			result.inferenceVersion.shouldBeNull()
		}
	}

	// ================================================================
	// ActivityMapping
	// ================================================================

	@Nested
	@DisplayName("ActivityMapping")
	inner class ActivityMapping {

		@Test
		@DisplayName("IN_VEHICLE maps to 0")
		fun inVehicleMapsTo0() {
			TripEnricher.mapActivityTypeToInt(DetectedActivityType.IN_VEHICLE) shouldBe 0
		}

		@Test
		@DisplayName("ON_BICYCLE maps to 1")
		fun onBicycleMapsTo1() {
			TripEnricher.mapActivityTypeToInt(DetectedActivityType.ON_BICYCLE) shouldBe 1
		}

		@Test
		@DisplayName("ON_FOOT maps to 2")
		fun onFootMapsTo2() {
			TripEnricher.mapActivityTypeToInt(DetectedActivityType.ON_FOOT) shouldBe 2
		}

		@Test
		@DisplayName("STILL maps to 3")
		fun stillMapsTo3() {
			TripEnricher.mapActivityTypeToInt(DetectedActivityType.STILL) shouldBe 3
		}

		@Test
		@DisplayName("UNKNOWN maps to 4")
		fun unknownMapsTo4() {
			TripEnricher.mapActivityTypeToInt(DetectedActivityType.UNKNOWN) shouldBe 4
		}

		@Test
		@DisplayName("TILTING maps to 5")
		fun tiltingMapsTo5() {
			TripEnricher.mapActivityTypeToInt(DetectedActivityType.TILTING) shouldBe 5
		}

		@Test
		@DisplayName("WALKING maps to 7")
		fun walkingMapsTo7() {
			TripEnricher.mapActivityTypeToInt(DetectedActivityType.WALKING) shouldBe 7
		}

		@Test
		@DisplayName("RUNNING maps to 8")
		fun runningMapsTo8() {
			TripEnricher.mapActivityTypeToInt(DetectedActivityType.RUNNING) shouldBe 8
		}

		@Test
		@DisplayName("primaryActivity is mapped via mapActivityTypeToInt in enrich result")
		fun enrichMapsPrimaryActivityToInt() {
			val event = tripEndedEvent(primaryActivity = DetectedActivityType.ON_BICYCLE)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.primaryActivity shouldBe 1
		}

		@Test
		@DisplayName("null primaryActivity in event produces null primaryActivity in result")
		fun enrichNullPrimaryActivity() {
			val event = tripEndedEvent(primaryActivity = null)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.primaryActivity.shouldBeNull()
		}

		@Test
		@DisplayName("all DetectedActivityType values map to unique integers")
		fun allActivityTypesMapToUniqueInts() {
			val mappedValues = DetectedActivityType.entries.map { TripEnricher.mapActivityTypeToInt(it) }
			mappedValues.distinct().size shouldBe mappedValues.size
		}
	}

	// ================================================================
	// PlaceMatching
	// ================================================================

	@Nested
	@DisplayName("PlaceMatching")
	inner class PlaceMatching {

		@Test
		@DisplayName("matches departure to cluster when within match radius")
		fun departureMatchesNearbyCluster() {
			val clusters = listOf(cluster(id = 10L, centerLatE7 = baseLat, centerLonE7 = baseLon))
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = nearbyLat,
				departureLonE7 = nearbyLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = clusters,
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.departurePlaceMatch.shouldNotBeNull()
			val matched = result.departurePlaceMatch.shouldBeInstanceOf<PlaceMatchResult.Matched>()
			matched.cluster.id shouldBe 10L
		}

		@Test
		@DisplayName("matches arrival to cluster when within match radius")
		fun arrivalMatchesNearbyCluster() {
			val clusters = listOf(cluster(id = 20L, centerLatE7 = farLat, centerLonE7 = farLon))
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat + 200,
				arrivalLonE7 = farLon,
				existingClusters = clusters,
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.arrivalPlaceMatch.shouldNotBeNull()
			val matched = result.arrivalPlaceMatch.shouldBeInstanceOf<PlaceMatchResult.Matched>()
			matched.cluster.id shouldBe 20L
		}

		@Test
		@DisplayName("creates NewPlace for departure when no cluster is within radius")
		fun departureNewPlaceWhenNoMatch() {
			val clusters = listOf(cluster(id = 30L, centerLatE7 = farLat, centerLonE7 = farLon))
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = clusters,
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.departurePlaceMatch.shouldNotBeNull()
			val newPlace = result.departurePlaceMatch.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
			newPlace.latE7 shouldBe baseLat
			newPlace.lonE7 shouldBe baseLon
		}

		@Test
		@DisplayName("creates NewPlace for arrival when no cluster is within radius")
		fun arrivalNewPlaceWhenNoMatch() {
			val clusters = listOf(cluster(id = 30L, centerLatE7 = baseLat, centerLonE7 = baseLon))
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = clusters,
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.arrivalPlaceMatch.shouldNotBeNull()
			val newPlace = result.arrivalPlaceMatch.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
			newPlace.latE7 shouldBe farLat
			newPlace.lonE7 shouldBe farLon
		}

		@Test
		@DisplayName("picks closest cluster when multiple are within match radius")
		fun matchesClosestCluster() {
			// Two clusters near baseLat: one at exact location, one offset by ~30m
			val closerCluster = cluster(id = 1L, centerLatE7 = baseLat + 100, centerLonE7 = baseLon)
			val fartherCluster = cluster(id = 2L, centerLatE7 = baseLat + 1000, centerLonE7 = baseLon)
			val clusters = listOf(fartherCluster, closerCluster) // order should not matter

			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = clusters,
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			val matched = result.departurePlaceMatch.shouldBeInstanceOf<PlaceMatchResult.Matched>()
			matched.cluster.id shouldBe 1L
		}

		@Test
		@DisplayName("both departure and arrival can match different clusters")
		fun departureAndArrivalMatchDifferentClusters() {
			val departureCluster = cluster(id = 100L, centerLatE7 = baseLat, centerLonE7 = baseLon)
			val arrivalCluster = cluster(id = 200L, centerLatE7 = farLat, centerLonE7 = farLon)
			val clusters = listOf(departureCluster, arrivalCluster)
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat + 100,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat + 100,
				arrivalLonE7 = farLon,
				existingClusters = clusters,
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			val depMatch = result.departurePlaceMatch.shouldBeInstanceOf<PlaceMatchResult.Matched>()
			depMatch.cluster.id shouldBe 100L

			val arrMatch = result.arrivalPlaceMatch.shouldBeInstanceOf<PlaceMatchResult.Matched>()
			arrMatch.cluster.id shouldBe 200L
		}

		@Test
		@DisplayName("both departure and arrival can be NewPlace when no clusters match")
		fun bothNewPlaceWhenNoClustersMatch() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.departurePlaceMatch.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
			result.arrivalPlaceMatch.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
		}

		@Test
		@DisplayName("matchPlace with empty clusters returns NewPlace")
		fun matchPlaceEmptyClusters() {
			val result = enricher.matchPlace(baseLat, baseLon, emptyList())

			val newPlace = result.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
			newPlace.latE7 shouldBe baseLat
			newPlace.lonE7 shouldBe baseLon
		}

		@Test
		@DisplayName("matchPlace returns Matched for cluster at exact coordinates")
		fun matchPlaceExactCoordinates() {
			val clusters = listOf(cluster(id = 5L, centerLatE7 = baseLat, centerLonE7 = baseLon))

			val result = enricher.matchPlace(baseLat, baseLon, clusters)

			val matched = result.shouldBeInstanceOf<PlaceMatchResult.Matched>()
			matched.cluster.id shouldBe 5L
		}

		@Test
		@DisplayName("custom config with larger match radius matches farther clusters")
		fun customConfigLargerRadius() {
			// Default radius is 150m; farLat is ~500m north. Use large radius to match.
			val largeRadiusEnricher = TripEnricher(PlaceClusterConfig(matchRadiusM = 600f))
			val clusters = listOf(cluster(id = 99L, centerLatE7 = farLat, centerLonE7 = farLon))

			val result = largeRadiusEnricher.matchPlace(baseLat, baseLon, clusters)

			result.shouldBeInstanceOf<PlaceMatchResult.Matched>()
		}

		@Test
		@DisplayName("custom config with small match radius rejects nearby clusters")
		fun customConfigSmallRadius() {
			// nearbyLat is ~50m north. Use very small radius to reject.
			val smallRadiusEnricher = TripEnricher(PlaceClusterConfig(matchRadiusM = 1f))
			val clusters = listOf(cluster(id = 88L, centerLatE7 = baseLat, centerLonE7 = baseLon))

			val result = smallRadiusEnricher.matchPlace(nearbyLat, nearbyLon, clusters)

			result.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
		}
	}

	// ================================================================
	// EdgeCases
	// ================================================================

	@Nested
	@DisplayName("EdgeCases")
	inner class EdgeCases {

		@Test
		@DisplayName("null departure coordinates produce null departurePlaceMatch")
		fun nullDepartureCoordinates() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = null,
				departureLonE7 = null,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = listOf(cluster()),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.departurePlaceMatch.shouldBeNull()
		}

		@Test
		@DisplayName("null arrival coordinates produce null arrivalPlaceMatch")
		fun nullArrivalCoordinates() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = null,
				arrivalLonE7 = null,
				existingClusters = listOf(cluster()),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.arrivalPlaceMatch.shouldBeNull()
		}

		@Test
		@DisplayName("null lat with non-null lon for departure produces null match")
		fun nullDepartureLatOnly() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = null,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = listOf(cluster()),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.departurePlaceMatch.shouldBeNull()
		}

		@Test
		@DisplayName("non-null lat with null lon for departure produces null match")
		fun nullDepartureLonOnly() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = null,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = listOf(cluster()),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.departurePlaceMatch.shouldBeNull()
		}

		@Test
		@DisplayName("null lat with non-null lon for arrival produces null match")
		fun nullArrivalLatOnly() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = null,
				arrivalLonE7 = farLon,
				existingClusters = listOf(cluster()),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.arrivalPlaceMatch.shouldBeNull()
		}

		@Test
		@DisplayName("non-null lat with null lon for arrival produces null match")
		fun nullArrivalLonOnly() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = null,
				existingClusters = listOf(cluster()),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.arrivalPlaceMatch.shouldBeNull()
		}

		@Test
		@DisplayName("both departure and arrival null produces both matches null")
		fun allCoordinatesNull() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = null,
				departureLonE7 = null,
				arrivalLatE7 = null,
				arrivalLonE7 = null,
				existingClusters = listOf(cluster()),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.departurePlaceMatch.shouldBeNull()
			result.arrivalPlaceMatch.shouldBeNull()
		}

		@Test
		@DisplayName("empty cluster list with valid coordinates produces NewPlace results")
		fun emptyClustersWithValidCoordinates() {
			val event = tripEndedEvent()

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.departurePlaceMatch.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
			result.arrivalPlaceMatch.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
		}

		@Test
		@DisplayName("zero steps in event produces null steps in enriched trip")
		fun zeroStepsProducesNull() {
			val event = tripEndedEvent(totalSteps = 0)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.steps.shouldBeNull()
		}

		@Test
		@DisplayName("negative steps in event are preserved (not nulled)")
		fun negativeStepsPreserved() {
			// Negative steps should pass through since only zero is nulled
			val event = tripEndedEvent(totalSteps = -1)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			// -1 > 0 is false, so steps should be null
			result.steps.shouldBeNull()
		}

		@Test
		@DisplayName("single step is preserved as non-null")
		fun singleStepPreserved() {
			val event = tripEndedEvent(totalSteps = 1)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.steps shouldBe 1
		}

		@Test
		@DisplayName("zero distance is preserved in enriched trip")
		fun zeroDistancePreserved() {
			val event = tripEndedEvent(totalDistanceM = 0f)

			val result = enricher.enrich(
				event = event,
				departureLatE7 = baseLat,
				departureLonE7 = baseLon,
				arrivalLatE7 = farLat,
				arrivalLonE7 = farLon,
				existingClusters = emptyList(),
				source = defaultSource,
				inferenceVersion = defaultInferenceVersion,
			)

			result.distanceM shouldBe 0f
		}

		@Test
		@DisplayName("enriches correctly with all TransportMode values")
		fun allTransportModes() {
			for (mode in TransportMode.entries) {
				val event = tripEndedEvent(inferredTransportMode = mode)

				val result = enricher.enrich(
					event = event,
					departureLatE7 = baseLat,
					departureLonE7 = baseLon,
					arrivalLatE7 = farLat,
					arrivalLonE7 = farLon,
					existingClusters = emptyList(),
					source = defaultSource,
					inferenceVersion = defaultInferenceVersion,
				)

				result.transportMode shouldBe mode
				result.legs.first().transportMode shouldBe mode
			}
		}

		@Test
		@DisplayName("cluster just at match radius boundary is included")
		fun clusterAtBoundaryIncluded() {
			// Default matchRadiusM is 150f. Place cluster exactly at ~150m distance.
			// 150m / 0.0111 m-per-E7 ~ 13514 E7 units latitude offset
			val offsetE7 = 13500 // slightly under 150m
			val clusters = listOf(
				cluster(id = 77L, centerLatE7 = baseLat + offsetE7, centerLonE7 = baseLon)
			)

			val result = enricher.matchPlace(baseLat, baseLon, clusters)

			result.shouldBeInstanceOf<PlaceMatchResult.Matched>()
		}

		@Test
		@DisplayName("cluster just beyond match radius boundary is excluded")
		fun clusterBeyondBoundaryExcluded() {
			// Place cluster beyond 150m. 150m / 0.0111 ~ 13514 E7 units
			val offsetE7 = 14000 // slightly over 150m
			val clusters = listOf(
				cluster(id = 78L, centerLatE7 = baseLat + offsetE7, centerLonE7 = baseLon)
			)

			val result = enricher.matchPlace(baseLat, baseLon, clusters)

			result.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
		}
	}
}
