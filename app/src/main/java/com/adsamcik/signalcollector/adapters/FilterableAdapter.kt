package com.adsamcik.signalcollector.adapters

import android.content.Context
import android.os.Looper
import android.support.annotation.LayoutRes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import com.adsamcik.signalcollector.interfaces.IFilterRule
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.util.regex.Pattern

class FilterableAdapter<T>
/**
 * Filterable adapter constructor
 *
 * @param context      Context
 * @param res     Resource for items
 * @param items        Initial item list
 * @param filterRule   Initial filtering rule
 * @param stringMethod Method to convert objects to strings
 */
(context: Context, @param:LayoutRes @field:LayoutRes
private val res: Int, items: ArrayList<T>?, private var filterRule: IFilterRule<T>?, private val stringMethod: (T) -> String) : BaseAdapter(), Filterable {
    private val dataList: ArrayList<T>?
    private val stringDataList: ArrayList<String>
    private var filteredData: ArrayList<String>? = null
    private var lastConstraint: CharSequence? = null

    private val mInflater: LayoutInflater
    private val mFilter: ItemFilter

    init {
        if (items == null) {
            this.dataList = ArrayList()
            this.stringDataList = ArrayList()
        } else {
            this.dataList = items
            this.stringDataList = ArrayList(items.size)
            for (item in items)
                this.stringDataList.add(stringMethod.invoke(item))
        }

        if (Looper.myLooper() == null)
            Looper.prepare()

        mFilter = ItemFilter()

        mInflater = LayoutInflater.from(context)
    }

    override fun getCount(): Int =
            if (lastConstraint == null) stringDataList.size else filteredData!!.size

    override fun getItem(position: Int): String =
            if (lastConstraint == null) stringDataList[position] else filteredData!![position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View
        val vh: ListRowHolder
        if (convertView == null) {
            view = mInflater.inflate(res, parent, false)
            vh = ListRowHolder(view)
            view.tag = vh
        } else {
            view = convertView
            vh = view.tag as ListRowHolder
        }

        vh.label.text = getItem(position)
        return view
    }

    private class ListRowHolder(row: View?) {
        val label: TextView = row as TextView
    }

    fun setFilterRule(filterRule: IFilterRule<T>?) {
        this.filterRule = filterRule
        if (lastConstraint != null)
            filter.filter(lastConstraint)
    }

    override fun getFilter(): Filter = mFilter

    fun add(item: T) {
        dataList!!.add(item)
        val string = stringMethod.invoke(item)
        stringDataList.add(string)
        if (lastConstraint != null) {
            if (filterRule != null) {
                if (filterRule!!.filter(item, string, lastConstraint!!))
                    filteredData!!.add(string)
            } else {
                val pattern = Pattern.compile(lastConstraint!!.toString())
                if (pattern.matcher(string).find())
                    filteredData!!.add(string)
            }
        }

        launch(UI) {
            notifyDataSetChanged()
        }
    }

    fun clear() {
        dataList!!.clear()
        stringDataList.clear()
        if (filteredData != null)
            filteredData!!.clear()
        notifyDataSetChanged()
    }

    //todo rewrite filtering
    private inner class ItemFilter : Filter() {
        override fun performFiltering(constraint: CharSequence?): Filter.FilterResults {
            val results = Filter.FilterResults()
            if (dataList == null) {
                results.values = ArrayList<Any>(0)
                results.count = 0
            } else if (constraint == null || constraint.isEmpty()) {
                results.values = stringDataList
                results.count = stringDataList.size
            } else {
                val count = stringDataList.size
                val nlist = ArrayList<String>(count)

                if (filterRule != null) {
                    for (i in 0 until count) {
                        val stringified = stringDataList[i]
                        if (filterRule!!.filter(dataList[i], stringified, constraint))
                            nlist.add(stringified)
                    }
                } else {
                    val pattern = Pattern.compile(constraint.toString())

                    for (i in 0 until count) {
                        val filterableString = stringDataList[i]
                        val matcher = pattern.matcher(filterableString)
                        if (matcher.find()) {
                            nlist.add(filterableString)
                        }
                    }
                }

                results.values = nlist
                results.count = nlist.size

            }
            return results
        }

        override fun publishResults(constraint: CharSequence?, results: Filter.FilterResults) {
            @Suppress("UNCHECKED_CAST")
            filteredData = results.values as ArrayList<String>
            lastConstraint = if (constraint == null || constraint.isEmpty())
                null
            else
                constraint
            notifyDataSetChanged()
        }

    }
}
