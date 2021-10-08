package com.adsamcik.tracker.tracker.ui.recycler.data

import android.text.format.DateUtils
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance

import com.adsamcik.tracker.tracker.R

class SessionTrackerInfo(var session: TrackerSession) : TrackerInfo(NAME_RESOURCE) {
	override val iconRes: Int
		get() {
			val ageInMinutes = (Time.nowMillis - session.start) / Time.MINUTE_IN_MILLISECONDS
			return when {
				ageInMinutes < 15 -> R.drawable.seed_outline
				ageInMinutes < 40 -> R.drawable.sprout_outline
				else -> R.drawable.tree_outline
			}
		}

	override fun bindContent(holder: InfoFieldHolder) {
		val context = holder.context
		val resources = holder.resources
		val lengthSystem = Preferences.getLengthSystem(context)

		holder.getBoldText().apply {
			value.text = DateUtils.getRelativeTimeSpanString(
					session.start,
					Time.nowMillis,
					MINUTE_IN_MILLIS
			)
			title.setText(R.string.tracker_session_age)
		}

		holder.getBoldText().apply {
			value.text = session.collections.toString()
			title.setText(R.string.tracker_collections_title)
		}

		holder.getBoldText().apply {
			value.text = resources.formatDistance(session.distanceInM, 1, lengthSystem)
			title.setText(R.string.tracker_distance_title)
		}
	}

	companion object {
		val NAME_RESOURCE: Int = R.string.tracker_session_card_title
	}
}
