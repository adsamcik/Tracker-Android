package com.adsamcik.tracker.map.data

import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GeoJsonConverter")
class GeoJsonConverterTest {

	@Nested
	@DisplayName("pointsToFeatureCollection")
	inner class PointsToFeatureCollectionTests {

		@Test
		fun `empty list produces empty FeatureCollection`() {
			val result = GeoJsonConverter.pointsToFeatureCollection(emptyList())
			result shouldBe """{"type":"FeatureCollection","features":[]}"""
		}

		@Test
		fun `single point produces valid GeoJSON`() {
			val points = listOf(
				WeightedGeoFeature(lat = 50.0, lon = 15.0, time = 100L, weight = 1.0)
			)
			val result = GeoJsonConverter.pointsToFeatureCollection(points)
			result shouldBe """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[15.0,50.0]},"properties":{"weight":1.0}}]}"""
		}

		@Test
		fun `multiple points separated by commas`() {
			val points = listOf(
				WeightedGeoFeature(lat = 50.0, lon = 15.0, time = 100L, weight = 1.0),
				WeightedGeoFeature(lat = 51.0, lon = 16.0, time = 200L, weight = 2.5),
			)
			val result = GeoJsonConverter.pointsToFeatureCollection(points)
			result shouldContain """"type":"FeatureCollection""""
			result shouldContain """[15.0,50.0]"""
			result shouldContain """[16.0,51.0]"""
			result shouldContain """"weight":2.5"""
		}

		@Test
		fun `output is valid JSON structure`() {
			val points = listOf(
				WeightedGeoFeature(lat = 0.0, lon = 0.0, time = 0L, weight = 0.0)
			)
			val result = GeoJsonConverter.pointsToFeatureCollection(points)
			result shouldStartWith """{"type":"FeatureCollection""""
			result shouldContain """"type":"Feature""""
			result shouldContain """"type":"Point""""
		}
	}

	@Nested
	@DisplayName("weightedSegmentsToFeatureCollection")
	inner class WeightedSegmentsTests {

		@Test
		fun `empty paths produce an empty FeatureCollection`() {
			GeoJsonConverter.weightedSegmentsToFeatureCollection(emptyList()) shouldBe
				"""{"type":"FeatureCollection","features":[]}"""
		}

		@Test
		fun `each edge is a valid weighted LineString feature`() {
			val paths = listOf(
				listOf(
					WeightedGeoFeature(50.0, 14.0, 0L, 0.2),
					WeightedGeoFeature(51.0, 15.0, 1L, 0.6),
				),
			)

			GeoJsonConverter.weightedSegmentsToFeatureCollection(paths) shouldBe
				"""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[14.0,50.0],[15.0,51.0]]},"properties":{"weight":0.4}}]}"""
		}

		@Test
		fun `near duplicate traversals emit only one edge`() {
			val paths = listOf(
				listOf(
					WeightedGeoFeature(50.0, 14.0, 0L, 0.2),
					WeightedGeoFeature(50.001, 14.001, 1L, 0.6),
				),
				listOf(
					WeightedGeoFeature(50.00001, 14.00001, 10L, 0.8),
					WeightedGeoFeature(50.00101, 14.00101, 11L, 0.8),
				),
			)

			val result = GeoJsonConverter.weightedSegmentsToFeatureCollection(
				paths = paths,
				mergeToleranceDegrees = 0.00005,
			)

			result shouldBe
				"""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[14.00001,50.00001],[14.00101,50.00101]]},"properties":{"weight":0.8}}]}"""
		}
	}

	@Nested
	@DisplayName("tilesToFeatureCollection")
	inner class TilesToFeatureCollectionTests {

		@Test
		fun `empty list produces empty FeatureCollection`() {
			GeoJsonConverter.tilesToFeatureCollection(emptyList()) shouldBe
				"""{"type":"FeatureCollection","features":[]}"""
		}

		@Test
		fun `single tile produces a closed polygon ring with weight`() {
			val tiles = listOf(
				GridTile(west = 14.0, south = 50.0, east = 14.001, north = 50.001, weight = 0.5, count = 3),
			)
			val result = GeoJsonConverter.tilesToFeatureCollection(tiles)
			result shouldContain """"type":"Polygon""""
			result shouldContain """"weight":0.5"""
			// Ring: SW, SE, NE, NW, SW (closed) — first and last coordinate must match.
			result shouldContain """[[14.0,50.0],[14.001,50.0],[14.001,50.001],[14.0,50.001],[14.0,50.0]]"""
		}

		@Test
		fun `multiple tiles separated by commas`() {
			val tiles = listOf(
				GridTile(14.0, 50.0, 14.001, 50.001, weight = 1.0, count = 5),
				GridTile(14.001, 50.0, 14.002, 50.001, weight = 0.2, count = 1),
			)
			val result = GeoJsonConverter.tilesToFeatureCollection(tiles)
			result shouldStartWith """{"type":"FeatureCollection","features":["""
			result shouldContain """"weight":1.0"""
			result shouldContain """"weight":0.2"""
		}
	}

