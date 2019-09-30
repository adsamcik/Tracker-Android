package com.adsamcik.tracker.common.dialog

import android.content.Context
import com.adsamcik.tracker.common.debug.Reporter
import com.adsamcik.tracker.common.module.FirstRun
import com.afollestad.materialdialogs.MaterialDialog

class FirstRunDialogBuilder {
	private val dialogDataList = mutableListOf<FirstRun>()

	private var isLocked = false

	private var currentIndex = -1

	fun addData(data: FirstRun) {
		if (isLocked) {
			Reporter.report("Trying to add data after builder was locked.")
		} else {
			dialogDataList.add(data)
		}
	}

	fun show(context: Context) {
		isLocked = true
		next(context)
	}

	private fun next(context: Context) {
		if (++currentIndex < dialogDataList.size) {
			dialogDataList[currentIndex].onFirstRun(context, this::next)
		}
	}
}
