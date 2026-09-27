package com.adsamcik.tracker.shared.base.database.data

/**
 * Historical input for one physical delivery. The reader must load the complete authorization
 * timeline, starting with the revision effective at the earliest item and ending with the exact
 * callback revision. Missing transitions must never be filled from today's authorization.
 * Registration, policy/consent, deletion and carrier identity remain independently authenticated
 * by the caller. This pure qualifier neither reads the database nor changes a stored contract.
 */
data class RadioPurposeItemAuthority(
	val sourceKind: Int,
	val registrationGeneration: Long,
	val clockDomainId: String,
	val providerStartInclusiveNanos: Long,
	val providerEndExclusiveNanos: Long,
	val receivedElapsedNanos: Long,
	val authorizationTimeline: List<SourceAuthorizationSnapshot>,
	val immutableDemands: List<SourceDemandEntity>,
)

enum class RadioPurposeItemFailure {
	WRONG_SOURCE,
	DEPENDENCY_OVERFLOW,
	INVALID_PROVIDER_INTERVAL,
	AUTHORIZATION_UNVERIFIABLE,
}

sealed interface RadioPurposeItemQualification {
	/** Empty membership means no product observation; it must not be written as a zero fact. */
	data class Qualified(val acceptedIndexesByDemandId: Map<String, List<Int>>) : RadioPurposeItemQualification {
		val unionIndexes: List<Int> = acceptedIndexesByDemandId.values.flatten().distinct().sorted()
	}

	data class Rejected(val reason: RadioPurposeItemFailure) : RadioPurposeItemQualification
}

/** One source-specific shape check and one historical authority rule shared by radio destinations. */
object RadioPurposeItemQualifier {
	fun wifi(
		payload: WifiWalPayload,
		authority: RadioPurposeItemAuthority,
	): RadioPurposeItemQualification {
		if (authority.sourceKind != SourceDestinationOwnerEntity.SOURCE_WIFI) {
			return rejected(RadioPurposeItemFailure.WRONG_SOURCE)
		}
		if (payload.observations.size > MAX_WIFI_WAL_CHILDREN) {
			return rejected(RadioPurposeItemFailure.DEPENDENCY_OVERFLOW)
		}
		return qualify(
			payload.observations.map { observation ->
				Item(observation.providerTimestampNanos,
					observation.identifierToken.isEmpty() && observation.hasSupportedWifiProductShape())
			},
			authority,
		)
	}

	fun cell(
		payload: CellWalPayload,
		authority: RadioPurposeItemAuthority,
	): RadioPurposeItemQualification {
		if (authority.sourceKind != SourceDestinationOwnerEntity.SOURCE_CELL) {
			return rejected(RadioPurposeItemFailure.WRONG_SOURCE)
		}
		if (payload.observations.size > MAX_CELL_WAL_CHILDREN) {
			return rejected(RadioPurposeItemFailure.DEPENDENCY_OVERFLOW)
		}
		return qualify(
			payload.observations.map { observation ->
				Item(observation.providerTimestampNanos,
					observation.identifierToken.isEmpty() && observation.hasSupportedCellProductShape())
			},
			authority,
		)
	}

	private data class Item(val providerTimeNanos: Long?, val supportedShape: Boolean)

