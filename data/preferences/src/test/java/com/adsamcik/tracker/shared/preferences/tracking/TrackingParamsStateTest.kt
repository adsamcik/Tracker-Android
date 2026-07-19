package com.adsamcik.tracker.shared.preferences.tracking

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrackingParamsStateTest {

	@Nested
	inner class `default construction` {
		@Test
		fun `default state has location enabled`() {
			TrackingParamsState().locationEnabled shouldBe true
		}

		@Test
		fun `default state has activity enabled`() {
			TrackingParamsState().activityEnabled shouldBe true
		}

		@Test
		fun `default state has steps enabled`() {
			TrackingParamsState().stepsEnabled shouldBe true
		}

		@Test
		fun `default state has wifi disabled`() {
			TrackingParamsState().wifiEnabled shouldBe false
		}

		@Test
		fun `default state has cell disabled`() {
			TrackingParamsState().cellEnabled shouldBe false
		}

		@Test
		fun `default state has barometer enabled`() {
			TrackingParamsState().barometerEnabled shouldBe true
		}

		@Test
		fun `default state has auto tracking mode 1`() {
			TrackingParamsState().autoTrackingMode shouldBe 1
		}

		@Test
		fun `default state has transition detection enabled`() {
			TrackingParamsState().transitionDetectionEnabled shouldBe true
		}

		@Test
		fun `default state has styled notification enabled`() {
			TrackingParamsState().notificationStyled shouldBe true
		}

		@Test
		fun `default state has ski detection disabled`() {
			TrackingParamsState().skiDetectionEnabled shouldBe false
		}

		@Test
		fun `default distance matches companion constant`() {
			TrackingParamsState().minDistanceMeters shouldBe TrackingParamsState.DEFAULT_MIN_DISTANCE
		}

		@Test
		fun `default time matches companion constant`() {
			TrackingParamsState().minTimeSeconds shouldBe TrackingParamsState.DEFAULT_MIN_TIME
		}

		@Test
		fun `default accuracy matches companion constant`() {
			TrackingParamsState().requiredAccuracyMeters shouldBe TrackingParamsState.DEFAULT_REQUIRED_ACCURACY
		}

		@Test
		fun `default preset name matches companion constant`() {
			TrackingParamsState().presetName shouldBe TrackingParamsState.DEFAULT_PRESET
		}
	}

	@Nested
	inner class `companion constants` {
		@Test
		fun `DEFAULT_MIN_DISTANCE is 10`() {
			TrackingParamsState.DEFAULT_MIN_DISTANCE shouldBe 10
		}

		@Test
		fun `DEFAULT_MIN_TIME is 2`() {
			TrackingParamsState.DEFAULT_MIN_TIME shouldBe 2
		}

		@Test
		fun `DEFAULT_REQUIRED_ACCURACY is 50`() {
			TrackingParamsState.DEFAULT_REQUIRED_ACCURACY shouldBe 50
		}

		@Test
		fun `DEFAULT_PRESET is BALANCED`() {
			TrackingParamsState.DEFAULT_PRESET shouldBe "BALANCED"
		}
	}

	@Nested
	inner class `preset property` {
		@Test
		fun `preset resolves BALANCED by default`() {
			TrackingParamsState().preset shouldBe TrackingPreset.BALANCED
		}

		@Test
		fun `preset resolves HIGH_ACCURACY from name`() {
			TrackingParamsState(presetName = "HIGH_ACCURACY").preset shouldBe TrackingPreset.HIGH_ACCURACY
		}

		@Test
		fun `preset resolves POWER_SAVE from name`() {
			TrackingParamsState(presetName = "POWER_SAVE").preset shouldBe TrackingPreset.POWER_SAVE
		}

		@Test
		fun `preset resolves CUSTOM from name`() {
			TrackingParamsState(presetName = "CUSTOM").preset shouldBe TrackingPreset.CUSTOM
		}

		@Test
		fun `preset resolves legacy BATTERY_SAVER to POWER_SAVE`() {
			TrackingParamsState(presetName = "BATTERY_SAVER").preset shouldBe TrackingPreset.POWER_SAVE
		}

		@Test
		fun `preset resolves legacy HIGH_PRECISION to HIGH_ACCURACY`() {
			TrackingParamsState(presetName = "HIGH_PRECISION").preset shouldBe TrackingPreset.HIGH_ACCURACY
		}

		@Test
		fun `preset falls back to DEFAULT for unknown name`() {
			TrackingParamsState(presetName = "UNKNOWN").preset shouldBe TrackingPreset.DEFAULT
		}
	}

	@Nested
	inner class `data class behavior` {
		@Test
		fun `copy preserves unmodified fields`() {
			val original = TrackingParamsState()
			val copied = original.copy(locationEnabled = false)
			copied.locationEnabled shouldBe false
			copied.activityEnabled shouldBe original.activityEnabled
			copied.minDistanceMeters shouldBe original.minDistanceMeters
		}

		@Test
		fun `equality for identical states`() {
			TrackingParamsState() shouldBe TrackingParamsState()
		}

		@Test
		fun `inequality for different states`() {
			TrackingParamsState() shouldNotBe TrackingParamsState(locationEnabled = false)
		}

		@Test
		fun `custom construction overrides all fields`() {
			val state = TrackingParamsState(
				locationEnabled = false,
				activityEnabled = false,
				stepsEnabled = false,
				wifiEnabled = true,
				cellEnabled = true,
				barometerEnabled = false,
				autoTrackingMode = 3,
				transitionDetectionEnabled = false,
				notificationStyled = false,
				minDistanceMeters = 99,
				minTimeSeconds = 55,
				requiredAccuracyMeters = 200,
				presetName = "CUSTOM",
				skiDetectionEnabled = true,
			)
			state.locationEnabled shouldBe false
			state.wifiEnabled shouldBe true
			state.cellEnabled shouldBe true
			state.barometerEnabled shouldBe false
			state.autoTrackingMode shouldBe 3
			state.minDistanceMeters shouldBe 99
			state.minTimeSeconds shouldBe 55
			state.requiredAccuracyMeters shouldBe 200
			state.skiDetectionEnabled shouldBe true
		}

		@Test
		fun `barometer availability participates in source viability`() {
			val state = TrackingParamsState(
				locationEnabled = false,
				activityEnabled = false,
				stepsEnabled = false,
				wifiEnabled = false,
				cellEnabled = false,
				barometerEnabled = true,
			)

			state.hasAnyCaptureSource(barometerAvailable = true) shouldBe true
			state.hasAnyCaptureSource(barometerAvailable = false) shouldBe false
		}

		@Test
		fun `permission and hardware dependent sources only count when available`() {
			val unavailableSources = TrackingParamsState(
				locationEnabled = true,
				activityEnabled = true,
				stepsEnabled = true,
				wifiEnabled = true,
				cellEnabled = true,
				barometerEnabled = true,
			)

			unavailableSources.hasAnyCaptureSource(
				locationAvailable = false,
				activityAvailable = false,
				stepsAvailable = false,
				wifiAvailable = false,
				cellAvailable = false,
				barometerAvailable = false,
			) shouldBe false
		}
	}
}
