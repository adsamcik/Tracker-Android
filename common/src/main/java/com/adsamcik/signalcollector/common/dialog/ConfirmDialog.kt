package com.adsamcik.signalcollector.common.dialog

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.adsamcik.signalcollector.common.R

object ConfirmDialog {
	fun create(context: Context, action: String, onConfirmed: () -> Unit): AlertDialog {
		val resources = context.resources
		return AlertDialog.Builder(context)
				.setPositiveButton(resources.getText(R.string.yes)) { _, _ -> onConfirmed.invoke() }
				.setNegativeButton(resources.getText(R.string.no)) { _, _ -> }
				.setMessage(resources.getString(R.string.alert_confirm, action.toLowerCase()))
				.show()
	}
}