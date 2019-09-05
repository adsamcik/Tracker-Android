package com.adsamcik.tracker.map

import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.commonmap.MapLayerData
import com.adsamcik.tracker.map.layer.legend.MapLegendAdapter

class MapLegendController(rootLayout: View) {
	private val legendAdapter = MapLegendAdapter()

	private val titleView = rootLayout.findViewById<TextView>(R.id.map_legend_title)
	private val descriptionView = rootLayout.findViewById<TextView>(R.id.map_legend_description)

	init {
		val context = rootLayout.context
		rootLayout.findViewById<RecyclerView>(R.id.map_legend_recycler).apply {
			adapter = legendAdapter
			layoutManager = LinearLayoutManager(context)

			addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
				override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

				override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
					if (e.action == MotionEvent.ACTION_DOWN &&
							rv.scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
						rv.stopScroll()
					}
					return false
				}

				override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
			})
		}
	}

	fun setLayer(layerData: MapLayerData) {
		titleView.setText(layerData.info.nameRes)

		val descriptionRes = layerData.legend.description
		if (descriptionRes != null) {
			descriptionView.setText(descriptionRes)
		}

		legendAdapter.removeAll()
		legendAdapter.addAll(layerData.legend.valueList)
	}

}
