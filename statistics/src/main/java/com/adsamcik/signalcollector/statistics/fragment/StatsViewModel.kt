package com.adsamcik.signalcollector.statistics.fragment

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class StatsViewModel(application: Application) : AndroidViewModel(application), CoroutineScope {
	override val coroutineContext: CoroutineContext = Dispatchers.Default

	//todo update to paged list (Seems like a bit of work with sections)
	val sessionLiveData: LiveData<Collection<TrackerSession>> get() = mutableSessionData

	private val mutableSessionData = MutableLiveData<Collection<TrackerSession>>()

	init {
		launch {
			val sessionDao = AppDatabase.getDatabase(getApplication()).sessionDao()
			val end = Time.dayStartMillis + Time.DAY_IN_MILLISECONDS
			val start = end - Time.DAY_IN_MILLISECONDS * 30
			mutableSessionData.postValue(sessionDao.getBetween(start, end))
		}
	}
}