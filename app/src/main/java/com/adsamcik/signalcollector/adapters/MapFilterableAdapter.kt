package com.adsamcik.signalcollector.adapters

import android.content.Context
import android.support.annotation.LayoutRes
import com.adsamcik.signalcollector.data.MapLayer
import com.adsamcik.signalcollector.utility.CoordinateBounds

/**
 * Implementation of the [FilterableAdapter] using a MapLayer and CoordinateBounds
 */
class MapFilterableAdapter(context: Context, @LayoutRes resource: Int, stringMethod: (MapLayer) -> String) : FilterableAdapter<MapLayer, CoordinateBounds>(context, resource, stringMethod) {
    override fun filter(item: MapLayer, filterObject: CoordinateBounds?): Boolean {
        return if (filterObject != null && item.name != "wifi")
            item.top > filterObject.bottom && item.right > filterObject.left && item.bottom < filterObject.top && item.left < filterObject.right
        else
            true
    }
}