package com.adsamcik.tracker.preference.component

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import com.adsamcik.tracker.shared.utils.debug.assertEqual
import java.util.*

class IndicesDialogListPreference : DialogListPreference {
	@Suppress("unused")
	constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(
			context, attrs,
			defStyleAttr, defStyleRes
	) {
		initAttributes(context, attrs)
	}

	@Suppress("unused")
	constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
			context,
			attrs,
			defStyleAttr
	) {
		initAttributes(context, attrs)
	}

	@Suppress("unused")
	constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
		initAttributes(context, attrs)
	}

	@Suppress("unused")
	constructor(context: Context) : super(context)

	private val indices: MutableList<Int> = mutableListOf()


	private fun initAttributes(context: Context, attrs: AttributeSet) {
		if (keyList.isNotEmpty()) {
			indices.addAll(keyList.map { it.toInt() })
		} else {
			indices.addAll(valueList.indices)
		}
	}

	override fun setValues(list: List<String>, keys: List<String>) {
		throw UnsupportedOperationException("Call setValuesIndices instead.")
	}

	fun setValuesIndices(list: List<String>, indices: List<Int>) {
		super.setValues(list, indices.map { it.toString() })
		assertEqual(list.size, indices.size)

		this.indices.clear()
		this.indices.addAll(indices)
	}

	override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
		return a.getInt(index, 0)
	}

	override fun onSetInitialValue(defaultValue: Any?) {
		if (valueList.isEmpty()) return

		val defaultIndex = getPersistedInt(0)
		setIndex(defaultIndex)
	}


	override fun setIndex(index: Int) {
		if (selectedValueIndex != index && index in 0..valueList.size) {
			selectedValueIndex = index
			val key = indices[index]
			persistInt(key)
			summary = String.format(Locale.getDefault(), summaryText, valueList[index])
			notifyChanged()
			notifyValueChanged(key)
		}
	}

	protected fun notifyValueChanged(newKey: Int) {
		onPreferenceChangeListener?.onPreferenceChange(this, newKey)
	}
}
