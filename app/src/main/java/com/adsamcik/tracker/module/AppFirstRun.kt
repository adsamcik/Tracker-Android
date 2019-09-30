package com.adsamcik.tracker.module

import android.content.Context
import android.text.util.Linkify
import com.adsamcik.tracker.R
import com.adsamcik.tracker.common.module.FirstRun
import com.adsamcik.tracker.common.module.OnDoneListener
import com.afollestad.materialdialogs.callbacks.onDismiss

class AppFirstRun : FirstRun() {
	override fun onFirstRun(context: Context, onDoneListener: OnDoneListener) {
		createDialog(context) {
			title(res = R.string.first_run_app_title)
			message(res = R.string.first_run_app_description)
			positiveButton { errorReporting(context, onDoneListener) }
			negativeButton(res = R.string.skip_tips) {
				it.dismiss()
			}
		}
	}

	private fun errorReporting(context: Context, onDoneListener: OnDoneListener) {
		createDialog(context) {
			title(res = R.string.first_run_error_reporting_title)
			message(res = R.string.first_run_error_reporting_description) {
				messageTextView.apply {
					autoLinkMask = Linkify.WEB_URLS
					linksClickable = true
				}
			}
			positiveButton { }
			negativeButton { }

			onDismiss { onDoneListener(context) }
		}
	}
}
