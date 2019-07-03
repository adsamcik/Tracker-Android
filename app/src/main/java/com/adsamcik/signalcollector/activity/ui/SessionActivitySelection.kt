package com.adsamcik.signalcollector.activity.ui

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import com.adsamcik.signalcollector.common.data.SessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.tracker.data.session.MutableTrackerSession
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class SessionActivitySelection(private val context: Context,
                               private val activityList: List<SessionActivity>,
                               private val session: TrackerSession) : CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job


	@MainThread
	private fun createActivitySelectionDialog(): MaterialDialog {
		return MaterialDialog(context).apply {
			val selectedId = session.sessionActivityId

			/*val selectedIndex =
					if (selectedId != null) {
						activityList.indexOfFirst { it.id == selectedId }
					} else {
						-1
					}*/

			listItems(items = activityList.map { it.name },
					//initialSelection = selectedIndex,
					selection = this@SessionActivitySelection::onSelected)
		}
	}

	@AnyThread
	fun showActivitySelectionDialog() {
		launch {
			createActivitySelectionDialog().show()
		}
	}

	private fun onSelected(dialog: MaterialDialog, index: Int, title: String) {
		val session = MutableTrackerSession(session)
		session.sessionActivityId = activityList[index].id
		launch(Dispatchers.Default) {
			AppDatabase.getDatabase(context).sessionDao().update(session)
		}
	}
}