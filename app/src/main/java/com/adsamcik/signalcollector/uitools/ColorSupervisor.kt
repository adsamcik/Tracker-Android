package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.graphics.Color
import android.support.annotation.ColorInt
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.ColorUtils
import android.util.Log
import com.adsamcik.signalcollector.BuildConfig
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
        colorManagers.add(colorManager)
        ensureUpdate()

        return colorManager
    }

    fun ensureUpdate() {
        if (colorList.size > 1) {
            synchronized(updateLock) {
                if (!timerActive)
                    startUpdate()
            }
        }
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
    }

    fun deltaUpdate(delta: Float, newPeriod: Boolean) {
        if (newPeriod) {
            currentIndex = nextIndex
            updateUpdate()
        }

        Log.d("ColorSupervisor", "index $currentIndex current ${colorList[currentIndex]} next ${colorList[nextIndex]} delta $delta")
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

        colorManagers.forEach {
            it.update(color, fgColor)
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
        timerActive = true
        val (changeLength, progress) = calculateTimeOfDay()
        val period = calculateUpdatePeriod(changeLength).toLong()

        if (BuildConfig.DEBUG) {
            val sunriseHour = sunriseTime / Constants.HOUR_IN_MILLISECONDS
            val sunsetHour = sunsetTime / Constants.HOUR_IN_MILLISECONDS
            Log.d("ColorSupervisor", "Now is ${getTimeOfDay(currentIndex)} with length of $changeLength and progress $progress. " +
                    "Sunrise is at $sunriseHour:${(sunriseTime - sunriseHour * Constants.HOUR_IN_MILLISECONDS) / Constants.MINUTE_IN_MILLISECONDS} " +
                    "and sun sets at $sunsetHour:${(sunsetTime - sunsetHour * Constants.HOUR_IN_MILLISECONDS) / Constants.MINUTE_IN_MILLISECONDS}")

            Log.d("ColorSupervisor", "Update rate is $period")
        }

        timerTask = ColorUpdateTask(period, changeLength.toLong(), progress.toLong())
        timer.scheduleAtFixedRate(timerTask, 0L, period)
    }

    private fun getTimeOfDay(value: Int) = when (value) {
        0 -> "Morning"
        1 -> "Noon"
        2 -> "Evening"
        3 -> "Night"
        else -> "Bug"
    }

    private fun calculateTimeOfDay(): Pair<Int, Int> {
        val time = Assist.time
        val changeLength: Int
        val progress: Int

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

        return Pair(changeLength, progress)
    }

    private fun calculateUpdatePeriod(changeLength: Int) = changeLength / calculateUpdateCount()

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

}

internal class ColorUpdateTask(private val deltaTime: Long, private val periodLength: Long, private var currentTime: Long = 0) : TimerTask() {
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