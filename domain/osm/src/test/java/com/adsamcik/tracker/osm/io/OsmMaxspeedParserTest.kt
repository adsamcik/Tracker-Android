package com.adsamcik.tracker.osm.io

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("OsmMaxspeedParser")
class OsmMaxspeedParserTest {

	@ParameterizedTest
	@CsvSource(
		"'50', 50",
		"'  90 ', 90",
		"'130', 130",
		"'50 km/h', 50",
		"'50km/h', 50",
		"'90 kmh', 90",
		"'90kmh', 90",
	)
	fun `parses plain kmh values`(raw: String, expected: Int) {
		OsmMaxspeedParser.parseKmh(raw) shouldBe expected
	}

	@ParameterizedTest
	@CsvSource(
		"'60 mph', 97",
		"'35mph', 56",
		"'25 mph', 40",
	)
	fun `converts mph to kmh and rounds`(raw: String, expected: Int) {
		OsmMaxspeedParser.parseKmh(raw) shouldBe expected
	}

	@Test
	fun `converts knots to kmh`() {
		OsmMaxspeedParser.parseKmh("35 knots") shouldBe 65
	}

	@ParameterizedTest
	@ValueSource(strings = ["", " ", "none", "signals", "variable", "walk", "DE:urban", "RO:rural"])
	fun `returns null for unsupported expressions`(raw: String) {
		OsmMaxspeedParser.parseKmh(raw) shouldBe null
	}

	@Test
	fun `null input returns null`() {
		OsmMaxspeedParser.parseKmh(null) shouldBe null
	}

	@Test
	fun `zero and negative numbers are rejected`() {
		OsmMaxspeedParser.parseKmh("0") shouldBe null
		OsmMaxspeedParser.parseKmh("-50") shouldBe null
	}

	@Test
	fun `unrealistically large values are rejected`() {
		OsmMaxspeedParser.parseKmh("1000") shouldBe null
		OsmMaxspeedParser.parseKmh("9999 mph") shouldBe null
	}
}
