package com.adsamcik.tracker.tracker.notification

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.DOWN
import androidx.recyclerview.widget.ItemTouchHelper.END
import androidx.recyclerview.widget.ItemTouchHelper.START
import androidx.recyclerview.widget.ItemTouchHelper.UP
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.common.activity.ManageActivity
import com.adsamcik.tracker.common.database.PreferenceDatabase
import com.adsamcik.tracker.common.database.dao.NotificationPreferenceDao
import com.adsamcik.tracker.tracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationManagementActivity : ManageActivity() {
	private val adapter = NotificationRecyclerAdapter()

	private val simpleItemTouchCallback =
			object : ItemTouchHelper.SimpleCallback(
					UP or DOWN or START or END,
					0
			) {

				override fun onMove(
						recyclerView: RecyclerView,
						viewHolder: RecyclerView.ViewHolder,
						target: RecyclerView.ViewHolder
				): Boolean {

					val adapter = recyclerView.adapter as NotificationRecyclerAdapter
					val from = viewHolder.adapterPosition
					val to = target.adapterPosition
					adapter.move(from, to)

					return true
				}

				override fun onSwiped(
						viewHolder: RecyclerView.ViewHolder,
						direction: Int
				) {
					// 4. Code block for horizontal swipe.
					//    ItemTouchHelper handles horizontal swipe as well, but
					//    it is not relevant with reordering. Ignoring here.
				}
			}

	override fun getAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder> {
		@Suppress("unchecked_cast")
		return adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
	}

	override fun onCreateRecycler(recyclerView: RecyclerView) {
		dao = PreferenceDatabase.database(this).notificationDao

		launch(Dispatchers.Default) {
			val notifications = dao.getAll()
			adapter.addAll(notifications.map { NotificationPreferenceInstance(it, 0) })
		}

		ItemTouchHelper(simpleItemTouchCallback).attachToRecyclerView(recyclerView)
	}

	override fun onDataSave(tag: String?, dataCollection: List<EditDataInstance>) {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun getEmptyEditData(): Collection<EditData> {
		return listOf(
				EditData(
						"showInTitle",
						EditType.Checkbox,
						R.string.hint_customizenoti_show_in_title
				),
				EditData(
						"showInContent",
						EditType.Checkbox,
						R.string.hint_customizenoti_show_in_content
				)
		)
	}

	private lateinit var dao: NotificationPreferenceDao
}
