package com.adsamcik.tracker.preference

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.preference.Preference
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.debug.assertEqual
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import java.util.*

open class DialogListPreference : Preference {
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

	protected val valueList: MutableList<String> = mutableListOf()
	protected val keyList: MutableList<String> = mutableListOf()

	protected var summaryText: String = ""
	protected var selectedValueIndex: Int = -1


	private fun initAttributes(context: Context, attrs: AttributeSet) {
		val attributes = context.obtainStyledAttributes(attrs, R.styleable.DialogListPreference)
		val titleResources = attributes
				.getTextArray(R.styleable.DialogListPreference_titles)
				?.map { it.toString() }
		if (titleResources != null) {
			valueList.addAll(titleResources)
		}

		val keyResources = attributes
				.getTextArray(R.styleable.DialogListPreference_keys)
				?.map { it.toString() }
		if (keyResources != null) {
			keyList.addAll(keyResources)
		}

		attributes.recycle()
		summaryText = summary.toString()
	}

	open fun setValues(list: List<String>, keys: List<String>) {
		assertEqual(list.size, keys.size)
		valueList.clear()
		this.keyList.clear()

		valueList.addAll(list)
		this.keyList.addAll(keys)
	}

	override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
		return a.getString(index) ?: ""
	}

	override fun onSetInitialValue(defaultValue: Any?) {
		if (keyList.isEmpty()) return

		val value = getPersistedString(keyList.first())
		val index = keyList.indexOf(value)
		setIndex(if (index >= 0) index else 0)
	}

	/**
	 * Set currently selected index.
	 */
	open fun setIndex(index: Int) {
		if (selectedValueIndex != index && index in 0..valueList.size) {
			selectedValueIndex = index
			val key = keyList[index]
			persistString(key)
			summary = String.format(Locale.getDefault(), summaryText, valueList[index])
			notifyChanged()
			notifyValueChanged(key)
		}
	}

	protected fun notifyValueChanged(newKey: String) {
		onPreferenceChangeListener?.onPreferenceChange(this, newKey)
	}

	override fun onClick() {
		MaterialDialog(context).show {
			listItemsSingleChoice(
					items = valueList,
					initialSelection = selectedValueIndex
			) { _, index, _ ->
				setIndex(index)
			}
			dynamicStyle()
		}
	}
}
