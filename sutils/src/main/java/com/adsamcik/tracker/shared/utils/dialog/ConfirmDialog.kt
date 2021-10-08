package com.adsamcik.tracker.shared.utils.dialog

import com.afollestad.materialdialogs.DialogCallback
import com.afollestad.materialdialogs.MaterialDialog

/**
 * Creates alert dialog with yes or no answers.
 */
fun MaterialDialog.alertDialog(action: String, onConfirmed: DialogCallback): MaterialDialog {
	positiveButton(com.adsamcik.tracker.shared.base.R.string.generic_yes, click = onConfirmed)
	negativeButton(com.adsamcik.tracker.shared.base.R.string.generic_no)
	message(
			text = context.getString(
					com.adsamcik.tracker.shared.base.R.string.alert_confirm,
					action
			)
	)
	return this
}
