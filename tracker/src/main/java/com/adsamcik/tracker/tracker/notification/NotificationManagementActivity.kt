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
import com.adsamcik.tracker.common.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationManagementActivity : ManageActivity(), OnStartDragListener {
	private val adapter = NotificationRecyclerAdapter(this, this::onEdit)

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
					adapter.moveItemPersistent(this@NotificationManagementActivity, from, to)

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

	private val touchHelper = ItemTouchHelper(simpleItemTouchCallback)

	override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
		touchHelper.startDrag(viewHolder)
	}

	private fun onEdit(position: Int) {
		val data = adapter.getItem(position).preference
		val editList = generateEditDataList(data)
		edit(data.id, editList)
	}

	private fun generateEditDataList(preference: NotificationPreference): Collection<EditDataInstance> {
		return listOf(
				EditDataInstance(SHOW_IN_TITLE, preference.isInTitle),
				EditDataInstance(SHOW_IN_CONTENT, preference.isInContent)
		)
	}

	override fun getAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder> {
		@Suppress("unchecked_cast")
		return adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
	}

	override fun onCreateRecycler(recyclerView: RecyclerView) {
		dao = PreferenceDatabase.database(this).notificationDao

		launch(Dispatchers.Default) {
			TrackerNotificationProvider.updatePreferences(this@NotificationManagementActivity)
			val activeComponentList = TrackerNotificationProvider.internalActiveList

			adapter.addAll(activeComponentList)
		}

		touchHelper.attachToRecyclerView(recyclerView)
	}

	override fun onDataSave(tag: String?, dataCollection: List<EditDataInstance>) {
		require(dataCollection.size == 2)
		require(tag != null)

		val isInTitle = requireNotNull(dataCollection.find { it.id == SHOW_IN_TITLE })
		val isInContent = requireNotNull(dataCollection.find { it.id == SHOW_IN_CONTENT })

		launch(Dispatchers.Default) {
			val id = tag.toString()
			val index = adapter.indexOf { it.id == id }
			require(index >= 0)
			val preference = NotificationPreference(
					id,
					index,
					isInTitle.value.toBoolean(),
					isInContent.value.toBoolean()
			)
			adapter.updateItemPersistent(this@NotificationManagementActivity, preference)
		}
	}

	override fun getEmptyEditData(): Collection<EditData> {
		return listOf(
				EditData(
						SHOW_IN_TITLE,
						EditType.Checkbox,
						R.string.hint_customizenoti_show_in_title
				),
				EditData(
						SHOW_IN_CONTENT,
						EditType.Checkbox,
						R.string.hint_customizenoti_show_in_content
				)
		)
	}

	private lateinit var dao: NotificationPreferenceDao

	companion object {
		private const val SHOW_IN_TITLE = "showInTitle"
		private const val SHOW_IN_CONTENT = "showInContent"
	}
}
