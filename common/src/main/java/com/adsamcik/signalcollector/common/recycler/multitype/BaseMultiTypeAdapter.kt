package com.adsamcik.signalcollector.common.recycler.multitype

import android.view.ViewGroup
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.signalcollector.common.color.ColorController

open class BaseMultiTypeAdapter<Data : BaseMultiTypeData>(private val colorController: ColorController) : SortableAdapter<Data, MultiTypeViewHolder<Data>>() {
	private val typeMap = mutableMapOf<Int, MultiTypeViewHolderCreator<Data>>()

	override fun getItemViewType(position: Int) = getItem(position).typeValue

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MultiTypeViewHolder<Data> {
		val type = typeMap[viewType]
				?: throw NotRegisteredException("Type $viewType not registered")
		return type.createViewHolder(parent)
	}

	override fun onBindViewHolder(holder: MultiTypeViewHolder<Data>, position: Int) {
		holder.bind(getItem(position), colorController)
	}

	@Throws(AlreadyRegisteredException::class)
	fun registerType(typeValue: Int, creator: MultiTypeViewHolderCreator<Data>) {
		if (typeMap.containsKey(typeValue)) throw AlreadyRegisteredException("Type $typeValue already registered")
		typeMap[typeValue] = creator
	}

	override fun onViewRecycled(holder: MultiTypeViewHolder<Data>) {
		super.onViewRecycled(holder)
		holder.onRecycle(colorController)
	}

	class NotRegisteredException(message: String) : Exception(message)
	class AlreadyRegisteredException(message: String) : Exception(message)
}