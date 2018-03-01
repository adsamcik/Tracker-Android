package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.support.annotation.ColorInt
import android.support.annotation.IdRes
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.ColorUtils
import android.support.v7.widget.CardView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import com.adsamcik.signalcollector.R
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.locks.ReentrantLock


internal class ColorManager(private val mContext: Context) {
    private val watchedElements = ArrayList<ColorView>()

    private var currentLuminance = 0.0

    @ColorInt
    private var currentForeground = 0

    @ColorInt
    private var currentColor = 0

    private val arrayLock = ReentrantLock()

    fun watchElement(view: ColorView) {
        synchronized(arrayLock) {
            watchedElements.add(view)
            update(view, currentColor, currentForeground)
        }
    }

    fun watchElement(view: View) = watchElement(ColorView(view, 0))

    fun watchRecycler(view: ColorView) {
        if (!view.recursive)
            throw RuntimeException("Recycler view cannot be non recursive")

        watchElement(view)
        (view.view as AdapterView<*>).setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewRemoved(parent: View, child: View) {
            }

            override fun onChildViewAdded(parent: View, child: View) {
                update(view, currentColor, currentForeground)
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

    private fun relRed(@ColorInt color: Int) = Color.red(color) / 255.0
    private fun relGreen(@ColorInt color: Int) = Color.green(color) / 255.0
    private fun relBlue(@ColorInt color: Int) = Color.blue(color) / 255.0

    private fun perceivedLuminance(@ColorInt color: Int) = 0.299 * relRed(color) + 0.587 * relGreen(color) + 0.114 * relBlue(color)

    private fun perceivedRelLuminance(@ColorInt color: Int) = Math.signum(perceivedLuminance(color) - 0.5)
    private fun relLuminance(@ColorInt color: Int) = Math.signum(ColorUtils.calculateLuminance(color) - 0.5)

    internal fun update(@ColorInt color: Int) {
        currentColor = color
        val lum = perceivedRelLuminance(getLayerColor(color, 1))
        currentLuminance = lum
        val fgColor: Int = if (lum > 0) {
            ContextCompat.getColor(mContext, R.color.text_primary_dark)
        } else {
            ContextCompat.getColor(mContext, R.color.text_primary)
        }

        currentForeground = fgColor

        launch(UI) {
            synchronized(arrayLock) {
                watchedElements.forEach {
                    if (it.backgroundIsForeground)
                        update(it, fgColor, color)
                    else
                        update(it, color, fgColor)
                }
            }
        }
    }

    private fun update(view: ColorView, @ColorInt color: Int, @ColorInt fgColor: Int) {
        if (!view.ignoreRoot) {
            if (view.rootIsBackground)
                setBackgroundColor(view.view, color, view.layer)
            else
                updateBackgroundDrawable(view.view, getLayerColor(color, view.layer))

            updateStyleForeground(view.view, fgColor)
        }

        if (view.recursive && view.view is ViewGroup) {
            for (i in 0 until view.view.childCount)
                updateStyleRecursive(view.view.getChildAt(i), fgColor, color, view.layer + 1)
        }
    }

    private fun brightenComponent(component: Int, value: Int) = Math.min(component + value, 255)

    private fun brightenColor(@ColorInt color: Int, value: Int): Int {
        val r = brightenComponent(Color.red(color), value)
        val g = brightenComponent(Color.green(color), value)
        val b = brightenComponent(Color.blue(color), value)
        return Color.rgb(r, g, b)
    }

    private fun getLayerColor(@ColorInt color: Int, layer: Int): Int {
        return if (layer == 0)
            color
        else
            brightenColor(color, 17 * layer)
    }

    private fun setBackgroundColor(view: View, @ColorInt color: Int, layer: Int) {
        view.setBackgroundColor(getLayerColor(color, layer))
    }

    private fun updateStyleRecursive(view: View, @ColorInt fgColor: Int, @ColorInt color: Int, layer: Int) {
        val updatedBg = updateBackgroundDrawable(view, getLayerColor(color, layer))
        if (view is ViewGroup) {
            for (i in 0 until view.childCount)
                updateStyleRecursive(view.getChildAt(i), fgColor, color, if (updatedBg) layer + 1 else layer)
        } else {
            updateStyleForeground(view, fgColor)
        }
    }

    private fun updateStyleForeground(view: View, @ColorInt fgColor: Int) {
        when (view) {
            is TextView -> {
                view.setTextColor(fgColor)
                view.setHintTextColor(fgColor)
            }
            is ImageView -> view.setColorFilter(fgColor)
        }
    }

    private fun updateBackgroundDrawable(view: View, @ColorInt bgColor: Int): Boolean {
        val background = view.background
        if (view is CardView) {
            view.setCardBackgroundColor(bgColor)
            return true
        } else if (background != null) {
            background.setTint(bgColor)
            return true
        }
        return false
    }
}