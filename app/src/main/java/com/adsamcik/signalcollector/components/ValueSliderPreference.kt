package com.adsamcik.signalcollector.components

import android.content.Context
import android.support.v7.preference.Preference
import android.support.v7.preference.PreferenceViewHolder
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.TextView
import androidx.view.setPadding
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.slider.implementations.StringValueSlider


class ValueSliderPreference : Preference {
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?) : super(context)

    var slider: StringValueSlider? = null

    init {
        layoutResource = R.layout.layout_settings_string_slider
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val slider = holder.findViewById(R.id.slider) as StringValueSlider

        val textView = holder.findViewById(R.id.title) as TextView

        slider.setItems(arrayOf("First", "Second", "Third"))
        slider.setPadding(Assist.dpToPx(context, 8))
        slider.setTextView(textView) { it }

        this.slider = slider
    }
}