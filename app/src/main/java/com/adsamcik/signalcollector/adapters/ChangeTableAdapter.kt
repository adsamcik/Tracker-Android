package com.adsamcik.signalcollector.adapters

import android.content.Context
import android.support.annotation.StyleRes
import android.view.View
import android.view.ViewGroup
import com.adsamcik.signalcollector.interfaces.IViewChange
import com.adsamcik.table.TableAdapter

/**
 * ChangeTableAdapter was created to extend TableAdapter with IViewChange interface for proper color updating
 */
internal class ChangeTableAdapter(context: Context, itemMarginDp: Int, @StyleRes themeInt: Int) : TableAdapter(context, itemMarginDp, themeInt), IViewChange {
    override var onViewChangedListener: ((View) -> Unit)? = null

    override fun getView(i: Int, view: View?, viewGroup: ViewGroup): View {
        val finalView = super.getView(i, view, viewGroup)
        onViewChangedListener?.invoke(finalView)
        return finalView
    }
}