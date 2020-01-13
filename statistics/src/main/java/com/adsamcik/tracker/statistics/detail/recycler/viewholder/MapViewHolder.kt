package com.adsamcik.tracker.statistics.detail.recycler.viewholder

import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.map.ColorMap
import com.adsamcik.tracker.statistics.detail.recycler.data.MapStatisticsData
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions

class MapViewHolder(val map: MapView) : StyleMultiTypeViewHolder<MapStatisticsData>(map) {
	private var googleMap: GoogleMap? = null

	override fun bind(data: MapStatisticsData, styleController: StyleController) {
		map.onCreate(null)
		map.getMapAsync {
			googleMap = it
			val polyline = PolylineOptions().apply {
				addAll(data.locations)
			}
			it.addPolyline(polyline)

			val bounds = LatLngBounds.Builder()
					.include(LatLng(data.bounds.bottom, data.bounds.left))
					.include(LatLng(data.bounds.top, data.bounds.right))
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
