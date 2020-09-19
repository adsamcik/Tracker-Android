package com.adsamcik.tracker.statistics.detail.recycler.data

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.map.CoordinateBounds
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.detail.recycler.StatisticsDetailData
import com.google.android.gms.maps.model.LatLng

/**
 * Data for map statistics.
 * Contains all data required to draw line data on a map.
 */
class MapStatisticsData(
		val locations: Collection<LatLng>,
		val bounds: CoordinateBounds
) : StatisticsDetailData {
	override val type = StatisticDisplayType.Map

	constructor(locations: List<Location>)
			: this(locations.map {
		LatLng(it.latitude, it.longitude)
	}, CoordinateBounds().apply { updateBounds(locations) })

	constructor(locations: Array<Location>)
			: this(locations.map {
		LatLng(it.latitude, it.longitude)
	}, CoordinateBounds().apply { updateBounds(locations.asIterable()) })

}

