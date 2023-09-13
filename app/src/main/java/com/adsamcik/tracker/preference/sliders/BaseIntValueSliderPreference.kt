package com.adsamcik.tracker.preference.sliders

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import androidx.annotation.ArrayRes
import androidx.core.content.res.use
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.adsamcik.slider.abstracts.Slider
import com.adsamcik.slider.abstracts.SliderExtension
import com.adsamcik.slider.extensions.IntSliderSharedPreferencesExtension
import com.adsamcik.slider.implementations.IntValueSlider
import com.adsamcik.tracker.R

/**
 * Custom Preference implementation of the IntValueSlider from Slider library.
 * It allows Slider to be used as preference.
 */
open class BaseIntValueSliderPreference : Preference {
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
		context.obtainStyledAttributes(
				attrs,
				R.styleable.BaseIntValueSliderPreference
		).use {
			valuesResource = it.getResourceId(R.styleable.BaseIntValueSliderPreference_items, 0)
		}
	}

	@ArrayRes
	var valuesResource: Int = 0
	private var initialValue: Int = 0

	var slider: IntValueSlider? = null
		private set

	init {
		layoutResource = R.layout.layout_settings_int_slider
	}

	override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
		val valueString = a.getString(index)
		return valueString?.toInt() ?: 0
	}

	override fun onSetInitialValue(defaultValue: Any?) {
		if (defaultValue != null) {
			initialValue = defaultValue as Int
		}
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val slider = holder.findViewById(R.id.slider) as IntValueSlider

		slider.background = ColorDrawable(Color.TRANSPARENT)

		slider.setItems(context.resources.getIntArray(valuesResource).toTypedArray())

		this.summary?.let { summary -> slider.description = summary }

		slider.addExtension(
				IntSliderSharedPreferencesExtension(
						sharedPreferences!!,
						key,
						initialValue
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
							this@BaseIntValueSliderPreference,
							value
					)
				}
			}
		})

		this.slider = slider
	}
}

