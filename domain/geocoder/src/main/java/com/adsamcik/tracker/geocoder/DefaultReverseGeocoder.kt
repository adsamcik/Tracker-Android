package com.adsamcik.tracker.geocoder

import com.adsamcik.tracker.geocoder.places.PlacesAssetReader
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Offline [ReverseGeocoder] backed by the bundled worldwide places dataset.
 * Reverse lookups return the nearest locality; forward lookups search the same
 * local dataset. If the asset is unavailable, lookups fail closed without using
 * the network.
 */
@Singleton
class DefaultReverseGeocoder @Inject constructor(
    private val placesReader: PlacesAssetReader,
    private val dispatchers: DispatchersProvider,
) : ReverseGeocoder {

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): GeocodedPlace? {
        val coordinates = toE7CoordinatesOrNull(latitude, longitude) ?: return null
        val latE7 = coordinates.latitude
        val lonE7 = coordinates.longitude

        val place = withContext(dispatchers.default) {
            placesReader.dataset()?.nearest(latE7, lonE7)
        } ?: return null
        val locality = place.name
        return GeocodedPlace(
            displayName = locality,
            street = null,
            locality = locality,
            countryCode = place.countryCode,
            latitude = place.latitude,
            longitude = place.longitude,
        )
    }

    override suspend fun searchPlaces(
        query: String,
        nearLatitude: Double?,
        nearLongitude: Double?,
        limit: Int,
    ): List<PlaceSearchResult> {
        if (query.isBlank()) return emptyList()
        val near = if (nearLatitude != null && nearLongitude != null) {
            toE7CoordinatesOrNull(nearLatitude, nearLongitude) ?: return emptyList()
        } else {
            null
        }
        val dataset = placesReader.dataset() ?: return emptyList()
        return withContext(dispatchers.default) {
            dataset.search(query, near?.latitude, near?.longitude, limit).map { place ->
                PlaceSearchResult(
                    displayName = place.name,
                    locality = place.name,
                    countryCode = place.countryCode.takeIf { it.isNotBlank() },
                    latitude = place.latitude,
                    longitude = place.longitude,
                    population = place.population,
                )
            }
        }
    }
}

internal data class E7Coordinates(val latitude: Int, val longitude: Int)

internal fun toE7CoordinatesOrNull(latitude: Double, longitude: Double): E7Coordinates? {
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0) return null
    val wrappedLongitude = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return E7Coordinates(
        latitude = (latitude * E7).roundToInt(),
        longitude = (wrappedLongitude * E7).roundToInt(),
    )
}

private const val E7 = 10_000_000.0
