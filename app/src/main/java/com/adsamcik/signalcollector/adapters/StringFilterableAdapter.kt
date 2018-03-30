package com.adsamcik.signalcollector.adapters

import android.content.Context
import android.support.annotation.LayoutRes


class StringFilterableAdapter(context: Context, @LayoutRes resource: Int, stringMethod: (Array<String>) -> String) : FilterableAdapter<Array<String>, String>(context, resource, stringMethod) {
    override fun filter(item: Array<String>, filterObject: String?): Boolean {
        return filterObject == null || item.contains(filterObject)
    }
}