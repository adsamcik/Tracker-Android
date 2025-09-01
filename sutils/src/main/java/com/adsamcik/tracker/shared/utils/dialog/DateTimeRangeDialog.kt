package com.adsamcik.tracker.shared.utils.dialog

import android.graphics.Color
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker

/**
 * Creates time DateTime selection dialog.
 */
fun FragmentActivity.createDateTimeDialog(
		allowedRange: LongRange,
		selectedRange: LongRange,
		successCallback: (range: LongRange) -> Unit
) {

	val constraints = CalendarConstraints.Builder()
			.setStart(allowedRange.first)
			.setEnd(allowedRange.last)
			.build()

	MaterialDatePicker.Builder.dateRangePicker()
			.setCalendarConstraints(constraints)
			.setTheme(com.adsamcik.tracker.shared.base.R.style.CalendarPicker)
			.setSelection(androidx.core.util.Pair(selectedRange.first, selectedRange.last))
			.build().apply {
				addOnPositiveButtonClickListener {
					val from = it.first ?: selectedRange.first
					val to = it.second ?: from
					successCallback(from..to)
				}
				addOnDismissListener {}

				var listener: (() -> Unit)? = null

				viewLifecycleOwnerLiveData.observe(this) { owner: LifecycleOwner? ->
					when (owner?.lifecycle?.currentState) {
						Lifecycle.State.INITIALIZED -> {
							val view = requireView()
							view.setBackgroundColor(Color.WHITE)
							// Legacy dynamic styling removed

						}
						Lifecycle.State.DESTROYED -> {
							val view = requireView()
							listener?.let { listener ->
								view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
							}
						}
						else -> return@observe
					}
				}
			}.show(supportFragmentManager, "picker")
}
