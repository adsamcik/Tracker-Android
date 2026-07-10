package com.adsamcik.tracker.tracker.component.consumer.post

internal data class NotificationPayload(
	val title: String,
	val text: String,
)

internal class NotificationUpdateGate(
	private val maxRefreshIntervalNanos: Long,
) {
	private var lastPayload: NotificationPayload? = null
	private var lastUpdateElapsedRealtimeNanos = -1L

	fun shouldUpdate(payload: NotificationPayload, elapsedRealtimeNanos: Long): Boolean {
		val refreshDue = lastUpdateElapsedRealtimeNanos < 0L ||
			elapsedRealtimeNanos - lastUpdateElapsedRealtimeNanos >= maxRefreshIntervalNanos
		if (payload == lastPayload && !refreshDue) {
			return false
		}

		lastPayload = payload
		lastUpdateElapsedRealtimeNanos = elapsedRealtimeNanos
		return true
	}

	fun reset() {
		lastPayload = null
		lastUpdateElapsedRealtimeNanos = -1L
	}
}
