package com.adsamcik.signalcollector.activity.ui

import android.os.Bundle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.signalcollector.activity.NativeSessionActivity
import com.adsamcik.signalcollector.activity.ui.recycler.ActivityRecyclerAdapter
import com.adsamcik.signalcollector.activity.ui.recycler.ContextualSwipeTouchHelper
import com.adsamcik.signalcollector.common.activity.DetailActivity
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.data.SessionActivity
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.android.synthetic.main.activity_session_activity.*
import kotlinx.android.synthetic.main.layout_add_activity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class SessionActivityActivity : DetailActivity() {

	private lateinit var swipeTouchHelper: ContextualSwipeTouchHelper<SessionActivity>

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		inflateContent(com.adsamcik.signalcollector.R.layout.activity_session_activity)

		val adapter = ActivityRecyclerAdapter()

		val recycler = findViewById<RecyclerView>(com.adsamcik.signalcollector.R.id.recycler).apply {
			setAdapter(adapter)
			val layoutManager = LinearLayoutManager(this@SessionActivityActivity)
			this.layoutManager = layoutManager

			val dividerItemDecoration = DividerItemDecoration(this@SessionActivityActivity, layoutManager.orientation)
			addItemDecoration(dividerItemDecoration)
			addItemDecoration(SimpleMarginDecoration(horizontalMargin = 0))
		}

		swipeTouchHelper = ContextualSwipeTouchHelper(this, adapter) { it.id >= 0 }
		ItemTouchHelper(swipeTouchHelper).attachToRecyclerView(recycler)
		val fab = findViewById<FloatingActionButton>(com.adsamcik.signalcollector.R.id.fab).apply {
			setOnClickListener { isExpanded = true }
		}

		button_cancel.setOnClickListener { fab.isExpanded = false }

		button_ok.setOnClickListener {
			adapter.add(SortableAdapter.SortableData(SessionActivity(5, input_name.text.toString(), null)))
			fab.isExpanded = false
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
	}

	private fun initializeColorController() {
		colorController.watchRecyclerView(ColorView(recycler, 0))
		colorController.watchView(ColorView(fab, 1, isInverted = true))
		colorController.watchView(ColorView(add_item_layout, 2))
	}

	override fun onDestroy() {
		super.onDestroy()
		swipeTouchHelper.onDestroy()
	}
}
