package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult

class FakeActiveTrackingSessionStore(
	initialDescriptor: ActiveTrackingSessionDescriptor? = null,
) : ActiveTrackingSessionStore {
	var descriptor: ActiveTrackingSessionDescriptor? = initialDescriptor
		private set

	override suspend fun read(): ActiveTrackingSessionStoreResult =
		ActiveTrackingSessionStoreResult.Success(descriptor)

	override suspend fun save(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult {
		this.descriptor = descriptor
		return ActiveTrackingSessionStoreResult.Success(descriptor)
	}

	override suspend fun clear(): ActiveTrackingSessionStoreResult {
		descriptor = null
		return ActiveTrackingSessionStoreResult.Success(null)
	}
}

