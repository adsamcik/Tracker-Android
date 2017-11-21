package com.adsamcik.signalcollector.adapters

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import com.adsamcik.signalcollector.enums.AppendBehavior
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Table
import java.util.*

class TableAdapter(context: Context, itemMarginDp: Int) : BaseAdapter() {
    private val tables: ArrayList<Table> = ArrayList()
    private val context: Context

    private val itemMarginPx: Int

    init {
        this.context = context.applicationContext
        itemMarginPx = if (itemMarginDp == 0) 0 else Assist.dpToPx(context, itemMarginDp)
    }

    fun add(table: Table) {
        tables.add(table)
    }

    fun clear() {
        tables.clear()
        notifyDataSetChanged()
    }

    fun sort() {
        Collections.sort(tables) { tx, ty -> tx.appendBehavior - ty.appendBehavior }
        notifyDataSetChanged()
    }

    fun remove(@AppendBehavior appendBehavior: Int) {
        if (Build.VERSION.SDK_INT >= 24)
            tables.removeIf { table -> table.appendBehavior == appendBehavior }
        else {
            var i = 0
            while (i < tables.size) {
                if (tables[i].appendBehavior == appendBehavior)
                    tables.removeAt(i--)
                i++
            }
        }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = tables.size

    override fun getItem(i: Int): Any = tables[i]

    override fun getItemId(i: Int): Long = i.toLong()

    override fun getView(i: Int, view: View, viewGroup: ViewGroup): View {
        val v = tables[i].getView(context, view, true) as ViewGroup

        val lp = v.getChildAt(0).layoutParams as FrameLayout.LayoutParams
        lp.setMargins(lp.leftMargin, if (i > 0) itemMarginPx / 2 else itemMarginPx, lp.rightMargin, if (i < count - 1) itemMarginPx / 2 else itemMarginPx)
        v.layoutParams = lp
        return v
    }
}
