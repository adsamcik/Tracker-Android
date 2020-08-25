package com.adsamcik.tracker.shared.utils.style

import android.content.Context
import android.graphics.Color
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.preferences.BuildConfig
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.R
import com.adsamcik.tracker.shared.utils.style.color.perceivedRelLuminance
import com.adsamcik.tracker.shared.utils.style.update.abstraction.StyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.StyleConfigData
import com.adsamcik.tracker.shared.utils.style.update.implementation.DayNightChangeUpdate
import com.adsamcik.tracker.shared.utils.style.update.implementation.LightDayNightSwitchUpdate
import com.adsamcik.tracker.shared.utils.style.update.implementation.LightDayNightTransitionUpdate
import com.adsamcik.tracker.shared.utils.style.update.implementation.MorningDayEveningNightTransitionUpdate
import com.adsamcik.tracker.shared.utils.style.update.implementation.NoChangeUpdate
import com.adsamcik.tracker.shared.utils.style.update.implementation.SingleColorUpdate
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Class that handles globally calculation of current color.
 * It needs to be updated with proper location to have accurate color transitions.
 */
// TODO: move updates into objects so custom changes can be implemented in the future
//  for example someone could choose between x colors and the system would divide the day by them
//  and updates as needed while reusing existing transition functions
@AnyThread
object StyleManager {
	//Lock order colorList, colorManagerLock, timer

	private val controllerCollection = mutableListOf<StyleController>()

	private var darkTextColor: Int = 0
	private var lightTextColor: Int = 0

	var styleData: StyleData = StyleData(0, 0)
		private set

	private val controllerLock = ReentrantLock()

	private var update: StyleUpdate = NoChangeUpdate()

	private val enabledUpdateList = listOf(
			MorningDayEveningNightTransitionUpdate(),
			DayNightChangeUpdate(),
			SingleColorUpdate(),
			LightDayNightTransitionUpdate(),
			LightDayNightSwitchUpdate()
	)

	val enabledUpdateInfo: List<StyleUpdateInfo>
		get() = enabledUpdateList.map { StyleUpdateInfo(it) }

	val activeUpdateInfo: StyleUpdateInfo
		get() = StyleUpdateInfo(update)

	val activeColorList: List<ActiveColorData>
		get() = update.colorList.zip(update.defaultColors.list) { a, r ->
			ActiveColorData(active = a, default = r)
		}

	private const val TEXT_ALPHA = 222

	init {
		if (BuildConfig.DEBUG) {
			enabledUpdateList.forEach { styleUpdate ->
				styleUpdate.defaultColors.list.forEachIndexed { index, colorData ->
					val default = colorData.defaultColor
					require(default.alpha == 255) {
						"Default color #${default.toString(16)} at index $index " +
								"from ${styleUpdate::class.java.name} was not opaque"
					}
				}
			}
		}
	}

	/**
	 * Creates color manager instance
	 */
	fun createController(): StyleController {
		if (darkTextColor == 0) {
			lightTextColor = Color.WHITE.withAlpha(TEXT_ALPHA)
			darkTextColor = Color.BLACK.withAlpha(TEXT_ALPHA)
		}

		val colorManager = StyleController()

		if (controllerLock.isHeldByCurrentThread) {
			throw ConcurrentModificationException("Controller cannot be created during update")
		}

		controllerLock.withLock {
			controllerCollection.add(colorManager)

			//if (controllerCollection.size == 1) ensureUpdate()
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

		controllerLock.withLock {
			controllerCollection.remove(styleController)
			styleController.dispose()
		}
	}

	private fun enableUpdate(context: Context, preferenceColorList: List<Int> = listOf()) {
		update.onEnable(context, StyleConfigData(preferenceColorList, this::update))
	}

	private fun enableUpdateWithPreference(context: Context) {
		val preferences = Preferences.getPref(context)
		val requiredColorList = update.defaultColors.list
		val format = context.getString(R.string.settings_color_key)
		val list = ArrayList<Int>(requiredColorList.size)

		for (i in requiredColorList.indices) {
			val default = requiredColorList[i].defaultColor
			val key = format.format(i)
			val color = preferences.getInt(key, default)
			list.add(color)
		}

		enableUpdate(context, list)
	}

	private fun disableUpdate(context: Context) {
		if (update.isEnabled) {
			update.onDisable(context)
		}
	}

	/**
	 * Update function is called with new color and handles updating of all the colorManagers.
	 */
	private fun update(@ColorInt backgroundColor: Int) {
		val perceivedLuminance = perceivedRelLuminance(backgroundColor)
		val foregroundColor: Int = if (perceivedLuminance > 0) darkTextColor else lightTextColor

		val styleData = StyleData(backgroundColor, foregroundColor)

		StyleManager.styleData = styleData

		controllerLock.withLock {
			controllerCollection.forEach {
				it.update(styleData)
			}
		}
	}

	fun setMode(context: Context, info: StyleUpdateInfo) {
		val index = enabledUpdateInfo.indexOf(info)
		require(index >= 0 && index < enabledUpdateList.size) { "Invalid update info $info" }

		val newUpdate = enabledUpdateList[index]
		val isResetRequired = update !is NoChangeUpdate && update.javaClass != newUpdate.javaClass
		disableUpdate(context)
		update = newUpdate
		if (isResetRequired) {
			enableUpdate(context)
		} else {
			enableUpdateWithPreference(context)
		}
	}

	/**
	 * Initializes colors from preference. This completely replaces all current colors with those saved in preferences.
	 */
	fun initializeFromPreferences(context: Context) {
		val preferences = Preferences.getPref(context)
		val mode = preferences.getStringRes(R.string.settings_style_mode_key)
				?: enabledUpdateList.first().id

		if (mode == update.id) return

		disableUpdate(context)

		//can be null if the mode was removed
		update = enabledUpdateList.firstOrNull { it.id == mode } ?: enabledUpdateList.first()

		enableUpdateWithPreference(context)
	}

	/**
	 * Updates specific color at given index. This function requires proper knowledge of the current colors.
	 * This function can cause a lot of bugs so use it carefully.
	 * It is intended mainly to be used for easy color switching when preference is changed.
	 */
	fun updateColorAt(context: Context, index: Int, @ColorInt color: Int) {
		val colorList = update.colorList
		// ignore changes if not right
		// this may hide bugs, but is better alternative to controlled propagation on mode change
		if (index >= colorList.size) return

		val mutableColorList = colorList.toMutableList()
		disableUpdate(context)
		mutableColorList[index] = color
		enableUpdate(context, mutableColorList)
	}
}

