package com.adsamcik.tracker.activity.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.activity.data.CreateSessionActivityCommand
import com.adsamcik.tracker.activity.data.SessionActivityRepository
import com.adsamcik.tracker.activity.data.SessionActivityItem
import com.adsamcik.tracker.activity.data.UpdateSessionActivityCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionActivityUiState(
	val items: List<SessionActivityItem> = emptyList(),
)

@HiltViewModel
class SessionActivityViewModel @Inject constructor(
	private val activityRepository: SessionActivityRepository,
) : ViewModel() {

	private val _uiState = MutableStateFlow(SessionActivityUiState())
	val uiState: StateFlow<SessionActivityUiState> = _uiState.asStateFlow()

	init {
		viewModelScope.launch {
			refresh()
		}
	}

	private suspend fun refresh() {
		_uiState.value = SessionActivityUiState(
			items = activityRepository.getActivities(),
		)
	}

	fun deleteActivity(activity: SessionActivityItem) {
		viewModelScope.launch {
			activityRepository.delete(activity.id)
			refresh()
		}
	}

	fun insertActivity(name: String) {
		viewModelScope.launch {
			activityRepository.create(
				CreateSessionActivityCommand(
					name = name.trim(),
				)
			)
			refresh()
		}
	}

	fun updateActivity(activity: SessionActivityItem) {
		viewModelScope.launch {
			activityRepository.update(
				UpdateSessionActivityCommand(
					id = activity.id,
					name = activity.name,
					iconName = activity.iconName,
				),
			)
			refresh()
		}
	}
}
