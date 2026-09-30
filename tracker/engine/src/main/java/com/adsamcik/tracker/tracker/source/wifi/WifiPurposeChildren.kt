package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadDecodeResult

/** Accepted item indexes retain the original callback order for exact replay and per-purpose proof. */
internal object WifiPurposeChildren {
	fun session(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
		indexes: List<Int>,
	): List<WifiCapturedChildEntity>? = materialize(
		wal, indexes, RadioChildPurpose.SESSION, fact.writerProjectionId,
		fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision,
	)?.map { child ->
		WifiCapturedChildEntity(
			fact.writerProjectionId, fact.writerProjectionVersion, fact.logicalFactId,
			fact.semanticRevision, child.index, child.providerNanos, child.wallMs,
			child.uncertaintyMs, child.bssid, child.ssid, child.frequencyMhz,
			child.signalDbm, child.checksum,
		)
	}

	fun background(
		fact: AmbientWifiFactRevisionEntity,
		wal: SourceEventWalEntity,
		indexes: List<Int>,
	): List<AmbientWifiChildEntity>? = materialize(
		wal, indexes, RadioChildPurpose.BACKGROUND, fact.writerId,
		fact.writerVersion, fact.logicalFactId, fact.semanticRevision,
	)?.map { child ->
		AmbientWifiChildEntity(
			fact.writerId, fact.writerVersion, fact.logicalFactId, fact.semanticRevision,
			child.index, child.providerNanos, child.wallMs, child.uncertaintyMs,
			child.bssid, child.ssid, child.frequencyMhz, child.signalDbm, child.checksum,
		)
	}

	private fun materialize(
		wal: SourceEventWalEntity,
		indexes: List<Int>,
		purpose: RadioChildPurpose,
		writerId: String,
		writerVersion: Int,
		factId: String,
		revision: Long,
	): List<Child>? {
		val payload = (WifiWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
			as? WifiWalPayloadDecodeResult.Decoded)?.payload ?: return null
		if (indexes.isEmpty() || indexes.size != indexes.distinct().size ||
			indexes.any { it !in payload.observations.indices }
		) return null
		val wallAnchor = wal.wallTimeMs ?: return null
		val uncertaintyAnchor = wal.wallTimeUncertaintyMs ?: return null
		return indexes.sorted().map { index ->
			val observation = payload.observations[index]
			val providerNanos = observation.providerTimestampNanos ?: return null
			val ageNanos = wal.observedElapsedNanos - providerNanos
			if (providerNanos <= 0L || ageNanos < 0L) return null
			val wallMs = runCatching {
				Math.subtractExact(wallAnchor, ageNanos / NANOS_PER_MILLISECOND)
			}.getOrNull() ?: return null
			val uncertaintyMs = runCatching {
				Math.addExact(uncertaintyAnchor,
					if (ageNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L)
			}.getOrNull() ?: return null
			if (wallMs < 0L || uncertaintyMs < 0L) return null
			val bssid = observation.identifierToken.takeIf(String::isNotEmpty)
			val ssid = observation.networkName
			val input = WifiChildChecksumInput(
				RadioChildOwner(purpose, writerId, writerVersion, factId, revision, index),
				providerNanos, wallMs, uncertaintyMs, bssid, ssid,
				observation.frequencyMhz, observation.signalLevelDbm,
			)
			Child(index, providerNanos, wallMs, uncertaintyMs, bssid, ssid,
				observation.frequencyMhz, observation.signalLevelDbm,
				RadioObservationChildIntegrity.wifi(input))
		}
	}

	private data class Child(
		val index: Int,
		val providerNanos: Long,
		val wallMs: Long,
		val uncertaintyMs: Long,
		val bssid: String?,
		val ssid: String?,
		val frequencyMhz: Int,
		val signalDbm: Int,
		val checksum: String,
	)

	private const val NANOS_PER_MILLISECOND = 1_000_000L
}
