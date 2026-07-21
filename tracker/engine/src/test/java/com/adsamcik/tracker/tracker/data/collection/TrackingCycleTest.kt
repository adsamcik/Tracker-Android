package com.adsamcik.tracker.tracker.data.collection

import android.location.Location
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.component.producer.PressureReading
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrackingCycleTest {

	companion object {
		private const val TIME_MS = 1_700_000_000_000L
		private const val ELAPSED_NANOS = 5_000_000_000L
	}

	// --- Helpers ---

	private fun createLocation(lat: Double = 50.0, lon: Double = 14.0): Location {
		val loc = mockk<Location>(relaxed = true)
		every { loc.latitude } returns lat
		every { loc.longitude } returns lon
		return loc
	}

	private fun createLocationData(lat: Double = 50.0, lon: Double = 14.0): LocationData {
		return LocationData(
			locations = listOf(createLocation(lat, lon)),
			previousLocation = null,
			distance = null,
		)
	}

	// --- Tests ---

	@Nested
	inner class `builder sets all fields correctly` {

		@Test
		fun `all fields populated round-trip through build`() {
			val activity = ActivityInfo(activityType = 7, confidence = 80)
			val location = createLocationData()
			val cell = mockk<CellScanData>(relaxed = true)
			val wifi = mockk<WifiScanData>(relaxed = true)
			val pressure = PressureReading(pressureHpa = 1013.25f, altitudeM = 250.0f)

			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.activity = activity
			builder.location = location
			builder.cellScan = cell
			builder.wifiScan = wifi
			builder.stepDelta = 42
			builder.totalStepsSinceBoot = 10_000L
			builder.pressure = pressure
			builder.rawGpsAltitude = 248.5

			val cycle = builder.build()

			cycle.timestampMs shouldBe TIME_MS
			cycle.elapsedRealtimeNanos shouldBe ELAPSED_NANOS
			cycle.activity shouldBe activity
			cycle.location shouldBe location
			cycle.cellScan shouldBe cell
			cycle.wifiScan shouldBe wifi
			cycle.stepDelta shouldBe 42
			cycle.totalStepsSinceBoot shouldBe 10_000L
			cycle.pressure shouldBe pressure
			cycle.rawGpsAltitude shouldBe 248.5
		}
	}

	@Nested
	inner class `build produces immutable snapshot` {

		@Test
		fun `mutations after build do not affect cycle`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.stepDelta = 10

			val cycle = builder.build()

			builder.stepDelta = 99

			cycle.stepDelta shouldBe 10
		}
	}

	@Nested
	inner class `null fields remain null` {

		@Test
		fun `builder with no fields set produces all-null cycle`() {
			val cycle = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS).build()

			cycle.timestampMs shouldBe TIME_MS
			cycle.elapsedRealtimeNanos shouldBe ELAPSED_NANOS
			cycle.activity.shouldBeNull()
			cycle.location.shouldBeNull()
			cycle.cellScan.shouldBeNull()
			cycle.wifiScan.shouldBeNull()
			cycle.stepDelta.shouldBeNull()
			cycle.totalStepsSinceBoot.shouldBeNull()
			cycle.pressure.shouldBeNull()
			cycle.rawGpsAltitude.shouldBeNull()
		}

		@Test
		fun `partial fields leave others null`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.location = createLocationData()

			val cycle = builder.build()

			cycle.location shouldBe builder.location
			cycle.activity.shouldBeNull()
			cycle.cellScan.shouldBeNull()
			cycle.wifiScan.shouldBeNull()
			cycle.stepDelta.shouldBeNull()
			cycle.totalStepsSinceBoot.shouldBeNull()
			cycle.pressure.shouldBeNull()
			cycle.rawGpsAltitude.shouldBeNull()
		}
	}

	@Nested
	inner class `data class equality` {

		@Test
		fun `two cycles with same data are equal`() {
			val activity = ActivityInfo(activityType = 0, confidence = 100)
			val a = TrackingCycle(
				TIME_MS,
				ELAPSED_NANOS,
				activity = activity,
				stepDelta = 5,
				persistenceSignalId = "same-cycle",
			)
			val b = TrackingCycle(
				TIME_MS,
				ELAPSED_NANOS,
				activity = activity,
				stepDelta = 5,
				persistenceSignalId = "same-cycle",
			)

			a shouldBe b
		}

		@Test
		fun `copy preserves equality`() {
			val original = TrackingCycle(TIME_MS, ELAPSED_NANOS, stepDelta = 3)
			val copied = original.copy()

			copied shouldBe original
		}
	}

	@Nested
	inner class `persistable producer payload` {

		@Test
		fun `freshness gates cached activity and cell snapshots`() {
			val base = TrackingCycle(timestampMs = TIME_MS, elapsedRealtimeNanos = ELAPSED_NANOS)
			val activity = ActivityInfo.UNKNOWN
			val cell = mockk<CellScanData>(relaxed = true)

			base.copy(activity = activity).hasPersistableProducerPayload() shouldBe false
			base.copy(activity = activity, activityFresh = true)
				.hasPersistableProducerPayload() shouldBe true
			base.copy(cellScan = cell).hasPersistableProducerPayload() shouldBe false
			base.copy(cellScan = cell, cellScanFresh = true)
				.hasPersistableProducerPayload() shouldBe true
			base.copy(cellScanFresh = true).hasPersistableProducerPayload() shouldBe false
		}

		@Test
		fun `wifi steps and pressure require an emitted payload`() {
			val base = TrackingCycle(timestampMs = TIME_MS, elapsedRealtimeNanos = ELAPSED_NANOS)

			base.hasPersistableProducerPayload() shouldBe false
			base.copy(wifiScan = mockk(relaxed = true))
				.hasPersistableProducerPayload() shouldBe true
			base.copy(stepDelta = 0).hasPersistableProducerPayload() shouldBe false
			base.copy(stepDelta = 1).hasPersistableProducerPayload() shouldBe true
			base.copy(pressure = PressureReading(1_013.25f, 0f))
				.hasPersistableProducerPayload() shouldBe true
		}
	}
}
