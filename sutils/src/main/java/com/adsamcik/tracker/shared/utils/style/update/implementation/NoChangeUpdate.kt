package com.adsamcik.tracker.shared.utils.style.update.implementation

import android.content.Context
import com.adsamcik.tracker.shared.utils.style.update.abstraction.StyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColors
import com.adsamcik.tracker.shared.utils.style.update.data.StyleConfigData

internal class NoChangeUpdate : StyleUpdate() {
	override val nameRes: Int = 0

	override val requiredColorData: RequiredColors
		get() = RequiredColors(emptyList())

	override fun onPostEnable(context: Context, configData: StyleConfigData) = Unit

	override fun onPreDisable(context: Context) = Unit

}
