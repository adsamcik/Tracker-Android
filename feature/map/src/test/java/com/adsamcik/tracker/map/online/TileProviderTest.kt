package com.adsamcik.tracker.map.online

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TileProvider")
class TileProviderTest {

	@Nested
	@DisplayName("OpenFreeMap")
	inner class OpenFreeMapTests {

		@Test
		fun `light theme returns liberty style`() {
			TileProvider.OpenFreeMap.styleUrl(isDarkTheme = false) shouldBe
				"https://tiles.openfreemap.org/styles/liberty"
		}

		@Test
		fun `dark theme returns dark style`() {
			TileProvider.OpenFreeMap.styleUrl(isDarkTheme = true) shouldBe
				"https://tiles.openfreemap.org/styles/dark"
		}

		@Test
		fun `id is stable`() {
			TileProvider.OpenFreeMap.id shouldBe "openfreemap"
		}

		@Test
		fun `allowed hosts include tiles subdomain`() {
			TileProvider.OpenFreeMap.allowedHosts shouldContain "tiles.openfreemap.org"
		}

		@Test
		fun `attribution mentions OpenStreetMap and OpenFreeMap`() {
			val attribution = TileProvider.OpenFreeMap.attribution
			attribution shouldContain "OpenStreetMap"
			attribution shouldContain "OpenFreeMap"
		}
	}

	@Nested
	@DisplayName("Protomaps")
	inner class ProtomapsTests {

		@Test
		fun `light theme returns light style json`() {
			TileProvider.Protomaps.styleUrl(isDarkTheme = false) shouldBe
				"https://api.protomaps.com/styles/v5/light.json"
		}

		@Test
		fun `dark theme returns dark style json`() {
			TileProvider.Protomaps.styleUrl(isDarkTheme = true) shouldBe
				"https://api.protomaps.com/styles/v5/dark.json"
		}

		@Test
		fun `id is stable`() {
			TileProvider.Protomaps.id shouldBe "protomaps"
		}

		@Test
		fun `allowed hosts include api subdomain`() {
			TileProvider.Protomaps.allowedHosts shouldContain "api.protomaps.com"
		}

		@Test
		fun `attribution mentions OpenStreetMap and Protomaps`() {
			val attribution = TileProvider.Protomaps.attribution
			attribution shouldContain "OpenStreetMap"
			attribution shouldContain "Protomaps"
		}
	}

	@Nested
	@DisplayName("Custom")
	inner class CustomTests {

		@Test
		fun `id is custom regardless of url`() {
			TileProvider.Custom("https://example.com/style.json").id shouldBe "custom"
		}

		@Test
		fun `style url returns the configured url for both themes`() {
			val url = "https://maps.example.org/v1/style.json"
			val provider = TileProvider.Custom(url)
			provider.styleUrl(isDarkTheme = false) shouldBe url
			provider.styleUrl(isDarkTheme = true) shouldBe url
		}

		@Test
		fun `allowed hosts inferred from url`() {
			TileProvider.Custom("https://Tiles.Example.com/foo/bar.json")
				.allowedHosts shouldBe setOf("tiles.example.com")
		}

		@Test
		fun `blank url yields empty allowed hosts`() {
			TileProvider.Custom("").allowedHosts shouldHaveSize 0
		}

		@Test
		fun `malformed url yields empty allowed hosts`() {
			TileProvider.Custom(":::not a url:::").allowedHosts shouldHaveSize 0
		}

		@Test
		fun `relative url yields empty allowed hosts`() {
			TileProvider.Custom("/no/host/here.json").allowedHosts shouldHaveSize 0
		}
	}

	@Nested
	@DisplayName("companion")
	inner class CompanionTests {

		@Test
		fun `built-in list exposes OpenFreeMap then Protomaps`() {
			TileProvider.builtIn shouldBe listOf(
				TileProvider.OpenFreeMap,
				TileProvider.Protomaps,
			)
		}

		@Test
		fun `built-in list does NOT include Custom`() {
			TileProvider.builtIn.map { it.id } shouldBe listOf("openfreemap", "protomaps")
		}

		@Test
		fun `DEFAULT_ID matches OpenFreeMap`() {
			TileProvider.DEFAULT_ID shouldBe TileProvider.OpenFreeMap.id
		}

		@Test
		fun `resolve maps known ids to providers`() {
			TileProvider.resolve("openfreemap") shouldBe TileProvider.OpenFreeMap
			TileProvider.resolve("protomaps") shouldBe TileProvider.Protomaps
		}

		@Test
		fun `resolve maps custom id with url`() {
			val resolved = TileProvider.resolve("custom", "https://example.com/s.json")
			(resolved is TileProvider.Custom) shouldBe true
			(resolved as TileProvider.Custom).url shouldBe "https://example.com/s.json"
		}

		@Test
		fun `resolve falls back to default for unknown ids`() {
			TileProvider.resolve("does-not-exist") shouldBe TileProvider.OpenFreeMap
			TileProvider.resolve(null) shouldBe TileProvider.OpenFreeMap
			TileProvider.resolve("") shouldBe TileProvider.OpenFreeMap
		}
	}

	@Test
	fun `all built-in style URLs are HTTPS`() {
		TileProvider.builtIn.forEach { provider ->
			provider.styleUrl(isDarkTheme = false).shouldStartWith("https://")
			provider.styleUrl(isDarkTheme = true).shouldStartWith("https://")
		}
	}

	@Test
	fun `OpenFreeMap dark and light URLs differ`() {
		val light = TileProvider.OpenFreeMap.styleUrl(isDarkTheme = false)
		val dark = TileProvider.OpenFreeMap.styleUrl(isDarkTheme = true)
		(light == dark) shouldBe false
	}

	@Test
	fun `Protomaps URL endings are sane`() {
		TileProvider.Protomaps.styleUrl(isDarkTheme = false).shouldEndWith(".json")
		TileProvider.Protomaps.styleUrl(isDarkTheme = true).shouldEndWith(".json")
	}
}
