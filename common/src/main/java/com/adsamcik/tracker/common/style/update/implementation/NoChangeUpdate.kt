package com.adsamcik.tracker.common.style.update.implementation

import android.content.Context
import com.adsamcik.tracker.common.style.update.data.RequiredColors
import com.adsamcik.tracker.common.style.update.data.StyleConfigData
import com.adsamcik.tracker.common.style.update.abstraction.StyleUpdate

internal class NoChangeUpdate : StyleUpdate() {
	override val nameRes: Int = 0

	override val requiredColorData: RequiredColors
		get() = RequiredColors(emptyList())

	override fun onPostEnable(context: Context, configData: StyleConfigData) = Unit

	override fun onPreDisable(context: Context) = Unit

}
