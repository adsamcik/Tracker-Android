package com.adsamcik.tracker.preference

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.adsamcik.slider.implementations.IntValueSlider
import com.adsamcik.tracker.R

/**
 * Custom Preference implementation of the IntValueSlider from Slider library.
 * It allows Slider to be used as preference.
 */
class IntValueSliderPreference : Preference {
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
		val attributes = context.obtainStyledAttributes(attrs, R.styleable.IntValueSliderPreference)
		mValuesResource = attributes.getResourceId(R.styleable.IntValueSliderPreference_items, 0)

		val stringFormat = attributes.getString(R.styleable.IntValueSliderPreference_stringFormat)
		if (stringFormat != null) {
			mTextViewString = stringFormat
		}

		attributes.recycle()
	}

	private var mTextViewString = "%d"

	@ArrayRes
	private var mValuesResource: Int = 0
	private var mInitialValue: Int = 0

	var slider: IntValueSlider? = null

	init {
		layoutResource = R.layout.layout_settings_int_slider
	}

	fun setStringFormat(format: String) {
		mTextViewString = format
	}

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
		val textView = holder.findViewById(R.id.slider_value) as TextView

		slider.setItems(context.resources.getIntArray(mValuesResource).toTypedArray())
		//slider.setPadding(8.dpAsPx)
		slider.setTextView(textView) { String.format(mTextViewString, it) }

		slider.setPreferences(sharedPreferences, key, mInitialValue)

		slider.setOnValueChangeListener { value, fromUser ->
			if (fromUser) onPreferenceChangeListener?.onPreferenceChange(this, value)
		}

		this.slider = slider
	}
}

