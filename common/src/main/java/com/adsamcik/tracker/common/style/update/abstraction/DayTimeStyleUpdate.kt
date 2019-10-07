package com.adsamcik.tracker.common.style.update.abstraction

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.common.style.SunSetRise
import com.adsamcik.tracker.common.style.update.data.StyleConfigData
import com.adsamcik.tracker.common.style.update.data.UpdateData
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

internal abstract class DayTimeStyleUpdate : StyleUpdate() {
	private var timer: Timer? = null
	private var timerActive = false

	private var currentIndex = 0

	private val nextIndex: Int
		get() {
			synchronized(colorList) {
				return (currentIndex + 1).rem(colorList.size)
			}
		}

	private val sunsetRise = SunSetRise()

	private val timerLock = ReentrantLock()

	override fun onPostEnable(context: Context, configData: StyleConfigData) {
		sunsetRise.addListener(this::onLocationChange)
		sunsetRise.initialize(context)
		startUpdate()
	}

	override fun onPreDisable(context: Context) {
		stopUpdate()
	}

	private fun onLocationChange(@Suppress("unused_parameter") sunSetRise: SunSetRise) {
		timerLock.withLock {
			stopUpdate()
			startUpdate()
		}
	}


	/**
	 * Checks if a timer is running, if not start a new timer.
	 */
	fun ensureUpdate() {
		updateLock.withLock {
			if (colorList.size > 1) {
				synchronized(timerActive) {
					if (!timerActive) startUpdate()
				}
			} else if (colorList.size == 1) {
				onColorUpdate(colorList.first())
			}
		}
	}

	private fun onColorUpdate(color: Int) {
		requireConfigData().callback(color)
	}

	private fun updateUpdate() {
		updateLock.withLock {
			if (colorList.size > 1) {
				synchronized(timerActive) {
					stopUpdate()
					startUpdate()
				}
			} else {
				onColorUpdate(colorList.first())
			}
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
	 * Delta update which is called internally by update functions
	 *
	 * @param delta value from 0 to 1
	 */
	private fun deltaUpdate(delta: Float, data: UpdateData) {
		if (delta >= 1) {
			updateUpdate()
			return
		}

		updateLock.withLock {
			if (isEnabled) {
				onColorUpdate(ColorUtils.blendARGB(data.fromColor, data.toColor, delta))
			}
		}
	}

	abstract fun getUpdateData(styleList: List<Int>, sunSetRise: SunSetRise): UpdateData

	/**
	 * Handles start update function. Supports only 2 or 4 colors.
	 * 1 color should never call an update, because the color never changes.
	 */
	private fun startUpdate() {
		updateLock.withLock {
			if (colorList.size >= 2) {
				val data = getUpdateData(
						colorList,
						sunsetRise
				)

				require(data.duration >= 0)
				timerLock.withLock {
					timerActive = true
					val timer = Timer("ColorUpdate", true)
					val deltaTime = calculateDeltaUpdate(data.duration)
					timer.schedule(
							UpdateTask(data, deltaTime),
							0L,
							deltaTime
					)

					this.timer = timer
				}
			}
		}
	}

	private fun calculateDeltaUpdate(changeLength: Long) = changeLength / calculateUpdateCount()

	private fun calculateUpdateCount(): Int {
		updateLock.withLock {
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


	/**
	 * Color update task that calculates delta update based on parameters. It is used for color transitions.
	 */
	internal inner class UpdateTask(
			private val data: UpdateData,
			private val deltaTime: Long
	) : TimerTask() {
		private var currentTime: Long = data.progress

		override fun run() {
			updateLock.withLock {
				if (isEnabled) {
					timerLock.withLock {
						currentTime = (currentTime + deltaTime).coerceAtMost(data.duration)
						val delta = currentTime.toFloat() / data.duration
						deltaUpdate(delta, data)
					}
				}
			}
		}
	}
}
