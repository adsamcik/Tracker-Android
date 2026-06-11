package com.adsamcik.tracker.map.data

import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity
import io.kotest.matchers.nulls.shouldBeNull
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
		fun `throws when east is less than west`() {
			assertThrows<IllegalArgumentException> {
				Bounds(north = 50.0, east = 14.0, south = 49.0, west = 15.0)
			}
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
		fun `clamps to max longitude`() {
			val bounds = cameraToBounds(lat = 0.0, lng = 170.0, zoom = 3.0)!!
			bounds.east shouldBe 180.0
		}

		@Test
		fun `clamps to min longitude`() {
			val bounds = cameraToBounds(lat = 0.0, lng = -170.0, zoom = 3.0)!!
			bounds.west shouldBe -180.0
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
