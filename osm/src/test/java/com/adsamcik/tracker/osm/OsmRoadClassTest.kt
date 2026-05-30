package com.adsamcik.tracker.osm

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("OsmRoadClass.fromOsmValue")
class OsmRoadClassTest {

	@ParameterizedTest
	@CsvSource(
		"motorway, MOTORWAY",
		"motorway_link, MOTORWAY_LINK",
		"trunk, TRUNK",
		"primary, PRIMARY",
		"residential, RESIDENTIAL",
		"living_street, LIVING_STREET",
		"service, SERVICE",
	)
	fun `maps known driveable highway values`(osm: String, expectedName: String) {
		OsmRoadClass.fromOsmValue(osm)!!.name shouldBe expectedName
	}

	@ParameterizedTest
	@CsvSource(
		"footway", "cycleway", "path", "track", "pedestrian", "steps", "bridleway",
	)
	fun `null for non-driveable values`(osm: String) {
		OsmRoadClass.fromOsmValue(osm) shouldBe null
	}

	@Test
	fun `null input returns null`() {
		OsmRoadClass.fromOsmValue(null) shouldBe null
	}

	@Test
	fun `unknown highway value returns null`() {
		OsmRoadClass.fromOsmValue("xyzzy") shouldBe null
	}

	@Test
	fun `defaults are sensible for safety baseline`() {
		// Service roads should have a low default; motorways high. Catches accidental
		// reordering of the enum.
		(OsmRoadClass.MOTORWAY.defaultMaxspeedKmh > OsmRoadClass.RESIDENTIAL.defaultMaxspeedKmh) shouldBe true
		(OsmRoadClass.LIVING_STREET.defaultMaxspeedKmh < OsmRoadClass.PRIMARY.defaultMaxspeedKmh) shouldBe true
	}
}
