package com.adsamcik.signalcollector.app.dialog

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
import com.adsamcik.signalcollector.common.extension.dp
import com.adsamcik.signalcollector.common.style.StyleController
import com.adsamcik.signalcollector.common.style.StyleManager
import com.adsamcik.signalcollector.common.style.StyleView
import com.appeaser.sublimepickerlibrary.SublimePicker
import com.appeaser.sublimepickerlibrary.datepicker.SelectedDate
import com.appeaser.sublimepickerlibrary.helpers.SublimeListenerAdapter
import com.appeaser.sublimepickerlibrary.recurrencepicker.SublimeRecurrencePicker
import java.util.*

class DateTimeRangeDialog : AppCompatDialogFragment() {
	private lateinit var styleController: StyleController


	var successCallback: ((range: ClosedRange<Calendar>) -> Unit)? = null

	var clearCallback: (() -> Unit)? = null

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val context = requireContext()
		styleController = StyleManager.createController()

		val linearLayout = LinearLayout(context)
		linearLayout.orientation = LinearLayout.VERTICAL

		val text = AppCompatTextView(context)
		text.text = getString(R.string.date_long_press_range_selection)
		text.width = MATCH_PARENT
		text.height = 56.dp
		text.gravity = Gravity.CENTER
		text.setPadding(8.dp)
		linearLayout.addView(text)

		val mListener = object : SublimeListenerAdapter() {

			override fun onCancelled() {
				// Handle click on `Cancel` button
				this@DateTimeRangeDialog.dismiss()
			}

			override fun onDateTimeRecurrenceSet(sublimeMaterialPicker: SublimePicker?, selectedDate: SelectedDate?, hourOfDay: Int, minute: Int, recurrenceOption: SublimeRecurrencePicker.RecurrenceOption?, recurrenceRule: String?) {

				selectedDate?.let {
					successCallback?.invoke(it.startDate..it.endDate)
				}

				this@DateTimeRangeDialog.dismiss()
			}
		}

		val sublimePicker = SublimePicker(context)


		val args = arguments!!

		if (!args.containsKey(ARG_OPTIONS))
			throw IllegalArgumentException("Arguments must contain `$ARG_OPTIONS`")

		sublimePicker.initializePicker(args.getParcelable(ARG_OPTIONS), mListener)

		linearLayout.addView(sublimePicker)

		styleController.watchView(StyleView(sublimePicker.findViewById(R.id.date_picker_header), 0))
		styleController.watchView(StyleView(text, 0, 0))

		return linearLayout
	}

	companion object {
		const val ARG_OPTIONS: String = "options"
	}
}