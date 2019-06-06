package com.adsamcik.signalcollector.statistics.detail.recycler

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.color.ColorController

interface StatisticDetailViewHolderCreator {
	fun createViewHolder(parent: ViewGroup): ViewHolder<StatisticDetailData>
}

enum class StatisticDetailType {
	Information,
	Map,
	LineChart
}

interface StatisticDetailData {
	val type: StatisticDetailType
}

abstract class ViewHolder<T : StatisticDetailData>(rootView: View) : RecyclerView.ViewHolder(rootView) {
	abstract fun bind(value: T, colorController: ColorController)

	open fun onRecycle(colorController: ColorController) {}
}