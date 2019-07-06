package com.adsamcik.signalcollector.activity.ui

import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.data.NativeSessionActivity
import com.adsamcik.signalcollector.activity.ui.recycler.ActivityRecyclerAdapter
import com.adsamcik.signalcollector.activity.ui.recycler.ContextualSwipeTouchHelper
import com.adsamcik.signalcollector.common.activity.DetailActivity
import com.adsamcik.signalcollector.common.style.RecyclerStyleView
import com.adsamcik.signalcollector.common.style.StyleView
import com.adsamcik.signalcollector.common.data.SessionActivity
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.misc.keyboard.KeyboardManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.android.synthetic.main.activity_session_activity.*
import kotlinx.android.synthetic.main.layout_add_activity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


//todo add session editing
class SessionActivityActivity : DetailActivity() {

	private lateinit var swipeTouchHelper: ContextualSwipeTouchHelper
	private lateinit var keyboardManager: KeyboardManager

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val rootView = inflateContent<ViewGroup>(com.adsamcik.signalcollector.R.layout.activity_session_activity)
		keyboardManager = KeyboardManager(rootView)

		val adapter = ActivityRecyclerAdapter()

		val recycler = rootView.findViewById<RecyclerView>(com.adsamcik.signalcollector.R.id.recycler).apply {
			setAdapter(adapter)
			val layoutManager = LinearLayoutManager(this@SessionActivityActivity)
			this.layoutManager = layoutManager

			val dividerItemDecoration = DividerItemDecoration(this@SessionActivityActivity, layoutManager.orientation)
			addItemDecoration(dividerItemDecoration)
		}

		swipeTouchHelper = ContextualSwipeTouchHelper(this, adapter) { it.id >= 0 }
		ItemTouchHelper(swipeTouchHelper).attachToRecyclerView(recycler)
		val fab = rootView.findViewById<FloatingActionButton>(com.adsamcik.signalcollector.R.id.fab).apply {
			setOnClickListener { isExpanded = true }
		}

		button_cancel.setOnClickListener { fab.isExpanded = false }

		button_ok.setOnClickListener {
			val context = this@SessionActivityActivity

			fab.isExpanded = false
			keyboardManager.hideKeyboard()

			launch(Dispatchers.Default) {
				val newActivity = SessionActivity(0, input_name.text.toString(), null)

				adapter.addItemPersistent(context, newActivity, AppendPriority.Any)
			}
		}

		launch(Dispatchers.Default) {
			val database = AppDatabase.getDatabase(this@SessionActivityActivity)
			val activityDao = database.activityDao()
			val itemCollection = mutableListOf<SortableAdapter.SortableData<SessionActivity>>().apply {
				addAll(NativeSessionActivity.values().map {
					SortableAdapter.SortableData(it.getSessionActivity(this@SessionActivityActivity), AppendPriority.Start)
				})
				addAll(activityDao.getAll().map {
					SortableAdapter.SortableData(it, AppendPriority.Any)
				})
			}

			launch(Dispatchers.Main) {
				adapter.addAll(itemCollection)
			}
		}

		initializeColorController()
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
