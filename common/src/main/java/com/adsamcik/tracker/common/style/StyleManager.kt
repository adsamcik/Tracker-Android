package com.adsamcik.tracker.common.style

import android.content.Context
import android.graphics.Color
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.common.style.update.DayNightChangeUpdate
import com.adsamcik.tracker.common.style.update.MorningDayEveningNightTransitionUpdate
import com.adsamcik.tracker.common.style.update.NoChangeUpdate
import com.adsamcik.tracker.common.style.update.StyleUpdate
import com.adsamcik.tracker.common.style.utility.perceivedRelLuminance
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.ConcurrentModificationException
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * Class that handles globally calculation of current color.
 * It needs to be updated with proper location to have accurate color transitions.
 */
// TODO: move updates into objects so custom changes can be implemented in the future
//  for example someone could choose between x colors and the system would divide the day by them
//  and updates as needed while reusing existing transition functions
@AnyThread
@Suppress("TooManyFunctions")
object StyleManager {
	//Lock order colorList, colorManagerLock, timer

	private val colorList = ArrayList<@ColorInt Int>(0)
	private var timer: Timer? = null
	private var timerActive = false

	private val controllerCollection = mutableListOf<StyleController>()

	private val sunsetRise = SunSetRise()

	private var currentIndex = 0

	private val nextIndex: Int
		get() {
			synchronized(colorList) {
				return (currentIndex + 1).rem(colorList.size)
			}
		}

	private var darkTextColor: Int = 0
	private var lightTextColor: Int = 0

	var styleData: StyleData = StyleData(0, 0)
		private set

	private val controllerLock = ReentrantLock()
	private val colorListLock = ReentrantLock()
	private val timerLock = ReentrantLock()

	private val enabledUpdateList = listOf(
			MorningDayEveningNightTransitionUpdate(),
			DayNightChangeUpdate()
	)
	private var update: StyleUpdate = NoChangeUpdate()

	val requiredColors = update.requiredColorData.colorList
	val activeColorList: List<Int> = colorList

	private const val TEXT_ALPHA = 222

	/**
	 * Creates color manager instance
	 */
	fun createController(): StyleController {
		if (darkTextColor == 0) {
			lightTextColor = ColorUtils.setAlphaComponent(Color.WHITE, TEXT_ALPHA)
			darkTextColor = ColorUtils.setAlphaComponent(Color.BLACK, TEXT_ALPHA)
		}

		val colorManager = StyleController()

		if (controllerLock.isHeldByCurrentThread) {
			throw ConcurrentModificationException("Controller cannot be created during update")
		}

		controllerLock.withLock {
			controllerCollection.add(colorManager)

			if (controllerCollection.size == 1) ensureUpdate()
		}

		return colorManager
	}

	/**
	 * Recycles color manager instance. Cleans it up and prepares it for removal.
	 * It is also removed from active color managers.
	 */
	fun recycleController(styleController: StyleController) {
		if (controllerLock.isHeldByCurrentThread) {
			throw ConcurrentModificationException("Controller cannot be removed during an update")
		}

		var isCollectionEmpty = false
		controllerLock.withLock {
			controllerCollection.remove(styleController)
			styleController.dispose()
			isCollectionEmpty = controllerCollection.isEmpty()
		}

		if (isCollectionEmpty) stopUpdate()
	}

