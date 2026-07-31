package com.adsamcik.tracker.shared.preferences

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PreferenceKeysTest {

	@Nested
	inner class `key values are non-empty` {
		@Test
		fun `ACTIVITY_ENABLED key is non-empty`() {
			PreferenceKeys.ACTIVITY_ENABLED.shouldNotBeEmpty()
		}

		@Test
		fun `AUTO_TRACKING_TRANSITION_ENABLED key is non-empty`() {
			PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED.shouldNotBeEmpty()
		}

		@Test
		fun `BAROMETER_ENABLED key is non-empty`() {
			PreferenceKeys.BAROMETER_ENABLED.shouldNotBeEmpty()
		}

		@Test
		fun `CELL_ENABLED key is non-empty`() {
			PreferenceKeys.CELL_ENABLED.shouldNotBeEmpty()
		}

		@Test
		fun `LENGTH_SYSTEM key is non-empty`() {
			PreferenceKeys.LENGTH_SYSTEM.shouldNotBeEmpty()
		}

		@Test
		fun `LOCATION_ENABLED key is non-empty`() {
			PreferenceKeys.LOCATION_ENABLED.shouldNotBeEmpty()
		}

		@Test
		fun `SPEED_FORMAT key is non-empty`() {
			PreferenceKeys.SPEED_FORMAT.shouldNotBeEmpty()
		}

		@Test
		fun `STEPS_ENABLED key is non-empty`() {
			PreferenceKeys.STEPS_ENABLED.shouldNotBeEmpty()
		}

		@Test
		fun `WIFI_ENABLED key is non-empty`() {
			PreferenceKeys.WIFI_ENABLED.shouldNotBeEmpty()
		}
	}

	@Nested
	inner class `key uniqueness` {
		@Test
		fun `all keys are unique`() {
			val allKeys = listOf(
				PreferenceKeys.ACTIVITY_ENABLED,
				PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED,
				PreferenceKeys.BAROMETER_ENABLED,
				PreferenceKeys.CELL_ENABLED,
				PreferenceKeys.LENGTH_SYSTEM,
				PreferenceKeys.LOCATION_ENABLED,
				PreferenceKeys.NOTIFICATION_STYLED,
				PreferenceKeys.SKI_INFRASTRUCTURE_ENABLED,
				PreferenceKeys.SPEED_FORMAT,
				PreferenceKeys.STEPS_ENABLED,
				PreferenceKeys.TRACKER_TIMER,
				PreferenceKeys.TRACKING_ACTIVITY_MODE,
				PreferenceKeys.TRACKING_MIN_DISTANCE,
				PreferenceKeys.TRACKING_MIN_TIME,
				PreferenceKeys.TRACKING_REQUIRED_ACCURACY,
				PreferenceKeys.WIFI_ENABLED,
				PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED,
				PreferenceKeys.WIFI_NETWORK_ENABLED,
			)
			allKeys.size shouldBe allKeys.toSet().size
		}
	}

	@Nested
	inner class `default values` {
		@Test
		fun `ACTIVITY_ENABLED_DEFAULT is true`() {
			PreferenceKeys.ACTIVITY_ENABLED_DEFAULT shouldBe true
		}

		@Test
		fun `AUTO_TRACKING_TRANSITION_ENABLED_DEFAULT is true`() {
			PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED_DEFAULT shouldBe true
		}

		@Test
		fun `BAROMETER_ENABLED_DEFAULT is true`() {
			PreferenceKeys.BAROMETER_ENABLED_DEFAULT shouldBe true
		}

		@Test
		fun `CELL_ENABLED_DEFAULT is false`() {
			PreferenceKeys.CELL_ENABLED_DEFAULT shouldBe false
		}

		@Test
		fun `LENGTH_SYSTEM_DEFAULT is Metric`() {
			PreferenceKeys.LENGTH_SYSTEM_DEFAULT shouldBe "Metric"
		}

		@Test
		fun `LOCATION_ENABLED_DEFAULT is true`() {
			PreferenceKeys.LOCATION_ENABLED_DEFAULT shouldBe true
		}

		@Test
		fun `NOTIFICATION_STYLED_DEFAULT is true`() {
			PreferenceKeys.NOTIFICATION_STYLED_DEFAULT shouldBe true
		}

		@Test
		fun `SKI_INFRASTRUCTURE_ENABLED_DEFAULT is false`() {
			PreferenceKeys.SKI_INFRASTRUCTURE_ENABLED_DEFAULT shouldBe false
		}

		@Test
		fun `SPEED_FORMAT_DEFAULT is Hour`() {
			PreferenceKeys.SPEED_FORMAT_DEFAULT shouldBe "Hour"
		}

		@Test
		fun `STEPS_ENABLED_DEFAULT is true`() {
			PreferenceKeys.STEPS_ENABLED_DEFAULT shouldBe true
		}

		@Test
		fun `TRACKING_ACTIVITY_MODE_DEFAULT is 1`() {
			PreferenceKeys.TRACKING_ACTIVITY_MODE_DEFAULT shouldBe 1
		}

		@Test
		fun `TRACKING_MIN_DISTANCE_DEFAULT is 10`() {
			PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT shouldBe 10
		}

		@Test
		fun `TRACKING_MIN_TIME_DEFAULT is 2`() {
			PreferenceKeys.TRACKING_MIN_TIME_DEFAULT shouldBe 2
		}

		@Test
		fun `TRACKING_REQUIRED_ACCURACY_DEFAULT is 50`() {
			PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT shouldBe 50
		}

		@Test
		fun `WIFI_ENABLED_DEFAULT is false`() {
			PreferenceKeys.WIFI_ENABLED_DEFAULT shouldBe false
		}

		@Test
		fun `WIFI_LOCATION_COUNT_ENABLED_DEFAULT is false`() {
			PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED_DEFAULT shouldBe false
		}

		@Test
		fun `WIFI_NETWORK_ENABLED_DEFAULT is false`() {
			PreferenceKeys.WIFI_NETWORK_ENABLED_DEFAULT shouldBe false
		}
	}

	@Nested
	inner class `specific key values` {
		@Test
		fun `ACTIVITY_ENABLED has expected value`() {
			PreferenceKeys.ACTIVITY_ENABLED shouldBe "trackingActivityEnabled"
		}

		@Test
		fun `LENGTH_SYSTEM has expected value`() {
			PreferenceKeys.LENGTH_SYSTEM shouldBe "lengthSystem"
		}

		@Test
		fun `SPEED_FORMAT has expected value`() {
			PreferenceKeys.SPEED_FORMAT shouldBe "speedFormat"
		}

		@Test
		fun `TRACKING_MIN_DISTANCE has expected value`() {
			PreferenceKeys.TRACKING_MIN_DISTANCE shouldBe "minTrackingDistance"
		}

		@Test
		fun `TRACKING_MIN_TIME has expected value`() {
			PreferenceKeys.TRACKING_MIN_TIME shouldBe "minTrackingTimeDifference"
		}

		@Test
		fun `TRACKING_REQUIRED_ACCURACY has expected value`() {
			PreferenceKeys.TRACKING_REQUIRED_ACCURACY shouldBe "requiredTrackingAccuracy"
		}
	}
}
