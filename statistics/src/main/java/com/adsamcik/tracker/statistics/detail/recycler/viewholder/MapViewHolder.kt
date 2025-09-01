package com.adsamcik.tracker.statistics.detail.recycler.viewholder

import com.adsamcik.tracker.shared.map.ColorMap
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.statistics.detail.recycler.data.MapStatisticsData
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions

/**
 * ViewHolder for map displaying in statistics.
 */
@Deprecated("Use Compose LazyColumn with Material 3 theming instead of legacy View-based adapters with StyleController")
class MapViewHolder(val map: MapView) : StyleMultiTypeViewHolder<MapStatisticsData>(map) {
	private var googleMap: GoogleMap? = null

	@Deprecated("Use Compose LazyColumn with Material 3 theming instead of StyleController-based ViewHolders")
	@Suppress("DEPRECATION")
	override fun bind(data: MapStatisticsData, styleController: StyleController) {
		map.onCreate(null)
		map.getMapAsync {
			googleMap = it
			ColorMap.addListener(map.context, it)

			if (data.locations.isNotEmpty()) {
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
				map.invalidate()
			}
		}
	}

	@Deprecated("Use Compose LazyColumn with Material 3 theming instead of StyleController-based ViewHolders")
	@Suppress("DEPRECATION")
	override fun onRecycle(styleController: StyleController) {
		googleMap?.let {
			ColorMap.removeListener(it)
			googleMap = null
		}
	}

}
