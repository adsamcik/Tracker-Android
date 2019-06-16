package com.adsamcik.signalcollector.common.color

import android.content.Context
import android.graphics.Color
import android.location.Location
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.adsamcik.signalcollector.common.BuildConfig
import com.adsamcik.signalcollector.common.Constants
import com.adsamcik.signalcollector.common.R
import com.adsamcik.signalcollector.common.misc.extension.toTimeSinceMidnight
import com.adsamcik.signalcollector.common.preference.Preferences
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock

/**
 * Class that handles globally calculation of current color
 * It needs to be updated with proper location to have accurate color transitions
 */
@AnyThread
object ColorManager {
	//Lock order colorList, colorManagerLock, timer

	private val colorList = ArrayList<@ColorInt Int>()
	private var timer: Timer? = null
	private var timerActive = false

	private val controllerCollection = ArrayList<ColorController>()

	private val sunsetRise = SunSetRise()

	private var currentIndex = 0

	private val nextIndex get () = (currentIndex + 1).rem(colorList.size)

	private var darkTextColor: Int = 0
	private var lightTextColor: Int = 0

	var currentColorData = ColorData(0, 0, floatArrayOf(), 0)
		private set

	private val controllerLock = ReentrantLock()

	/**
	 * Returns proper base foreground color for given ColorView
	 */
	@ColorInt
	fun foregroundColorFor(colorView: ColorView): Int = foregroundColorFor(colorView.isInverted)

	/**
	 * Returns proper base foreground color based on [backgroundIsForeground]
	 *
	 * @param backgroundIsForeground True if background and foreground should be inverted
	 */
	@ColorInt
	fun foregroundColorFor(backgroundIsForeground: Boolean): Int = if (backgroundIsForeground) currentColorData.baseColor else currentColorData.foregroundColor

	/**
	 * Returns proper base background color for given ColorView
	 */
	@ColorInt
	fun backgroundColorFor(colorView: ColorView): Int = backgroundColorFor(colorView.isInverted)

	/**
	 * Returns proper base background color based on [backgroundIsForeground]
	 *
	 * @param backgroundIsForeground True if background and foreground should be inverted
	 */
	@ColorInt
	fun backgroundColorFor(backgroundIsForeground: Boolean): Int = if (backgroundIsForeground) currentColorData.foregroundColor else currentColorData.baseColor

	/**
	 * Creates color manager instance
	 */
	fun createController(): ColorController {
		if (darkTextColor == 0) {
			darkTextColor = Color.argb(222, 0, 0, 0)
			lightTextColor = Color.argb(222, 255, 255, 255)
		}

		val colorManager = ColorController()

		controllerLock.lock()
		controllerCollection.add(colorManager)
		controllerLock.unlock()

		ensureUpdate()

		return colorManager
	}

	/**
	 * Recycles color manager instance. Cleans it up and prepares it for removal.
	 * It is also removed from active color managers.
	 */
	fun recycleController(colorController: ColorController) {
		controllerLock.lock()
		controllerCollection.remove(colorController)
		colorController.cleanup()
		if (controllerCollection.isEmpty()) {
			controllerCollection.trimToSize()
			controllerLock.unlock()
			stopUpdate()
		} else {
			controllerLock.unlock()
		}
	}

	/**
	 * Checks if a timer is running, if not start a new timer.
	 */
	fun ensureUpdate() {
		synchronized(colorList) {
			if (colorList.size > 1) {
				synchronized(timerActive) {
					if (!timerActive) startUpdate()
				}
			} else if (colorList.size == 1) {
				update(colorList[0])
			}
		}
	}

	/**
	 * Returns proper color for given layer
	 *
	 * @param color base color
	 * @param layer layer (should be positive)
	 */
	fun layerColor(@ColorInt color: Int, layer: Int): Int {
		return if (layer == 0) {
			color
		} else {
			brightenColor(color, 17 * layer)
		}
	}

