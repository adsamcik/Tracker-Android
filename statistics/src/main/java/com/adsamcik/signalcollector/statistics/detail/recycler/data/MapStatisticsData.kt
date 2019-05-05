package com.adsamcik.signalcollector.statistics.detail.recycler.data

import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.map.CoordinateBounds
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailType
import com.google.android.gms.maps.model.LatLng

class MapStatisticsData(val locations: Collection<LatLng>, val bounds: com.adsamcik.signalcollector.map.CoordinateBounds) : StatisticDetailData {
	override val type = StatisticDetailType.Map


	constructor(locations: List<DatabaseLocation>) : this(locations.map { LatLng(it.latitude, it.longitude) }, com.adsamcik.signalcollector.map.CoordinateBounds().apply { updateBounds(locations) })

}