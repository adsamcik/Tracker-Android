package com.adsamcik.tracker.common.recycler.multitype

import android.view.View
import android.view.ViewGroup
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.tracker.common.style.marker.IViewChange
import com.adsamcik.tracker.common.style.StyleController

open class BaseMultiTypeAdapter<Data : BaseMultiTypeData>(
		private val styleController: StyleController
) : SortableAdapter<Data, MultiTypeViewHolder<Data>>(),
		IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	private val typeMap = mutableMapOf<Int, MultiTypeViewHolderCreator<Data>>()

	override fun getItemViewType(position: Int): Int = getItem(position).typeValue

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MultiTypeViewHolder<Data> {
		val type = typeMap[viewType]
				?: throw NotRegisteredException("Type $viewType not registered")
		return type.createViewHolder(parent)
	}

	override fun onBindViewHolder(holder: MultiTypeViewHolder<Data>, position: Int) {
		holder.bind(getItem(position), styleController)
		onViewChangedListener?.invoke(holder.itemView)
	}

	@Throws(AlreadyRegisteredException::class)
	fun registerType(typeValue: Int, creator: MultiTypeViewHolderCreator<Data>) {
		if (typeMap.containsKey(typeValue)) {
			throw AlreadyRegisteredException("Type $typeValue already registered")
		}

		typeMap[typeValue] = creator
	}

	override fun onViewRecycled(holder: MultiTypeViewHolder<Data>) {
		super.onViewRecycled(holder)
		holder.onRecycle(styleController)
	}

	class NotRegisteredException(message: String) : Exception(message)
	class AlreadyRegisteredException(message: String) : Exception(message)
}

