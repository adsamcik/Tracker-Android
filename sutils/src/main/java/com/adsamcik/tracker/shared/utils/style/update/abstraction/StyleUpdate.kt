package com.adsamcik.tracker.shared.utils.style.update.abstraction

import android.content.Context
import com.adsamcik.tracker.shared.utils.debug.Reporter
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColors
import com.adsamcik.tracker.shared.utils.style.update.data.StyleConfigData
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal abstract class StyleUpdate {
	abstract val nameRes: Int
	abstract val requiredColorData: RequiredColors

	val colorList: List<Int> get() = _colorList

	private val _colorList: MutableList<Int> = mutableListOf()

	val id: String
		get() = this::class.java.simpleName

	private var configData: StyleConfigData? = null

	var isEnabled: Boolean = false
		private set

	protected val updateLock = ReentrantLock()

	fun requireConfigData(): StyleConfigData = requireNotNull(configData)

	fun onEnable(context: Context, configData: StyleConfigData) {
		if (isEnabled) Reporter.report("Style update is already in enabled state.")

		updateLock.withLock {
			this.configData = configData

			if (configData.preferenceColorList.isNotEmpty() &&
					configData.preferenceColorList.size == requiredColorData.list.size) {
				_colorList.addAll(configData.preferenceColorList)
			} else {
				_colorList.addAll(requiredColorData.list.map { it.defaultColor })
			}

			onPostEnable(context, configData)
			isEnabled = true
		}
	}

	fun onDisable(context: Context) {
		if (!isEnabled) Reporter.report("Style update is already in disabled state.")

		onPreDisable(context)

		updateLock.withLock {
			isEnabled = false
			this.configData = null
			_colorList.clear()
		}
	}

	protected abstract fun onPostEnable(context: Context, configData: StyleConfigData)
	protected abstract fun onPreDisable(context: Context)
}
