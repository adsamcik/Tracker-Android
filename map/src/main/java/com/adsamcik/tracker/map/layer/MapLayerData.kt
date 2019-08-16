package com.adsamcik.tracker.map.layer

import androidx.annotation.StringRes
import com.adsamcik.tracker.commonmap.CoordinateBounds
import com.adsamcik.tracker.commonmap.CoordinateBounds.Companion.MAX_LATITUDE
import com.adsamcik.tracker.commonmap.CoordinateBounds.Companion.MAX_LONGITUDE
import com.adsamcik.tracker.commonmap.CoordinateBounds.Companion.MIN_LATITUDE
import com.adsamcik.tracker.commonmap.CoordinateBounds.Companion.MIN_LONGITUDE
import kotlin.reflect.KClass

internal data class MapLayerData(val id: KClass<MapLayerLogic>,
                                 @StringRes val nameRes: Int,
                                 val bounds: CoordinateBounds,
                                 var legend: Legend? = null
) {

	constructor(id: KClass<MapLayerLogic>,
	            nameRes: Int,
	            top: Double = MAX_LATITUDE,
	            right: Double = MAX_LONGITUDE,
	            bottom: Double = MIN_LATITUDE,
	            left: Double = MIN_LONGITUDE,
	            legend: Legend? = null
	) : this(id, nameRes, CoordinateBounds(top, right, bottom, left), legend)

	companion object {
		/**
		 * Checks if MapLayer is in given array
		 */
		fun contains(layerDataArray: Array<MapLayerData>, id: KClass<MapLayerLogic>): Boolean =
				layerDataArray.any { it.id == id }
	}
}

data class ValueColor(val name: String, val color: Int)

data class Legend(val items: List<ValueColor>)

