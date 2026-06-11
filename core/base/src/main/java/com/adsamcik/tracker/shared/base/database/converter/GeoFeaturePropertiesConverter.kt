package com.adsamcik.tracker.shared.base.database.converter

import androidx.room.TypeConverter
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Converts a Map<String, Double> to JSON and back for GeoFeatureEntity.properties.
 * While GeoFeatureEntity itself isn't a Room @Entity (raw query projection),
 * this converter supports future persistence or query projections that include a JSON column.
 */
class GeoFeaturePropertiesConverter {
    private val moshi: Moshi = Moshi.Builder().build()
    private val type = Types.newParameterizedType(Map::class.java, String::class.java, Double::class.javaObjectType)
    private val adapter: JsonAdapter<Map<String, Double>> = moshi.adapter(type)

    @TypeConverter
    fun fromMap(map: Map<String, Double>?): String? = map?.takeIf { it.isNotEmpty() }?.let { adapter.toJson(it) }

    @TypeConverter
    fun toMap(json: String?): Map<String, Double> = json?.takeIf { it.isNotBlank() }?.let { adapter.fromJson(it) } ?: emptyMap()
}
