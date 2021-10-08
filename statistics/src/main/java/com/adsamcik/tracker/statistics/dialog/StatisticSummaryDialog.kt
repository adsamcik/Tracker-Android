package com.adsamcik.tracker.statistics.dialog

import android.content.Context
import androidx.annotation.StringRes
import androidx.recyclerview.widget.LinearLayoutManager
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.shared.utils.dialog.setLoading
import com.adsamcik.tracker.shared.utils.dialog.setLoadingFinished
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.list.recycler.SessionSummaryAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.customListAdapter
import com.afollestad.materialdialogs.list.getRecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Statistic summary dialog to display summary data over a period of time.
 */
class StatisticSummaryDialog : CoroutineScope {
	companion object {
		private const val DIALOG_LAYER = 2
	}

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	/**
	 * Show dialog and lazily load it's content by calling [dataLoader] function.
	 *
	 * @param context Context
	 * @param titleRes Title string resource
	 * @param dataLoader Asynchronously called function on work thread that returns stat collection
	 */
	fun show(
			context: Context,
			@StringRes titleRes: Int,
			dataLoader: (context: Context) -> Collection<Stat>
	) {
		val adapter = SessionSummaryAdapter()
		var styleController: StyleController? = null
		val dialog = MaterialDialog(context).apply {
			title(res = titleRes)
			setLoading()
			dynamicStyle(DIALOG_LAYER) {
				styleController = it
			}
		}

		dialog.show()

		launch {
			val statDataCollection = dataLoader(context)
			launch(Dispatchers.Main) {
				adapter.addAll(statDataCollection)
				dialog.apply {
					setLoadingFinished()
					customListAdapter(adapter, LinearLayoutManager(context))
					getRecyclerView().addItemDecoration(MarginDecoration())
					requireNotNull(styleController).watchRecyclerView(
							RecyclerStyleView(
									getRecyclerView()
							)
					)
				}
			}
		}
	}

}
