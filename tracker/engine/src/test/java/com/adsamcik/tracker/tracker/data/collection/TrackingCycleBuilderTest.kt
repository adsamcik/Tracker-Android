package com.adsamcik.tracker.tracker.data.collection

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TrackingCycleBuilder")
class TrackingCycleBuilderTest {

	companion object {
		private const val TIME_MS = 1_700_000_000_000L
		private const val ELAPSED_NANOS = 5_000_000_000L
	}

	@Nested
	@DisplayName("Constructor")
	inner class Constructor {

		@Test
		fun `timestamps are set from constructor`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.timestampMs shouldBe TIME_MS
			builder.elapsedRealtimeNanos shouldBe ELAPSED_NANOS
		}
	}

	@Nested
	@DisplayName("Default values")
	inner class Defaults {

		@Test
		fun `all nullable fields default to null`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.activity.shouldBeNull()
			builder.location.shouldBeNull()
			builder.cellScan.shouldBeNull()
			builder.wifiScan.shouldBeNull()
			builder.stepDelta.shouldBeNull()
			builder.totalStepsSinceBoot.shouldBeNull()
			builder.pressure.shouldBeNull()
			builder.rawGpsAltitude.shouldBeNull()
		}

		@Test
		fun `stepSensorValueStart defaults to 0`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.stepSensorValueStart shouldBe 0
		}

		@Test
		fun `stepSensorValueEnd defaults to 0`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.stepSensorValueEnd shouldBe 0
		}

		@Test
		fun `stepSensorReset defaults to false`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.stepSensorReset shouldBe false
		}
	}

	@Nested
	@DisplayName("build() produces correct snapshot")
	inner class Build {

		@Test
		fun `all fields populated`() {
			val activity = ActivityInfo(activityType = 7, confidence = 80)
			val pressure = PressureReading(pressureHpa = 1013.25f, altitudeM = 250.0f)
			val cellScan = mockk<CellScanData>(relaxed = true)
			val wifiScan = mockk<WifiScanData>(relaxed = true)

			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS).apply {
				this.activity = activity
				this.cellScan = cellScan
				this.wifiScan = wifiScan
				this.stepDelta = 15
				this.totalStepsSinceBoot = 5000L
				this.stepSensorValueStart = 100
				this.stepSensorValueEnd = 115
				this.stepSensorReset = true
				this.pressure = pressure
				this.rawGpsAltitude = 200.5
			}

			val cycle = builder.build()

			cycle.timestampMs shouldBe TIME_MS
			cycle.elapsedRealtimeNanos shouldBe ELAPSED_NANOS
			cycle.activity shouldBe activity
			cycle.cellScan shouldBe cellScan
			cycle.wifiScan shouldBe wifiScan
			cycle.stepDelta shouldBe 15
			cycle.totalStepsSinceBoot shouldBe 5000L
			cycle.stepSensorValueStart shouldBe 100
			cycle.stepSensorValueEnd shouldBe 115
			cycle.stepSensorReset shouldBe true
			cycle.pressure shouldBe pressure
			cycle.rawGpsAltitude shouldBe 200.5
		}

		@Test
		fun `empty builder produces all-null optional fields`() {
			val cycle = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS).build()

			cycle.activity.shouldBeNull()
			cycle.location.shouldBeNull()
			cycle.cellScan.shouldBeNull()
			cycle.wifiScan.shouldBeNull()
			cycle.stepDelta.shouldBeNull()
			cycle.totalStepsSinceBoot.shouldBeNull()
			cycle.pressure.shouldBeNull()
			cycle.rawGpsAltitude.shouldBeNull()
			cycle.stepSensorValueStart shouldBe 0
			cycle.stepSensorValueEnd shouldBe 0
			cycle.stepSensorReset shouldBe false
		}
	}

	@Nested
	@DisplayName("Immutability after build")
	inner class Immutability {

		@Test
		fun `mutations after build do not affect the cycle`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.stepDelta = 10
			builder.stepSensorReset = false

			val cycle = builder.build()

			builder.stepDelta = 99
			builder.stepSensorReset = true

			cycle.stepDelta shouldBe 10
			cycle.stepSensorReset shouldBe false
		}

		@Test
		fun `multiple builds produce independent cycles`() {
			val builder = TrackingCycleBuilder(TIME_MS, ELAPSED_NANOS)
			builder.stepDelta = 5
			val cycle1 = builder.build()

			builder.stepDelta = 20
			val cycle2 = builder.build()

			cycle1.stepDelta shouldBe 5
			cycle2.stepDelta shouldBe 20
		}
	}
}
