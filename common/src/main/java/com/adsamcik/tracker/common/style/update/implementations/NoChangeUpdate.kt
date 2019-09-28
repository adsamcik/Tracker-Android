package com.adsamcik.tracker.common.style.update.implementations

import android.content.Context
import com.adsamcik.tracker.common.style.SunSetRise
import com.adsamcik.tracker.common.style.update.RequiredColors
import com.adsamcik.tracker.common.style.update.StyleConfigData
import com.adsamcik.tracker.common.style.update.StyleUpdate
import com.adsamcik.tracker.common.style.update.UpdateData

internal class NoChangeUpdate : StyleUpdate() {
	override val nameRes: Int = 0

	override val requiredColorData: RequiredColors
		get() = RequiredColors(emptyList())

	override fun onPostEnable(context: Context, configData: StyleConfigData) = Unit

	override fun onPreDisable(context: Context) = Unit

}
