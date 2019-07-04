package com.adsamcik.signalcollector.statistics.detail.recycler.viewholder

import com.adsamcik.signalcollector.common.color.StyleController
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.signalcollector.commonmap.ColorMap
import com.adsamcik.signalcollector.statistics.detail.recycler.data.MapStatisticsData
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions

class MapViewHolder(val map: MapView) : MultiTypeViewHolder<MapStatisticsData>(map) {
	private var googleMap: GoogleMap? = null

	override fun bind(value: MapStatisticsData, styleController: StyleController) {
		map.onCreate(null)
		map.getMapAsync {
			googleMap = it
			val polyline = PolylineOptions().apply {
				addAll(value.locations)
			}
			it.addPolyline(polyline)

			val bounds = LatLngBounds.Builder()
					.include(LatLng(value.bounds.bottom, value.bounds.left))
					.include(LatLng(value.bounds.top, value.bounds.right))
					.build()

			val padding = 0
			val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, padding)
			it.moveCamera(cameraUpdate)
			ColorMap.addListener(map.context, it)
			map.invalidate()
		}
	}

	override fun onRecycle(styleController: StyleController) {
		googleMap?.let {
			ColorMap.removeListener(it)
			googleMap = null
		}
	}

}