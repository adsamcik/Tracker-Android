package com.adsamcik.signalcollector.uitools

import android.animation.ValueAnimator
import android.content.Context
import android.support.annotation.ColorInt
import android.support.v4.content.ContextCompat
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.adsamcik.signalcollector.R

class ColorManager(private val bgElement: View, val context: Context) {
    private val colorList = ArrayList<@ColorInt Int>()
    private val watchedElements = ArrayList<View>()

    private var currentLuminance = 0.0

    @ColorInt
    private var currentForeground = 0

    @ColorInt
    private var currentBackground = 0

    private var animation: ValueAnimator? = null

    private fun initializeAnimation() {
        animation?.end()

        val anim = ValueAnimator.ofArgb(*colorList.toIntArray())
        anim.duration = 30000
        anim.repeatMode = ValueAnimator.REVERSE
        anim.repeatCount = ValueAnimator.INFINITE
        anim.addUpdateListener { updateBackground(it.animatedValue as Int) }
        anim.start()

        animation = anim
    }


    fun addColors(@ColorInt vararg varargs: Int) {
        colorList.ensureCapacity(colorList.size + varargs.size)
        varargs.forEach { colorList.add(it) }
        if (colorList.size == 1) {
            updateBackground(colorList[0])
            updateStyle(relLuminance(colorList[0]))
        } else
            initializeAnimation()
    }

    fun watchElement(element: View) {
        watchedElements.add(element)
        updateStyle(element, currentForeground, currentBackground)
    }

    private fun red(@ColorInt color: Int) = (color shr 16) and 255
    private fun relRed(@ColorInt color: Int) = red(color) / 255.0

    private fun green(@ColorInt color: Int) = (color shr 8) and 255
    private fun relGreen(@ColorInt color: Int) = green(color) / 255.0

    private fun blue(@ColorInt color: Int) = color and 255
    private fun relBlue(@ColorInt color: Int) = blue(color) / 255.0

    private fun luminance(@ColorInt color: Int) = 0.299 * relRed(color) + 0.587 * relGreen(color) + 0.114 * relBlue(color)
    private fun relLuminance(@ColorInt color: Int) = Math.signum(luminance(color) - 0.5)

    private fun updateBackground(@ColorInt color: Int) {
        bgElement.setBackgroundColor(color)

        val lum = relLuminance(color)
        if (currentLuminance != lum) {
            currentLuminance = lum
            updateStyle(lum)
        }
    }

    private fun updateStyle(lumType: Double) {
        if (lumType > 0) {
            currentForeground = ContextCompat.getColor(context, R.color.text_primary_dark)
            currentBackground = ContextCompat.getColor(context, R.color.background_new_dark)
        } else {
            currentForeground = ContextCompat.getColor(context, R.color.text_primary)
            currentBackground = ContextCompat.getColor(context, R.color.background_new_light)
        }

        updateStyle(currentForeground, currentBackground)
    }

    private fun updateStyle(@ColorInt fgColor: Int, @ColorInt bgColor: Int) {
        watchedElements.forEach {
            updateStyle(it, fgColor, bgColor)
        }
    }

    private fun updateStyle(view: View, @ColorInt fgColor: Int, @ColorInt bgColor: Int) {
        if (view.background != null)
            view.setBackgroundColor(bgColor)
        when (view) {
            is TextView -> view.setTextColor(fgColor)
            is ImageView -> view.setColorFilter(fgColor)
            is ViewGroup -> for (i in 0 until view.childCount)
                updateStyle(view.getChildAt(i), fgColor, bgColor)
        }
    }
}