package com.adsamcik.signalcollector.statistics.detail.recycler

import android.view.ViewGroup
import com.adsamcik.recycler.SortableAdapter

class StatsDetailAdapter : SortableAdapter<StatisticDetailData, ViewHolder<StatisticDetailData>>() {
	private val typeMap = mutableMapOf<Int, StatisticDetailViewHolderCreator>()

	override fun getItemViewType(position: Int) = getItem(position).type.ordinal

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder<StatisticDetailData> {
		val type = typeMap[viewType]
				?: throw NotRegisteredException("Type $viewType not registered")
		return type.createViewHolder(parent)
	}

	override fun onBindViewHolder(holder: ViewHolder<StatisticDetailData>, position: Int) {
		holder.bind(getItem(position))
	}

	fun registerType(type: StatisticDetailType, creator: StatisticDetailViewHolderCreator) {
		if (typeMap.containsKey(type.ordinal)) throw AlreadyRegisteredException("Type $type already registered")
		typeMap[type.ordinal] = creator
	}

	class NotRegisteredException(message: String) : Exception(message)
	class AlreadyRegisteredException(message: String) : Exception(message)
}