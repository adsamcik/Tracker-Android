package com.adsamcik.signalcollector.adapters

import android.app.Activity
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
import java.util.*
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
private val res: Int, items: MutableList<T>?, private var filterRule: IFilterRule<T>?, private val stringMethod: (T) -> String) : BaseAdapter(), Filterable {
    private val dataList: MutableList<T>?
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
        var cView = convertView
        // A ViewHolder keeps references to children views to avoid unnecessary calls
        // to findViewById() on each row.

        val holder: ViewHolder

        // When convertView is not null, we can reuse it directly, there is no need
        // to reinflate it. We only inflate a new View when the convertView supplied
        // by ListView is null.
        if (cView == null) {
            cView = mInflater.inflate(res, null)

            // Creates a ViewHolder and store references to the two children views
            // we want to bind data to.
            holder = ViewHolder()
            holder.text = cView as TextView?

            // Bind the data efficiently with the holder.
            cView.tag = holder
        } else {
            // Get the ViewHolder back to getPref fast access to the TextView
            // and the ImageView.
            holder = cView.tag as ViewHolder
        }

        // If weren't re-ordering this you could rely on what you set last time
        holder.text!!.text = getItem(position)

        return cView!!
    }

    internal class ViewHolder {
        var text: TextView? = null
    }

    fun setFilterRule(filterRule: IFilterRule<T>?) {
        this.filterRule = filterRule
        if (lastConstraint != null)
            filter.filter(lastConstraint)
    }

    override fun getFilter(): Filter = mFilter

    fun add(item: T, activity: Activity?) {
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

        if (activity != null)
            activity.runOnUiThread { this.notifyDataSetChanged() }
        else
            notifyDataSetChanged()
    }

    fun clear() {
        dataList!!.clear()
        stringDataList.clear()
        if (filteredData != null)
            filteredData!!.clear()
        notifyDataSetChanged()
    }

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
