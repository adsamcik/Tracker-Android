package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.graphics.Color
import android.support.annotation.ColorInt
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.ColorUtils
import android.util.Log
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.extensions.getColor
import com.adsamcik.signalcollector.extensions.getString
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Preferences
import java.util.*
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList


internal object ColorSupervisor {
    private val colorList = ArrayList<@ColorInt Int>()
    private var timer: Timer? = null
    private var timerTask: ColorUpdateTask? = null
    private var timerActive = false

    private val colorManagers = ArrayList<ColorManager>()

    private var currentIndex = 0

    private val nextIndex get () = (currentIndex + 1).rem(colorList.size)

    private var updateLock: Lock = ReentrantLock()

    private var sunriseTime = 7 * Constants.HOUR_IN_MILLISECONDS
    private var nightTime = 1 * Constants.HOUR_IN_MILLISECONDS
    private var sunsetTime = 21 * Constants.HOUR_IN_MILLISECONDS
    private var dayTime = 13 * Constants.HOUR_IN_MILLISECONDS

    private var darkTextColor: Int = 0
    private var lightTextColor: Int = 0

    var currentLuminance = 0
        private set

    @ColorInt
    var currentForegroundColor = 0
        private set

    @ColorInt
    var currentBaseColor = 0
        private set

    fun createColorManager(context: Context): ColorManager {
        if (darkTextColor == 0) {
            darkTextColor = ContextCompat.getColor(context, android.R.color.primary_text_light)
            lightTextColor = ContextCompat.getColor(context, android.R.color.primary_text_dark)
        }

        val colorManager = ColorManager()
        synchronized(colorManagers) {
            colorManagers.add(colorManager)
            ensureUpdate()
        }

        return colorManager
    }

    fun recycleColorManager(colorManager: ColorManager) {
        synchronized(colorManagers) {
            colorManagers.remove(colorManager)
            if (colorManagers.isEmpty()) {
                colorManagers.trimToSize()
                stopUpdate()
            }
        }
    }

    fun ensureUpdate() {
        if (colorList.size > 1) {
            synchronized(updateLock) {
                if (!timerActive)
                    startUpdate()
            }
        } else if (colorList.size == 1)
            update(colorList[0])
    }

    fun layerColor(@ColorInt color: Int, layer: Int): Int {
        return if (layer == 0)
            color
        else
            brightenColor(color, 17 * layer)
    }

    fun addColors(@ColorInt vararg varargs: Int) {
        if (varargs.isEmpty())
            throw RuntimeException("You can't just add no colors.")

        colorList.ensureCapacity(colorList.size + varargs.size)
        varargs.forEach { colorList.add(it) }

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
        val lum = perceivedRelLuminance(layerColor(color, 1))
        val fgColor: Int = if (lum > 0)
            darkTextColor
        else
            lightTextColor

        currentLuminance = lum
        currentForegroundColor = fgColor
        currentBaseColor = color

        synchronized(colorManagers) {
            colorManagers.forEach {
                it.update(color, fgColor)
            }
        }
    }

    private fun updateUpdate() {
        if (colorList.size > 1) {
            synchronized(updateLock) {
                stopUpdate()
                startUpdate()
            }
        }
    }

    private fun startUpdate() {
        if (colorList.size >= 2) {
            timerActive = true
            timer = Timer("ColorUpdate", true)
            val (changeLength, progress, period) = calculateTimeOfDay()

            if (BuildConfig.DEBUG) {
                val sunriseHour = sunriseTime / Constants.HOUR_IN_MILLISECONDS
                val sunsetHour = sunsetTime / Constants.HOUR_IN_MILLISECONDS
                Log.d("ColorSupervisor", "Now is ${getTimeOfDay(currentIndex)} with length of $changeLength and progress $progress. " +
                        "Sunrise is at $sunriseHour:${(sunriseTime - sunriseHour * Constants.HOUR_IN_MILLISECONDS) / Constants.MINUTE_IN_MILLISECONDS} " +
                        "and sun sets at $sunsetHour:${(sunsetTime - sunsetHour * Constants.HOUR_IN_MILLISECONDS) / Constants.MINUTE_IN_MILLISECONDS}")

                Log.d("ColorSupervisor", "Update rate is $period")
            }

            timerTask = ColorUpdateTask(changeLength, progress, period)
            timer!!.scheduleAtFixedRate(timerTask, 0L, period)
        }
    }

    private fun getTimeOfDay(value: Int) = when (value) {
        0 -> "Morning"
        1 -> "Noon"
        2 -> "Evening"
        3 -> "Night"
        else -> "Bug"
    }

    /**
     * Generate Triple of change length, progress, period in this order
     */
    private fun calculateTimeOfDay(): Triple<Long, Long, Long> =
            when {
                colorList.size == 4 -> calculateTimeOfDay4()
                colorList.size == 2 -> calculateTimeOfDay2()
                else -> throw RuntimeException("Invalid size of color list (${colorList.size}) for time of day ")
            }

