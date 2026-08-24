package com.adsamcik.tracker.shared.base.time

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Canonical identity for Android's elapsed-realtime clock domain.
 *
 * Every persisted elapsed timestamp that can be compared across tracking subsystems must use the
 * same application-scoped provider. A process-stable fallback deliberately prevents comparison
 * across process death when [Settings.Global.BOOT_COUNT] cannot be read.
 */
fun interface BootClockDomainProvider {
	fun current(): String
}

class AndroidBootClockDomainProvider(context: Context) : BootClockDomainProvider {
	private val clockDomainId = readClockDomainId(context.applicationContext)

	override fun current(): String = clockDomainId

	private companion object {
		const val UNKNOWN_BOOT_COUNT = -1
		val PROCESS_FALLBACK_CLOCK_DOMAIN_ID = "process:${UUID.randomUUID()}"

		fun readClockDomainId(context: Context): String {
			val bootCount = Settings.Global.getInt(
				context.contentResolver,
				Settings.Global.BOOT_COUNT,
				UNKNOWN_BOOT_COUNT,
			)
			return if (bootCount >= 0) {
				"android-boot-count:$bootCount"
			} else {
				PROCESS_FALLBACK_CLOCK_DOMAIN_ID
			}
		}
	}
}
