package com.adsamcik.tracker.preference.sliders

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.utils.extension.formatDistance

/**
 * Custom Preference implementation of the IntValueSlider from Slider library.
 * It allows Slider to be used as preference.
 */
class DistanceValueSliderPreference : BaseIntValueSliderPreference {
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

	private var lengthSystem: LengthSystem = LengthSystem.Metric

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)

		slider?.let { slider ->
			lengthSystem = Preferences.getLengthSystem(context)

			slider.setLabelFormatter {
				context.resources.formatDistance(it, 0, lengthSystem)
			}
		}
	}
}

