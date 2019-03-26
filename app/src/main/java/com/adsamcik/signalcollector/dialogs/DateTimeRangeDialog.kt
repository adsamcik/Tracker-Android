package com.adsamcik.signalcollector.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialogFragment
import com.appeaser.sublimepickerlibrary.SublimePicker
import com.appeaser.sublimepickerlibrary.datepicker.SelectedDate
import com.appeaser.sublimepickerlibrary.helpers.SublimeListenerAdapter
import com.appeaser.sublimepickerlibrary.helpers.SublimeOptions
import com.appeaser.sublimepickerlibrary.recurrencepicker.SublimeRecurrencePicker
import java.util.*

class DateTimeRangeDialog : AppCompatDialogFragment() {
	var from: Date
		private set

	var to: Date
		private set


	var successCallback: ((from: Date, to: Date) -> Unit)? = null

	var clearCallback: (() -> Unit)? = null

	init {
		val cal = Calendar.getInstance()
		from = cal.time
		cal.add(Calendar.MONTH, -1)
		to = cal.time
	}


	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val mListener = object : SublimeListenerAdapter() {

			override fun onCancelled() {
				// Handle click on `Cancel` button
			}

			override fun onDateTimeRecurrenceSet(sublimeMaterialPicker: SublimePicker?, selectedDate: SelectedDate?, hourOfDay: Int, minute: Int, recurrenceOption: SublimeRecurrencePicker.RecurrenceOption?, recurrenceRule: String?) {

				recurrenceRule?.let {
					// Do something with recurrenceRule
				}

				recurrenceOption?.let {
					// Do something with recurrenceOption
					// Call to recurrenceOption.toString() to get recurrenceOption as a String
				}
			}
		}

		val sublimePicker = SublimePicker(context)
		val sublimeOptions = SublimeOptions() // This is optional
		sublimeOptions.pickerToShow = SublimeOptions.Picker.DATE_PICKER // I want the recurrence picker to show.
		sublimeOptions.setDisplayOptions(SublimeOptions.ACTIVATE_DATE_PICKER.or(SublimeOptions.ACTIVATE_TIME_PICKER)) // I only want the recurrence picker, not the date/time pickers.
		sublimePicker.initializePicker(sublimeOptions, mListener)
		return sublimePicker
	}
}