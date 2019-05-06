package com.adsamcik.signalcollector.map.adapter

import android.content.Context
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.AppCompatTextView
import com.adsamcik.signalcollector.common.adapter.BaseFilterableAdapter
import com.adsamcik.signalcollector.common.adapter.SimpleFilterableAdapter
import com.adsamcik.signalcollector.commonmap.CoordinateBounds
import com.adsamcik.signalcollector.map.MapLayer
import com.adsamcik.signalcollector.common.R

/**
 * Implementation of the [BaseFilterableAdapter] using a MapLayer and CoordinateBounds
 */
class MapFilterableAdapter(context: Context, @LayoutRes resource: Int, stringMethod: (MapLayer) -> String) : SimpleFilterableAdapter<MapLayer, CoordinateBounds>(context, resource, stringMethod) {
	override fun getTitleView(root: View): AppCompatTextView = root.findViewById(R.id.text_view)

	override fun filter(item: MapLayer, filterObject: CoordinateBounds?): Boolean {
		return if (filterObject != null && item.name != "wifi")
			item.bounds.top > filterObject.bottom && item.bounds.right > filterObject.left && item.bounds.bottom < filterObject.top && item.bounds.left < filterObject.right
		else
			true
	}
}