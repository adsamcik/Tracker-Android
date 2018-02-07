package com.adsamcik.signalcollector.utility

import android.app.Activity
import android.content.Context
import android.support.design.widget.FloatingActionButton
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.adapters.FilterableAdapter
import com.adsamcik.signals.base.Assist
import com.adsamcik.signals.base.components.Animate
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch

class FabMenu<T, F>(parent: ViewGroup, private val fab: FloatingActionButton?, activity: Activity, val adapter: FilterableAdapter<T, F>, filterObject: F?) {
    private val TAG = "SignalsFabMenu"

    private val wrapper: ViewGroup = LayoutInflater.from(activity).inflate(R.layout.fab_menu, parent, false) as ViewGroup
    private val listView: ListView

    private var callback: ((View, T) -> Unit)? = null

    private val closeClickListener = View.OnClickListener { _ -> hide() }

    private var isVisible = false
    private var boundsCalculated = false

    val itemCount: Int
        get() = adapter.count

    init {
        listView = wrapper.getChildAt(0) as ListView
        wrapper.visibility = View.INVISIBLE
        listView.visibility = View.INVISIBLE
        adapter.filter(filterObject)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, view, i, _ -> callback(view, adapter.getItem(i)) }

        activity.runOnUiThread { parent.addView(wrapper) }
    }

    private fun callback(view: View, value: T) {
        if (callback != null)
            callback!!.invoke(view, value)
        hide()
    }

    fun setCallback(callback: ((View, T) -> Unit)?): FabMenu<*, *> {
        this.callback = callback
        return this
    }

    fun addItems(itemList: List<T>): FabMenu<*, *> {
        for (item in itemList)
            addItem(item)
        return this
    }

    fun addItems(itemList: Array<T>): FabMenu<*, *> {
        for (item in itemList)
            addItem(item)
        return this
    }

    fun addItem(item: T): FabMenu<*, *> {
        adapter.add(item)
        boundsCalculated = false
        return this
    }

    fun recalculateBounds(context: Context) {
        if (boundsCalculated)
            return

        val dp16px = Assist.dpToPx(context, 16)

        val maxHeight = wrapper.height / 2
        var height: Int
        height = if (adapter.count == 0)
            0
        else {
            val item = adapter.getView(0, null, listView)
            item.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val measureHeight = item.measuredHeight
            measureHeight * adapter.count
        }
        val minHeight = fab!!.height + dp16px
        if (height > maxHeight)
            height = maxHeight
        else if (height < minHeight)
            height = minHeight

        val fabPos = IntArray(2)
        val fabParentPos = IntArray(2)
        val wrapperPos = IntArray(2)
        fab.getLocationOnScreen(fabPos)
        wrapper.getLocationOnScreen(wrapperPos)
        val parent = fab.parent.parent.parent as View
        parent.getLocationOnScreen(fabParentPos)


        val displayMetrics = context.resources.displayMetrics
        listView.x = (displayMetrics.widthPixels - listView.width - dp16px).toFloat()

        fabPos[1] += fab.height
        var offset = height
        val botY = fabPos[1]
        val maxY = wrapperPos[1] + wrapper.height - Assist.dpToPx(context, 56)
        if (botY > maxY)
            offset += botY - maxY

        val y = fabPos[1] - offset
        listView.y = y.toFloat()

        val layoutParams = FrameLayout.LayoutParams(listView.width, height)
        listView.layoutParams = layoutParams
        boundsCalculated = true
        //Log.d(TAG, "offset " + offset + " y " + y + " max y " + maxY + " bot y " + botY + " height " + menu.getHeight() + " target height " + height + " max height " + maxHeight);
    }

    fun clear() {
        adapter.clear()
    }

    fun destroy() {
        launch(UI) {
            wrapper.removeAllViews()
            (wrapper.parent as ViewGroup).removeView(wrapper)
        }
    }

    private fun calculateRevealCenter(): IntArray {
        val fabPos = IntArray(2)
        fab!!.getLocationOnScreen(fabPos)
        val menuPos = IntArray(2)
        listView.getLocationOnScreen(menuPos)

        val result = IntArray(2)
        result[0] = fabPos[0] - menuPos[0] + fab.width / 2
        result[1] = fabPos[1] - menuPos[1] + fab.height / 2
        return result
    }

    fun hide() {
        if (!isVisible)
            return
        isVisible = false
        wrapper.setOnClickListener(null)
        val pos = calculateRevealCenter()
        fab!!.animate().alpha(255f).setDuration(100).start()
        Animate.revealHide(listView, pos[0], pos[1], 0, { wrapper.visibility = View.INVISIBLE })
    }

    fun hideAndDestroy() {
        if (!isVisible)
            destroy()
        else {
            isVisible = false
            wrapper.setOnClickListener(null)
            val pos = calculateRevealCenter()
            fab!!.animate().alpha(255f).setDuration(100).start()
            Animate.revealHide(listView, pos[0], pos[1], 0, { destroy() })
        }
    }

    @Throws(NullPointerException::class)
    fun show(activity: Activity) {
        if (fab == null)
            throw NullPointerException("Fab is null")
        if (isVisible)
            return

        adapter.filter()

        isVisible = true
        boundsCalculated = false

        recalculateBounds(activity)
        wrapper.visibility = View.VISIBLE
        listView.visibility = View.INVISIBLE
        val fabPos = IntArray(2)
        fab.getLocationOnScreen(fabPos)

        val pos = calculateRevealCenter()

        fab.animate().alpha(0f).setDuration(100).start()
        Animate.revealShow(listView, pos[0], pos[1], 0)
        wrapper.setOnClickListener(closeClickListener)
    }
}