package com.adsamcik.tracker.statistics.detail.recycler.creator

import android.view.ViewGroup
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.statistics.detail.recycler.StatisticsDetailData
import com.adsamcik.tracker.statistics.detail.recycler.viewholder.MapViewHolder
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView

/**
 * Creates view holder for map statistics.
 */
class MapViewHolderCreator : StatisticsViewHolderCreator {
	override fun createViewHolder(parent: ViewGroup): StyleMultiTypeViewHolder<StatisticsDetailData> {
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
					HEIGHT.dp
			)

			isClickable = false
		}
		@Suppress("unchecked_cast")
		return MapViewHolder(mapView) as StyleMultiTypeViewHolder<StatisticsDetailData>
	}

	companion object {
		private const val HEIGHT = 200
	}
}
