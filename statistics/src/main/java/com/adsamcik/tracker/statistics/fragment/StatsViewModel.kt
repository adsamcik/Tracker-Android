package com.adsamcik.tracker.statistics.fragment

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.statistics.BuildConfig
import com.adsamcik.tracker.statistics.list.recycler.SessionUiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class StatsViewModel(application: Application) : AndroidViewModel(application) {
	internal lateinit var sessionFlow: Flow<PagingData<SessionUiModel>>

	init {
		updateSessionData()
	}

	fun updateSessionData() {
		viewModelScope.launch {
			val sessionDao = AppDatabase.database(getApplication()).sessionDao()
			val paged = sessionDao.getAllPaged()

			val pager = Pager(
					PagingConfig(
							pageSize = 10,
							enablePlaceholders = false,
							maxSize = 400,
							initialLoadSize = 10
					)
			) {
				paged
			}

			fun shouldSeparate(
					before: SessionUiModel.SessionModel,
					after: SessionUiModel.SessionModel
			): Boolean {
				val beforeTime = Instant.ofEpochMilli(before.session.start)
						.atZone(ZoneId.systemDefault())
						.toLocalDate()
				val afterTime = Instant.ofEpochMilli(after.session.start)
						.atZone(ZoneId.systemDefault())
						.toLocalDate()
				return beforeTime != afterTime
			}

			sessionFlow = pager
					.flow
					.cachedIn(viewModelScope)
					.map { pagingData -> pagingData.map { SessionUiModel.SessionModel(it) } }
					.map {
						it.insertSeparators { after, before ->
							when {
								before == null -> null
								after == null -> SessionUiModel.ListHeader(before.session.start)
								shouldSeparate(
										before,
										after
								) -> SessionUiModel.SessionHeader(after.session.start)
								else -> null
							}
						}
					}
		}
	}
}
