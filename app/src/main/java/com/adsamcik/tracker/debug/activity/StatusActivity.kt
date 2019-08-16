package com.adsamcik.tracker.debug.activity

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.work.WorkManager
import com.adsamcik.tracker.common.activity.DetailActivity
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.tracker.locker.TrackerLocker

/**
 * Debug Activity used for displaying states of some parts of the app
 */
class StatusActivity : DetailActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val layout = createScrollableContentParent(true, androidx.constraintlayout.widget.ConstraintLayout::class.java)
		val workManager = WorkManager.getInstance(this)
		val waitForRechargeStates = workManager.getWorkInfosByTag(TrackerLocker.WORK_DISABLE_TILL_RECHARGE_TAG)
				.get()

		val isWaitingForRecharge = waitForRechargeStates != null && waitForRechargeStates.size > 0

		var lastId = createPair("Is time locked", TrackerLocker.isTimeLocked.toString(), layout, null)
		lastId = createPair("Is locked until recharge", TrackerLocker.isChargeLocked.toString(), layout, lastId)
		createPair("Has active wait for recharge job", isWaitingForRecharge.toString(), layout, lastId)
	}

	fun createPair(titleString: String, valueString: String, parent: ViewGroup, aboveId: Int?): Int {
		val title = TextView(this)
		val titleParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

		if (aboveId != null)
			titleParams.topToBottom = aboveId

		title.textSize = 16f
		title.layoutParams = titleParams
		title.text = titleString
		title.id = View.generateViewId()

		parent.addView(title)

		val value = TextView(this)
		val valueParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

		valueParams.leftToRight = title.id
		valueParams.topToTop = title.id
		valueParams.marginStart = 16.dp

		value.layoutParams = valueParams
		value.text = valueString
		value.textSize = 16f

		when (valueString) {
			"true" -> value.setTextColor(Color.GREEN)
			"false" -> value.setTextColor(Color.RED)
			else -> value.setTextColor(Color.BLUE)
		}

		parent.addView(value)


		return title.id
	}
}

