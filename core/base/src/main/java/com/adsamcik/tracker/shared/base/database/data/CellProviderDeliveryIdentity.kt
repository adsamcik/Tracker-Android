package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Identity-safe provider facts used by the canonical Cell v1 delivery identity. */
data class CellProviderDeliveryIdentityFact(
	val radioType: String,
	val registered: Boolean,
	val signalLevelDbm: Int?,
	val providerTimestampNanos: Long,
) {
	init {
		require(providerTimestampNanos > 0L) { "Cell delivery identity requires positive provider time" }
	}
}

/**
 * Process-independent identity for one qualified Cell provider delivery.
 *
 * This is shared by live capture and retained-fact authentication so maintenance cannot accept a
 * payload whose self-consistent hashes no longer describe the delivery identity. Raw cell,
 * subscription, SIM, and slot identifiers must never be supplied here.
 */
fun canonicalCellProviderDeliveryIdentity(
	clockDomainId: String,
	facts: List<CellProviderDeliveryIdentityFact>,
): String {
	require(clockDomainId.isNotBlank())
	require(facts.isNotEmpty()) { "Cell delivery identity requires provider facts" }
	val canonical = ByteArrayOutputStream().also { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(CELL_DELIVERY_IDENTITY_VERSION)
			output.writeCanonicalString(clockDomainId)
			val ordered = facts.sortedWith(CELL_PROVIDER_FACT_ORDER)
			output.writeInt(ordered.size)
			ordered.forEach { fact ->
				output.writeCanonicalString(fact.radioType)
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
	CellProviderDeliveryIdentityFact::registered,
	CellProviderDeliveryIdentityFact::signalLevelDbm,
	CellProviderDeliveryIdentityFact::providerTimestampNanos,
)

private const val CELL_DELIVERY_IDENTITY_VERSION = 1