	private fun qualify(
		items: List<Item>,
		authority: RadioPurposeItemAuthority,
	): RadioPurposeItemQualification {
		val timeline = authority.authorizationTimeline
		if (timeline.size > MAX_RADIO_AUTHORIZATION_REVISIONS ||
			authority.immutableDemands.size > MAX_RADIO_AUTHORIZATION_REVISIONS * MAX_RADIO_AUTHORIZATION_MEMBERS ||
			timeline.any { it.members.size > MAX_RADIO_AUTHORIZATION_MEMBERS }
		) return rejected(RadioPurposeItemFailure.DEPENDENCY_OVERFLOW)
		if (authority.providerStartInclusiveNanos < 0L ||
			authority.providerEndExclusiveNanos <= authority.providerStartInclusiveNanos ||
			authority.receivedElapsedNanos < 0L
		) return rejected(RadioPurposeItemFailure.INVALID_PROVIDER_INTERVAL)
		val demandsById = authority.immutableDemands.associateBy(SourceDemandEntity::demandId)
		if (authority.registrationGeneration <= 0L || authority.clockDomainId.isBlank() ||
			demandsById.size != authority.immutableDemands.size || timeline.isEmpty() ||
			timeline.zipWithNext().any { (previous, next) ->
				next.authorizationRevision <= previous.authorizationRevision ||
					next.effectiveElapsedRealtimeNanos < previous.effectiveElapsedRealtimeNanos
			} || timeline.any { !it.isAuthenticFor(authority, demandsById) }
		) return rejected(RadioPurposeItemFailure.AUTHORIZATION_UNVERIFIABLE)

		val callback = timeline.last()
		val demandIdsByRevision = timeline.map { revision ->
			revision.authorizedMembers.mapNotNull(SourceAuthorizationEntity::demandId).toHashSet()
		}
		val accepted = linkedMapOf<String, List<Int>>()
		for (member in callback.authorizedMembers) {
			if (!member.persistenceEligible || member.purpose !in PRODUCT_PURPOSES) continue
			val demand = demandsById.getValue(requireNotNull(member.demandId))
			var continuousFromIndex = timeline.lastIndex
			while (continuousFromIndex > 0 && demand.demandId in demandIdsByRevision[continuousFromIndex - 1]) {
				continuousFromIndex--
			}
			// Every vector was reconstructed from the same immutable demand row above. The
			// uninterrupted suffix prevents revoke/rejoin from lending consent to earlier items.
			val continuousFromNanos = timeline[continuousFromIndex].effectiveElapsedRealtimeNanos
			val indexes = items.indices.filter { index ->
				val item = items[index]
				val time = item.providerTimeNanos ?: return@filter false
				if (!item.supportedShape || time <= 0L || time > authority.receivedElapsedNanos ||
					time < authority.providerStartInclusiveNanos || time >= authority.providerEndExclusiveNanos ||
					demand.requestedBootId != authority.clockDomainId || time < demand.requestedElapsedRealtimeNanos ||
					(demand.retireBootId == authority.clockDomainId &&
						demand.retireElapsedRealtimeNanos?.let { time >= it } == true) ||
					authority.receivedElapsedNanos - time > demand.maximumAgeMs.toSaturatedNanos()
				) return@filter false
				time >= continuousFromNanos
			}
			if (indexes.isNotEmpty()) accepted[demand.demandId] = indexes
		}
		return RadioPurposeItemQualification.Qualified(accepted)
	}

	private fun SourceAuthorizationSnapshot.isAuthenticFor(
		authority: RadioPurposeItemAuthority,
		demandsById: Map<String, SourceDemandEntity>,
	): Boolean {
		if (effectiveBootId != authority.clockDomainId ||
			effectiveElapsedRealtimeNanos > authority.receivedElapsedNanos ||
			members.any { it.sourceKind != authority.sourceKind ||
				it.registrationGeneration != authority.registrationGeneration } ||
			authorizedMembers.mapNotNull { it.demandId }.distinct().size != authorizedMembers.size
		) return false
		val demands = authorizedMembers.map { demandsById[it.demandId] ?: return false }
		val reconstructed = SourceBrokerAuthorization.rows(
			sourceKind = authority.sourceKind,
			registrationGeneration = authority.registrationGeneration,
			authorizationRevision = authorizationRevision,
			demands = demands,
			effectiveBootId = effectiveBootId,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = members.first().effectiveWallTimeMs,
		)
		return reconstructed.sortedBy { it.memberId } == members.sortedBy { it.memberId }
	}

	private fun Long.toSaturatedNanos(): Long =
		if (this > Long.MAX_VALUE / NANOS_PER_MILLISECOND) Long.MAX_VALUE else this * NANOS_PER_MILLISECOND

	private fun rejected(reason: RadioPurposeItemFailure) = RadioPurposeItemQualification.Rejected(reason)

	private val PRODUCT_PURPOSES = setOf(SourceBrokerPurpose.SESSION_CAPTURE, SourceBrokerPurpose.AMBIENT_PRODUCT)
	private const val MAX_RADIO_AUTHORIZATION_REVISIONS = 256
	private const val MAX_RADIO_AUTHORIZATION_MEMBERS = 64
	private const val NANOS_PER_MILLISECOND = 1_000_000L
}
