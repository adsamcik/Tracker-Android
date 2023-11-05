package com.adsamcik.tracker.preference.sliders

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import androidx.annotation.IntegerRes
import androidx.core.content.res.getStringOrThrow
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.adsamcik.slider.LabelFormatter
import com.adsamcik.slider.abstracts.Slider
import com.adsamcik.slider.abstracts.SliderExtension
import com.adsamcik.slider.extensions.FloatSliderSharedPreferencesExtension
import com.adsamcik.slider.implementations.FloatSlider
import com.adsamcik.tracker.R

/**
 * Custom Preference implementation of the FloatValueSlider from Slider library.
 * It allows Slider to be used as preference.
 */
class FloatSliderPreference : Preference {
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
				R.styleable.FloatValueSliderPreference
		)
		mValuesResource = attributes.getResourceId(R.styleable.FloatValueSliderPreference_items, 0)
		if (attributes.hasValue(R.styleable.FloatValueSliderPreference_stringFormat)) {
			mTextViewString = attributes.getStringOrThrow(R.styleable.FloatValueSliderPreference_stringFormat)
		}

		attributes.recycle()
	}

	private var mTextViewString = "%.2f"

	@IntegerRes
	private var mValuesResource: Int? = null
	var initialValue: Float = 0f

	var minValue: Float = 0f
	var maxValue: Float = 1f
	var step: Float = 0.1f
	var labelFormatter: LabelFormatter<Float> = { mTextViewString.format(it) }

	var slider: FloatSlider? = null

	/**
	 * Sets string format.
	 * Value is not updated in view if ViewHolder was already bound.
	 */
	fun setStringFormat(format: String) {
		mTextViewString = format
	}

	/**
	 * Sets value resource.
	 * Value is not updated in view if ViewHolder was already bound.
	 */
	fun setValuesResource(resource: Int) {
		mValuesResource = resource
	}

	init {
		layoutResource = R.layout.layout_settings_float_slider
	}

	override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
		return a.getStringOrThrow(index).toFloat()
	}

	override fun onSetInitialValue(defaultValue: Any?) {
		if (defaultValue != null) {
			initialValue = defaultValue as Float
		}
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val slider = holder.findViewById(R.id.slider) as FloatSlider

		slider.minValue = minValue
		slider.maxValue = maxValue
		slider.step = step
		slider.setLabelFormatter(labelFormatter)

		slider.background = ColorDrawable(Color.TRANSPARENT)

		this.summary?.let { summary -> slider.description = summary }

		slider.addExtension(
				FloatSliderSharedPreferencesExtension(
						sharedPreferences!!,
						key,
						initialValue
				)
		)

		slider.addExtension(object : SliderExtension<Float> {
			override fun onValueChanged(
					slider: Slider<Float>,
					value: Float,
					position: Float,
					isFromUser: Boolean
			) {
				if (isFromUser) {
					onPreferenceChangeListener?.onPreferenceChange(
							this@FloatSliderPreference,
							value
					)
				}
			}
		})

		this.slider = slider
	}
}

