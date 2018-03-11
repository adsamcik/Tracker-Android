package com.adsamcik.signalcollector.adapters


import android.content.Context
import android.os.Looper
import android.support.annotation.LayoutRes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch

abstract class FilterableAdapter<T, F> : BaseAdapter {

    /**
     * Collection contains raw elements before filtering
     */
    private var mRawCollection: MutableList<T>? = null

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

    @LayoutRes
    private val res: Int

    val filteredCount: Int
        get() = mDisplayCollection!!.size


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
     * Adds item to adapter
     *
     * @param item     object that will be added to adapter
     */
    @Synchronized
    fun add(item: T) {
        mRawCollection!!.add(item)
        if (filter(item, filterObject)) {
            mDisplayCollection.add(item)
            launch(UI) {
                notifyDataSetChanged()
            }
        }
    }

    @Synchronized
    fun addAll(items: Collection<T>) {
        var anyPassed = false
        mRawCollection!!.addAll(items)
        for (item in items) {
            if (filter(item, filterObject)) {
                mDisplayCollection.add(item)
                anyPassed = true
            }
        }

        if (anyPassed)
            launch(UI) {
                notifyDataSetChanged()
            }
    }

    fun clear() {
        mRawCollection!!.clear()
        mDisplayCollection.clear()
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return mDisplayCollection.size
    }

    override fun getItem(position: Int): T {
        return mDisplayCollection[position]
    }

    fun getItemName(position: Int): String {
        return mStringify.invoke(mDisplayCollection[position])
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var cView = convertView
        val holder: ViewHolder
        if (cView == null) {
            cView = mInflater.inflate(res, null)
            holder = ViewHolder()
            holder.text = cView as TextView
            cView.tag = holder
        } else {
            holder = cView.tag as ViewHolder
        }
        holder.text!!.text = getItemName(position)

        return cView
    }

    fun filter() {
        filter(filterObject)
    }

    fun filter(filterObject: F?) {
        this.filterObject = filterObject
        mDisplayCollection = ArrayList(mRawCollection!!.size)
        mRawCollection!!
                .filter { filter(it, filterObject) }
                .forEach { mDisplayCollection.add(it) }
        notifyDataSetChanged()
    }

    protected abstract fun filter(item: T, filterObject: F?): Boolean

    internal class ViewHolder {
        var text: TextView? = null
    }
}
