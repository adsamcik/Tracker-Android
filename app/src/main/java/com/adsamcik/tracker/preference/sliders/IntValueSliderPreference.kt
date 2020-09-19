package com.adsamcik.tracker.preference.sliders

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.use
import androidx.preference.PreferenceViewHolder
import com.adsamcik.tracker.R

/**
 * Custom Preference implementation of the IntValueSlider from Slider library.
 * It allows Slider to be used as preference.
 */
open class IntValueSliderPreference : BaseIntValueSliderPreference {
	constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(
			context, attrs,
			defStyleAttr, defStyleRes
	) {
		initAttributes(context, attrs)
	}

	constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
			context,
			attrs,
			defStyleAttr
	) {
		initAttributes(context, attrs)
	}

	constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
		initAttributes(context, attrs)
	}

	constructor(context: Context) : super(context)

	protected var textViewString = "%d"

	private fun initAttributes(context: Context, attrs: AttributeSet) {
		context.obtainStyledAttributes(attrs, R.styleable.IntValueSliderPreference)
				.use { attributes ->
					attributes.getString(R.styleable.IntValueSliderPreference_stringFormat)?.let {
						textViewString = it
					}
				}
	}

	/**
	 * Sets string format.
	 * Value is not updated in view if ViewHolder was already bound.
	 */
	fun setStringFormat(format: String) {
		textViewString = format
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		this.slider?.let { slider ->
			slider.setLabelFormatter { textViewString.format(it) }
		}
	}
}

