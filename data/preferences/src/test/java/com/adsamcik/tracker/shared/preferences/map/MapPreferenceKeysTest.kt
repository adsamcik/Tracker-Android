package com.adsamcik.tracker.shared.preferences.map

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MapPreferenceKeysTest {

	@Nested
	inner class `key values are non-empty` {
		@Test
		fun `LEGACY_QUALITY is non-empty`() {
			MapPreferenceKeys.LEGACY_QUALITY.shouldNotBeEmpty()
		}

		@Test
		fun `LEGACY_MAX_HEAT is non-empty`() {
			MapPreferenceKeys.LEGACY_MAX_HEAT.shouldNotBeEmpty()
		}

		@Test
		fun `LEGACY_VISIT_THRESHOLD is non-empty`() {
			MapPreferenceKeys.LEGACY_VISIT_THRESHOLD.shouldNotBeEmpty()
		}

		@Test
		fun `BASEMAP_PATH is non-empty`() {
			MapPreferenceKeys.BASEMAP_PATH.shouldNotBeEmpty()
		}
	}

	@Nested
	inner class `key uniqueness` {
		@Test
		fun `all keys are unique`() {
			val allKeys = listOf(
				MapPreferenceKeys.LEGACY_QUALITY,
				MapPreferenceKeys.LEGACY_MAX_HEAT,
				MapPreferenceKeys.LEGACY_VISIT_THRESHOLD,
				MapPreferenceKeys.BASEMAP_PATH,
			)
			allKeys.size shouldBe allKeys.toSet().size
		}
	}

	@Nested
	inner class `specific key values` {
		@Test
		fun `LEGACY_QUALITY has expected value`() {
			MapPreferenceKeys.LEGACY_QUALITY shouldBe "mapHeatmapQuality"
		}

		@Test
		fun `LEGACY_MAX_HEAT has expected value`() {
			MapPreferenceKeys.LEGACY_MAX_HEAT shouldBe "mapMaxHeat"
		}

		@Test
		fun `LEGACY_VISIT_THRESHOLD has expected value`() {
			MapPreferenceKeys.LEGACY_VISIT_THRESHOLD shouldBe "mapVisitThreshold"
		}

		@Test
		fun `BASEMAP_PATH has expected value`() {
			MapPreferenceKeys.BASEMAP_PATH shouldBe "map.basemap.path"
		}
	}

	@Nested
	inner class `object identity` {
		@Test
		fun `MapPreferenceKeys is a singleton`() {
			val ref1 = MapPreferenceKeys
			val ref2 = MapPreferenceKeys
			(ref1 === ref2) shouldBe true
		}
	}
}
