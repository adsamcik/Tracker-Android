package com.adsamcik.signalcollector.data

import android.content.Context
import com.adsamcik.signalcollector.utility.Preferences
import com.squareup.moshi.JsonClass
import java.util.*

/**
 * Data class containing information about the name and boundaries.
 * Does not use [com.adsamcik.signalcollector.utility.CoordinateBounds] because Stag does not support this level of customization for TypeAdapters and custom type adapter is not a priority right now.
 */
//todo Update to use CoordinateBounds
@JsonClass(generateAdapter = true)
data class MapLayer(var name: String,
                    var top: Double = MAX_LATITUDE,
                    var right: Double = MAX_LONGITUDE,
                    var bottom: Double = MIN_LATITUDE,
                    var left: Double = MIN_LONGITUDE,
                    /**
                     * Contains information for the legend
                     */
                    var values: Array<ValueColor>? = null) {

	companion object {
		const val MIN_LATITUDE = -90.0
		const val MAX_LATITUDE = 90.0
		const val MIN_LONGITUDE = -180.0
		const val MAX_LONGITUDE = 180.0
		/**
		 * Checks if MapLayer is in given array
		 */
		fun contains(layerArray: Array<MapLayer>, name: String): Boolean =
				layerArray.any { it.name == name }

		fun mockArray(): Array<MapLayer> = arrayOf(
				MapLayer("Mock", 30.0, 30.0, -30.0, -30.0),
				MapLayer("Wifi", 0.0, 30.0, -20.0, -30.0),
				MapLayer("Cell", 30.0, 30.0, -30.0, -30.0),
				MapLayer("Cell", 60.0, 30.0, -30.0, -30.0),
				MapLayer("Cell", 30.0, 30.0, -30.0, -30.0)
		)
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as MapLayer

		if (name != other.name) return false
		if (top != other.top) return false
		if (right != other.right) return false
		if (bottom != other.bottom) return false
		if (left != other.left) return false
		if (!Arrays.equals(values, other.values)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = name.hashCode()
		result = 31 * result + top.hashCode()
		result = 31 * result + right.hashCode()
		result = 31 * result + bottom.hashCode()
		result = 31 * result + left.hashCode()
		result = 31 * result + (values?.let { Arrays.hashCode(it) } ?: 0)
		return result
	}
}

enum class LayerType {
	Location,
	Cell,
	WiFi;

	companion object {
		fun valueOfCaseInsensitive(value: String) = when (value.toLowerCase()) {
			Location.name.toLowerCase() -> Location
			Cell.name.toLowerCase() -> Cell
			WiFi.name.toLowerCase() -> WiFi
			else -> throw IllegalArgumentException("Value '$value' is not a valid layer type.")
		}

		fun fromPreference(context: Context, key: String, default: LayerType): LayerType {
			val stringName = Preferences.getPref(context).getString(key, null) ?: return default
			return valueOfCaseInsensitive(stringName)
		}
	}
}

@JsonClass(generateAdapter = true)
data class ValueColor(val name: String, val color: Int)
