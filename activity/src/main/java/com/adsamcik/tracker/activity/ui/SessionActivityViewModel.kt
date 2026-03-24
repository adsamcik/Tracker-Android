package com.adsamcik.tracker.activity.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SessionActivityUiState(
	val items: List<SessionActivity> = emptyList(),
)

@HiltViewModel
class SessionActivityViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val activityDao: ActivityDao,
	private val dispatchers: DispatchersProvider,
) : ViewModel() {

	private val _uiState = MutableStateFlow(SessionActivityUiState())
	val uiState: StateFlow<SessionActivityUiState> = _uiState.asStateFlow()

	init {
		viewModelScope.launch {
			refresh()
		}
	}

	private suspend fun refresh() {
		_uiState.value = withContext(dispatchers.io) {
			SessionActivityUiState(
				items = activityDao.getAllUser() +
					NativeSessionActivity.entries.map { it.getSessionActivity(context) }
			)
		}
	}

	fun deleteActivity(activity: SessionActivity) {
		viewModelScope.launch {
			withContext(dispatchers.io) {
				activityDao.delete(activity.id)
			}
			refresh()
		}
	}

	fun insertActivity(name: String) {
		viewModelScope.launch {
			withContext(dispatchers.io) {
				activityDao.insert(
					SessionActivity(
						name = name.trim(),
					)
				)
			}
			refresh()
		}
	}

	fun updateActivity(activity: SessionActivity) {
		viewModelScope.launch {
			withContext(dispatchers.io) {
				activityDao.update(activity)
			}
			refresh()
		}
	}
}
