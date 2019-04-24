package com.adsamcik.signalcollector.statistics.detail.recycler.viewholder

import android.view.View
import com.adsamcik.signalcollector.statistics.detail.recycler.ViewHolder
import com.adsamcik.signalcollector.statistics.detail.recycler.data.MapStatisticsData
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions

class MapViewHolder(view: View, val map: MapView) : ViewHolder<MapStatisticsData>(view) {
	override fun bind(value: MapStatisticsData) {
		map.onCreate(null)
		map.getMapAsync {
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
			map.invalidate()
		}
	}
}