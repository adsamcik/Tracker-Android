package com.adsamcik.tracker.geocoder

import com.adsamcik.tracker.geocoder.osm.OsmStreetResolver
import com.adsamcik.tracker.geocoder.places.PlacesAssetReader
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import io.mockk.coVerify
import io.mockk.mockk
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefaultReverseGeocoder coordinate validation")
class DefaultReverseGeocoderTest {

    private val placesReader = mockk<PlacesAssetReader>()
    private val osmStreetResolver = mockk<OsmStreetResolver>()
    private val geocoder = DefaultReverseGeocoder(
        placesReader = placesReader,
        osmStreetResolver = osmStreetResolver,
        dispatchers = TestDispatchersProvider(Dispatchers.Unconfined),
    )

    @Test
    fun `reverse geocoding rejects non-finite coordinates before querying providers`() = runTest {
        geocoder.reverseGeocode(Double.NaN, 0.0).shouldBeNull()
        geocoder.reverseGeocode(0.0, Double.NaN).shouldBeNull()
        geocoder.reverseGeocode(Double.POSITIVE_INFINITY, 0.0).shouldBeNull()
        geocoder.reverseGeocode(Double.NEGATIVE_INFINITY, 0.0).shouldBeNull()
        geocoder.reverseGeocode(0.0, Double.POSITIVE_INFINITY).shouldBeNull()
        geocoder.reverseGeocode(0.0, Double.NEGATIVE_INFINITY).shouldBeNull()

        coVerify(exactly = 0) { placesReader.dataset() }
        coVerify(exactly = 0) { osmStreetResolver.nearestRoadName(any(), any()) }
    }

    @Test
    fun `reverse geocoding rejects latitude outside the globe`() = runTest {
        geocoder.reverseGeocode(90.0000001, 0.0).shouldBeNull()
        geocoder.reverseGeocode(-90.0000001, 0.0).shouldBeNull()

        coVerify(exactly = 0) { placesReader.dataset() }
        coVerify(exactly = 0) { osmStreetResolver.nearestRoadName(any(), any()) }
    }

    @Test
    fun `accepts latitude boundaries`() {
        toE7CoordinatesOrNull(90.0, 0.0) shouldBe E7Coordinates(900_000_000, 0)
        toE7CoordinatesOrNull(-90.0, 0.0) shouldBe E7Coordinates(-900_000_000, 0)
    }

    @Test
    fun `wraps finite longitude into the canonical range`() {
        toE7CoordinatesOrNull(0.0, 181.0) shouldBe E7Coordinates(0, -1_790_000_000)
        toE7CoordinatesOrNull(0.0, -181.0) shouldBe E7Coordinates(0, 1_790_000_000)
        toE7CoordinatesOrNull(0.0, 540.0) shouldBe E7Coordinates(0, -1_800_000_000)
        toE7CoordinatesOrNull(0.0, -540.0) shouldBe E7Coordinates(0, -1_800_000_000)
    }
}
