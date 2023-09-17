package com.adsamcik.tracker.shared.base.adapter


import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.base.assist.Assist
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Abstract class that contains basic implementation to allow filtering.
 */
abstract class BaseFilterableAdapter<DataType, FilterType, ViewHolder : RecyclerView.ViewHolder>(
		stringMethod: (DataType) -> String,
		initialCollection: MutableList<DataType>
) : RecyclerView.Adapter<ViewHolder>() {

	/**
	 * Collection contains raw elements before filtering
	 */
	private var mRawCollection: MutableList<DataType> = initialCollection

	/**
	 * Collection contains elements that will BaseFilterableAdapter primarily display
	 */
	private var mDisplayCollection: ArrayList<DataType> = ArrayList(0)

	/**
	 * Used to convert objects to titles
	 */
	protected var mStringify: (DataType) -> String = stringMethod

	protected var filterObject: FilterType? = null

	var onItemClickListener: ((position: Int, item: DataType) -> Unit)? = null

	val filteredCount: Int
		get() = mDisplayCollection.size


	init {
		Assist.ensureLooper()
	}

	/**
	 * Adds item to the adapter
	 *
	 * @param item     object that will be added to adapter
	 */
	@OptIn(DelicateCoroutinesApi::class)
	@Synchronized
	fun add(item: DataType) {
		mRawCollection.add(item)
		if (filter(item, filterObject)) {
			mDisplayCollection.add(item)
			val index = mDisplayCollection.size
			GlobalScope.launch(Dispatchers.Main) {
				notifyItemInserted(index)
			}
		}
	}

	/**
	 * Adds all items from the Collection to the adapter
	 *
	 * @param items Collection of items
	 */
	@OptIn(DelicateCoroutinesApi::class)
	@Synchronized
	fun addAll(items: Collection<DataType>) {
		var numberAdded = 0
		val countBefore = mDisplayCollection.size
		mRawCollection.addAll(items)
		for (item in items) {
			if (filter(item, filterObject)) {
				mDisplayCollection.add(item)
				numberAdded++
			}
		}

		if (numberAdded > 0) {
			GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
				notifyItemRangeInserted(countBefore, numberAdded)
			}
		}
	}

	/**
	 * Clears all items from the adapter
	 */
	fun clear() {
		mRawCollection.clear()
		val sizeBeforeClear = mDisplayCollection.size
		mDisplayCollection.clear()
		notifyItemRangeRemoved(0, sizeBeforeClear)
	}

	override fun getItemCount(): Int {
		return mDisplayCollection.size
	}

	/**
	 * Returns item name for item at a given position
	 *
	 * @param position Position of the item
	 */
	fun getItemName(position: Int): String {
		return mStringify.invoke(mDisplayCollection[position])
	}

	fun getItem(position: Int): DataType = mRawCollection[position]

	override fun getItemId(position: Int): Long {
		return position.toLong()
	}

	/**
	 * Triggers filtering of the whole adapter using filter object.
	 * This object is used by implementation to filter items.
	 * It can be of different type than the containing objects to provide
	 * the exact information that is needed for proper filtering.
	 *
	 * @param filterObject Object used for filtering
	 */
	fun filter(filterObject: FilterType?) {
		this.filterObject = filterObject
		mDisplayCollection = ArrayList(mRawCollection.size)
		mRawCollection
				.filter { filter(it, filterObject) }
				.forEach { mDisplayCollection.add(it) }
		notifyDataSetChanged()
	}

	/**
	 * Filter function that is called to filter specific item
	 *
	 * @param item Item to filter
	 * @param filterObject Object used for filtering
	 * @return True if object should be included
	 */
	protected abstract fun filter(item: DataType, filterObject: FilterType?): Boolean
}

