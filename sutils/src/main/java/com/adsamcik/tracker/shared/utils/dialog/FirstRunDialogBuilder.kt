package com.adsamcik.tracker.shared.utils.dialog

import android.content.Context
import com.adsamcik.tracker.shared.utils.debug.Reporter
import com.adsamcik.tracker.shared.utils.module.FirstRun

/**
 * First Run Dialog builder
 */
class FirstRunDialogBuilder {
	private val dialogDataList = mutableListOf<FirstRun>()

	private var isLocked = false

	private var currentIndex = -1

	var onFirstRunFinished: (() -> Unit)? = null

	fun addData(data: FirstRun) {
		if (isLocked) {
			Reporter.report("Trying to add data after builder was locked.")
		} else {
			dialogDataList.add(data)
		}
	}

	fun show(context: Context) {
		isLocked = true
		next(context, isCloseRequested = false)
	}

	private fun next(context: Context, isCloseRequested: Boolean) {
		if (!isCloseRequested && ++currentIndex < dialogDataList.size) {
			dialogDataList[currentIndex].onFirstRun(context, this::next)
		} else {
			onFirstRunFinished?.invoke()
		}
	}
}
