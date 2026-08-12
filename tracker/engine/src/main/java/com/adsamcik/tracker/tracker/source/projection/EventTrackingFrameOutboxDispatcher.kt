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
		require(limit > 0)
		val activeEndpoint = synchronized(lock) { endpoint } ?: return 0
		var delivered = 0
		val dao = database.sourceProjectionStateDao()
		dao.pendingOutbox(EventTrackingFrameProjection.effectKind(activeEndpoint.logicalTrackingId), limit)
			.forEach { effect ->
				activeEndpoint.consumer.consume(
					EventTrackingFrameEffectCodec.decode(effect.payload, effect.payloadVersion).cycle,
				)
				if (dao.markOutboxDelivered(effect.stableId, System.currentTimeMillis()) == 1) delivered++
			}
		if (delivered >= limit) return delivered

		// Version 1 effects were not session-scoped in their kind. Paginate through the complete
		// legacy queue so another session's oldest effects cannot block this session forever.
		var afterOrdinal = 0L
		var afterStableId = ""
		while (delivered < limit) {
			val page = dao.pendingOutboxAfter(
				EventTrackingFrameProjection.LEGACY_OUTBOX_KIND,
				afterOrdinal,
				afterStableId,
				LEGACY_SCAN_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			for (effect in page) {
				afterOrdinal = effect.admissionOrdinal
				afterStableId = effect.stableId
				val delivery = EventTrackingFrameEffectCodec.decode(effect.payload, effect.payloadVersion)
				if (delivery.logicalTrackingId != activeEndpoint.logicalTrackingId) continue
				activeEndpoint.consumer.consume(delivery.cycle)
				if (dao.markOutboxDelivered(effect.stableId, System.currentTimeMillis()) == 1) delivered++
				if (delivered >= limit) break
			}
			if (page.size < LEGACY_SCAN_PAGE_SIZE) break
		}
		return delivered
	}

	/** Completes legacy effects whose logical session can no longer own a delivery endpoint. */
	suspend fun completeTerminalLegacyEffects(): Int = drainMutex.withLock {
		val dao = database.sourceProjectionStateDao()
		var afterOrdinal = 0L
		var afterStableId = ""
		var completed = 0
		while (true) {
			val page = dao.pendingOutboxAfter(
				EventTrackingFrameProjection.LEGACY_OUTBOX_KIND,
				afterOrdinal,
				afterStableId,
				LEGACY_SCAN_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			page.forEach { effect ->
				afterOrdinal = effect.admissionOrdinal
				afterStableId = effect.stableId
				val logicalTrackingId = EventTrackingFrameEffectCodec
					.decode(effect.payload, effect.payloadVersion)
					.logicalTrackingId
				val state = database.sourceSessionDao().session(logicalTrackingId)?.state
				if (state == "CLOSED" || state == "FAILED") {
					completed += dao.markOutboxDelivered(effect.stableId, System.currentTimeMillis())
				}
			}
			if (page.size < LEGACY_SCAN_PAGE_SIZE) break
		}
		completed
	}

	private data class DeliveryEndpoint(
		val ownerToken: String,
		val logicalTrackingId: String,
		val consumer: EventTrackingFrameConsumer,
	)

	private companion object {
		const val LEGACY_SCAN_PAGE_SIZE = 100
	}
}
