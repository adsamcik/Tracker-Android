package com.adsamcik.tracker.geocoder

import com.adsamcik.tracker.geocoder.osm.OsmStreetResolver
import com.adsamcik.tracker.geocoder.places.PlacesAssetReader
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Layered offline [ReverseGeocoder]:
 *  * reverse — nearest locality from the bundled worldwide places dataset, upgraded
 *    with a street name from imported OSM road data where available;
 *  * forward — name search against the bundled places dataset.
 *
 * Fully offline. Degrades gracefully: if the places asset is unavailable the
 * reverse result falls back to OSM street-only, and `null` if neither is present.
 */
@Singleton
class DefaultReverseGeocoder @Inject constructor(
    private val placesReader: PlacesAssetReader,
    private val osmStreetResolver: OsmStreetResolver,
    private val dispatchers: DispatchersProvider,
) : ReverseGeocoder {

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): GeocodedPlace? {
        val coordinates = toE7CoordinatesOrNull(latitude, longitude) ?: return null
        val latE7 = coordinates.latitude
        val lonE7 = coordinates.longitude

        val (place, street) = coroutineScope {
            val placeDeferred = async(dispatchers.default) {
                placesReader.dataset()?.nearest(latE7, lonE7)
            }
            val streetDeferred = async(dispatchers.io) {
                runCatching { osmStreetResolver.nearestRoadName(latE7, lonE7) }.getOrNull()
            }
            placeDeferred.await() to streetDeferred.await()
        }

        val locality = place?.name
        val displayName = when {
            street != null && locality != null -> "$street, $locality"
            street != null -> street
            locality != null -> locality
            else -> return null
        }
        return GeocodedPlace(
            displayName = displayName,
            street = street,
            locality = locality,
            countryCode = place?.countryCode,
            latitude = place?.latitude ?: coordinates.latitude / E7,
            longitude = place?.longitude ?: coordinates.longitude / E7,
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
