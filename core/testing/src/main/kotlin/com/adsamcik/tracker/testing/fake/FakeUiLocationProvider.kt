package com.adsamcik.tracker.testing.fake

import android.location.Location
import com.adsamcik.tracker.shared.base.location.UiLocationProvider
import com.adsamcik.tracker.shared.base.location.UiLocationRequest
import com.adsamcik.tracker.shared.base.location.UiLocationRequestInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

class FakeUiLocationProvider : UiLocationProvider {
    private val locations = MutableSharedFlow<Location>()
    private val _activeRequests = MutableStateFlow<List<UiLocationRequestInfo>>(emptyList())
    override val activeRequests = _activeRequests.asStateFlow()

    override fun locationUpdates(request: UiLocationRequest): Flow<Location> =
        locations.asSharedFlow()
            .onStart {
                _activeRequests.update {
                    it + UiLocationRequestInfo(request.tag, System.currentTimeMillis())
                }
            }
            .onCompletion {
                _activeRequests.update { active -> active.filterNot { it.tag == request.tag } }
            }

    suspend fun emit(location: Location) {
        locations.emit(location)
    }
}
