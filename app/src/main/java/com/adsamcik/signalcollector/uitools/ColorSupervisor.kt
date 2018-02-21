package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.support.annotation.ColorInt
import android.support.v4.graphics.ColorUtils
import com.adsamcik.signalcollector.utility.Constants
import java.util.*
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList

internal object ColorSupervisor {
    private val colorList = ArrayList<@ColorInt Int>()
    private val timer = Timer("ColorUpdate", true)
    private var timerActive = false

    private val colorManagers = ArrayList<ColorManager>()
    private const val PERIOD = Constants.MINUTE_IN_MILLISECONDS.toLong()

    private var currentIndex = 0

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
                if (!timerActive) {
                    timerActive = true
                    timer.scheduleAtFixedRate(ColorUpdateTask(PERIOD, (6 * Constants.HOUR_IN_MILLISECONDS).toLong()), 0L, PERIOD)
                }
            }
        }
    }

    private fun stopUpdate() {
        synchronized(updateLock) {
            if (timerActive) {
                timerActive = false
                timer.cancel()
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
        if (newPeriod)
            currentIndex = (currentIndex + 1).rem(colorList.size)

        val targetIndex: Int =
                if (currentIndex == colorList.size - 1)
                    0
                else
                    currentIndex + 1

        update(ColorUtils.blendARGB(colorList[currentIndex], colorList[targetIndex], delta))
    }

    fun update(@ColorInt color: Int) {
        colorManagers.forEach {
            it.update(color)
        }
    }

}

internal class ColorUpdateTask(private val period: Long, private val changePeriod: Long) : TimerTask() {
    private var currentTime = 0f

    override fun run() {
        val newTime = currentTime + period
        currentTime = newTime.rem(changePeriod)
        val delta = currentTime / changePeriod

        if (newTime != currentTime)
            ColorSupervisor.deltaUpdate(delta, true)
        else
            ColorSupervisor.deltaUpdate(delta, false)
    }
}