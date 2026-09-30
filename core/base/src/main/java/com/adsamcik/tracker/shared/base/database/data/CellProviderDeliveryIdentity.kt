package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Provider facts used by the canonical Cell delivery identity. */
data class CellProviderDeliveryIdentityFact(
	val radioType: String,
	val registered: Boolean,
	val signalLevelDbm: Int?,
	val providerTimestampNanos: Long,
	val identifierToken: String = "",
) {
	init {
		require(providerTimestampNanos > 0L) { "Cell delivery identity requires positive provider time" }
	}
}

/**
 * Process-independent identity for one qualified Cell provider delivery.
 *
 * This is shared by live capture and retained-fact authentication so maintenance cannot accept a
 * payload whose self-consistent hashes no longer describe the delivery identity. Version 5 uses
 * the original identity-free digest; version 6 binds available tower identifiers. Subscription,
 * SIM, subscriber, and slot identifiers must never be supplied here.
 */
fun canonicalCellProviderDeliveryIdentity(
	clockDomainId: String,
	facts: List<CellProviderDeliveryIdentityFact>,
	payloadVersion: Int = CELL_WAL_PAYLOAD_VERSION,
): String {
	require(clockDomainId.isNotBlank())
	require(facts.isNotEmpty()) { "Cell delivery identity requires provider facts" }
	require(payloadVersion in LEGACY_CELL_WAL_PAYLOAD_VERSION..CELL_WAL_PAYLOAD_VERSION)
	if (payloadVersion == LEGACY_CELL_WAL_PAYLOAD_VERSION) {
		require(facts.all { it.identifierToken.isEmpty() })
	}
	val canonical = ByteArrayOutputStream().also { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(if (payloadVersion == LEGACY_CELL_WAL_PAYLOAD_VERSION) 1 else 2)
			output.writeCanonicalString(clockDomainId)
			val ordered = facts.sortedWith(CELL_PROVIDER_FACT_ORDER)
			output.writeInt(ordered.size)
			ordered.forEach { fact ->
				output.writeCanonicalString(fact.radioType)
				if (payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
					output.writeCanonicalString(fact.identifierToken)
				}
				output.writeBoolean(fact.registered)
				output.writeBoolean(fact.signalLevelDbm != null)
				fact.signalLevelDbm?.let(output::writeInt)
				output.writeLong(fact.providerTimestampNanos)
			}
		}
	}.toByteArray()
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun DataOutputStream.writeCanonicalString(value: String) {
	val encoded = value.toByteArray(Charsets.UTF_8)
	writeInt(encoded.size)
	write(encoded)
}

private val CELL_PROVIDER_FACT_ORDER = compareBy<CellProviderDeliveryIdentityFact>(
	CellProviderDeliveryIdentityFact::radioType,
	CellProviderDeliveryIdentityFact::identifierToken,
	CellProviderDeliveryIdentityFact::registered,
	CellProviderDeliveryIdentityFact::signalLevelDbm,
	CellProviderDeliveryIdentityFact::providerTimestampNanos,
)
