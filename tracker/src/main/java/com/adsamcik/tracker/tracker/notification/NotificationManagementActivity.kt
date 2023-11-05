package com.adsamcik.tracker.tracker.notification

import android.os.Bundle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.DOWN
import androidx.recyclerview.widget.ItemTouchHelper.END
import androidx.recyclerview.widget.ItemTouchHelper.START
import androidx.recyclerview.widget.ItemTouchHelper.UP
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.base.database.PreferenceDatabase
import com.adsamcik.tracker.shared.base.database.dao.NotificationPreferenceDao
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.shared.utils.activity.ManageActivity
import com.adsamcik.tracker.tracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notification management activity that allows user to customize notification content.
 */
class NotificationManagementActivity : ManageActivity(), OnStartDragListener {
	private lateinit var dao: NotificationPreferenceDao

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
					val from = viewHolder.absoluteAdapterPosition
					val to = target.absoluteAdapterPosition
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
		dao = PreferenceDatabase.database(this).getNotificationDao()

		launch(Dispatchers.Default) {
			TrackerNotificationProvider.updatePreferences(this@NotificationManagementActivity)
			val activeComponentList = TrackerNotificationProvider.internalActiveList

			val collection = activeComponentList.sortedBy { it.preference.order }
			collection.forEachIndexed { index, trackerNotificationComponent ->
				trackerNotificationComponent.preference = trackerNotificationComponent.preference.copy(
						order = index
				)
			}
			launch(Dispatchers.Main) { adapter.addAll(collection) }
		}

		touchHelper.attachToRecyclerView(recyclerView)
	}

	override fun onDataConfirmed(tag: String?, dataCollection: List<EditDataInstance>) {
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
						R.string.hint_customize_notification_show_in_title
				),
				EditData(
						SHOW_IN_CONTENT,
						EditType.Checkbox,
						R.string.hint_customize_notification_show_in_content
				)
		)
	}

	override fun onManageConfigure(configuration: ManageConfiguration) {
		configuration.isAddEnabled = false
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setTitle(R.string.settings_notification_customize_title)
	}

	companion object {
		private const val SHOW_IN_TITLE = "showInTitle"
		private const val SHOW_IN_CONTENT = "showInContent"
	}
}
