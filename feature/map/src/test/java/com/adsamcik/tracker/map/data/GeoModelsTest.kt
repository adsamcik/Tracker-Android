package com.adsamcik.tracker.map.data

import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("GeoModels")
class GeoModelsTest {

	@Nested
	@DisplayName("GeoSource")
	inner class GeoSourceTests {

		@Test
		fun `has all expected values`() {
			GeoSource.entries.map { it.name } shouldBe listOf("LOCATION", "WIFI", "CELL")
		}

		@Test
		fun `valueOf resolves correctly`() {
			GeoSource.valueOf("LOCATION") shouldBe GeoSource.LOCATION
			GeoSource.valueOf("WIFI") shouldBe GeoSource.WIFI
			GeoSource.valueOf("CELL") shouldBe GeoSource.CELL
		}
	}

	@Nested
	@DisplayName("Bounds")
	inner class BoundsTests {

		@Test
		fun `valid bounds are constructed`() {
			val bounds = Bounds(north = 50.0, east = 15.0, south = 49.0, west = 14.0)
			bounds.north shouldBe 50.0
			bounds.east shouldBe 15.0
			bounds.south shouldBe 49.0
			bounds.west shouldBe 14.0
		}

		@Test
		fun `equal north and south is valid`() {
			val bounds = Bounds(north = 50.0, east = 15.0, south = 50.0, west = 14.0)
			bounds.north shouldBe bounds.south
		}

		@Test
		fun `equal east and west is valid`() {
			val bounds = Bounds(north = 50.0, east = 15.0, south = 49.0, west = 15.0)
			bounds.east shouldBe bounds.west
		}

		@Test
		fun `throws when north is less than south`() {
			assertThrows<IllegalArgumentException> {
				Bounds(north = 49.0, east = 15.0, south = 50.0, west = 14.0)
			}
		}

		@Test
		fun `east less than west represents an antimeridian crossing`() {
			val bounds = Bounds(north = 50.0, east = -170.0, south = 49.0, west = 170.0)
			bounds.crossesAntimeridian shouldBe true
		}

		@Test
		fun `equality works`() {
			val b1 = Bounds(north = 50.0, east = 15.0, south = 49.0, west = 14.0)
			val b2 = Bounds(north = 50.0, east = 15.0, south = 49.0, west = 14.0)
			b1 shouldBe b2
			b1.hashCode() shouldBe b2.hashCode()
		}

		@Test
		fun `copy works`() {
			val original = Bounds(north = 50.0, east = 15.0, south = 49.0, west = 14.0)
			val copy = original.copy(north = 51.0)
			copy.north shouldBe 51.0
			copy.south shouldBe original.south
		}
	}

