package com.adsamcik.tracker.activity.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.activity.ui.recycler.ActivityRecyclerAdapter
import com.adsamcik.tracker.activity.ui.recycler.ContextualSwipeTouchHelper
import com.adsamcik.tracker.common.activity.DetailActivity
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.keyboard.KeyboardManager
import com.adsamcik.tracker.common.misc.SnackMaker
import com.adsamcik.tracker.common.style.RecyclerStyleView
import com.adsamcik.tracker.common.style.StyleView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import kotlinx.android.synthetic.main.activity_session_activity.*
import kotlinx.android.synthetic.main.layout_add_activity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


//todo add session editing
class SessionActivityActivity : DetailActivity() {

	private lateinit var swipeTouchHelper: ContextualSwipeTouchHelper
	private lateinit var keyboardManager: KeyboardManager

	private lateinit var snackMaker: SnackMaker

	private val adapter = ActivityRecyclerAdapter()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val rootView = inflateContent<ViewGroup>(R.layout.activity_session_activity)
		keyboardManager = KeyboardManager(rootView)
		snackMaker = SnackMaker(rootView.findViewById(R.id.coordinator))

		val recycler = rootView.findViewById<RecyclerView>(R.id.recycler).apply {
			this.adapter = this@SessionActivityActivity.adapter
			val layoutManager = LinearLayoutManager(this@SessionActivityActivity)
			this.layoutManager = layoutManager

			val dividerItemDecoration = DividerItemDecoration(
					this@SessionActivityActivity,
					layoutManager.orientation
			)
			addItemDecoration(dividerItemDecoration)
		}

		swipeTouchHelper = ContextualSwipeTouchHelper(this, adapter) { it.id >= 0 }.apply {
			onSwipedCallback = this@SessionActivityActivity::onItemSwipedCallback
		}
		ItemTouchHelper(swipeTouchHelper).attachToRecyclerView(recycler)
		val fab = rootView.findViewById<FloatingActionButton>(R.id.fab).apply {
			setOnClickListener { isExpanded = true }
		}

		button_cancel.setOnClickListener { fab.isExpanded = false }

		button_ok.setOnClickListener {
			val context = this@SessionActivityActivity

			fab.isExpanded = false
			keyboardManager.hideKeyboard()

			val inputText = input_name.text.toString()
			input_name.clearFocus()
			input_name.text = null

			launch(Dispatchers.Default) {
				val newActivity = SessionActivity(0, inputText, null)

				adapter.addItemPersistent(context, newActivity, AppendPriority.Any)
			}
		}

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

		initializeColorController()
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

	private fun initializeColorController() {
		styleController.watchRecyclerView(RecyclerStyleView(recycler, 0))
		styleController.watchView(StyleView(findViewById(R.id.fab), 1, isInverted = true))
		styleController.watchView(StyleView(add_item_layout, 2))
	}

	override fun onDestroy() {
		super.onDestroy()
		swipeTouchHelper.onDestroy()
	}
}

