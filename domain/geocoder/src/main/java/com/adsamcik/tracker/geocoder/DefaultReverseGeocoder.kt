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
        val latE7 = (latitude * 1e7).roundToInt()
        val lonE7 = (longitude * 1e7).roundToInt()

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
            latitude = place?.latitude ?: latitude,
            longitude = place?.longitude ?: longitude,
        )
    }

    override suspend fun searchPlaces(
        query: String,
        nearLatitude: Double?,
        nearLongitude: Double?,
        limit: Int,
    ): List<PlaceSearchResult> {
        if (query.isBlank()) return emptyList()
        val dataset = placesReader.dataset() ?: return emptyList()
        val nearLatE7 = nearLatitude?.let { (it * 1e7).roundToInt() }
        val nearLonE7 = nearLongitude?.let { (it * 1e7).roundToInt() }
        return withContext(dispatchers.default) {
            dataset.search(query, nearLatE7, nearLonE7, limit).map { place ->
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
