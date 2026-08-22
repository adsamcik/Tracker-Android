package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface EventTrackingFrameConsumer {
	suspend fun consume(cycle: TrackingCycle)
}

/** Replays committed compatibility effects only while an initialized service owns delivery. */
@Singleton
class EventTrackingFrameOutboxDispatcher @Inject constructor(
	private val database: AppDatabase,
) {
	private val lock = Any()
	private var endpoint: DeliveryEndpoint? = null
	private val drainMutex = Mutex()

	internal fun attach(
		ownerToken: String,
		logicalTrackingId: String,
		consumer: EventTrackingFrameConsumer,
	) {
		require(ownerToken.isNotBlank())
		require(logicalTrackingId.isNotBlank())
		synchronized(lock) {
			endpoint = DeliveryEndpoint(ownerToken, logicalTrackingId, consumer)
		}
	}

	internal fun detach(ownerToken: String) {
		synchronized(lock) {
			if (endpoint?.ownerToken == ownerToken) endpoint = null
		}
	}

	suspend fun drain(limit: Int = 100): Int = drainMutex.withLock {
		val activeEndpoint = synchronized(lock) { endpoint } ?: return 0
		var delivered = 0
		database.sourceProjectionStateDao()
			.pendingOutbox(
				EventTrackingFrameProjection.ID,
				EventTrackingFrameProjection.VERSION,
				EventTrackingFrameProjection.OUTBOX_KIND,
				limit,
			)
			.forEach { effect ->
				val delivery = EventTrackingFrameEffectCodec.decode(effect.payload, effect.payloadVersion)
				if (delivery.logicalTrackingId != activeEndpoint.logicalTrackingId) return@forEach
				activeEndpoint.consumer.consume(delivery.cycle)
				if (database.sourceProjectionStateDao().markOutboxDelivered(
						effect.stableId,
						System.currentTimeMillis(),
					) == 1
				) delivered++
			}
		return delivered
	}

	private data class DeliveryEndpoint(
		val ownerToken: String,
		val logicalTrackingId: String,
		val consumer: EventTrackingFrameConsumer,
	)
}
