package com.adsamcik.tracker.statistics.wifi

import android.os.Bundle
import androidx.recyclerview.widget.RecyclerView
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import com.adsamcik.tracker.common.activity.ManageActivity
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.statistics.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WifiBrowseActivity : ManageActivity() {
	private val adapter = WifiRecyclerAdapter()

	private var filterData = FilterData(null, null, null, null, 0L)

	override fun getAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder> {
		@Suppress("unchecked_cast")
		return adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
	}

	override fun onManageConfigure(configuration: ManageConfiguration) {
		configuration.apply {
			isHorizontallyScrollable = true
			dialogFabIcon = com.adsamcik.tracker.common.R.drawable.ic_filter_list
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setTitle(R.string.wifilist_title)
	}

	override fun onCreateRecycler(recyclerView: RecyclerView) {
		launch(Dispatchers.Default) {
			val database = AppDatabase.database(this@WifiBrowseActivity)
			val wifiList = database.wifiDao().getAll(DEFAULT_LIMIT)
			launch(Dispatchers.Main) { adapter.addAll(wifiList) }
		}
	}

	override fun onDataConfirmed(tag: String?, dataCollection: List<EditDataInstance>) {
		val bssid = requireNotNull(dataCollection.find { it.id == FILTER_BSSID })
		val ssid = requireNotNull(dataCollection.find { it.id == FILTER_SSID })
		val capabilities = requireNotNull(dataCollection.find { it.id == FILTER_CAPABILITIES })
		val frequency = requireNotNull(dataCollection.find { it.id == FILTER_FREQUENCY })
		val count = requireNotNull(dataCollection.find { it.id == FILTER_COUNT })

		val filterData = FilterData(
				bssid = bssid.value,
				ssid = ssid.value,
				capabilities = capabilities.value,
				frequency = frequency.value,
				count = if (count.value.isBlank()) DEFAULT_LIMIT else count.value.toLong()
		)
		this.filterData = filterData
		filter(filterData)
	}

	private fun filter(filterData: FilterData) {
		launch(Dispatchers.Default) {
			val whereBuilder = generateWhere(filterData)
			val queryBuilder = SupportSQLiteQueryBuilder.builder(TABLE_NAME)
					.selection(whereBuilder.first, whereBuilder.second)
					.limit(filterData.count.toString())
			val filteredData = AppDatabase
					.database(this@WifiBrowseActivity)
					.wifiDao()
					.getAll(queryBuilder.create())

			launch(Dispatchers.Main) {
				adapter.removeAll()
				adapter.addAll(filteredData)
			}

		}
	}

	private fun generateWhere(filterData: FilterData): Pair<String, Array<out Any>> {
		val conditionList = mutableListOf<String>()
		val outList = mutableListOf<Any>()
		if (!filterData.bssid.isNullOrBlank()) {
			conditionList.add("bssid LIKE ?")
			outList.add("%${filterData.bssid}%")
		}
		if (!filterData.ssid.isNullOrBlank()) {
			conditionList.add("ssid LIKE ?")
			outList.add("%${filterData.ssid}%")
		}
		if (!filterData.capabilities.isNullOrBlank()) {
			conditionList.add("capabilities LIKE ?")
			outList.add("%${filterData.capabilities}%")
		}
		if (!filterData.frequency.isNullOrBlank()) {
			conditionList.add("frequency LIKE ?")
			outList.add("%${filterData.frequency}%")
		}

		return conditionList.joinToString(separator = " AND ") to outList.toTypedArray()
	}

	override fun getEmptyEditData(): Collection<EditData> {
		return listOf(
				EditData(FILTER_BSSID, EditType.EditText, R.string.wifilist_filter_bssid),
				EditData(FILTER_SSID, EditType.EditText, R.string.wifilist_filter_ssid),
				EditData(
						FILTER_CAPABILITIES,
						EditType.EditText,
						R.string.wifilist_filter_capabilities
				),
				EditData(FILTER_FREQUENCY, EditType.EditText, R.string.wifilist_filter_frequency),
				EditData(FILTER_COUNT, EditType.EditText, R.string.wifilist_filter_count)
		)
	}

	companion object {
		private const val FILTER_SSID = "ssid"
		private const val FILTER_FREQUENCY = "frequency"
		private const val FILTER_BSSID = "bssid"
		private const val FILTER_CAPABILITIES = "capabilities"
		private const val FILTER_COUNT = "count"
		private const val DEFAULT_LIMIT = 1000L
		private const val TABLE_NAME = "wifi_data"
	}

	data class FilterData(
			val bssid: String?,
			val ssid: String?,
			val frequency: String?,
			val capabilities: String?,
			val count: Long
	)
}
