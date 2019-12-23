package com.adsamcik.tracker.shared.utils.dialog

import android.graphics.Color
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.adsamcik.tracker.common.extension.observe
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker

fun FragmentActivity.createDateTimeDialog(
		styleController: StyleController,
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
			.setTheme(com.adsamcik.tracker.common.R.style.CalendarPicker)
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
							val styleView = StyleView(view, 2)
							view.setBackgroundColor(Color.WHITE)
							styleController.watchView(styleView)
							listener = { styleController.updateOnce(styleView, true) }
									.also { listener ->
										view.viewTreeObserver.addOnGlobalLayoutListener(listener)
									}

						}
						Lifecycle.State.DESTROYED -> {
							val view = requireView()
							styleController.stopWatchingView(view)
							listener?.let { listener ->
								view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
							}
						}
						else -> return@observe
					}
				}
			}.show(supportFragmentManager, "picker")
}
