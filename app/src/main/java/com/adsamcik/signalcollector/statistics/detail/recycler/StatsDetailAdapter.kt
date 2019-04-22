package com.adsamcik.signalcollector.statistics.detail.recycler

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class StatsDetailAdapter : RecyclerView.Adapter<ViewHolder<StatisticDetailData>>() {
	private val typeMap = mutableMapOf<Int, StatisticDetailViewHolderCreator>()
	private val dataList = mutableListOf<StatisticDetailData>()

	override fun getItemViewType(position: Int) = dataList[position].type.ordinal

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder<StatisticDetailData> {
		val type = typeMap[viewType]
				?: throw NotRegisteredException("Type $viewType not registered")
		return type.createViewHolder(parent)
	}

	override fun getItemCount() = dataList.size

	override fun onBindViewHolder(holder: ViewHolder<StatisticDetailData>, position: Int) {
		holder.bind(dataList[position])
	}

	fun registerType(type: StatisticDetailType, creator: StatisticDetailViewHolderCreator) {
		if (typeMap.containsKey(type.ordinal)) throw RuntimeException("Type $type already registered")
		typeMap[type.ordinal] = creator
	}

	fun addData(data: Collection<StatisticDetailData>) {
		dataList.addAll(data)
		notifyDataSetChanged()
	}

	class NotRegisteredException(message: String) : Exception(message)
}