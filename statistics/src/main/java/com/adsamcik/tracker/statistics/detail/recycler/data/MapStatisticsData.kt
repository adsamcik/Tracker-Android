package com.adsamcik.tracker.statistics.detail.recycler.data

import com.adsamcik.tracker.common.database.data.DatabaseLocation
import com.adsamcik.tracker.commonmap.CoordinateBounds
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDetailType
import com.google.android.libraries.maps.model.LatLng

class MapStatisticsData(val locations: Collection<LatLng>, val bounds: CoordinateBounds) : StatisticDetailData {
	override val type = StatisticDetailType.Map

	constructor(locations: List<DatabaseLocation>) : this(locations.map { LatLng(it.latitude, it.longitude) },
			CoordinateBounds().apply { updateBounds(locations.map { it.location }) })

}

