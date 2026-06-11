package com.adsamcik.tracker.tracker.shortcut

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class ShortcutData(
		val id: String,
		@StringRes val shortLabelRes: Int,
		@StringRes val longLabelRes: Int,
		@DrawableRes val iconResource: Int,
		val shortcutAction: Shortcuts.ShortcutAction
)
