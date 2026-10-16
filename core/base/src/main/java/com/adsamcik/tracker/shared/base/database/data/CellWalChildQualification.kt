package com.adsamcik.tracker.shared.base.database.data

import java.time.Instant
import java.time.ZoneId

internal fun qualifyCellWalChildren(
	wal: SourceEventWalEntity,
	payload: CellWalPayload,
	authority: CellWalCaptureAuthority,
	qualifiedItemIndexes: Set<Int>?,
): CellWalChildQualification? {
	val carrier = AuthenticatedCellWalCarrier(
		deliveryIdentity = requireNotNull(wal.deliveryIdentity),
		payload = payload,
		childQualification = null,
		admissionOrdinal = wal.admissionOrdinal,
		observedElapsedNanos = wal.observedElapsedNanos,
		receivedElapsedNanos = wal.receivedElapsedNanos,
		observedWallTimeMs = requireNotNull(wal.wallTimeMs),
		wallTimeUncertaintyMs = requireNotNull(wal.wallTimeUncertaintyMs),
	)
	return qualifyChildrenFromAuthenticatedCarrier(carrier, authority,
		preserveProviderRounding = wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION,
		qualifiedItemIndexes = qualifiedItemIndexes)
}

private fun qualifyChildDispositions(
	carrier: AuthenticatedCellWalCarrier,
	authority: CellWalCaptureAuthority,
	qualifiedItemIndexes: Set<Int>?,
): List<CellWalQualifiedChild> {
	val children = carrier.payload.observations.mapIndexed { index, observation ->
		val providerTime = requireNotNull(observation.providerTimestampNanos)
		val disposition = when {
			providerTime > carrier.receivedElapsedNanos ->
				CellWalChildDisposition.FUTURE_TIME
			!authority.containsQualifiedCellTime(providerTime) ||
				carrier.receivedElapsedNanos - providerTime >
				authority.maximumObservationAgeNanos -> CellWalChildDisposition.STALE
			!observation.hasSupportedCellProductShape() ->
				CellWalChildDisposition.UNSUPPORTED_TECHNOLOGY
			qualifiedItemIndexes != null && index !in qualifiedItemIndexes -> CellWalChildDisposition.STALE
			else -> CellWalChildDisposition.ACCEPTED
		}
		CellWalQualifiedChild(observation, disposition)
	}
	return children
}

private fun providerWallInterval(
	carrier: AuthenticatedCellWalCarrier,
	coverageStart: Long,
	observedWall: Long,
	acceptedTimes: List<Long>,
	preserveProviderRounding: Boolean,
): LongRange? {
	return runCatching {
		val first = CanonicalRetentionBounds.cellCoverage(
			walObservedWallTimeMs = carrier.observedWallTimeMs,
			wallTimeUncertaintyMs = carrier.wallTimeUncertaintyMs,
			walObservedElapsedRealtimeNanos = carrier.observedElapsedNanos,
			coverageStartElapsedRealtimeNanos = coverageStart,
		) ?: return null
		val last = if (preserveProviderRounding) {
			acceptedTimes.maxOf { providerTime ->
				val wall = carrier.cellCarrierProviderWallTime(providerTime) ?: return null
				val rounding = if ((carrier.observedElapsedNanos - providerTime) % 1_000_000L == 0L) {
					0L
				} else {
					1L
				}
				Math.addExact(wall, Math.addExact(carrier.wallTimeUncertaintyMs, rounding))
			}
		} else {
			Math.addExact(observedWall, carrier.wallTimeUncertaintyMs)
		}
		first..last
	}.getOrNull()
}

private fun qualifyChildrenFromAuthenticatedCarrier(
	carrier: AuthenticatedCellWalCarrier,
	authority: CellWalCaptureAuthority,
	preserveProviderRounding: Boolean,
	qualifiedItemIndexes: Set<Int>? = null,
): CellWalChildQualification? {
	if (authority.maximumObservationAgeNanos < 0L) {
		return null
	}
	val children = qualifyChildDispositions(carrier, authority, qualifiedItemIndexes)
	val accepted = children.filter { it.disposition == CellWalChildDisposition.ACCEPTED }
	if (accepted.isEmpty()) {
		return CellWalChildQualification(children, null, null, null, null, null)
	}
	return qualifyAcceptedCellWalChildren(carrier, authority, children, accepted, preserveProviderRounding)
}

private fun qualifyAcceptedCellWalChildren(
	carrier: AuthenticatedCellWalCarrier,
	authority: CellWalCaptureAuthority,
	children: List<CellWalQualifiedChild>,
	accepted: List<CellWalQualifiedChild>,
	preserveProviderRounding: Boolean,
): CellWalChildQualification? {
	val acceptedTimes = accepted.map { requireNotNull(it.observation.providerTimestampNanos) }
	val coverageStart = requireNotNull(acceptedTimes.minOrNull())
	val coverageEnd = requireNotNull(acceptedTimes.maxOrNull())
	val observedWall = carrier.cellCarrierProviderWallTime(coverageEnd) ?: return null
	val providerWallInterval = providerWallInterval(
		carrier, coverageStart, observedWall, acceptedTimes, preserveProviderRounding,
	) ?: return null
	if (!hasExpectedCellWalStructuralDay(providerWallInterval, observedWall, authority, preserveProviderRounding)) {
		return null
	}
	return CellWalChildQualification(
		children = children,
		coverageIntervalStartNanos = coverageStart,
		coverageIntervalEndNanos = coverageEnd,
		observedWallTimeMs = observedWall,
		providerWallInterval = providerWallInterval,
		aggregate = accepted.map(CellWalQualifiedChild::observation).toQualifiedCellAggregate(),
	)
}

private fun hasExpectedCellWalStructuralDay(
	providerWallInterval: LongRange,
	observedWall: Long,
	authority: CellWalCaptureAuthority,
	preserveProviderRounding: Boolean,
): Boolean {
	val zone = runCatching { ZoneId.of(authority.zoneId) }.getOrNull() ?: return false
	val firstDay = Instant.ofEpochMilli(providerWallInterval.first)
		.atZone(zone).toLocalDate().toEpochDay()
	val lastDay = Instant.ofEpochMilli(providerWallInterval.last)
		.atZone(zone).toLocalDate().toEpochDay()
	val measuredDay = Instant.ofEpochMilli(observedWall).atZone(zone).toLocalDate().toEpochDay()
	return if (preserveProviderRounding) {
		measuredDay == authority.structuralEpochDay
	} else {
		firstDay == authority.structuralEpochDay && lastDay == authority.structuralEpochDay
	}
}
