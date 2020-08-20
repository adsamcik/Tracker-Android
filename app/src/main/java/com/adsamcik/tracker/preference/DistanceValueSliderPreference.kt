package com.adsamcik.tracker.preference

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.annotation.ArrayRes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.adsamcik.slider.abstracts.Slider
import com.adsamcik.slider.abstracts.SliderExtension
import com.adsamcik.slider.extensions.IntSliderSharedPreferencesExtension
import com.adsamcik.slider.implementations.IntValueSlider
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.utils.extension.formatDistance

/**
 * Custom Preference implementation of the IntValueSlider from Slider library.
 * It allows Slider to be used as preference.
 */
class DistanceValueSliderPreference : Preference {
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

	private fun initAttributes(context: Context, attrs: AttributeSet) {
		val attributes = context.obtainStyledAttributes(
				attrs,
				R.styleable.DistanceValueSliderPreference
		)
		mValuesResource = attributes.getResourceId(
				R.styleable.DistanceValueSliderPreference_items,
				0
		)

		attributes.recycle()
	}


	@ArrayRes
	private var mValuesResource: Int = 0
	private var mInitialValue: Int = 0

	var slider: IntValueSlider? = null

	private var lengthSystem: LengthSystem = LengthSystem.Metric

	init {
		layoutResource = R.layout.layout_settings_int_slider
	}

	/**
	 * Sets value resource.
	 * Needs to be called before bound to ViewHolder. Realtime updating is not supported.
	 */
	fun setValuesResource(resource: Int) {
		mValuesResource = resource
	}

	override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
		val valueString = a.getString(index)
		return valueString?.toInt() ?: 0
	}

	override fun onSetInitialValue(defaultValue: Any?) {
		if (defaultValue != null) {
			mInitialValue = defaultValue as Int
		}
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val slider = holder.findViewById(R.id.slider) as IntValueSlider

		slider.setItems(context.resources.getIntArray(mValuesResource).toTypedArray())

		lengthSystem = Preferences.getLengthSystem(context)

		slider.setLabelFormatter {
			context.resources.formatDistance(it, 0, lengthSystem)
		}

		this.summary?.let { summary ->
			slider.description = summary
		}

		slider.addExtension(
				IntSliderSharedPreferencesExtension(
						sharedPreferences,
						key,
						mInitialValue
				)
		)

		slider.addExtension(object : SliderExtension<Int> {
			override fun onValueChanged(
					slider: Slider<Int>,
					value: Int,
					position: Float,
					isFromUser: Boolean
			) {
				if (isFromUser) {
					onPreferenceChangeListener?.onPreferenceChange(
							this@DistanceValueSliderPreference,
							value
					)
				}
			}
		})

		this.slider = slider
	}
}

