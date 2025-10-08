package com.adsamcik.tracker.tracker.ui.receiver

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver

internal class SessionUpdateReceiver : TrackerUpdateReceiver {
	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData
	) {
		mutableCollectionData.postValue(collectionData)
		mutableSessionData.postValue(session)
	}

	companion object {
		private val mutableCollectionData = MutableLiveData<CollectionData>()
		private val mutableSessionData = MutableLiveData<TrackerSession>()

		/**
		 * Collection data from tracking updates (LiveData, deprecated).
		 * 
		 * @deprecated LiveData-based update receiver. Migrate to Flow-based TrackerService.sessionFlow
		 * or inject a repository abstraction. Will be removed in a future release.
		 */
		@Deprecated(
			message = "LiveData-based update receiver. Use TrackerService.sessionFlow or repository abstraction.",
			level = DeprecationLevel.WARNING
		)
		val collectionData: LiveData<CollectionData> get() = mutableCollectionData
		
		/**
		 * Session data from tracking updates (LiveData, deprecated).
		 * 
		 * @deprecated LiveData-based update receiver. Migrate to Flow-based TrackerService.sessionFlow
		 * or inject a repository abstraction. Will be removed in a future release.
		 */
		@Deprecated(
			message = "LiveData-based update receiver. Use TrackerService.sessionFlow or repository abstraction.",
			level = DeprecationLevel.WARNING
		)
		val sessionData: LiveData<TrackerSession> get() = mutableSessionData
	}

}
