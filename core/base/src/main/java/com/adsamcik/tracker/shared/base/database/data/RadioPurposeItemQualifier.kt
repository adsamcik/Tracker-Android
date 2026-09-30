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
	UNSUPPORTED_PAYLOAD_VERSION,
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
		payloadVersion: Int = WIFI_WAL_PAYLOAD_VERSION,
	): RadioPurposeItemQualification {
		if (authority.sourceKind != SourceDestinationOwnerEntity.SOURCE_WIFI) {
			return rejected(RadioPurposeItemFailure.WRONG_SOURCE)
		}
		if (!isSupportedWifiWalPayloadVersion(payloadVersion)) {
			return rejected(RadioPurposeItemFailure.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (payload.observations.size > MAX_WIFI_WAL_CHILDREN) {
			return rejected(RadioPurposeItemFailure.DEPENDENCY_OVERFLOW)
		}
		return qualify(
			payload.observations.map { observation ->
				Item(observation.providerTimestampNanos,
					(payloadVersion != LEGACY_WIFI_WAL_PAYLOAD_VERSION ||
						observation.identifierToken.isEmpty() && observation.networkName == null) &&
						observation.hasSupportedWifiProductShape())
			},
			authority,
		)
	}

	fun cell(
		payload: CellWalPayload,
		authority: RadioPurposeItemAuthority,
		payloadVersion: Int = CELL_WAL_PAYLOAD_VERSION,
	): RadioPurposeItemQualification {
		if (authority.sourceKind != SourceDestinationOwnerEntity.SOURCE_CELL) {
			return rejected(RadioPurposeItemFailure.WRONG_SOURCE)
		}
		if (payloadVersion !in LEGACY_CELL_WAL_PAYLOAD_VERSION..CELL_WAL_PAYLOAD_VERSION) {
			return rejected(RadioPurposeItemFailure.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (payload.observations.size > MAX_CELL_WAL_CHILDREN) {
			return rejected(RadioPurposeItemFailure.DEPENDENCY_OVERFLOW)
		}
		return qualify(
			payload.observations.map { observation ->
				Item(observation.providerTimestampNanos,
					(payloadVersion != LEGACY_CELL_WAL_PAYLOAD_VERSION ||
						observation.identifierToken.isEmpty()) &&
						observation.hasSupportedCellProductShape())
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

		val accepted = linkedMapOf<String, MutableList<Int>>()
		items.forEachIndexed { index, item ->
			val time = item.providerTimeNanos ?: return@forEachIndexed
			if (!item.supportedShape || time <= 0L || time > authority.receivedElapsedNanos ||
				time < authority.providerStartInclusiveNanos || time >= authority.providerEndExclusiveNanos
			) return@forEachIndexed
			// Resolve permission at the reading's time. A callback can arrive after one purpose
			// retires while another still owns the physical provider.
			val active = timeline.lastOrNull { it.effectiveElapsedRealtimeNanos <= time }
				?: return@forEachIndexed
			active.authorizedMembers.forEach memberLoop@ { member ->
				if (!member.persistenceEligible || member.purpose !in PRODUCT_PURPOSES) return@memberLoop
				val demand = demandsById.getValue(requireNotNull(member.demandId))
				if (demand.requestedBootId != authority.clockDomainId ||
					time < demand.requestedElapsedRealtimeNanos ||
					(demand.retireBootId == authority.clockDomainId &&
						demand.retireElapsedRealtimeNanos?.let { time >= it } == true) ||
					authority.receivedElapsedNanos - time > demand.maximumAgeMs.toSaturatedNanos()
				) return@memberLoop
				accepted.getOrPut(demand.demandId) { mutableListOf() } += index
			}
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