	/**
	 * Add all given colors to colorList. This is usually not the way you want to initialize the colors. Consider using load from preferences.
	 *
	 * @param varargs colors to add
	 */
	fun addColors(@ColorInt vararg varargs: Int) {
		synchronized(colorList) {
			if (varargs.isEmpty())
				throw RuntimeException("You can't just add no colors.")

			//Has to be added one by one because it is vararg
			colorList.ensureCapacity(colorList.size + varargs.size)
			varargs.forEach { colorList.add(it) }

			ensureUpdate()
		}
	}

	/**
	 * Delta update which is called internally by update functions
	 *
	 * @param delta value from 0 to 1
	 */
	private fun deltaUpdate(delta: Float) {
		if (delta > 1) {
			currentIndex = nextIndex
			updateUpdate()
		}

		synchronized(colorList) {
			if (colorList.size < 2) {
				stopUpdate()
				return
			}

			update(ColorUtils.blendARGB(colorList[currentIndex], colorList[nextIndex], delta))
		}
	}

	/**
	 * Update function is called with new color and handles updating of all the colorManagers.
	 */
	private fun update(@ColorInt color: Int) {
		val perceivedLuminance = perceivedRelLuminance(layerColor(color, 1))
		val fgColor: Int = if (perceivedLuminance > 0)
			darkTextColor
		else
			lightTextColor

		val baseColorHSL = FloatArray(3)
		ColorUtils.RGBToHSL(color.red, color.green, color.blue, baseColorHSL)

		currentColorData = ColorData(color, fgColor, baseColorHSL, perceivedLuminance)

		controllerLock.withLock {
			controllerCollection.forEach {
				it.update()
			}
		}
	}

	private fun updateUpdate() {
		synchronized(colorList) {
			if (colorList.size > 1) {
				synchronized(timerActive) {
					stopUpdate()
					startUpdate()
				}
			} else {
				update(colorList[0])
			}
		}
	}

	/**
	 * Handles start update function. Supports only 2 or 4 colors.
	 * 1 color should never call an update, because the color never changes.
	 */
	private fun startUpdate() {
		synchronized(colorList) {
			if (colorList.size >= 2) {
				synchronized(timerActive) {
					timerActive = true
					val timer = Timer("ColorUpdate", true)
					when (colorList.size) {
						2 -> startUpdate2(timer)
						4 -> startUpdate4(timer)
						else -> throw IllegalStateException()
					}

					ColorManager.timer = timer
				}
			}
		}
	}

	/**
	 * Starts update function for 2 colors
	 */
	private fun startUpdate2(timer: Timer) {
		timer.schedule(object : TimerTask() {
			override fun run() {
				deltaUpdate(0f)
			}
		}, calculateTimeOfDay2().time)
		deltaUpdate(0f)
	}

	/**
	 * Starts update function for 4 colors
	 */
	private fun startUpdate4(timer: Timer) {
		val (changeLength, progress, period) = calculateTimeOfDay4()
		timer.scheduleAtFixedRate(ColorUpdateTask(changeLength, progress, period), 0L, period)

		deltaUpdate(progress / changeLength.toFloat())

		if (BuildConfig.DEBUG) {
			val sunset = sunsetRise.nextSunset()
			val sunrise = sunsetRise.nextSunrise()
			val format = SimpleDateFormat("HH:mm:ss dd-MM-yyyy", Locale.getDefault())
			Log.d("ColorManager", "Now is ${getTimeOfDay(currentIndex)} with length of $changeLength and progress $progress. " +
					"Sunrise is at ${format.format(sunrise.time)} " +
					"and sun sets at ${format.format(sunset.time)}")

			Log.d("ColorManager", "Update rate is $period")
		}
	}

	private fun getTimeOfDay(value: Int) = when (value) {
		0 -> "Morning"
		1 -> "Noon"
		2 -> "Evening"
		3 -> "Night"
		else -> "Bug"
	}

