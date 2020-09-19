package com.adsamcik.tracker.activity.ui

import android.os.Bundle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.activity.ui.recycler.ActivityRecyclerAdapter
import com.adsamcik.tracker.activity.ui.recycler.ContextualSwipeTouchHelper
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.utils.activity.ManageActivity
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Activity for session activities
 */
class SessionActivityActivity : ManageActivity() {
	private lateinit var swipeTouchHelper: ContextualSwipeTouchHelper

	private val adapter = ActivityRecyclerAdapter(this::onEdit)

	private fun onEdit(position: Int) {
		val data = adapter.getItem(position)
		val editList = generateEditDataList(data)
		edit(data.id.toString(), editList)
	}

	private fun generateEditDataList(sessionActivity: SessionActivity): Collection<EditDataInstance> {
		return listOf(EditDataInstance(NAME_FIELD, sessionActivity.name))
	}

	override fun onManageConfigure(configuration: ManageConfiguration) {
		configuration.isRecyclerMarginEnabled = false
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		launch(Dispatchers.Default) {
			val itemCollection = SessionActivity.getAll(this@SessionActivityActivity)

			launch(Dispatchers.Main) {
				adapter.addAll(itemCollection)
			}
		}

		setTitle(R.string.settings_activity_title)
	}

	override fun getAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder> {
		@Suppress("unchecked_cast")
		return adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
	}

	override fun onCreateRecycler(recyclerView: RecyclerView) {
		swipeTouchHelper = ContextualSwipeTouchHelper(this, adapter) { it.id >= 0 }.apply {
			onSwipedCallback = this@SessionActivityActivity::onItemSwipedCallback
		}
		ItemTouchHelper(swipeTouchHelper).attachToRecyclerView(recyclerView)
	}

	override fun onDataConfirmed(tag: String?, dataCollection: List<EditDataInstance>) {
		require(dataCollection.size == 1)
		require(dataCollection.first().id == NAME_FIELD)

		val name = dataCollection.first().value
		launch(Dispatchers.Default) {
			if (tag != null) {
				val id = tag.toLong()
				val item = requireNotNull(adapter.find { it.id == id })

				val newItem = SessionActivity(item.id, name, item.iconName)

				adapter.updateItemPersistent(this@SessionActivityActivity, newItem)
			} else {
				val newActivity = SessionActivity(0, name, null)

				adapter.addItemPersistent(
						this@SessionActivityActivity,
						newActivity
				)
			}
		}
	}

	override fun getEmptyEditData(): Collection<EditData> {
		return listOf(EditData(NAME_FIELD, EditType.EditText, R.string.activity_name, true))
	}

	override fun onConfigure(configuration: Configuration) {
		configuration.useColorControllerForContent = true
		configuration.titleBarLayer = 1
	}

	private fun onItemSwipedCallback(index: Int) {
		val context = this
		val item = adapter.getItem(index)
		adapter.removeAt(index)
		snackMaker.addMessage(
				SnackMaker.SnackbarRecipe(
						message = getString(R.string.settings_activity_snackbar_message, item.name),
						priority = SnackMaker.SnackbarPriority.IMPORTANT,
						action = getString(com.adsamcik.tracker.shared.base.R.string.undo),
						duration = LENGTH_LONG,
						onDismissed = {
							launch(Dispatchers.Default) {
								AppDatabase.database(context).activityDao().delete(item.id)
							}
						},
						onActionClick = {
							adapter.add(item)
						})
		)
	}

	override fun onDestroy() {
		super.onDestroy()
		swipeTouchHelper.onDestroy()
	}

	companion object {
		private const val NAME_FIELD = "name"
	}
}