    private fun calculateTimeOfDay2(): Triple<Long, Long, Long> {
        val time = Assist.time
        val changeLength: Long
        val progress: Long

        when {
            time > sunsetTime -> {
                //Between sunset and the end of the day
                changeLength = Constants.DAY_IN_MILLISECONDS - (sunsetTime - sunriseTime)
                progress = time - sunsetTime
                currentIndex = 1
            }
            time > sunriseTime -> {
                //Between sunrise and sunset
                changeLength = sunsetTime - sunriseTime
                progress = time - sunriseTime
                currentIndex = 0
            }
            else -> {
                //Between start of the day and sunrise
                changeLength = Constants.DAY_IN_MILLISECONDS - (sunsetTime - sunriseTime)
                progress = time + Constants.DAY_IN_MILLISECONDS - sunsetTime
                currentIndex = 1
            }
        }

        //Add +1 to make it more bug proof, it shouldn't really matter because +1 is just a millisecond
        return Triple(changeLength, 0, changeLength - progress + 1)
    }

    private fun calculateTimeOfDay4(): Triple<Long, Long, Long> {
        val time = Assist.time
        val changeLength: Long
        val progress: Long

        if (time > sunsetTime) {
            if (nightTime > sunsetTime) {
                if (time < nightTime) {
                    //Between sunset and night when night is before midnight and time is before midnight
                    changeLength = nightTime - sunsetTime
                    progress = time - sunsetTime
                    currentIndex = 2
                } else {
                    //Between night and sunrise when night is before midnight and time is before midnight
                    changeLength = 24 * Constants.HOUR_IN_MILLISECONDS - nightTime + sunriseTime
                    progress = time - nightTime
                    currentIndex = 3
                }
            } else {
                //Between sunset and night when night is after midnight and time is before midnight
                changeLength = 24 * Constants.HOUR_IN_MILLISECONDS - sunsetTime + nightTime
                progress = time - sunsetTime
                currentIndex = 2
            }
        } else if (time > dayTime) {
            //Between day and sunset
            changeLength = sunsetTime - dayTime
            progress = time - dayTime
            currentIndex = 1
        } else if (time > sunriseTime) {
            //Between sunrise and day
            changeLength = dayTime - sunriseTime
            progress = time - sunriseTime
            currentIndex = 0
        } else {
            if (nightTime > sunsetTime) {
                //Between night and sunrise when night is before midnight and time is after midnight
                val beforeMidnight = 24 * Constants.HOUR_IN_MILLISECONDS - nightTime
                changeLength = beforeMidnight + sunriseTime
                progress = time + beforeMidnight
                currentIndex = 3
            } else {
                if (time < nightTime) {
                    //Between sunset and night when night is after midnight and time is after midnight
                    val beforeMidnight = 24 * Constants.HOUR_IN_MILLISECONDS - sunsetTime
                    changeLength = beforeMidnight + nightTime
                    progress = time + beforeMidnight
                    currentIndex = 2
                } else {
                    //Between night and sunrise when night is after midnight and time is after midnight
                    changeLength = sunriseTime - nightTime
                    progress = time - nightTime
                    currentIndex = 3
                }
            }
        }

        return Triple(changeLength, progress, calculateUpdatePeriod(changeLength))
    }

    private fun calculateUpdatePeriod(changeLength: Long) = changeLength / calculateUpdateCount()

    private fun calculateUpdateCount(): Int {
        if (colorList.size < 2)
            throw RuntimeException("Update rate cannot be calculated for less than 2 colors")

        val currentColor = colorList[currentIndex]
        val targetColor = colorList[nextIndex]
        val rDiff = Math.abs(Color.red(currentColor) - Color.red(targetColor))
        val gDiff = Math.abs(Color.green(currentColor) - Color.green(targetColor))
        val bDiff = Math.abs(Color.blue(currentColor) - Color.blue(targetColor))
        val totalDiff = rDiff + gDiff + bDiff
        return if (totalDiff == 0) 1 else totalDiff
    }

    private fun stopUpdate() {
        synchronized(updateLock) {
            if (timerActive) {
                timerActive = false
                timer!!.cancel()
            }
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
        synchronized(updateLock) {
            stopUpdate()
            startUpdate()
        }
    }

}

internal class ColorUpdateTask(private val periodLength: Long, private var currentTime: Long = 0, private val deltaTime: Long) : TimerTask() {
    override fun run() {
        val newTime = currentTime + deltaTime
        currentTime = newTime.rem(periodLength)
        val delta = currentTime.toFloat() / periodLength

        if (newTime != currentTime)
            ColorSupervisor.deltaUpdate(delta, true)
        else
            ColorSupervisor.deltaUpdate(delta, false)
    }
}