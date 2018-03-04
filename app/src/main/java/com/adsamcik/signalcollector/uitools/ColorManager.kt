package com.adsamcik.signalcollector.uitools

import android.support.annotation.ColorInt
import android.support.annotation.IdRes
import android.support.v7.widget.CardView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import com.adsamcik.signalcollector.uitools.ColorSupervisor.currentBaseColor
import com.adsamcik.signalcollector.uitools.ColorSupervisor.currentForegroundColor
import com.adsamcik.signalcollector.uitools.ColorSupervisor.layerColor
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.locks.ReentrantLock


internal class ColorManager {
    private val watchedElements = ArrayList<ColorView>()

    private val arrayLock = ReentrantLock()

    fun watchElement(view: ColorView) {
        synchronized(arrayLock) {
            watchedElements.add(view)
        }
        update(view, currentBaseColor, currentForegroundColor)
    }

    fun watchElement(view: View) = watchElement(ColorView(view, 0))

    fun notififyChangeOn(view: View) {
        var find: ColorView? = null
        synchronized(arrayLock) {
            find = watchedElements.find { it.view == view }
        }

        if (find != null)
            update(find!!, currentBaseColor, currentForegroundColor)
    }

    fun watchRecycler(view: ColorView) {
        if (!view.recursive)
            throw RuntimeException("Recycler view cannot be non recursive")

        watchElement(view)
        (view.view as AdapterView<*>).setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewRemoved(parent: View, child: View) {
            }

            override fun onChildViewAdded(parent: View, child: View) {
                update(view, currentBaseColor, currentForegroundColor)
            }

        })

    }

    fun stopWatchingElement(predicate: (ColorView) -> Boolean) {
        synchronized(arrayLock) {
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
        view.setOnHierarchyChangeListener(null)
        stopWatchingElement(view)
    }

    fun stopWatchingRecycler(@IdRes id: Int) {
        synchronized(arrayLock) {
            val index = watchedElements.indexOfFirst { it.view.id == id }
            if (index >= 0) {
                (watchedElements[index].view as ViewGroup).setOnHierarchyChangeListener(null)
                watchedElements.removeAt(index)
            }
        }
    }

    fun stopWatchingAll() {
        synchronized(arrayLock) {
            watchedElements.clear()
        }
    }

    internal fun update(@ColorInt baseColor: Int, @ColorInt fgColor: Int) {
        launch(UI) {
            synchronized(arrayLock) {
                watchedElements.forEach {
                    if (it.backgroundIsForeground)
                        update(it, fgColor, baseColor)
                    else
                        update(it, baseColor, fgColor)
                }
            }
        }
    }

    private fun update(view: ColorView, @ColorInt color: Int, @ColorInt fgColor: Int) {
        if (!view.ignoreRoot) {
            if (view.rootIsBackground)
                setBackgroundColor(view.view, color, view.layer)
            else
                updateBackgroundDrawable(view.view, layerColor(color, view.layer))

            updateStyleForeground(view.view, fgColor)
        }

        if (view.recursive && view.view is ViewGroup) {
            val layer = if (!view.ignoreRoot) view.layer + 1 else view.layer
            for (i in 0 until view.view.childCount)
                updateStyleRecursive(view.view.getChildAt(i), fgColor, color, layer)
        }
    }

    private fun setBackgroundColor(view: View, @ColorInt color: Int, layer: Int) {
        view.setBackgroundColor(layerColor(color, layer))
    }

    private fun updateStyleRecursive(view: View, @ColorInt fgColor: Int, @ColorInt color: Int, layer: Int) {
        var newLayer = layer
        if (updateBackgroundDrawable(view, layerColor(color, layer)))
            newLayer++
        if (view is ViewGroup) {
            for (i in 0 until view.childCount)
                updateStyleRecursive(view.getChildAt(i), fgColor, color, newLayer)
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
            return true
        }
        return false
    }
}