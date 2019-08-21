package com.adsamcik.tracker.common.recycler

import androidx.annotation.MainThread
import androidx.recyclerview.widget.RecyclerView

@MainThread
abstract class BaseRecyclerAdapter<Data, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
	private val data = mutableListOf<Data>()

	override fun getItemCount(): Int = data.size

	fun clear() {
		data.clear()
		notifyDataSetChanged()
	}

	fun add(item: Data) {
		data.add(item)
		notifyItemInserted(data.size - 1)
	}

	fun addAll(item: Collection<Data>) {
		data.addAll(item)
		val lastIndex = data.size - 1
		notifyItemRangeInserted(lastIndex - item.size, lastIndex)
	}

	fun remove(item: Data) {
		val index = data.indexOf(item)
		data.removeAt(index)
		notifyItemRemoved(index)
	}

	fun get(index: Int): Data {
		return data[index]
	}
}
