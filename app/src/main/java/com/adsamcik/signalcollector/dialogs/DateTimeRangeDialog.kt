package com.adsamcik.signalcollector.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.setPadding
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.extensions.date
import com.adsamcik.signalcollector.extensions.dpAsPx
import com.adsamcik.signalcollector.extensions.toCalendar
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.appeaser.sublimepickerlibrary.SublimePicker
import com.appeaser.sublimepickerlibrary.datepicker.SelectedDate
import com.appeaser.sublimepickerlibrary.helpers.SublimeListenerAdapter
import com.appeaser.sublimepickerlibrary.helpers.SublimeOptions
import com.appeaser.sublimepickerlibrary.recurrencepicker.SublimeRecurrencePicker
import java.util.*

class DateTimeRangeDialog : AppCompatDialogFragment() {
	var range: ClosedRange<Date>? = null

	private lateinit var colorManager: ColorManager


	var successCallback: ((range: ClosedRange<Date>) -> Unit)? = null

	var clearCallback: (() -> Unit)? = null


	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val context = context!!
		colorManager = ColorSupervisor.createColorManager(context)

		val linearLayout = LinearLayout(context)
		linearLayout.orientation = LinearLayout.VERTICAL

		val text = AppCompatTextView(context)
		text.text = getString(R.string.tips_map_date_long_press_range_selection)
		text.width = MATCH_PARENT
		text.height = 56.dpAsPx
		text.gravity = Gravity.CENTER
		text.setPadding(8.dpAsPx)
		linearLayout.addView(text)

		val mListener = object : SublimeListenerAdapter() {

			override fun onCancelled() {
				// Handle click on `Cancel` button
				this@DateTimeRangeDialog.dismiss()
			}

			override fun onDateTimeRecurrenceSet(sublimeMaterialPicker: SublimePicker?, selectedDate: SelectedDate?, hourOfDay: Int, minute: Int, recurrenceOption: SublimeRecurrencePicker.RecurrenceOption?, recurrenceRule: String?) {

				selectedDate?.let {
					val range: ClosedRange<Date> =
							it.startDate.date().time..it.endDate.date().time
					this@DateTimeRangeDialog.range = range
					successCallback?.invoke(range)
				}

				this@DateTimeRangeDialog.dismiss()
			}
		}

		val sublimePicker = SublimePicker(context)


		val sublimeOptions = SublimeOptions() // This is optional
		sublimeOptions.pickerToShow = SublimeOptions.Picker.DATE_PICKER // I want the recurrence picker to show.
		sublimeOptions.setDisplayOptions(SublimeOptions.ACTIVATE_DATE_PICKER) // I only want the recurrence picker, not the date/time pickers.
		val range = range
		if (range != null) {
			if (range.start == range.endInclusive)
				sublimeOptions.setDateParams(range.start.toCalendar())
			else
				sublimeOptions.setDateParams(range.start.toCalendar(), range.endInclusive.toCalendar())
		}
		sublimeOptions.setCanPickDateRange(true)
		sublimePicker.initializePicker(sublimeOptions, mListener)

		linearLayout.addView(sublimePicker)

		colorManager.watchView(ColorView(sublimePicker.findViewById(R.id.date_picker_header), 0, true))
		colorManager.watchView(ColorView(text, 0, false))

		return linearLayout
	}
}