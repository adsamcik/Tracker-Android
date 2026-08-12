package com.adsamcik.tracker.stats.api

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TransportModeTest {

	@Nested
	inner class EnumValues {

		@Test
		fun `TransportMode has eight values`() {
			TransportMode.entries shouldHaveSize 8
		}

		@Test
		fun `all transport modes exist`() {
			TransportMode.entries shouldBe listOf(
				TransportMode.WALK,
				TransportMode.RUN,
				TransportMode.CYCLE,
				TransportMode.DRIVE,
				TransportMode.TRANSIT,
				TransportMode.HIGH_SPEED_RAIL,
				TransportMode.AIR,
				TransportMode.UNKNOWN,
			)
		}

		@Test
		fun `all transport mode names are distinct`() {
			val names = TransportMode.entries.map { it.name }
			names.toSet().size shouldBe names.size
		}
	}

	@Nested
	inner class HumanPowered {

		@Test
		fun `WALK is human powered`() {
			TransportMode.WALK.isHumanPowered.shouldBeTrue()
		}

		@Test
		fun `RUN is human powered`() {
			TransportMode.RUN.isHumanPowered.shouldBeTrue()
		}

		@Test
		fun `CYCLE is human powered`() {
			TransportMode.CYCLE.isHumanPowered.shouldBeTrue()
		}

		@Test
		fun `DRIVE is not human powered`() {
			TransportMode.DRIVE.isHumanPowered.shouldBeFalse()
		}

		@Test
		fun `TRANSIT is not human powered`() {
			TransportMode.TRANSIT.isHumanPowered.shouldBeFalse()
		}

		@Test
		fun `HIGH_SPEED_RAIL is not human powered`() {
			TransportMode.HIGH_SPEED_RAIL.isHumanPowered.shouldBeFalse()
		}

		@Test
		fun `AIR is not human powered`() {
			TransportMode.AIR.isHumanPowered.shouldBeFalse()
		}

		@Test
		fun `UNKNOWN is not human powered`() {
			TransportMode.UNKNOWN.isHumanPowered.shouldBeFalse()
		}
	}

	@Nested
	inner class GpsInterval {

		@Test
		fun `all modes have positive GPS interval`() {
			TransportMode.entries.forEach { mode ->
				assert(mode.typicalGpsIntervalMs > 0) {
					"${mode.name} should have positive GPS interval"
				}
			}
		}

		@Test
		fun `WALK has 25 second interval`() {
			TransportMode.WALK.typicalGpsIntervalMs shouldBe 25_000L
		}

		@Test
		fun `DRIVE has fastest interval at 5 seconds`() {
			TransportMode.DRIVE.typicalGpsIntervalMs shouldBe 5_000L
		}

		@Test
		fun `AIR has slowest interval at 30 seconds`() {
			TransportMode.AIR.typicalGpsIntervalMs shouldBe 30_000L
		}

		@Test
		fun `DRIVE has shorter interval than WALK`() {
			assert(TransportMode.DRIVE.typicalGpsIntervalMs < TransportMode.WALK.typicalGpsIntervalMs)
		}
	}

	@Nested
	inner class TripStateValues {

		@Test
		fun `TripState has five values`() {
			TripState.entries shouldHaveSize 5
		}

		@Test
		fun `TripState values are ordered correctly`() {
			TripState.entries shouldBe listOf(
				TripState.STATIONARY,
				TripState.DEPARTING,
				TripState.IN_TRIP,
				TripState.STOP_PENDING,
				TripState.ARRIVED,
			)
		}
	}

	@Nested
	inner class TripStateIsInTrip {

		@Test
		fun `STATIONARY is not in trip`() {
			TripState.STATIONARY.isInTrip.shouldBeFalse()
		}

		@Test
		fun `DEPARTING is not in trip`() {
			TripState.DEPARTING.isInTrip.shouldBeFalse()
		}

		@Test
		fun `IN_TRIP is in trip`() {
			TripState.IN_TRIP.isInTrip.shouldBeTrue()
		}

		@Test
		fun `STOP_PENDING is in trip`() {
			TripState.STOP_PENDING.isInTrip.shouldBeTrue()
		}

		@Test
		fun `ARRIVED is not in trip`() {
			TripState.ARRIVED.isInTrip.shouldBeFalse()
		}
	}

	@Nested
	inner class TripStateIsTransitional {

		@Test
		fun `STATIONARY is not transitional`() {
			TripState.STATIONARY.isTransitional.shouldBeFalse()
		}

		@Test
		fun `DEPARTING is transitional`() {
			TripState.DEPARTING.isTransitional.shouldBeTrue()
		}

		@Test
		fun `IN_TRIP is not transitional`() {
			TripState.IN_TRIP.isTransitional.shouldBeFalse()
		}

		@Test
		fun `STOP_PENDING is transitional`() {
			TripState.STOP_PENDING.isTransitional.shouldBeTrue()
		}

		@Test
		fun `ARRIVED is transitional`() {
			TripState.ARRIVED.isTransitional.shouldBeTrue()
		}
	}

	@Nested
	inner class DetectedActivityTypeValues {

		@Test
		fun `DetectedActivityType has eight values`() {
			DetectedActivityType.entries shouldHaveSize 8
		}

		@Test
		fun `all activity types exist`() {
			DetectedActivityType.entries shouldBe listOf(
				DetectedActivityType.STILL,
				DetectedActivityType.WALKING,
				DetectedActivityType.RUNNING,
				DetectedActivityType.ON_BICYCLE,
				DetectedActivityType.IN_VEHICLE,
				DetectedActivityType.ON_FOOT,
				DetectedActivityType.TILTING,
				DetectedActivityType.UNKNOWN,
			)
		}
	}

	@Nested
	inner class DetectedActivityTypeIsMoving {

		@Test
		fun `STILL is not moving`() {
			DetectedActivityType.STILL.isMoving.shouldBeFalse()
		}

		@Test
		fun `WALKING is moving`() {
			DetectedActivityType.WALKING.isMoving.shouldBeTrue()
		}

		@Test
		fun `RUNNING is moving`() {
			DetectedActivityType.RUNNING.isMoving.shouldBeTrue()
		}

		@Test
		fun `ON_BICYCLE is moving`() {
			DetectedActivityType.ON_BICYCLE.isMoving.shouldBeTrue()
		}

		@Test
		fun `IN_VEHICLE is moving`() {
			DetectedActivityType.IN_VEHICLE.isMoving.shouldBeTrue()
		}

		@Test
		fun `ON_FOOT is moving`() {
			DetectedActivityType.ON_FOOT.isMoving.shouldBeTrue()
		}

		@Test
		fun `TILTING is not moving`() {
			DetectedActivityType.TILTING.isMoving.shouldBeFalse()
		}

		@Test
		fun `UNKNOWN is not moving`() {
			DetectedActivityType.UNKNOWN.isMoving.shouldBeFalse()
		}
	}

	@Nested
	inner class DetectedActivityTypeIsLocomotion {

		@Test
		fun `WALKING is locomotion`() {
			DetectedActivityType.WALKING.isLocomotion.shouldBeTrue()
		}

		@Test
		fun `RUNNING is locomotion`() {
			DetectedActivityType.RUNNING.isLocomotion.shouldBeTrue()
		}

		@Test
		fun `ON_BICYCLE is locomotion`() {
			DetectedActivityType.ON_BICYCLE.isLocomotion.shouldBeTrue()
		}

		@Test
		fun `ON_FOOT is locomotion`() {
			DetectedActivityType.ON_FOOT.isLocomotion.shouldBeTrue()
		}

		@Test
		fun `IN_VEHICLE is not locomotion`() {
			DetectedActivityType.IN_VEHICLE.isLocomotion.shouldBeFalse()
		}

		@Test
		fun `STILL is not locomotion`() {
			DetectedActivityType.STILL.isLocomotion.shouldBeFalse()
		}

		@Test
		fun `TILTING is not locomotion`() {
			DetectedActivityType.TILTING.isLocomotion.shouldBeFalse()
		}

		@Test
		fun `UNKNOWN is not locomotion`() {
			DetectedActivityType.UNKNOWN.isLocomotion.shouldBeFalse()
		}
	}

	@Nested
	inner class SegmentSignalConstruction {

		@Test
		fun `minimal signal with only timestamp`() {
			val signal = SegmentSignal(timestampMs = 1_700_000_000_000L)
			signal.timestampMs shouldBe 1_700_000_000_000L
			signal.latE7.shouldBeNull()
			signal.lonE7.shouldBeNull()
			signal.horizontalAccuracyM.shouldBeNull()
			signal.speedMps.shouldBeNull()
			signal.stepDelta shouldBe 0
			signal.activityType.shouldBeNull()
			signal.activityConfidence.shouldBeNull()
			signal.distanceDeltaM.shouldBeNull()
		}

		@Test
		fun `full signal with all fields`() {
			val signal = SegmentSignal(
				timestampMs = 1_700_000_000_000L,
				latE7 = 487_000_000,
				lonE7 = 163_000_000,
				horizontalAccuracyM = 5.0f,
				speedMps = 1.5f,
				stepDelta = 12,
				activityType = DetectedActivityType.WALKING,
				activityConfidence = 85,
				distanceDeltaM = 15.0f,
			)
			signal.latE7 shouldBe 487_000_000
			signal.lonE7 shouldBe 163_000_000
			signal.horizontalAccuracyM shouldBe 5.0f
			signal.speedMps shouldBe 1.5f
			signal.stepDelta shouldBe 12
			signal.activityType shouldBe DetectedActivityType.WALKING
			signal.activityConfidence shouldBe 85
			signal.distanceDeltaM shouldBe 15.0f
		}

		@Test
		fun `signal data class equality`() {
			val signal1 = SegmentSignal(timestampMs = 1000L, stepDelta = 5)
			val signal2 = SegmentSignal(timestampMs = 1000L, stepDelta = 5)
			signal1 shouldBe signal2
		}
	}

	@Nested
	inner class SegmentEventTypes {

		@Test
		fun `TripStarted is a SegmentEvent`() {
			val event: SegmentEvent = SegmentEvent.TripStarted(
				startTimeMs = 1000L,
				triggerActivity = DetectedActivityType.WALKING,
			)
			event.shouldNotBeNull()
		}

		@Test
		fun `TripUpdated preserves state`() {
			val event = SegmentEvent.TripUpdated(
				currentTimeMs = 2000L,
				accumulatedDistanceM = 150.0f,
				accumulatedSteps = 200,
				sampleCount = 10,
				currentState = TripState.IN_TRIP,
			)
			event.currentState shouldBe TripState.IN_TRIP
			event.accumulatedDistanceM shouldBe 150.0f
		}

		@Test
		fun `TripEnded contains transport mode`() {
			val event = SegmentEvent.TripEnded(
				startTimeMs = 1000L,
				endTimeMs = 5000L,
				totalDistanceM = 500.0f,
				totalSteps = 600,
				sampleCount = 40,
				primaryActivity = DetectedActivityType.WALKING,
				averageActivityConfidence = 80,
				inferredTransportMode = TransportMode.WALK,
			)
			event.inferredTransportMode shouldBe TransportMode.WALK
			event.totalDistanceM shouldBe 500.0f
		}

		@Test
		fun `TripStarted with null trigger activity`() {
			val event = SegmentEvent.TripStarted(
				startTimeMs = 1000L,
				triggerActivity = null,
			)
			event.triggerActivity.shouldBeNull()
		}

		@Test
		fun `TripEnded with null activity fields`() {
			val event = SegmentEvent.TripEnded(
				startTimeMs = 1000L,
				endTimeMs = 5000L,
				totalDistanceM = 100.0f,
				totalSteps = 0,
				sampleCount = 5,
				primaryActivity = null,
				averageActivityConfidence = null,
				inferredTransportMode = TransportMode.UNKNOWN,
			)
			event.primaryActivity.shouldBeNull()
			event.averageActivityConfidence.shouldBeNull()
		}

		@Test
		fun `DepartureCancelled preserves reason`() {
			val event = SegmentEvent.DepartureCancelled(
				timestampMs = 3000L,
				reason = "GPS drift",
			)
			event.reason shouldBe "GPS drift"
			event.timestampMs shouldBe 3000L
		}
	}

	@Nested
	inner class DiscoveryQualityValues {

		@Test
		fun `DiscoveryQuality has five values`() {
			DiscoveryQuality.entries shouldHaveSize 5
		}

		@Test
		fun `quality values are ordered correctly`() {
			DiscoveryQuality.entries shouldBe listOf(
				DiscoveryQuality.PASSED_THROUGH,
				DiscoveryQuality.CYCLED_THROUGH,
				DiscoveryQuality.TRAVERSED_ON_FOOT,
				DiscoveryQuality.EXPLORED,
				DiscoveryQuality.THOROUGHLY_EXPLORED,
			)
		}

		@Test
		fun `multipliers increase with quality`() {
			val qualities = DiscoveryQuality.entries
			for (i in 0 until qualities.size - 1) {
				assert(qualities[i].multiplier < qualities[i + 1].multiplier) {
					"${qualities[i].name} (${qualities[i].multiplier}) should be less than ${qualities[i + 1].name} (${qualities[i + 1].multiplier})"
				}
			}
		}

		@Test
		fun `PASSED_THROUGH has 0_5 multiplier`() {
			DiscoveryQuality.PASSED_THROUGH.multiplier shouldBe 0.5
		}

		@Test
		fun `TRAVERSED_ON_FOOT has 1_0 multiplier`() {
			DiscoveryQuality.TRAVERSED_ON_FOOT.multiplier shouldBe 1.0
		}

		@Test
		fun `THOROUGHLY_EXPLORED has 2_0 multiplier`() {
			DiscoveryQuality.THOROUGHLY_EXPLORED.multiplier shouldBe 2.0
		}
	}

	@Nested
	inner class AggregatorSignalConstruction {

		@Test
		fun `minimal aggregator signal`() {
			val signal = AggregatorSignal(timestampMs = 1000L)
			signal.timestampMs shouldBe 1000L
			signal.distanceDeltaM.shouldBeNull()
			signal.speedMps.shouldBeNull()
			signal.stepDelta shouldBe 0
			signal.activityType.shouldBeNull()
			signal.activityConfidence.shouldBeNull()
		}

		@Test
		fun `full aggregator signal`() {
			val signal = AggregatorSignal(
				timestampMs = 1000L,
				distanceDeltaM = 10.0f,
				speedMps = 1.2f,
				stepDelta = 15,
				activityType = DetectedActivityType.RUNNING,
				activityConfidence = 90,
			)
			signal.distanceDeltaM shouldBe 10.0f
			signal.stepDelta shouldBe 15
			signal.activityType shouldBe DetectedActivityType.RUNNING
		}
	}

	@Nested
	inner class AggregatorSnapshotConstruction {

		@Test
		fun `snapshot preserves all fields`() {
			val snapshot = AggregatorSnapshot(
				sessionStartMs = 1000L,
				lastUpdateMs = 2000L,
				sessionDistanceM = 500.0f,
				sessionSteps = 650,
				sessionDurationMs = 1000L,
				dayTotalDistanceM = 5000.0f,
				dayTotalSteps = 6500,
				dayTotalDurationMs = 10_000L,
				currentSpeedMps = 1.5f,
				avgSpeedMps = 1.2f,
				maxSpeedMps = 2.0f,
				sampleCount = 100,
				dominantActivity = DetectedActivityType.WALKING,
				tripCount = 3,
			)
			snapshot.sessionStartMs shouldBe 1000L
			snapshot.sessionDistanceM shouldBe 500.0f
			snapshot.dayTotalSteps shouldBe 6500
			snapshot.dominantActivity shouldBe DetectedActivityType.WALKING
			snapshot.tripCount shouldBe 3
		}

		@Test
		fun `snapshot with null optional fields`() {
			val snapshot = AggregatorSnapshot(
				sessionStartMs = 1000L,
				lastUpdateMs = 1000L,
				sessionDistanceM = 0.0f,
				sessionSteps = 0,
				sessionDurationMs = 0L,
				dayTotalDistanceM = 0.0f,
				dayTotalSteps = 0,
				dayTotalDurationMs = 0L,
				currentSpeedMps = null,
				avgSpeedMps = 0.0f,
				maxSpeedMps = 0.0f,
				sampleCount = 0,
				dominantActivity = null,
				tripCount = 0,
			)
			snapshot.currentSpeedMps.shouldBeNull()
			snapshot.dominantActivity.shouldBeNull()
		}
	}
}
