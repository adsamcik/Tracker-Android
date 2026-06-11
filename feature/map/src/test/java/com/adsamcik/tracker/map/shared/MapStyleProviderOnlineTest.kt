package com.adsamcik.tracker.map.shared

import com.adsamcik.tracker.map.online.TileProvider
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [MapStyleProvider.onlineStyleUri].
 *
 * Offline behaviour (`customStyleJson`, `defaultStyleJson`) is exercised by
 * existing tests against the PMTiles bundle; this file only covers the
 * online helper added in the online-tiles rollout.
 */
class MapStyleProviderOnlineTest {

	@Test
	fun `openfreemap returns the dark style url in dark theme`() {
		MapStyleProvider.onlineStyleUri(TileProvider.OpenFreeMap, isDarkTheme = true) shouldBe
			"https://tiles.openfreemap.org/styles/dark"
	}

	@Test
	fun `openfreemap returns the light style url in light theme`() {
		MapStyleProvider.onlineStyleUri(TileProvider.OpenFreeMap, isDarkTheme = false) shouldBe
			"https://tiles.openfreemap.org/styles/liberty"
	}

	@Test
	fun `protomaps returns the dark style url in dark theme`() {
		MapStyleProvider.onlineStyleUri(TileProvider.Protomaps, isDarkTheme = true) shouldBe
			"https://api.protomaps.com/styles/v5/dark.json"
	}

	@Test
	fun `protomaps returns the light style url in light theme`() {
		MapStyleProvider.onlineStyleUri(TileProvider.Protomaps, isDarkTheme = false) shouldBe
			"https://api.protomaps.com/styles/v5/light.json"
	}

	@Test
	fun `custom returns the user-supplied url verbatim`() {
		val provider = TileProvider.Custom("https://example.com/style.json")
		MapStyleProvider.onlineStyleUri(provider, isDarkTheme = false) shouldBe
			"https://example.com/style.json"
		MapStyleProvider.onlineStyleUri(provider, isDarkTheme = true) shouldBe
			"https://example.com/style.json"
	}

	@Test
	fun `custom with blank url collapses to null`() {
		MapStyleProvider.onlineStyleUri(TileProvider.Custom(""), isDarkTheme = false).shouldBeNull()
		MapStyleProvider.onlineStyleUri(TileProvider.Custom("   "), isDarkTheme = true).shouldBeNull()
	}
}
