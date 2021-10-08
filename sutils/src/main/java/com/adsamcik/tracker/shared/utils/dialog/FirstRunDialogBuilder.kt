package com.adsamcik.tracker.shared.utils.dialog

import android.content.Context
import androidx.annotation.AnyThread
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.utils.module.FirstRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * First Run Dialog builder
 */
class FirstRunDialogBuilder : CoroutineScope {
	override val coroutineContext: CoroutineContext get() = Dispatchers.Main
	private val dialogDataList = mutableListOf<FirstRun>()

	private var isLocked = false

	private var currentIndex = -1

	var onFirstRunFinished: (() -> Unit)? = null

	/**
	 * Add dialog data.
	 */
	fun addData(data: FirstRun) {
		if (isLocked) {
			Reporter.report("Trying to add data after builder was locked.")
		} else {
			dialogDataList.add(data)
		}
	}

	/**
	 * Show dialog
	 */
	fun show(context: Context) {
		isLocked = true
		next(context, isCloseRequested = false)
	}

	@AnyThread
	private fun next(context: Context, isCloseRequested: Boolean) {
		launch {
			if (!isCloseRequested && ++currentIndex < dialogDataList.size) {
				dialogDataList[currentIndex].onFirstRun(context, this@FirstRunDialogBuilder::next)
			} else {
				onFirstRunFinished?.invoke()
			}
		}
	}
}
