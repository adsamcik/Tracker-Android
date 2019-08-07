package com.adsamcik.signalcollector.map.adapter

import android.content.Context
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.AppCompatTextView
import com.adsamcik.signalcollector.common.R
import com.adsamcik.signalcollector.common.adapter.BaseFilterableAdapter
import com.adsamcik.signalcollector.common.adapter.SimpleFilterableAdapter
import com.adsamcik.signalcollector.commonmap.CoordinateBounds
import com.adsamcik.signalcollector.map.layer.MapLayerData
import com.adsamcik.signalcollector.map.layer.MapLayerLogic

/**
 * Implementation of the [BaseFilterableAdapter] using a MapLayer and CoordinateBounds
 */
internal class MapFilterableAdapter(context: Context, @LayoutRes resource: Int, stringMethod: (MapLayerLogic) -> String) : SimpleFilterableAdapter<MapLayerLogic, CoordinateBounds>(context, resource, stringMethod) {
	override fun getTitleView(root: View): AppCompatTextView = root.findViewById(R.id.text_view)

	override fun filter(item: MapLayerLogic, filterObject: CoordinateBounds?): Boolean {
		val bounds = item.data.bounds
		return if (filterObject != null)
			bounds.top > filterObject.bottom && bounds.right > filterObject.left && bounds.bottom < filterObject.top && bounds.left < filterObject.right
		else
			true
	}
}