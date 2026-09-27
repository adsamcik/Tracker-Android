package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Canonical content integrity only; this does not qualify a retained purpose or provider grant. */
object LocationAcceptedReceiptIntegrity {
	/** Exactly the encoding already used by both qualified Location canonical writers. */
	fun canonicalChecksum(sample: LocationSample): String = MessageDigest.getInstance("SHA-256")
		.digest(sample.protectedLocationCanonicalReceiptBytes())
		.joinToString("") { "%02x".format(it) }

	fun matchesCanonicalSample(receipt: LocationAcceptedReceiptEntity, sample: LocationSample): Boolean =
		sample.id == receipt.sampleId &&
			sample.sourceEventId == receipt.sourceEventId &&
			sample.sourceSignalId == receipt.sourceSignalId &&
			receipt.sourceSignalId == protectedLocationCanonicalProductIdentity(receipt.sourceEventId) &&
			sample.clockDomainId == receipt.clockDomainId &&
			(sample.bootClockDomainId == null || sample.bootClockDomainId == receipt.bootId) &&
			sample.timeMs == receipt.observedWallTimeMs &&
			sample.elapsedRealtimeNanos == receipt.observedElapsedRealtimeNanos &&
			sample.receivedElapsedRealtimeNanos == receipt.receivedElapsedRealtimeNanos &&
			receipt.canonicalContentChecksum == canonicalChecksum(sample)
}