	@Nested
	@DisplayName("cameraToBounds")
	inner class CameraToBoundsTests {

		@Test
		fun `returns null when zoom is too low`() {
			cameraToBounds(lat = 50.0, lng = 15.0, zoom = 2.0).shouldBeNull()
		}

		@Test
		fun `returns null at exact threshold`() {
			cameraToBounds(lat = 50.0, lng = 15.0, zoom = 2.9).shouldBeNull()
		}

		@Test
		fun `returns bounds at zoom 3`() {
			val bounds = cameraToBounds(lat = 50.0, lng = 15.0, zoom = 3.0)
			bounds shouldBe Bounds(
				north = (50.0 + 45.0 * 1.5 / 2.0).coerceAtMost(90.0),
				south = (50.0 - 45.0 * 1.5 / 2.0).coerceAtLeast(-90.0),
				east = (15.0 + 45.0 * 1.5 / 2.0).coerceAtMost(180.0),
				west = (15.0 - 45.0 * 1.5 / 2.0).coerceAtLeast(-180.0)
			)
		}

		@Test
		fun `high zoom produces small bounds`() {
			val bounds = cameraToBounds(lat = 50.0, lng = 15.0, zoom = 15.0)!!
			val halfDeg = (360.0 / Math.pow(2.0, 15.0)) / 2.0 * 1.5
			bounds.north shouldBe 50.0 + halfDeg
			bounds.south shouldBe 50.0 - halfDeg
			bounds.east shouldBe 15.0 + halfDeg
			bounds.west shouldBe 15.0 - halfDeg
		}

		@Test
		fun `clamps to max latitude`() {
			val bounds = cameraToBounds(lat = 89.0, lng = 0.0, zoom = 3.0)!!
			bounds.north shouldBe 90.0
		}

		@Test
		fun `clamps to min latitude`() {
			val bounds = cameraToBounds(lat = -89.0, lng = 0.0, zoom = 3.0)!!
			bounds.south shouldBe -90.0
		}

		@Test
		fun `wraps across positive antimeridian`() {
			val bounds = cameraToBounds(lat = 0.0, lng = 170.0, zoom = 3.0)!!
			bounds.crossesAntimeridian shouldBe true
			bounds.east shouldBe -156.25
			bounds.west shouldBe 136.25
		}

		@Test
		fun `wraps across negative antimeridian`() {
			val bounds = cameraToBounds(lat = 0.0, lng = -170.0, zoom = 3.0)!!
			bounds.crossesAntimeridian shouldBe true
			bounds.east shouldBe -136.25
			bounds.west shouldBe 156.25
		}
	}

	@Nested
	@DisplayName("antimeridian bounds")
	inner class AntimeridianBoundsTests {

		@Test
		fun `reports wrapped longitude span`() {
			Bounds(north = 10.0, east = -170.0, south = -10.0, west = 170.0)
				.longitudeSpan shouldBe 20.0
		}

		@Test
		fun `contains longitudes on both sides of antimeridian`() {
			val bounds = Bounds(north = 10.0, east = -170.0, south = -10.0, west = 170.0)

			bounds.containsLongitude(175.0) shouldBe true
			bounds.containsLongitude(-175.0) shouldBe true
			bounds.containsLongitude(0.0) shouldBe false
		}

		@Test
		fun `intersects wrapped and unwrapped longitude ranges`() {
			val bounds = Bounds(north = 10.0, east = -170.0, south = -10.0, west = 170.0)

			bounds.intersectsLongitudeRange(175.0, 181.0) shouldBe true
			bounds.intersectsLongitudeRange(-179.0, -175.0) shouldBe true
			bounds.intersectsLongitudeRange(-10.0, 10.0) shouldBe false
		}
	}

	@Nested
	@DisplayName("paddedBounds")
	inner class PaddedBoundsTests {

		@Test
		fun `pads each side by the given fraction of the span`() {
			// 2 deg lat span, 4 deg lon span, 50% padding -> +1 deg lat, +2 deg lon each side.
			val bounds = paddedBounds(
				north = 51.0, east = 18.0, south = 49.0, west = 14.0,
				paddingFraction = 0.5,
			)!!
			bounds.north shouldBe 52.0
			bounds.south shouldBe 48.0
			bounds.east shouldBe 20.0
			bounds.west shouldBe 12.0
		}

		@Test
		fun `zero padding returns the box unchanged`() {
			val bounds = paddedBounds(
				north = 51.0, east = 18.0, south = 49.0, west = 14.0,
				paddingFraction = 0.0,
			)!!
			bounds shouldBe Bounds(north = 51.0, east = 18.0, south = 49.0, west = 14.0)
		}

		@Test
		fun `clamps padded values to valid coordinate ranges`() {
			val bounds = paddedBounds(
				north = 89.5, east = 179.5, south = -89.5, west = -179.5,
				paddingFraction = 0.5,
			)!!
			bounds.north shouldBe 90.0
			bounds.south shouldBe -90.0
			bounds.east shouldBe 180.0
			bounds.west shouldBe -180.0
		}

		@Test
		fun `returns null when north is not greater than south`() {
			paddedBounds(north = 50.0, east = 18.0, south = 50.0, west = 14.0).shouldBeNull()
			paddedBounds(north = 49.0, east = 18.0, south = 50.0, west = 14.0).shouldBeNull()
		}

		@Test
		fun `pads an antimeridian-crossing box`() {
			val bounds = paddedBounds(
				north = 10.0,
				east = -170.0,
				south = -10.0,
				west = 170.0,
				paddingFraction = 0.5,
			)!!
			bounds shouldBe Bounds(north = 20.0, east = -160.0, south = -20.0, west = 160.0)
		}
	}

