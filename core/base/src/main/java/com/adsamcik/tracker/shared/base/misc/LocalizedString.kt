package com.adsamcik.tracker.shared.base.misc

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * Describes string that can be localized.
 */
data class LocalizedString(@StringRes val stringRes: Int, val params: Array<Any>) {

	constructor(@StringRes stringRes: Int) : this(stringRes, emptyArray())

	constructor(@StringRes stringRes: Int, param: Any) : this(stringRes, arrayOf(param))

	constructor(@StringRes stringRes: Int, firstParam: Any, secondParam: Any) : this(
			stringRes,
			arrayOf(
					firstParam,
					secondParam
			)
	)

	/**
	 * Returns localized string for current language.
	 */
	fun localize(context: Context): String = localize(context.resources)

	/**
	 * Returns localized string for current language.
	 */
	fun localize(resources: Resources): String = resources.getString(stringRes, params)

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as LocalizedString

		if (stringRes != other.stringRes) return false
		if (!params.contentEquals(other.params)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = stringRes
		result = 31 * result + params.contentHashCode()
		return result
	}
}
