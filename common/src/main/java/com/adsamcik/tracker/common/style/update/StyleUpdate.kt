package com.adsamcik.tracker.common.style.update

import android.content.Context

internal abstract class StyleUpdate {
	abstract val nameRes: Int
	abstract val requiredColorData: RequiredColors

	val colorList: MutableList<Int> = mutableListOf()

	val id: String
		get() = this::class.java.simpleName

	private var configData: StyleConfigData? = null

	fun requireConfigData(): StyleConfigData = requireNotNull(configData)

	fun onEnable(context: Context, configData: StyleConfigData) {
		require(colorList.isEmpty())
		this.configData = configData

		if (configData.preferenceColorList.isNotEmpty() &&
				configData.preferenceColorList.size == requiredColorData.list.size) {
			colorList.addAll(configData.preferenceColorList)
		} else {
			colorList.addAll(requiredColorData.list.map { it.defaultColor })
		}

		onPostEnable(context, configData)
	}

	fun onDisable(context: Context) {
		onPreDisable(context)

		this.configData = null
		colorList.clear()
	}

	protected abstract fun onPostEnable(context: Context, configData: StyleConfigData)
	protected abstract fun onPreDisable(context: Context)
}
