package com.adsamcik.tracker.common.recycler.multitype

import android.view.View
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeAdapter
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeData
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeViewHolderCreator
import com.adsamcik.tracker.common.style.StyleController
import com.adsamcik.tracker.common.style.marker.IViewChange

open class StyleMultiTypeAdapter<DataTypeEnum : Enum<*>, Data : MultiTypeData<DataTypeEnum>>(
		val styleController: StyleController
) : MultiTypeAdapter<DataTypeEnum, Data, StyleMultiTypeViewHolder<Data>>(), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onBindViewHolder(holder: StyleMultiTypeViewHolder<Data>, position: Int) {
		holder.bind(getItem(position), styleController)
	}

	override fun onViewAttachedToWindow(holder: StyleMultiTypeViewHolder<Data>) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onViewRecycled(holder: StyleMultiTypeViewHolder<Data>) {
		super.onViewRecycled(holder)
		holder.onRecycle(styleController)
	}
}

