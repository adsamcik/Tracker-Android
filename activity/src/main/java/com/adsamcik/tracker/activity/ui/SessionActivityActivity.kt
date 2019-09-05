package com.adsamcik.tracker.activity.ui

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.sortable.AppendPriority
import com.adsamcik.recycler.adapter.implementation.sortable.SortableAdapter
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.activity.ui.recycler.ActivityRecyclerAdapter
import com.adsamcik.tracker.activity.ui.recycler.ContextualSwipeTouchHelper
import com.adsamcik.tracker.common.activity.ManageActivity
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.misc.SnackMaker
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SessionActivityActivity : ManageActivity() {
	private lateinit var swipeTouchHelper: ContextualSwipeTouchHelper

	private val adapter = ActivityRecyclerAdapter()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		launch(Dispatchers.Default) {
			val itemCollection = SessionActivity.getAll(this@SessionActivityActivity).map {
				SortableAdapter.SortableData(
						it,
						if (it.id >= 0) AppendPriority.Start else AppendPriority.Any
				)
			}

			launch(Dispatchers.Main) {
				adapter.addAll(itemCollection)
			}
		}
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

	override fun onDataSave(dataCollection: List<EditData>) {
		require(dataCollection.size == 1)
		require(dataCollection.first().id == NAME_FIELD)

		val value = dataCollection.first().currentValue
		launch(Dispatchers.Default) {
			val newActivity = SessionActivity(0, value, null)

			adapter.addItemPersistent(this@SessionActivityActivity, newActivity, AppendPriority.Any)
		}
	}

	override fun getEmptyEditData(): Collection<EditData> {
		return listOf(EditData(NAME_FIELD, EditType.EditText, R.string.activity_name, ""))
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
						action = getString(com.adsamcik.tracker.common.R.string.undo),
						duration = LENGTH_LONG,
						onDismissed = {
							launch(Dispatchers.Default) {
								AppDatabase.database(context).activityDao().delete(item.id)
							}
						},
						onActionClick = View.OnClickListener {
							adapter.add(item, AppendPriority.Any)
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

