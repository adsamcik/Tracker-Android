package com.adsamcik.tracker.statistics.detail

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import com.adsamcik.tracker.common.data.MutableTrackerSession
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class SessionActivitySelection(
		private val context: Context,
		private val activityList: List<SessionActivity>,
		private val session: TrackerSession
) : CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job


	@MainThread
	private fun createActivitySelectionDialog(): MaterialDialog {
		return MaterialDialog(context).apply {
			listItems(
					items = activityList.map { it.name },
					//initialSelection = selectedIndex,
					selection = this@SessionActivitySelection::onSelected
			)
		}
	}

	@AnyThread
	fun showActivitySelectionDialog() {
		launch {
			createActivitySelectionDialog().show()
		}
	}

	private fun onSelected(dialog: MaterialDialog, index: Int, title: CharSequence) {
		val session = MutableTrackerSession(session)
		session.sessionActivityId = activityList[index].id
		launch(Dispatchers.Default) {
			AppDatabase.database(context).sessionDao().update(session)
		}
	}
}

