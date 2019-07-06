package com.adsamcik.signalcollector.map

import android.content.Context
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.commonmap.CoordinateBounds
import com.adsamcik.signalcollector.commonmap.CoordinateBounds.Companion.MAX_LATITUDE
import com.adsamcik.signalcollector.commonmap.CoordinateBounds.Companion.MAX_LONGITUDE
import com.adsamcik.signalcollector.commonmap.CoordinateBounds.Companion.MIN_LATITUDE
import com.adsamcik.signalcollector.commonmap.CoordinateBounds.Companion.MIN_LONGITUDE
import java.util.*

data class MapLayer(var type: LayerType,
                    var name: String,
                    val bounds: CoordinateBounds,
                    /**
                     * Contains information for the legend
                     */
                    var values: Array<ValueColor>? = null) {

	constructor(type: LayerType,
	            name: String,
	            top: Double = MAX_LATITUDE,
	            right: Double = MAX_LONGITUDE,
	            bottom: Double = MIN_LATITUDE,
	            left: Double = MIN_LONGITUDE,
	            /**
	             * Contains information for the legend
	             */
	            values: Array<ValueColor>? = null) : this(type, name, CoordinateBounds(top, right, bottom, left), values)

	companion object {
		/**
		 * Checks if MapLayer is in given array
		 */
		fun contains(layerArray: Array<MapLayer>, name: String): Boolean =
				layerArray.any { it.name == name }
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as MapLayer

		if (name != other.name) return false
		if (bounds != other.bounds) return false
		if (values != null) {
			if (other.values == null) return false
			if (!values!!.contentEquals(other.values!!)) return false
		} else if (other.values != null) return false

		return true
	}

	override fun hashCode(): Int {
		var result = name.hashCode()
		result = 31 * result + bounds.hashCode()
		result = 31 * result + (values?.contentHashCode() ?: 0)
		return result
	}
}

enum class LayerType {
	Location,
	Cell,
	WiFi;

	companion object {
		fun valueOfCaseInsensitive(value: String): LayerType {
			val locale = Locale.getDefault()
			return when (value.toLowerCase(locale)) {
				Location.name.toLowerCase(locale) -> Location
				Cell.name.toLowerCase(locale) -> Cell
				WiFi.name.toLowerCase(locale), "wi-fi" -> WiFi
				else -> throw IllegalArgumentException("Value '$value' is not a valid layer type.")
			}
		}

		fun fromPreference(context: Context, key: String, default: LayerType): LayerType {
			val stringName = Preferences.getPref(context).getString(key) ?: return default
			return valueOfCaseInsensitive(stringName)
		}

		fun fromPreference(context: Context, key: String, default: String): LayerType {
			val stringName = Preferences.getPref(context).getString(key, default)
			return valueOfCaseInsensitive(stringName)
		}
	}
}

data class ValueColor(val name: String, val color: Int)
