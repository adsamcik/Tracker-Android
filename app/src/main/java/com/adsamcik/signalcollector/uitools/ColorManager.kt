package com.adsamcik.signalcollector.uitools

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.support.annotation.ColorInt
import android.support.v4.content.ContextCompat
import android.support.v7.widget.CardView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.adsamcik.signalcollector.R


class ColorManager(val context: Context) {
    private val colorList = ArrayList<@ColorInt Int>()
    private val watchedElements = ArrayList<ColorView>()

    private var currentLuminance = 0.0

    @ColorInt
    private var currentForeground = 0

    @ColorInt
    private var currentBackground = 0

    @ColorInt
    private var currentColor = 0

    private var animation: ValueAnimator? = null

    private fun initializeAnimation() {
        animation?.end()

        val anim = ValueAnimator.ofArgb(*colorList.toIntArray())
        anim.duration = 30000
        anim.repeatMode = ValueAnimator.REVERSE
        anim.repeatCount = ValueAnimator.INFINITE
        anim.addUpdateListener { update(it.animatedValue as Int) }
        anim.start()

        animation = anim
    }


    fun addColors(@ColorInt vararg varargs: Int) {
        colorList.ensureCapacity(colorList.size + varargs.size)
        varargs.forEach { colorList.add(it) }
        if (colorList.size == 1) {
            update(colorList[0])
        } else
            initializeAnimation()
    }

    fun watchElement(view: ColorView) {
        watchedElements.add(view)
        update(view, currentColor, currentBackground, currentForeground)
    }

    fun watchElement(view: View) = watchElement(ColorView(view))

    fun stopWatchingElement(view: View) {
        watchedElements.removeAt(watchedElements.indexOfFirst { it.view == view })
    }

    private fun red(@ColorInt color: Int) = (color shr 16) and 255
    private fun relRed(@ColorInt color: Int) = red(color) / 255.0

    private fun green(@ColorInt color: Int) = (color shr 8) and 255
    private fun relGreen(@ColorInt color: Int) = green(color) / 255.0

    private fun blue(@ColorInt color: Int) = color and 255
    private fun relBlue(@ColorInt color: Int) = blue(color) / 255.0

    private fun luminance(@ColorInt color: Int) = 0.299 * relRed(color) + 0.587 * relGreen(color) + 0.114 * relBlue(color)
    private fun relLuminance(@ColorInt color: Int) = Math.signum(luminance(color) - 0.5)

    private fun update(@ColorInt color: Int) {
        currentColor = color
        val lum = relLuminance(color)
        if (currentLuminance != lum) {
            currentLuminance = lum
            val bgColor: Int
            val fgColor: Int
            if (lum > 0) {
                fgColor = ContextCompat.getColor(context, R.color.text_primary_dark)
                bgColor = ContextCompat.getColor(context, R.color.background_new_dark)
            } else {
                fgColor = ContextCompat.getColor(context, R.color.text_primary)
                bgColor = ContextCompat.getColor(context, R.color.background_new_light)
            }

            currentBackground = bgColor
            currentForeground = fgColor

            watchedElements.forEach { update(it, color, bgColor, fgColor) }
        } else {
            watchedElements.forEach { if (it.rootIsBackground) updateBackground(it.view, color) }
        }
    }

    private fun update(view: ColorView, @ColorInt color: Int, @ColorInt bgColor: Int, @ColorInt fgColor: Int) {
        if (!view.ignoreRoot) {
            if (view.rootIsBackground)
                updateBackground(view.view, color)
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

    private fun updateBackground(view: View, @ColorInt color: Int) {
        view.setBackgroundColor(color)
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