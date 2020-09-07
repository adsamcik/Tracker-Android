package com.adsamcik.tracker.preference.sliders

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.formatAsDuration

/**
 * Custom Preference implementation of the IntValueSlider from Slider library.
 * It allows Slider to be used as preference.
 */
class DurationValueSliderPreference : BaseIntValueSliderPreference {
	constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(
			context, attrs,
			defStyleAttr, defStyleRes
	)

	constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
			context,
			attrs,
			defStyleAttr
	)

	constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

	constructor(context: Context) : super(context)

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)

		slider?.let { slider ->
			slider.setLabelFormatter {
				val time = (it.toLong() * Time.SECOND_IN_MILLISECONDS)
				time.formatAsDuration(context)
			}
		}
	}
}