	@Nested
	@DisplayName("boundsAround")
	inner class BoundsAroundTests {

		@Test
		fun `returns null for non-positive radius`() {
			boundsAround(lat = 50.0, lng = 14.0, radiusMeters = 0.0).shouldBeNull()
			boundsAround(lat = 50.0, lng = 14.0, radiusMeters = -10.0).shouldBeNull()
		}

		@Test
		fun `box encloses the requested radius`() {
			val radius = 100.0
			val bounds = boundsAround(lat = 50.0, lng = 14.0, radiusMeters = radius)!!
			// Latitude half-span ~= radius / 111320 deg.
			val latHalf = bounds.north - 50.0
			latHalf shouldBe ((radius / 111_320.0) plusOrMinus 1e-9)
			// The box must fully contain a point at exactly `radius` north of centre.
			val northPoint = 50.0 + radius / 111_320.0
			(bounds.north >= northPoint) shouldBe true
		}

		@Test
		fun `longitude span widens with latitude`() {
			val equator = boundsAround(lat = 0.0, lng = 0.0, radiusMeters = 100.0)!!
			val high = boundsAround(lat = 60.0, lng = 0.0, radiusMeters = 100.0)!!
			val equatorLonSpan = equator.east - equator.west
			val highLonSpan = high.east - high.west
			// cos(60deg) = 0.5, so the high-latitude longitude span is ~2x the equator span.
			(highLonSpan > equatorLonSpan) shouldBe true
		}

		@Test
		fun `clamps to valid coordinate ranges near the pole`() {
			val bounds = boundsAround(lat = 89.9999, lng = 0.0, radiusMeters = 100.0)!!
			(bounds.north <= 90.0) shouldBe true
			(bounds.south >= -90.0) shouldBe true
		}
	}

	@Nested
	@DisplayName("haversineMeters")
	inner class HaversineTests {

		@Test
		fun `zero distance for identical points`() {
			haversineMeters(50.0, 14.0, 50.0, 14.0) shouldBe (0.0 plusOrMinus 1e-6)
		}

		@Test
		fun `one degree of latitude is about 111 km`() {
			val d = haversineMeters(0.0, 0.0, 1.0, 0.0)
			d shouldBe (111_195.0 plusOrMinus 200.0)
		}

		@Test
		fun `short distance is accurate`() {
			// ~100 m north at the equator.
			val d = haversineMeters(0.0, 0.0, 100.0 / 111_320.0, 0.0)
			d shouldBe (100.0 plusOrMinus 1.0)
		}

		@Test
		fun `is symmetric`() {
			val ab = haversineMeters(50.0, 14.0, 50.1, 14.2)
			val ba = haversineMeters(50.1, 14.2, 50.0, 14.0)
			(kotlin.math.abs(ab - ba)) shouldBeLessThan 1e-6
		}
	}

