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
import java.util.*

abstract class FilterableAdapter<T, F> : BaseAdapter {

    /**
     * Collection contains raw elements before filtering
     */
    private var mRawCollection: MutableList<T>? = null

    /**
     * Collection contains elements that will FilterableAdapter primarily display
     */
    private var mDisplayCollection: MutableList<T>? = null

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
     * @param activity you need to pass activity if you are not calling this method on UI thread
     */
    @Synchronized
    fun add(item: T) {
        mRawCollection!!.add(item)
        if (filter(item, filterObject)) {
            mDisplayCollection!!.add(item)
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
                mDisplayCollection!!.add(item)
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
        if (mDisplayCollection != null)
            mDisplayCollection!!.clear()
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return mDisplayCollection!!.size
    }

    override fun getItem(position: Int): T {
        return mDisplayCollection!![position]
    }

    fun getItemName(position: Int): String {
        return mStringify.invoke(mDisplayCollection!![position])
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        val holder: ViewHolder
        if (convertView == null) {
            convertView = mInflater.inflate(res, null)
            holder = ViewHolder()
            holder.text = convertView as TextView?
            convertView!!.tag = holder
        } else {
            holder = convertView.tag as ViewHolder
        }
        holder.text!!.text = getItemName(position)

        return convertView
    }

    fun filter() {
        filter(filterObject)
    }

    fun filter(filterObject: F?) {
        this.filterObject = filterObject
        mDisplayCollection = ArrayList(mRawCollection!!.size)
        for (item in mRawCollection!!) {
            if (filter(item, filterObject))
                mDisplayCollection!!.add(item)
        }
        notifyDataSetChanged()
    }

    protected abstract fun filter(item: T, filterObject: F?): Boolean

    internal class ViewHolder {
        var text: TextView? = null
    }
}
