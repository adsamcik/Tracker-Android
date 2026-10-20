package com.adsamcik.tracker.shared.base.database.data

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Distinct recorded purpose of a network/tower child: session capture or background collection.
 */
enum class RadioChildPurpose { SESSION, BACKGROUND }

/**
 * Exact purpose/writer/fact/revision/index membership used to bind one network or tower child to
 * its parent.
 */
data class RadioChildOwner(
	val purpose: RadioChildPurpose,
	val writerId: String,
	val writerVersion: Int,
	val logicalFactId: String,
	val semanticRevision: Long,
	val itemIndex: Int,
)

/**
 * Original Wi-Fi child owner, provider/UTC times, optional network identifiers, frequency in MHz
 * and signal in dBm.
 */
data class WifiChildChecksumInput(
	val owner: RadioChildOwner,
	val providerTimestampNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val bssid: String?,
	val ssid: String?,
	val frequencyMhz: Int,
	val signalDbm: Int,
) {
	override fun toString(): String = "WifiChildChecksumInput"
}

/**
 * Original Cell child owner, provider/UTC times, optional tower identity and signal in dBm with
 * radio/registration state.
 */
data class CellChildChecksumInput(
	val owner: RadioChildOwner,
	val providerTimestampNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val towerIdentity: String?,
	val radioType: String,
	val registered: Boolean,
	val signalDbm: Int?,
) {
	override fun toString(): String = "CellChildChecksumInput"
}

/** Stable checksums bind detail content to one purpose-owned fact revision. */
object RadioObservationChildIntegrity {

	/**
	 * Hashes the Location child's original owner/purpose/epoch, measured time and available
	 * network/tower values.
	 */
	fun wifi(input: WifiChildChecksumInput): String = digest(
		listOf("wifi-child-v1") + ownerParts(input.owner) + listOf(
			input.providerTimestampNanos, input.observedWallTimeMs, input.wallTimeUncertaintyMs,
			input.bssid, input.ssid, input.frequencyMhz, input.signalDbm,
		),
	)

	/**
	 * Hashes the Location child's original owner/purpose/epoch, measured time and available
	 * network/tower values.
	 */
	fun cell(input: CellChildChecksumInput): String = digest(
		listOf("cell-child-v1") + ownerParts(input.owner) + listOf(
			input.providerTimestampNanos, input.observedWallTimeMs, input.wallTimeUncertaintyMs,
			input.towerIdentity, input.radioType, input.registered, input.signalDbm,
		),
	)

	private fun ownerParts(owner: RadioChildOwner): List<Any?> = listOf(
		owner.purpose.name, owner.writerId, owner.writerVersion, owner.logicalFactId,
		owner.semanticRevision, owner.itemIndex,
	)

	private fun digest(parts: List<Any?>): String {
		val sha256 = MessageDigest.getInstance("SHA-256")
		parts.forEach { part ->
			val bytes = part?.toString()?.toByteArray(StandardCharsets.UTF_8)
			sha256.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
			if (bytes != null) {
				sha256.update(bytes)
			}
		}
		return sha256.digest().toHexString()
	}
}
