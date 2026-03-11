package com.adsamcik.tracker.map.shared

import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MAX_LATITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MAX_LONGITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MIN_LATITUDE
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants.MIN_LONGITUDE
import com.adsamcik.tracker.shared.base.data.BaseLocation
import com.adsamcik.tracker.shared.base.data.Location
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CoordinateBounds")
class CoordinateBoundsTest {

    @Nested
    @DisplayName("Default Construction")
    inner class DefaultConstruction {

        @Test
        fun `default bounds are inverted for expansion`() {
            val bounds = CoordinateBounds()

            bounds.top shouldBeExactly MIN_LATITUDE
            bounds.right shouldBeExactly MIN_LONGITUDE
            bounds.bottom shouldBeExactly MAX_LATITUDE
            bounds.left shouldBeExactly MAX_LONGITUDE
        }
    }

    @Nested
    @DisplayName("Custom Construction")
    inner class CustomConstruction {

        @Test
        fun `custom bounds are preserved`() {
            val bounds = CoordinateBounds(
                topBound = 50.0,
                rightBound = 15.0,
                bottomBound = 48.0,
                leftBound = 12.0
            )

            bounds.top shouldBeExactly 50.0
            bounds.right shouldBeExactly 15.0
            bounds.bottom shouldBeExactly 48.0
            bounds.left shouldBeExactly 12.0
        }
    }

    @Nested
    @DisplayName("Dimensions")
    inner class Dimensions {

        @Test
        fun `width returns horizontal span`() {
            val bounds = CoordinateBounds(
                topBound = 50.0,
                rightBound = 20.0,
                bottomBound = 40.0,
                leftBound = 10.0
            )

            bounds.width shouldBeExactly 10.0
        }

        @Test
        fun `height returns vertical span`() {
            val bounds = CoordinateBounds(
                topBound = 50.0,
                rightBound = 20.0,
                bottomBound = 40.0,
                leftBound = 10.0
            )

            bounds.height shouldBeExactly 10.0
        }

        @Test
        fun `width is zero for single point`() {
            val bounds = CoordinateBounds()
            bounds.updateBounds(listOf(createLocation(lat = 50.0, lon = 14.0)))

            bounds.width shouldBeExactly 0.0
            bounds.height shouldBeExactly 0.0
        }
    }

    @Nested
    @DisplayName("Update Bounds")
    inner class UpdateBounds {

        @Test
        fun `with single Location expands bounds`() {
            val bounds = CoordinateBounds()
            val location = createLocation(lat = 50.0, lon = 14.0)

            bounds.updateBounds(listOf(location))

            bounds.top shouldBeExactly 50.0
            bounds.bottom shouldBeExactly 50.0
            bounds.right shouldBeExactly 14.0
            bounds.left shouldBeExactly 14.0
        }

        @Test
        fun `with multiple Locations finds extremes`() {
            val bounds = CoordinateBounds()
            val locations = listOf(
                createLocation(lat = 50.0, lon = 14.0),
                createLocation(lat = 48.0, lon = 16.0),
                createLocation(lat = 52.0, lon = 12.0)
            )

            bounds.updateBounds(locations)

            bounds.top shouldBeExactly 52.0    // max latitude
            bounds.bottom shouldBeExactly 48.0 // min latitude
            bounds.right shouldBeExactly 16.0  // max longitude
            bounds.left shouldBeExactly 12.0   // min longitude
        }

        @Test
        fun `with empty collection does not change default bounds`() {
            val bounds = CoordinateBounds()
            val originalTop = bounds.top
            val originalBottom = bounds.bottom

            bounds.updateBounds(emptyList<Location>())

            bounds.top shouldBeExactly originalTop
            bounds.bottom shouldBeExactly originalBottom
        }

        @Test
        fun `with BaseLocation works correctly`() {
            val bounds = CoordinateBounds()
            val locations = listOf(
                BaseLocation(latitude = 45.0, longitude = 10.0),
                BaseLocation(latitude = 55.0, longitude = 20.0)
            )

            bounds.updateBounds(locations)

            bounds.top shouldBeExactly 55.0
            bounds.bottom shouldBeExactly 45.0
            bounds.right shouldBeExactly 20.0
            bounds.left shouldBeExactly 10.0
        }

        @Test
        fun `accumulates across multiple calls`() {
            val bounds = CoordinateBounds()
            bounds.updateBounds(listOf(createLocation(lat = 50.0, lon = 14.0)))

            bounds.updateBounds(listOf(createLocation(lat = 48.0, lon = 16.0)))

            bounds.top shouldBeExactly 50.0
            bounds.bottom shouldBeExactly 48.0
            bounds.right shouldBeExactly 16.0
            bounds.left shouldBeExactly 14.0
        }

        @Test
        fun `handles negative coordinates`() {
            val bounds = CoordinateBounds()
            val locations = listOf(
                createLocation(lat = -10.0, lon = -50.0),
                createLocation(lat = -30.0, lon = -20.0)
            )

            bounds.updateBounds(locations)

            bounds.top shouldBeExactly -10.0   // -10 > -30
            bounds.bottom shouldBeExactly -30.0
            bounds.right shouldBeExactly -20.0 // -20 > -50
            bounds.left shouldBeExactly -50.0
        }

        @Test
        fun `handles crossing equator and prime meridian`() {
            val bounds = CoordinateBounds()
            val locations = listOf(
                createLocation(lat = 10.0, lon = 10.0),
                createLocation(lat = -10.0, lon = -10.0)
            )

            bounds.updateBounds(locations)

            bounds.top shouldBeExactly 10.0
            bounds.bottom shouldBeExactly -10.0
            bounds.right shouldBeExactly 10.0
            bounds.left shouldBeExactly -10.0
        }
    }

    @Nested
    @DisplayName("Equality and Copy")
    inner class EqualityAndCopy {

        @Test
        fun `data class equality works correctly`() {
            val bounds1 = CoordinateBounds(
                topBound = 50.0,
                rightBound = 15.0,
                bottomBound = 48.0,
                leftBound = 12.0
            )
            val bounds2 = CoordinateBounds(
                topBound = 50.0,
                rightBound = 15.0,
                bottomBound = 48.0,
                leftBound = 12.0
            )

            bounds1 shouldBe bounds2
        }

        @Test
        fun `copy creates independent instance`() {
            val original = CoordinateBounds(
                topBound = 50.0,
                rightBound = 15.0,
                bottomBound = 48.0,
                leftBound = 12.0
            )

            val copy = original.copy(topBound = 60.0)

            copy.top shouldBeExactly 60.0
            original.top shouldBeExactly 50.0
        }
    }

    private fun createLocation(lat: Double, lon: Double): Location {
        return Location(
            time = System.currentTimeMillis(),
            latitude = lat,
            longitude = lon,
            altitude = null,
            horizontalAccuracy = null,
            verticalAccuracy = null,
            speed = null,
            speedAccuracy = null
        )
    }
}
