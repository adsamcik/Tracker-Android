package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.graphics.Color
import android.support.annotation.ColorInt
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.ColorUtils
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.getColor
import com.adsamcik.signalcollector.utility.getInt
import java.util.*
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList

internal object ColorSupervisor {
    private val colorList = ArrayList<@ColorInt Int>()
    private val timer = Timer("ColorUpdate", true)
    private var timerTask: ColorUpdateTask? = null
    private var timerActive = false

    private val colorManagers = ArrayList<ColorManager>()

    private const val CHANGE_LENGTH = (6 * Constants.SECOND_IN_MILLISECONDS).toLong()

    private var currentIndex = 0

    private val nextIndex get () = (currentIndex + 1).rem(colorList.size)

    private var updateLock: Lock = ReentrantLock()

    fun createColorManager(context: Context): ColorManager {
        val colorManager = ColorManager(context)
        colorManagers.add(colorManager)
        ensureUpdate()
        return colorManager
    }

    private fun ensureUpdate() {
        if (colorManagers.size == 1 && colorList.size > 1) {
            synchronized(updateLock) {
                if (!timerActive)
                    startUpdate()
            }
        }
    }

    private fun updateUpdate() {
        if (colorManagers.size == 1 && colorList.size > 1) {
            synchronized(updateLock) {
                stopUpdate()
                startUpdate()
            }
        }
    }

    private fun startUpdate() {
        timerActive = true
        val period = calculateUpdatePeriod()
        timerTask = ColorUpdateTask(period, CHANGE_LENGTH)
        timer.scheduleAtFixedRate(timerTask, 0L, period)
    }

    private fun calculateUpdatePeriod() = CHANGE_LENGTH / calculateUpdateCount()

    private fun calculateUpdateCount(): Int {
        if (colorList.size < 2)
            throw RuntimeException("Update rate cannot be calculated for less than 2 colors")

        val currentColor = colorList[currentIndex]
        val targetColor = colorList[nextIndex]
        val rDiff = Math.abs(Color.red(currentColor) - Color.red(targetColor))
        val gDiff = Math.abs(Color.green(currentColor) - Color.green(targetColor))
        val bDiff = Math.abs(Color.blue(currentColor) - Color.blue(targetColor))
        return rDiff + gDiff + bDiff
    }

    private fun stopUpdate() {
        synchronized(updateLock) {
            if (timerActive) {
                timerActive = false
                timerTask!!.cancel()
                timer.purge()
            }
        }
    }

    fun recycleColorManager(colorManager: ColorManager) {
        colorManagers.remove(colorManager)
        if (colorManagers.isEmpty()) {
            colorManagers.trimToSize()
            stopUpdate()
        }
    }

    fun initializeFromPreferences(context: Context) {
        val resources = context.resources
        val preferences = Preferences.getPref(context)
        val mode = preferences.getInt(context, R.string.settings_style_mode_key, R.string.settings_style_mode_default)

        var day = ContextCompat.getColor(context, R.color.settings_color_day_default)

        if (mode == 0) {
            addColors(preferences.getInt(resources.getString(R.string.settings_color_day_key), day))
        }
    }

    fun addColors(@ColorInt vararg varargs: Int) {
        if (varargs.isEmpty())
            throw RuntimeException("You can't just add no colors.")

        colorList.ensureCapacity(colorList.size + varargs.size)
        varargs.forEach { colorList.add(it) }

        if (colorList.size == 1) {
            update(colorList[0])
        } else
            ensureUpdate()
    }

    fun deltaUpdate(delta: Float, newPeriod: Boolean) {
        if (newPeriod) {
            currentIndex = nextIndex
            updateUpdate()
        }

        update(ColorUtils.blendARGB(colorList[currentIndex], colorList[nextIndex], delta))
    }

    fun update(@ColorInt color: Int) {
        colorManagers.forEach {
            it.update(color)
        }
    }

}

internal class ColorUpdateTask(private val deltaTime: Long, private val periodLength: Long) : TimerTask() {
    private var currentTime = 0f

    override fun run() {
        val newTime = currentTime + deltaTime
        currentTime = newTime.rem(periodLength)
        val delta = currentTime / periodLength

        if (newTime != currentTime)
            ColorSupervisor.deltaUpdate(delta, true)
        else
            ColorSupervisor.deltaUpdate(delta, false)
    }
}