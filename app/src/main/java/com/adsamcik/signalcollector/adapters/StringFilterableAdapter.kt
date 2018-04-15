package com.adsamcik.signalcollector.adapters

import android.content.Context
import android.support.annotation.LayoutRes

/**
 * Implementation of the [FilterableAdapter] using Array of string for items and String for filtering
 */
class StringFilterableAdapter(context: Context, @LayoutRes resource: Int, stringMethod: (Array<String>) -> String) : FilterableAdapter<Array<String>, String>(context, resource, stringMethod) {
    override fun filter(item: Array<String>, filterObject: String?): Boolean {
        return filterObject == null || item.contains(filterObject)
    }
}