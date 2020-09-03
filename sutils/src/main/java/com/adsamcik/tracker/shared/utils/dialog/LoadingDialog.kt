package com.adsamcik.tracker.shared.utils.dialog

import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.core.view.children
import androidx.core.view.setPadding
import com.adsamcik.tracker.shared.base.extension.dp
import com.afollestad.materialdialogs.MaterialDialog

/**
 * Adds indeterminate loading.
 */
// It cannot be made into constant
@Suppress("MagicNumber")
fun MaterialDialog.setLoading() {
	val progressBar = ProgressBar(context).apply {
		isIndeterminate = true
		setPadding(48.dp)
	}
	view.contentLayout.addView(
			progressBar,
			ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT
			)
	)
}

/**
 * Removes ProgressBar from dialog.
 */
fun MaterialDialog.setLoadingFinished() {
	var progressBarIndex = -1
	view.contentLayout.children.iterator().withIndex().forEach {
		if (it.value is ProgressBar) {
			progressBarIndex = it.index
			return@forEach
		}
	}

	if (progressBarIndex >= 0) {
		view.contentLayout.removeViewAt(progressBarIndex)
	}
}
