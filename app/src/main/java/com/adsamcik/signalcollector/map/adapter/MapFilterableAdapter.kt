package com.adsamcik.signalcollector.map.adapter

import android.content.Context
import android.view.View
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.app.adapter.FilterableAdapter
import com.adsamcik.signalcollector.map.CoordinateBounds
import com.adsamcik.signalcollector.map.MapLayer

/**
 * Implementation of the [FilterableAdapter] using a MapLayer and CoordinateBounds
 */
class MapFilterableAdapter(context: Context, @LayoutRes resource: Int, stringMethod: (MapLayer) -> String) : FilterableAdapter<MapLayer, CoordinateBounds>(context, resource, stringMethod) {

	data class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

	override fun filter(item: MapLayer, filterObject: CoordinateBounds?): Boolean {
		return if (filterObject != null && item.name != "wifi")
			item.bounds.top > filterObject.bottom && item.bounds.right > filterObject.left && item.bounds.bottom < filterObject.top && item.bounds.left < filterObject.right
		else
			true
	}
}