	@Nested
	@DisplayName("lineToFeatureCollection")
	inner class LineToFeatureCollectionTests {

		@Test
		fun `empty list produces empty LineString coordinates`() {
			val result = GeoJsonConverter.lineToFeatureCollection(emptyList())
			result shouldBe """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[]},"properties":{}}]}"""
		}

		@Test
		fun `two points produce valid LineString`() {
			val points = listOf(
				LatLngModel(lat = 50.0, lng = 15.0),
				LatLngModel(lat = 51.0, lng = 16.0),
			)
			val result = GeoJsonConverter.lineToFeatureCollection(points)
			result shouldContain """"type":"LineString""""
			result shouldContain "[15.0,50.0]"
			result shouldContain "[16.0,51.0]"
		}

		@Test
		fun `single point LineString`() {
			val points = listOf(LatLngModel(lat = 50.0, lng = 15.0))
			val result = GeoJsonConverter.lineToFeatureCollection(points)
			result shouldContain "[15.0,50.0]"
		}
	}

	@Nested
	@DisplayName("segmentsToFeatureCollection")
	inner class SegmentsToFeatureCollectionTests {

		@Test
		fun `empty list produces empty FeatureCollection`() {
			val result = GeoJsonConverter.segmentsToFeatureCollection(emptyList())
			result shouldBe """{"type":"FeatureCollection","features":[]}"""
		}

		@Test
		fun `segments with fewer than 2 points are filtered`() {
			val segments = listOf(
				listOf(LatLngModel(lat = 50.0, lng = 15.0)),
			)
			val result = GeoJsonConverter.segmentsToFeatureCollection(segments)
			result shouldBe """{"type":"FeatureCollection","features":[]}"""
		}

		@Test
		fun `single valid segment delegates to lineToFeatureCollection`() {
			val segment = listOf(
				LatLngModel(lat = 50.0, lng = 15.0),
				LatLngModel(lat = 51.0, lng = 16.0),
			)
			val result = GeoJsonConverter.segmentsToFeatureCollection(listOf(segment))
			val expected = GeoJsonConverter.lineToFeatureCollection(segment)
			result shouldBe expected
		}

		@Test
		fun `multiple valid segments produce multiple LineString features`() {
			val seg1 = listOf(
				LatLngModel(lat = 50.0, lng = 15.0),
				LatLngModel(lat = 51.0, lng = 16.0),
			)
			val seg2 = listOf(
				LatLngModel(lat = 52.0, lng = 17.0),
				LatLngModel(lat = 53.0, lng = 18.0),
			)
			val result = GeoJsonConverter.segmentsToFeatureCollection(listOf(seg1, seg2))
			result shouldContain "[15.0,50.0]"
			result shouldContain "[16.0,51.0]"
			result shouldContain "[17.0,52.0]"
			result shouldContain "[18.0,53.0]"
		}

		@Test
		fun `mixed valid and invalid segments keeps only valid`() {
			val invalid = listOf(LatLngModel(lat = 0.0, lng = 0.0))
			val valid1 = listOf(
				LatLngModel(lat = 50.0, lng = 15.0),
				LatLngModel(lat = 51.0, lng = 16.0),
			)
			val valid2 = listOf(
				LatLngModel(lat = 52.0, lng = 17.0),
				LatLngModel(lat = 53.0, lng = 18.0),
			)
			val result = GeoJsonConverter.segmentsToFeatureCollection(listOf(invalid, valid1, valid2))
			result shouldContain "[15.0,50.0]"
			result shouldContain "[17.0,52.0]"
		}

		@Test
		fun `empty segments mixed with invalid produces empty`() {
			val segments = listOf(
				emptyList(),
				listOf(LatLngModel(lat = 0.0, lng = 0.0)),
			)
			val result = GeoJsonConverter.segmentsToFeatureCollection(segments)
			result shouldBe """{"type":"FeatureCollection","features":[]}"""
		}
	}

	@Nested
	@DisplayName("pointToFeature")
	inner class PointToFeatureTests {

		@Test
		fun `produces valid GeoJSON Point Feature`() {
			val result = GeoJsonConverter.pointToFeature(lat = 50.0, lng = 15.0)
			result shouldBe """{"type":"Feature","geometry":{"type":"Point","coordinates":[15.0,50.0]},"properties":{}}"""
		}

		@Test
		fun `coordinates are lon,lat order per GeoJSON spec`() {
			val result = GeoJsonConverter.pointToFeature(lat = 48.2, lng = 16.4)
			result shouldContain "[16.4,48.2]"
		}

		@Test
		fun `zero coordinates`() {
			val result = GeoJsonConverter.pointToFeature(lat = 0.0, lng = 0.0)
			result shouldContain "[0.0,0.0]"
		}

		@Test
		fun `negative coordinates`() {
			val result = GeoJsonConverter.pointToFeature(lat = -33.8688, lng = -151.2093)
			result shouldContain "[-151.2093,-33.8688]"
		}
	}
}
