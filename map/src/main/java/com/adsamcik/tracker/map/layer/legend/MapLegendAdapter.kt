package com.adsamcik.tracker.map.layer.legend

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.base.BaseRecyclerAdapter
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.shared.map.MapLegendValue
import com.adsamcik.tracker.shared.utils.style.implementations.StyleColorDrawable
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange

/**
 * Recycler adapter for [MapLegendValue].with support for [IViewChange].
 */
class MapLegendAdapter : BaseRecyclerAdapter<MapLegendValue, MapLegendAdapter.ViewHolder>(),
		IViewChange {

	override var onViewChangedListener: ((View) -> Unit)? = null

	/**
	 * Map Legend View Holder.
	 */
	class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val context = parent.context
		val inflater = LayoutInflater.from(context)
		val rootView = inflater.inflate(R.layout.map_sheet_legend_item, parent, false)
		val textView = rootView as TextView
		textView.setCompoundDrawablesWithIntrinsicBounds(
				StyleColorDrawable(
						requireNotNull(
								ContextCompat.getDrawable(context, R.drawable.legend_color)
						) as GradientDrawable,
						StyleColorDrawable.EXTENDED_STROKE_WIDTH
				),
				null,
				null,
				null
		)
		return ViewHolder(textView)
	}

	private fun updateCompoundDrawable(drawable: Drawable?, color: Int) {
		(drawable as? StyleColorDrawable)?.let { drawableWrapper ->
			drawableWrapper.drawable.color = ColorStateList.valueOf(color)
		}
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val data = getItem(position)

		holder.textView.apply {
			setText(data.nameRes)
			compoundDrawables.forEach { updateCompoundDrawable(it, data.color) }
		}
	}

	override fun onViewAttachedToWindow(holder: ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}
}
