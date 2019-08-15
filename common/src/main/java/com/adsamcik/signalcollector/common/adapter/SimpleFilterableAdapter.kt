package com.adsamcik.signalcollector.common.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

abstract class SimpleFilterableAdapter<DataType, FilterType>(context: Context,
                                                             @LayoutRes private val resource: Int,
                                                             stringMethod: (DataType) -> String,
                                                             initialCollection: MutableList<DataType> = mutableListOf()
) :
		BaseFilterableAdapter<DataType, FilterType, SimpleFilterableAdapter.ViewHolder>(stringMethod,
				initialCollection) {

	class ViewHolder(view: View, val titleView: TextView) : RecyclerView.ViewHolder(view)

	protected val mInflater: LayoutInflater = LayoutInflater.from(context)

	abstract fun getTitleView(root: View): TextView

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		holder.titleView.text = getItemName(position)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = mInflater.inflate(resource, parent, false)
		val viewHolder = ViewHolder(view, getTitleView(view))

		view.setOnClickListener {
			val position = viewHolder.adapterPosition
			onItemClickListener?.invoke(position, getItem(position))
		}
		return viewHolder
	}

}

