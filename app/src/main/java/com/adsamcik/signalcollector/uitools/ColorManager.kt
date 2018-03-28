package com.adsamcik.signalcollector.uitools

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.support.annotation.ColorInt
import android.support.annotation.IdRes
import android.support.v7.widget.CardView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import com.adsamcik.signalcollector.interfaces.IViewChange
import com.adsamcik.signalcollector.uitools.ColorSupervisor.backgroundColorFor
import com.adsamcik.signalcollector.uitools.ColorSupervisor.foregroundColorFor
import com.adsamcik.signalcollector.uitools.ColorSupervisor.layerColor
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch


internal class ColorManager {
    private val watchedElements = ArrayList<ColorView>()

    fun watchElement(view: ColorView) {
        synchronized(watchedElements) {
            watchedElements.add(view)
        }
        updateInternal(view)
    }

    fun watchElement(view: View) = watchElement(ColorView(view, 0))

    fun notififyChangeOn(view: View) {
        var find: ColorView? = null
        synchronized(watchedElements) {
            find = watchedElements.find { it.view == view }
        }

        if (find != null)
            updateInternal(find!!)
    }

    fun watchRecycler(view: ColorView) {
        if (!view.recursive)
            throw RuntimeException("Recycler view cannot be non recursive")

        view.view as AdapterView<*>
        val adapter = view.view.adapter
        if (adapter is IViewChange) {
            adapter.onViewChangedListener = {
                updateStyleRecursive(it, backgroundColorFor(view), foregroundColorFor(view), view.layer + 1)
            }
        } else {
            view.view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewRemoved(parent: View, child: View) {
                }

                override fun onChildViewAdded(parent: View, child: View) {
                    updateStyleRecursive(child, backgroundColorFor(view), foregroundColorFor(view), view.layer + 1)
                }

            })
        }
        watchElement(view)
    }

    fun stopWatchingElement(predicate: (ColorView) -> Boolean) {
        synchronized(watchedElements) {
            val index = watchedElements.indexOfFirst(predicate)
            if (index >= 0)
                watchedElements.removeAt(index)
        }
    }

    fun stopWatchingElement(view: View) {
        stopWatchingElement { it.view == view }
    }

    fun stopWatchingElement(@IdRes id: Int) {
        stopWatchingElement { it.view.id == id }
    }

    fun stopWatchingRecycler(view: AdapterView<*>) {
        val adapter = view.adapter
        if (adapter is IViewChange)
            adapter.onViewChangedListener = null
        else
            view.setOnHierarchyChangeListener(null)
        stopWatchingElement(view)
    }

    fun stopWatchingRecycler(@IdRes id: Int) {
        synchronized(watchedElements) {
            val index = watchedElements.indexOfFirst { it.view.id == id }
            if (index >= 0) {
                (watchedElements[index].view as ViewGroup).setOnHierarchyChangeListener(null)
                watchedElements.removeAt(index)
            }
        }
    }

    fun stopWatchingAll() {
        synchronized(watchedElements) {
            watchedElements.clear()
        }
    }

    internal fun update() {
        launch(UI) {
            synchronized(watchedElements) {
                watchedElements.forEach {
                    updateInternal(it)
                }
            }
        }
    }

    private fun updateInternal(colorView: ColorView) {
        val backgroundColor = backgroundColorFor(colorView)
        val foregroundColor = foregroundColorFor(colorView)

        if (!colorView.ignoreRoot) {
            val layerColor = layerColor(backgroundColor, colorView.layer)
            if (colorView.rootIsBackground)
                colorView.view.setBackgroundColor(layerColor)
            else
                updateBackgroundDrawable(colorView.view, layerColor)

            updateStyleForeground(colorView.view, foregroundColor)
        }

        if (colorView.recursive && colorView.view is ViewGroup) {
            val layer = if (!colorView.ignoreRoot) colorView.layer + 1 else colorView.layer
            for (i in 0 until colorView.view.childCount)
                updateStyleRecursive(colorView.view.getChildAt(i), backgroundColor, foregroundColor, layer)
        }
    }

    private fun updateStyleRecursive(view: View, @ColorInt color: Int, @ColorInt fgColor: Int, layer: Int) {
        var newLayer = layer
        if (updateBackgroundDrawable(view, layerColor(color, layer)))
            newLayer++
        if (view is ViewGroup) {
            for (i in 0 until view.childCount)
                updateStyleRecursive(view.getChildAt(i), color, fgColor, newLayer)
        } else {
            updateStyleForeground(view, fgColor)
        }
    }

    private fun updateStyleForeground(view: View, @ColorInt fgColor: Int) {
        when (view) {
            is TextView -> {
                view.setTextColor(fgColor)
                view.setHintTextColor(brightenColor(fgColor, 1))
                view.compoundDrawables.forEach {
                    it?.setTint(fgColor)
                }
            }
            is ImageView -> view.setColorFilter(fgColor)
        }
    }

    private fun updateBackgroundDrawable(view: View, @ColorInt bgColor: Int): Boolean {
        val background = view.background
        if (view is CardView) {
            view.setCardBackgroundColor(bgColor)
            return true
        } else if (background != null && background.isVisible) {
            if (background.alpha < 255)
                return false

            background.setTint(bgColor)
            view.background.colorFilter = PorterDuffColorFilter(bgColor, PorterDuff.Mode.SRC_IN)
            return true
        }
        return false
    }
}