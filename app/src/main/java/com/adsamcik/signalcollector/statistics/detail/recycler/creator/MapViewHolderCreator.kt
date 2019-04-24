package com.adsamcik.signalcollector.statistics.detail.recycler.creator

import android.view.ViewGroup
import com.adsamcik.signalcollector.misc.extension.dpAsPx
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailViewHolderCreator
import com.adsamcik.signalcollector.statistics.detail.recycler.ViewHolder
import com.adsamcik.signalcollector.statistics.detail.recycler.viewholder.MapViewHolder
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView

class MapViewHolderCreator : StatisticDetailViewHolderCreator {
	override fun createViewHolder(parent: ViewGroup): ViewHolder<StatisticDetailData> {
		val options = GoogleMapOptions().apply {
			mapType(GoogleMap.MAP_TYPE_NORMAL)
			liteMode(true)
			mapToolbarEnabled(false)
			zoomControlsEnabled(false)
			scrollGesturesEnabled(false)
		}
		val mapView = MapView(parent.context, options).apply {
			layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					200.dpAsPx)

			isClickable = false
		}
		@Suppress("unchecked_cast")
		return MapViewHolder(mapView, mapView) as ViewHolder<StatisticDetailData>
	}

}