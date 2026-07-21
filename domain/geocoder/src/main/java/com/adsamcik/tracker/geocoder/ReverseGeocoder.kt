package com.adsamcik.tracker.geocoder

/**
 * A place resolved from coordinates by the offline reverse geocoder.
 *
 * [displayName] is the most specific human-readable label available (e.g.
 * "Vinohradská, Prague" when a street is known, otherwise "Prague"). The
 * structured fields are provided so callers can format their own variants.
 */
data class GeocodedPlace(
    val displayName: String,
    val street: String?,
    val locality: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
)

/** A forward-search match: a named place the user can navigate to. */
data class PlaceSearchResult(
    val displayName: String,
    val locality: String,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val population: Int,
)

/**
 * Fully-offline geocoder. Privacy-first: never performs any network access.
 *
 * Reverse geocoding resolves a nearest locality from a bundled worldwide places
 * dataset (GeoNames), upgraded with a street name from the user's imported OSM
 * road data where available. Forward search matches free-text place names against
 * the same bundled dataset.
 */
interface ReverseGeocoder {

    /**
     * Resolve the nearest human-readable place to ([latitude], [longitude]), or
     * `null` if nothing is found (only in degenerate cases — the bundled dataset
     * has worldwide coverage at locality level). Non-finite coordinates and
     * latitudes outside [-90, 90] return `null`; finite longitudes are wrapped.
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): GeocodedPlace?

    /**
     * Forward search: match [query] against place names, returning up to [limit]
     * results ranked by match quality and population (or proximity when
     * [nearLatitude]/[nearLongitude] are provided). Returns an empty list for a
     * blank query, no matches, or an invalid complete proximity coordinate pair.
     */
    suspend fun searchPlaces(
        query: String,
        nearLatitude: Double? = null,
        nearLongitude: Double? = null,
        limit: Int = 8,
    ): List<PlaceSearchResult>
}
