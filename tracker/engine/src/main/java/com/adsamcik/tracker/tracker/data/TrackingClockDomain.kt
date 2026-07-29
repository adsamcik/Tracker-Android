package com.adsamcik.tracker.tracker.data

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Conservative identity for elapsed-realtime values produced by the active tracker service.
 *
 * Android's elapsed realtime cannot be compared over a reboot. We deliberately split domains on
 * every tracker-service session as well: that loses no trustworthy duration, and prevents a
 * process restart from ever bridging an unknown gap. The ID is persisted with all source evidence
 * and tracker-state events, where it becomes durable audit data.
 */
internal object TrackingClockDomain {
	@Volatile
	private var activeId: String = UUID.randomUUID().toString()

	@Volatile
	private var activeBootId: String = "process-${UUID.randomUUID()}"

	fun beginSession(context: Context): String {
		activeBootId = readBootDomain(context)
		return UUID.randomUUID().toString().also { activeId = it }
	}

	fun currentId(): String = activeId

	fun currentBootId(): String = activeBootId

	private fun readBootDomain(context: Context): String = runCatching {
		val bootCount = Settings.Global.getInt(
			context.contentResolver,
			Settings.Global.BOOT_COUNT,
		)
		"android-boot-$bootCount"
	}.getOrElse {
		// A random process boundary is deliberately conservative: it never joins evidence across
		// an unknown reboot when a vendor blocks access to BOOT_COUNT.
		activeBootId.takeIf { id -> id.startsWith("process-") } ?: "process-${UUID.randomUUID()}"
	}
}
