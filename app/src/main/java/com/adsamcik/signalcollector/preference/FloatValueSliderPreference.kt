package com.adsamcik.signalcollector.preference

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.TextView
import androidx.annotation.IntegerRes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.widget.FloatValueSlider

/**
 * Custom Preference implementation of the FloatValueSlider from Slider library.
 * It allows Slider to be used as preference.
 */
class FloatValueSliderPreference : Preference {
	constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
		initAttributes(context, attrs)
	}

	constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
		initAttributes(context, attrs)
	}

	constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
		initAttributes(context, attrs)
	}

	constructor(context: Context) : super(context)

	private fun initAttributes(context: Context, attrs: AttributeSet) {
		val attributes = context.obtainStyledAttributes(attrs, R.styleable.FloatValueSliderPreference)
		mValuesResource = attributes.getResourceId(R.styleable.FloatValueSliderPreference_items, 0)
		if (attributes.hasValue(R.styleable.FloatValueSliderPreference_stringFormat))
			mTextViewString = attributes.getString(R.styleable.FloatValueSliderPreference_stringFormat)!!

		attributes.recycle()
	}

	private var mTextViewString = "%.2f"
	@IntegerRes
	private var mValuesResource: Int? = null
	private var mInitialValue: Float = 0f

	var slider: FloatValueSlider? = null

	init {
		layoutResource = R.layout.layout_settings_float_slider
	}

	override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
		return a.getString(index)!!.toFloat()
	}

	override fun onSetInitialValue(defaultValue: Any?) {
		if (defaultValue != null)
			mInitialValue = defaultValue as Float
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val slider = holder.findViewById(R.id.slider) as FloatValueSlider
		val textView = holder.findViewById(R.id.slider_value) as TextView

		val stringArray = context.resources.getStringArray(R.array.settings_map_quality_values)

		slider.setItems(stringArray.map { it.toFloat() }.toTypedArray())
		//slider.setPadding(8.dpAsPx)
		slider.setTextView(textView) { mTextViewString.format(it) }

		slider.setPreferences(sharedPreferences, key, mInitialValue)

		slider.setOnValueChangeListener { value, fromUser -> if (fromUser) onPreferenceChangeListener?.onPreferenceChange(this, value) }

		this.slider = slider
	}
}