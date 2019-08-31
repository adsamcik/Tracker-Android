package com.adsamcik.tracker.common.dialog

import androidx.appcompat.widget.LinearLayoutCompat
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.savvi.rangedatepicker.CalendarPickerView
import java.util.*

fun MaterialDialog.dateTimeRangePicker(
		allowedRange: LongRange,
		selectedRange: LongRange,
		successCallback: (range: LongRange) -> Unit
): MaterialDialog {
	require(allowedRange.last > allowedRange.first)

	val minDate = Date(allowedRange.first)
	val maxDate = Date(allowedRange.last + Time.DAY_IN_MILLISECONDS)

	val limits = LongRange(minDate.time, maxDate.time)

	customView(R.layout.layout_date_range_picker)
	val layout = getCustomView() as LinearLayoutCompat
	val chipGroup = layout.findViewById<ChipGroup>(R.id.range_picker_preset_chip_group)

	val calendarPicker = CalendarPickerView(context, null).apply {
		init(minDate, maxDate)
				.inMode(CalendarPickerView.SelectionMode.RANGE)
				.withSelectedDates(
						listOf(
								Date(selectedRange.first.coerceIn(limits)),
								Date(selectedRange.last.coerceIn(limits))
						)
				)

		tag = TAG_DATE_TIME_RANGE
	}

	fun select(dayAge: Int, calendarPickerView: CalendarPickerView) {
		calendarPickerView.clearSelectedDates()
		val now = Time.nowMillis
		calendarPickerView.selectDate(Date((now - Time.DAY_IN_MILLISECONDS * dayAge).coerceIn(limits)))
		calendarPickerView.selectDate(Date(allowedRange.last.coerceIn(limits)))
	}

	val difference = maxDate.time - minDate.time
	val maxAgeInDays = (difference / Time.DAY_IN_MILLISECONDS).toInt()
	val list = listOf(1, 7, 14, 30, 90, 180, 365).filter { it <= maxAgeInDays }
	val resources = context.resources

	fun addChip(chipGroup: ChipGroup, value: Int, chipString: String) {
		val chip = Chip(context, null, R.style.Widget_MaterialComponents_Chip_Choice).apply {
			text = chipString
			isClickable = true
			checkedIcon = null
			tag = value
			setOnClickListener { select(it.tag as Int, calendarPicker) }
		}
		chipGroup.addView(chip)
	}

	list.forEach {
		addChip(
				chipGroup,
				it,
				resources.getQuantityString(R.plurals.map_date_range_last_days, it, it)
		)
	}

	addChip(chipGroup, maxAgeInDays, resources.getString(R.string.map_date_range_all))

	positiveButton {
		val first = calendarPicker.selectedDates.first()
		val last = calendarPicker.selectedDates.last()
		successCallback.invoke(LongRange(first.time, last.time))
	}

	negativeButton { }

	setActionButtonEnabled(WhichButton.POSITIVE, true)
	setActionButtonEnabled(WhichButton.NEGATIVE, true)
	setCancelable(true)

	layout.addView(calendarPicker)

	return this
}

const val TAG_DATE_TIME_RANGE: String = "datepicker"

