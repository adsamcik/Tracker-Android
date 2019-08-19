package com.adsamcik.tracker.commonmap

import android.os.Parcelable
import com.adsamcik.tracker.commonmap.CoordinateBounds.Companion.MAX_LATITUDE
import com.adsamcik.tracker.commonmap.CoordinateBounds.Companion.MAX_LONGITUDE
import com.adsamcik.tracker.commonmap.CoordinateBounds.Companion.MIN_LATITUDE
import com.adsamcik.tracker.commonmap.CoordinateBounds.Companion.MIN_LONGITUDE
import kotlinx.android.parcel.Parcelize
import kotlin.reflect.KClass

@Parcelize
data class MapLayerData(
		val id: Class<MapLayerLogic>,
		val bounds: CoordinateBounds,
		val legend: MapLegend
) : Parcelable {

	constructor(id: Class<MapLayerLogic>,
	            top: Double = MAX_LATITUDE,
	            right: Double = MAX_LONGITUDE,
	            bottom: Double = MIN_LATITUDE,
	            left: Double = MIN_LONGITUDE,
	            legend: MapLegend
	) : this(id, CoordinateBounds(top, right, bottom, left), legend)

	companion object {
		/**
		 * Checks if MapLayer is in given array
		 */
		fun contains(layerDataArray: Array<MapLayerData>, id: KClass<MapLayerLogic>): Boolean =
				layerDataArray.any { it.id == id }
	}
}
