package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.graphics.Color
import android.support.annotation.ColorInt
import android.support.v4.graphics.ColorUtils
import android.util.Log
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.utility.*
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

    private var sunriseTime = 7 * Constants.HOUR_IN_MILLISECONDS
    private var nightTime = 1 * Constants.HOUR_IN_MILLISECONDS
    private var sunsetTime = 21 * Constants.HOUR_IN_MILLISECONDS
    private var dayTime = 13 * Constants.HOUR_IN_MILLISECONDS

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
        val timeOfDay = calculateTimeOfDay()

        Log.d("ColorSupervisor", "Now is $currentIndex with length of ${timeOfDay.first} and progress ${timeOfDay.second}")

        timerTask = ColorUpdateTask(period, timeOfDay.first.toLong(), timeOfDay.second)
        timer.scheduleAtFixedRate(timerTask, 0L, period)
    }

    private fun calculateTimeOfDay(): Pair<Int, Float> {
        val time = Assist.time
        val changeLength: Int
        val progress: Float

        if (time > sunsetTime) {
            if (nightTime > sunsetTime) {
                if (time < nightTime) {
                    //Between sunset and night when night is before midnight and time is before midnight
                    changeLength = nightTime - sunsetTime
                    progress = (time - sunsetTime) / changeLength.toFloat()
                    currentIndex = 2
                } else {
                    //Between night and sunrise when night is before midnight and time is before midnight
                    changeLength = 24 * Constants.HOUR_IN_MILLISECONDS - nightTime + sunriseTime
                    progress = (time - nightTime) / changeLength.toFloat()
                    currentIndex = 3
                }
            } else {
                //Between sunset and night when night is after midnight and time is before midnight
                changeLength = 24 * Constants.HOUR_IN_MILLISECONDS - sunsetTime + nightTime
                progress = (time - sunsetTime) / changeLength.toFloat()
                currentIndex = 2
            }
        } else if (time > dayTime) {
            //Between day and sunset
            changeLength = sunsetTime - dayTime
            progress = (time - dayTime) / changeLength.toFloat()
            currentIndex = 1
        } else if (time > sunriseTime) {
            //Between sunrise and day
            changeLength = dayTime - sunriseTime
            progress = (time - sunriseTime) / changeLength.toFloat()
            currentIndex = 0
        } else {
            if (nightTime > sunsetTime) {
                //Between night and sunrise when night is before midnight and time is after midnight
                val beforeMidnight = 24 * Constants.HOUR_IN_MILLISECONDS - nightTime
                changeLength = beforeMidnight + sunriseTime
                progress = (time + beforeMidnight) / changeLength.toFloat()
                currentIndex = 3
            } else {
                if (time < nightTime) {
                    //Between sunset and night when night is after midnight and time is after midnight
                    val beforeMidnight = 24 * Constants.HOUR_IN_MILLISECONDS - sunsetTime
                    changeLength = beforeMidnight + nightTime
                    progress = (time + beforeMidnight) / changeLength.toFloat()
                    currentIndex = 2
                } else {
                    //Between night and sunrise when night is after midnight and time is after midnight
                    changeLength = sunriseTime - nightTime
                    progress = (time - nightTime) / changeLength.toFloat()
                    currentIndex = 3
                }
            }
        }

        return Pair(changeLength, progress)
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
        val preferences = Preferences.getPref(context)
        val mode = preferences.getString(context, R.string.settings_style_mode_key, R.string.settings_style_mode_default).toInt()

        stopUpdate()
        colorList.clear()

        val day = preferences.getColor(context, R.string.settings_color_day_key, R.color.settings_color_day_default)

        if (mode == 0) {
            addColors(day)
        } else {
            val night = preferences.getColor(context, R.string.settings_color_night_key, R.color.settings_color_night_default)
            if (mode == 1)
                addColors(day, night)
            else {
                val morning = preferences.getColor(context, R.string.settings_color_morning_key, R.color.settings_color_morning_default)
                val evening = preferences.getColor(context, R.string.settings_color_evening_key, R.color.settings_color_evening_default)
                addColors(morning, day, evening, night)
            }
        }
    }

    fun setSunsetSunrise(sunrise: Calendar, sunset: Calendar) {
        sunriseTime = sunrise.get(Calendar.HOUR_OF_DAY) * Constants.HOUR_IN_MILLISECONDS + sunrise.get(Calendar.MINUTE) * Constants.MINUTE_IN_MILLISECONDS
        sunsetTime = sunset.get(Calendar.HOUR_OF_DAY) * Constants.HOUR_IN_MILLISECONDS + sunset.get(Calendar.MINUTE) * Constants.MINUTE_IN_MILLISECONDS
        stopUpdate()
        startUpdate()
    }

    fun onSunrise() {

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

internal class ColorUpdateTask(private val deltaTime: Long, private val periodLength: Long, private var currentTime: Float = 0f) : TimerTask() {
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