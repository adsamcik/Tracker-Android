package com.adsamcik.signalcollector.app.adapter

import android.content.Context
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.AppCompatTextView
import kotlinx.android.synthetic.main.spinner_item.view.*

/**
 * Implementation of the [BaseFilterableAdapter] using Array of string for items and String for filtering
 */
class StringFilterableAdapter(context: Context, @LayoutRes resource: Int, stringMethod: (Array<String>) -> String) : SimpleFilterableAdapter<Array<String>, String>(context, resource, stringMethod) {
	override fun getTitleView(root: View): AppCompatTextView = root.text_view

	override fun filter(item: Array<String>, filterObject: String?): Boolean {
		return filterObject == null || item.contains(filterObject)
	}
}