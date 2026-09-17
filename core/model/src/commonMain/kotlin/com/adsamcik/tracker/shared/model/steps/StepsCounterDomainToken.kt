package com.adsamcik.tracker.shared.model.steps

/**
 * Provider-issued opaque identity for one physical cumulative counter epoch.
 *
 * A token is intentionally non-stable across counter resets or provider epochs. It contains no
 * provider account, raw sensor identity, user id, device id, registration id, QoS plan, or report
 * latency. Parsing a token grants no provider, import, session, writer, or deletion authority.
 */
class StepsCounterDomainToken private constructor(
	val encoded: String,
) {
	override fun equals(other: Any?): Boolean =
		other is StepsCounterDomainToken && encoded == other.encoded

	override fun hashCode(): Int = encoded.hashCode()
	override fun toString(): String = "StepsCounterDomainToken"

	companion object {
		const val MINIMUM_DURABLE_PAYLOAD_VERSION = 6

		fun opaque(encoded: String): StepsCounterDomainToken {
			require(OPAQUE_TOKEN.matches(encoded))
			return StepsCounterDomainToken(encoded)
		}
	}
}

private val OPAQUE_TOKEN = Regex("sha256:[0-9a-f]{64}")
