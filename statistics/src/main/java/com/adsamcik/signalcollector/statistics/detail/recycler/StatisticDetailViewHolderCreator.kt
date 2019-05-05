package com.adsamcik.signalcollector.statistics.detail.recycler

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

interface StatisticDetailViewHolderCreator {
	fun createViewHolder(parent: ViewGroup): ViewHolder<StatisticDetailData>
}

enum class StatisticDetailType {
	Information,
	Map
}

interface StatisticDetailData {
	val type: StatisticDetailType
}

abstract class ViewHolder<T : StatisticDetailData>(rootView: View) : RecyclerView.ViewHolder(rootView) {
	abstract fun bind(value: T)

	open fun onRecycle() {}
}