	@Nested
	@DisplayName("GeoQuery")
	inner class GeoQueryTests {

		@Test
		fun `default values`() {
			val query = GeoQuery(source = GeoSource.LOCATION)
			query.source shouldBe GeoSource.LOCATION
			query.bounds.shouldBeNull()
			query.timeFrom.shouldBeNull()
			query.timeTo.shouldBeNull()
			query.limit.shouldBeNull()
			query.extraColumns shouldBe emptyList()
			query.weight.shouldBeNull()
		}

		@Test
		fun `all fields populated`() {
			val bounds = Bounds(north = 50.0, east = 15.0, south = 49.0, west = 14.0)
			val query = GeoQuery(
				source = GeoSource.WIFI,
				bounds = bounds,
				timeFrom = 1000L,
				timeTo = 2000L,
				limit = 100,
				extraColumns = listOf("speed"),
				weight = "signal"
			)
			query.source shouldBe GeoSource.WIFI
			query.bounds shouldBe bounds
			query.timeFrom shouldBe 1000L
			query.timeTo shouldBe 2000L
			query.limit shouldBe 100
			query.extraColumns shouldBe listOf("speed")
			query.weight shouldBe "signal"
		}

		@Test
		fun `equality works`() {
			val q1 = GeoQuery(source = GeoSource.CELL, limit = 50)
			val q2 = GeoQuery(source = GeoSource.CELL, limit = 50)
			q1 shouldBe q2
		}
	}

	@Nested
	@DisplayName("GeoFeature hierarchy")
	inner class GeoFeatureTests {

		@Test
		fun `BasicGeoFeature implements GeoFeature`() {
			val feature: GeoFeature = BasicGeoFeature(lat = 50.0, lon = 15.0, time = 100L)
			feature.lat shouldBe 50.0
			feature.lon shouldBe 15.0
			feature.time shouldBe 100L
		}

		@Test
		fun `BasicGeoFeature with properties`() {
			val props = mapOf("speed" to 5.0, "accuracy" to 10.0)
			val feature = BasicGeoFeature(lat = 50.0, lon = 15.0, time = 100L, properties = props)
			feature.properties shouldBe props
		}

		@Test
		fun `BasicGeoFeature default empty properties`() {
			val feature = BasicGeoFeature(lat = 50.0, lon = 15.0, time = 100L)
			feature.properties shouldBe emptyMap()
		}

		@Test
		fun `WeightedGeoFeature implements GeoFeature`() {
			val feature: GeoFeature = WeightedGeoFeature(lat = 50.0, lon = 15.0, time = 200L, weight = 3.5)
			feature.lat shouldBe 50.0
			feature.lon shouldBe 15.0
			feature.time shouldBe 200L
		}

		@Test
		fun `WeightedGeoFeature stores weight`() {
			val feature = WeightedGeoFeature(lat = 50.0, lon = 15.0, time = 200L, weight = 3.5)
			feature.weight shouldBe 3.5
		}
	}

	@Nested
	@DisplayName("Entity to Domain mapping")
	inner class EntityMappingTests {

		@Test
		fun `GeoFeatureEntity toDomain maps correctly`() {
			val entity = GeoFeatureEntity(
				lat = 50.1,
				lon = 14.9,
				time = 12345L,
				properties = mapOf("speed" to 2.0)
			)
			val domain = entity.toDomain()
			domain.lat shouldBe 50.1
			domain.lon shouldBe 14.9
			domain.time shouldBe 12345L
			domain.properties shouldBe mapOf("speed" to 2.0)
		}

		@Test
		fun `GeoFeatureEntity toDomain with empty properties`() {
			val entity = GeoFeatureEntity(lat = 0.0, lon = 0.0, time = 0L)
			val domain = entity.toDomain()
			domain.properties shouldBe emptyMap()
		}

		@Test
		fun `GeoWeightedFeatureEntity toDomain maps correctly`() {
			val entity = GeoWeightedFeatureEntity(
				lat = 48.5,
				lon = 16.3,
				time = 99999L,
				weight = 7.7
			)
			val domain = entity.toDomain()
			domain.lat shouldBe 48.5
			domain.lon shouldBe 16.3
			domain.time shouldBe 99999L
			domain.weight shouldBe 7.7
		}
	}
}
