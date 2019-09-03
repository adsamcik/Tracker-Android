package com.adsamcik.tracker.statistics.fragment

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.extension.cloneCalendar
import com.adsamcik.tracker.common.extension.roundToDate
import com.adsamcik.tracker.statistics.list.recycler.SectionedRecyclerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import kotlin.coroutines.CoroutineContext

class StatsViewModel(application: Application) : AndroidViewModel(application), CoroutineScope {
	override val coroutineContext: CoroutineContext = Dispatchers.Default

	val adapter: SectionedRecyclerAdapter = SectionedRecyclerAdapter()
	//todo update to paged list (Seems like a bit of work with sections)
	val sessionLiveData: LiveData<Collection<TrackerSession>> get() = mutableSessionData

	private val mutableSessionData = MutableLiveData<Collection<TrackerSession>>()

	init {
		updateSessionData()
	}

	fun updateSessionData() {
		launch {
			val sessionDao = AppDatabase.database(getApplication()).sessionDao()
			val end = Calendar.getInstance().apply {
				roundToDate()
				add(Calendar.DAY_OF_MONTH, 1)
			}
			val start = end.cloneCalendar().apply { add(Calendar.MONTH, -1) }
			mutableSessionData.postValue(
					sessionDao.getBetween(
							start.timeInMillis,
							end.timeInMillis
					)
			)
		}
	}
}