	/**
	 * Checks if a timer is running, if not start a new timer.
	 */
	fun ensureUpdate() {
		colorListLock.withLock {
			if (colorList.size > 1) {
				synchronized(timerActive) {
					if (!timerActive) startUpdate()
				}
			} else if (colorList.size == 1) {
				update(colorList.first())
			}
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

		colorListLock.withLock {
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
	private fun update(@ColorInt backgroundColor: Int) {
		val perceivedLuminance = perceivedRelLuminance(backgroundColor)
		val foregroundColor: Int = if (perceivedLuminance > 0) darkTextColor else lightTextColor

		val styleData = StyleData(backgroundColor, foregroundColor)


		this.styleData = styleData

		controllerLock.withLock {
			controllerCollection.forEach {
				it.update(styleData)
			}
		}
	}

	private fun updateUpdate() {
		colorListLock.withLock {
			if (colorList.size > 1) {
				synchronized(timerActive) {
					stopUpdate()
					startUpdate()
				}
			} else {
				update(colorList.first())
			}
		}
	}

	/**
	 * Handles start update function. Supports only 2 or 4 colors.
	 * 1 color should never call an update, because the color never changes.
	 */
	private fun startUpdate() {
		colorListLock.withLock {
			if (colorList.size >= 2) {
				val data = update.getUpdateData(colorList, sunsetRise)
				timerLock.withLock {
					timerActive = true
					val timer = Timer("ColorUpdate", true)
					val deltaTime = calculateDeltaUpdate(data.duration)
					timer.schedule(
							ColorUpdateTask(data.duration, data.progress, deltaTime),
							0L,
							deltaTime
					)

					StyleManager.timer = timer
				}
			}
		}
	}

	private fun calculateDeltaUpdate(changeLength: Long) = changeLength / calculateUpdateCount()

	private fun calculateUpdateCount(): Int {
		colorListLock.withLock {
			check(colorList.size >= 2) { "Update rate cannot be calculated for less than 2 colors" }

			val currentColor = colorList[currentIndex]
			val targetColor = colorList[nextIndex]
			val rDiff = abs(Color.red(currentColor) - Color.red(targetColor))
			val gDiff = abs(Color.green(currentColor) - Color.green(targetColor))
			val bDiff = abs(Color.blue(currentColor) - Color.blue(targetColor))
			val totalDiff = rDiff + gDiff + bDiff
			return if (totalDiff == 0) 1 else totalDiff
		}
	}

	private fun stopUpdate() {
		timerLock.withLock {
			if (timerActive) {
				timerActive = false
				requireNotNull(timer).cancel()
			}
		}
	}

	/**
	 * Initializes colors from preference. This completely replaces all current colors with those saved in preferences.
	 */
	fun initializeFromPreferences(context: Context) {
		sunsetRise.addListener(this::onLocationChange)
		sunsetRise.initialize(context)

		val preferences = Preferences.getPref(context)
		val mode = preferences.getStringRes(R.string.settings_style_mode_key)
				?: MorningDayEveningNightTransitionUpdate::class.java.name

		if (mode == update::class.java.name) return

		stopUpdate()

		update = enabledUpdateList.firstOrNull { it::class.java.name == mode }
				?: enabledUpdateList.first()

		val requiredColorList = update.requiredColorData.colorList
		val format = context.getString(R.string.settings_color_key)

		colorListLock.withLock {
			colorList.clear()
			colorList.ensureCapacity(requiredColorList.size)

			for (i in requiredColorList.indices) {
				val default = requiredColorList[i].defaultColor
				val key = format.format(i)
				val color = preferences.getInt(key, default)
				colorList.add(color)
			}

			colorList.trimToSize()
		}

		startUpdate()
	}

	/**
	 * Updates specific color at given index. This function requires proper knowledge of the current colors.
	 * This function can cause a lot of bugs so use it carefully.
	 * It is intended mainly to be used for easy color switching when preference is changed.
	 */
	fun updateColorAt(index: Int, @ColorInt color: Int) {
		colorListLock.withLock {
			require(index >= 0 && index < colorList.size) { "Index $index is out of bounds. Size is ${colorList.size}." }
			colorList[index] = color
			updateUpdate()
		}
	}

	private fun onLocationChange(@Suppress("unused_parameter") sunSetRise: SunSetRise) {
		timerLock.withLock {
			stopUpdate()
			startUpdate()
		}
	}

	/**
	 * Color update task that calculates delta update based on parameters. It is used for color transitions.
	 */
	internal class ColorUpdateTask(
			private val periodLength: Long,
			private var currentTime: Long = 0,
			private val deltaTime: Long
	) : TimerTask() {
		override fun run() {
			currentTime = (currentTime + deltaTime).rem(periodLength)
			val delta = currentTime.toFloat() / periodLength
			deltaUpdate(delta)
		}
	}
}

