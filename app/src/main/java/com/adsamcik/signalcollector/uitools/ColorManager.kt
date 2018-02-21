package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.support.annotation.ColorInt
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.ColorUtils
import android.support.v7.widget.CardView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.adsamcik.signalcollector.R
import kotlin.math.roundToInt


internal class ColorManager(private val mContext: Context) {
    private val watchedElements = ArrayList<ColorView>()

    private var currentLuminance = 0.0

    @ColorInt
    private var currentForeground = 0

    @ColorInt
    private var currentBackground = 0

    @ColorInt
    private var currentColor = 0

    fun watchElement(view: ColorView) {
        watchedElements.add(view)
        update(view, currentColor, currentBackground, currentForeground)
    }

    fun watchElement(view: View) = watchElement(ColorView(view, 0))

    fun stopWatchingElement(view: View) {
        watchedElements.removeAt(watchedElements.indexOfFirst { it.view == view })
    }

    private fun relRed(@ColorInt color: Int) = Color.red(color) / 255.0
    private fun relGreen(@ColorInt color: Int) = Color.green(color) / 255.0
    private fun relBlue(@ColorInt color: Int) = Color.blue(color) / 255.0

    private fun perceivedLuminance(@ColorInt color: Int) = 0.299 * relRed(color) + 0.587 * relGreen(color) + 0.114 * relBlue(color)

    private fun perceivedRelLuminance(@ColorInt color: Int) = Math.signum(perceivedLuminance(color) - 0.5)
    private fun relLuminance(@ColorInt color: Int) = Math.signum(ColorUtils.calculateLuminance(color) - 0.5)

    internal fun update(@ColorInt color: Int) {
        currentColor = color
        val lum = perceivedRelLuminance(color)
        if (currentLuminance != lum) {
            currentLuminance = lum
            val bgColor: Int
            val fgColor: Int
            if (lum > 0) {
                fgColor = ContextCompat.getColor(mContext, R.color.text_primary_dark)
                bgColor = ContextCompat.getColor(mContext, R.color.background_new_dark)
            } else {
                fgColor = ContextCompat.getColor(mContext, R.color.text_primary)
                bgColor = ContextCompat.getColor(mContext, R.color.background_new_light)
            }

            currentBackground = bgColor
            currentForeground = fgColor

            watchedElements.forEach { update(it, color, bgColor, fgColor) }
        } else {
            watchedElements.forEach { if (it.rootIsBackground && !it.ignoreRoot) updateBackground(it.view, color, it.layer) }
        }
    }

    private fun update(view: ColorView, @ColorInt color: Int, @ColorInt bgColor: Int, @ColorInt fgColor: Int) {
        if (!view.ignoreRoot) {
            if (view.rootIsBackground)
                updateBackground(view.view, color, view.layer)
            else
                updateStyleBackground(view.view, bgColor)
        }

        if (view.recursive && view.view is ViewGroup) {
            for (i in 0 until view.view.childCount)
                updateStyleRecursive(view.view.getChildAt(i), fgColor, bgColor)
        } else if (!view.ignoreRoot) {
            updateStyleForeground(view.view, fgColor)
        }
    }

    private fun brightenComponent(component: Int, value: Int) = Math.min(component + value, 255)

    private fun brightenColor(@ColorInt color: Int, value: Int): Int {
        val r = brightenComponent(Color.red(color), value)
        val g = brightenComponent(Color.green(color), value)
        val b = brightenComponent(Color.blue(color), value)
        return Color.rgb(r, g, b)
    }

    private fun updateBackground(view: View, @ColorInt color: Int, layer: Int) {
        if (layer == 0)
            view.setBackgroundColor(color)
        else {
            val layerValue = (25.5 * layer - 1).roundToInt()
            view.setBackgroundColor(brightenColor(color, layerValue))
        }
    }

    private fun updateStyleRecursive(view: View, @ColorInt fgColor: Int, @ColorInt bgColor: Int) {
        updateStyleBackground(view, bgColor)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount)
                updateStyleRecursive(view.getChildAt(i), fgColor, bgColor)
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

    private fun updateStyleBackground(view: View, @ColorInt bgColor: Int) {
        val background = view.background
        if (view is CardView) {
            view.setCardBackgroundColor(bgColor)
            view.cardElevation = 0f
        } else if (background != null) {
            when (background) {
                is ColorDrawable -> view.setBackgroundColor(bgColor)
                is GradientDrawable -> view.setBackgroundColor(bgColor)
            }
        }
    }
}