	private fun calculateTimeOfDay2(): Calendar {
		val time = Calendar.getInstance().toTimeSinceMidnight()
		val sunset = sunsetRise.nextSunset()
		val sunrise = sunsetRise.nextSunrise()

		return when {
			(time > sunset.toTimeSinceMidnight()) or (time < sunrise.toTimeSinceMidnight()) -> {
				currentIndex = 1
				sunrise
			}
			else -> {
				currentIndex = 0
				sunset
			}
		}
	}

	private fun calculateTimeOfDay4(): Triple<Long, Long, Long> {
		val time = Calendar.getInstance().toTimeSinceMidnight()
		val changeLength: Long
		val progress: Long

		val sunsetTime = sunsetRise.nextSunset().toTimeSinceMidnight()
		val sunriseTime = sunsetRise.nextSunrise().toTimeSinceMidnight()

		val dayTime = (sunsetTime - sunriseTime) / 2 + sunriseTime
		val nightTime = ((Constants.DAY_IN_MILLISECONDS - sunsetTime + sunriseTime) / 2 + sunsetTime).rem(Constants.DAY_IN_MILLISECONDS)

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
		synchronized(colorList) {
			if (colorList.size < 2) throw IllegalStateException("Update rate cannot be calculated for less than 2 colors")

			val currentColor = colorList[currentIndex]
			val targetColor = colorList[nextIndex]
			val rDiff = Math.abs(Color.red(currentColor) - Color.red(targetColor))
			val gDiff = Math.abs(Color.green(currentColor) - Color.green(targetColor))
			val bDiff = Math.abs(Color.blue(currentColor) - Color.blue(targetColor))
			val totalDiff = rDiff + gDiff + bDiff
			return if (totalDiff == 0) 1 else totalDiff
		}
	}

	private fun stopUpdate() {
		synchronized(timerActive) {
			if (timerActive) {
				timerActive = false
				timer!!.cancel()
			}
		}
	}

	/**
	 * Initializes colors from preference. This completely replaces all current colors with those saved in preferences.
	 */
	fun initializeFromPreferences(context: Context) {
		val preferences = Preferences.getPref(context)
		val mode = preferences.getStringAsIntResString(R.string.settings_style_mode_key, R.string.settings_style_mode_default)

		stopUpdate()

		synchronized(colorList) {
			colorList.clear()

			val day = preferences.getColorRes(R.string.settings_color_day_key, R.color.settings_color_day_default)

			if (mode == 0) {
				addColors(day)
			} else {
				val night = preferences.getColorRes(R.string.settings_color_night_key, R.color.settings_color_night_default)
				if (mode == 1) {
					addColors(day, night)
				} else {
					val morning = preferences.getColorRes(R.string.settings_color_morning_key, R.color.settings_color_morning_default)
					val evening = preferences.getColorRes(R.string.settings_color_evening_key, R.color.settings_color_evening_default)
					addColors(morning, day, evening, night)
				}
			}
		}
	}

	/**
	 * Updates specific color at given index. This function requires proper knowledge of the current colors.
	 * This function can cause a lot of bugs so use it carefully.
	 * It is intended mainly to be used for easy color switching when preference is changed.
	 */
	fun updateColorAt(index: Int, @ColorInt color: Int) {
		synchronized(colorList) {
			if (index < 0 || index >= colorList.size) {
				throw IllegalArgumentException("Index $index is out of bounds. Size is ${colorList.size}.")
			} else {
				colorList[index] = color
				updateUpdate()
			}
		}
	}

	/**
	 * Sets location for the sunrise and sunset calculator
	 * This should be called every time when location significantly changes
	 */
	fun setLocation(location: Location) {
		sunsetRise.updateLocation(location)
		synchronized(timerActive) {
			stopUpdate()
			startUpdate()
		}
	}

	/**
	 * Color update task that calculates delta update based on parameters. It is used for color transitions.
	 */
	internal class ColorUpdateTask(private val periodLength: Long, private var currentTime: Long = 0, private val deltaTime: Long) : TimerTask() {
		override fun run() {
			currentTime = (currentTime + deltaTime).rem(periodLength)
			val delta = currentTime.toFloat() / periodLength
			deltaUpdate(delta)
		}
	}
}