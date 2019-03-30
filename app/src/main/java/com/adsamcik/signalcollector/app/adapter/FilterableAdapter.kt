package com.adsamcik.signalcollector.app.adapter


import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Abstract class that contains basic implementation to allow filtering.
 */
abstract class FilterableAdapter<T, F> : RecyclerView.Adapter<FilterableAdapter.ViewHolder> {

	/**
	 * Collection contains raw elements before filtering
	 */
	private var mRawCollection: MutableList<T>

	/**
	 * Collection contains elements that will FilterableAdapter primarily display
	 */
	private var mDisplayCollection: ArrayList<T> = ArrayList(0)

	/**
	 * Used to convert objects to titles
	 */
	protected var mStringify: (T) -> String

	protected var filterObject: F? = null

	private val mInflater: LayoutInflater

	var onItemClickListener: ((position: Int) -> Unit)? = null

	@LayoutRes
	private val res: Int

	val filteredCount: Int
		get() = mDisplayCollection.size


	constructor(context: Context, @LayoutRes resource: Int, stringMethod: (T) -> String) {
		mRawCollection = ArrayList()

		if (Looper.myLooper() == null)
			Looper.prepare()

		this.mStringify = stringMethod

		mInflater = LayoutInflater.from(context)
		res = resource
	}

	constructor(context: Context, @LayoutRes resource: Int, stringMethod: (T) -> String, initialCollection: MutableList<T>) {
		mRawCollection = initialCollection

		if (Looper.myLooper() == null)
			Looper.prepare()

		this.mStringify = stringMethod

		mInflater = LayoutInflater.from(context)
		res = resource
	}

	/**
	 * Adds item to the adapter
	 *
	 * @param item     object that will be added to adapter
	 */
	@Synchronized
	fun add(item: T) {
		mRawCollection.add(item)
		if (filter(item, filterObject)) {
			mDisplayCollection.add(item)
			GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
				notifyDataSetChanged()
			}
		}
	}

	/**
	 * Adds all items from the Collection to the adapter
	 *
	 * @param items Collection of items
	 */
	@Synchronized
	fun addAll(items: Collection<T>) {
		var anyPassed = false
		mRawCollection.addAll(items)
		for (item in items) {
			if (filter(item, filterObject)) {
				mDisplayCollection.add(item)
				anyPassed = true
			}
		}

		if (anyPassed)
			GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
				notifyDataSetChanged()
			}
	}

	/**
	 * Clears all items from the adapter
	 */
	fun clear() {
		mRawCollection.clear()
		mDisplayCollection.clear()
		notifyDataSetChanged()
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

	fun getItem(position: Int): T = mRawCollection[position]

	override fun getItemId(position: Int): Long {
		return position.toLong()
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = mInflater.inflate(res, parent, false)
		val viewHolder = ViewHolder(view as TextView)

		view.setOnClickListener { onItemClickListener?.invoke(viewHolder.adapterPosition) }
		return viewHolder
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		holder.text.text = getItemName(position)
	}

	/**
	 * Triggers filtering of the whole adapter using filter object.
	 * This object is used by implementation to filter items. It can be of different type than the containing objects to provide
	 * the exact information that is needed for proper filtering.
	 *
	 * @param filterObject Object used for filtering
	 */
	fun filter(filterObject: F?) {
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
	protected abstract fun filter(item: T, filterObject: F?): Boolean

	data class ViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}
