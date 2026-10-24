package com.adsamcik.tracker.tracker.source.location

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEffectChecksum
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceEventId

/**
 * Repairs only the unused positive revision produced by a delayed internal ambient replacement.
 * Original observations, demand identities, negative boundaries and projection cursors are untouched.
 */
// Keep the bounded historical proof and its compare-and-set in one corrective transaction.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
internal suspend fun AppDatabase.repairRetroactiveLocationAuthorization(eventId: SourceEventId): Boolean =
	withTransaction {
		val wal = sourceEventWalDao().boundedPayloadByEventId(eventId.value,
			LocationWalQualificationAdapter.MAX_LOCATION_PAYLOAD_BYTES) ?: return@withTransaction false
		if (wal.sourceKind != LOCATION || !wal.hasQualifiedIntegrity()) {
			return@withTransaction false
		}
		val dao = sourceBrokerDao()
		suspend fun exact(rows: List<SourceAuthorizationEntity>) = exactRepairAuthorization(rows, wal.clockDomainId)
		val captured = exact(dao.authorizationRevisionBounded(LOCATION, wal.registrationGeneration,
			wal.authorizationRevision ?: return@withTransaction false, MEMBERS + 1)) ?: return@withTransaction false
		val previous = exact(dao.nextAuthorizationRevisionBounded(LOCATION, wal.registrationGeneration,
			captured.header.authorizationRevision, MEMBERS + 1)) ?: return@withTransaction false
		val target = exact(dao.nextAuthorizationRevisionBounded(LOCATION, wal.registrationGeneration,
			previous.header.authorizationRevision, MEMBERS + 1)) ?: return@withTransaction false
		val boundary = previous.header.effectiveElapsedRealtimeNanos
		val observed = dao.authorizationAtBounded(LOCATION, wal.registrationGeneration, wal.clockDomainId,
			wal.observedElapsedNanos, MEMBERS + 1)
		if (captured.header.authorizationFingerprint != wal.authorizationFingerprint ||
			observed != target.header.members ||
			captured.header.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos ||
			wal.observedElapsedNanos >= boundary || target.header.effectiveElapsedRealtimeNanos >= boundary ||
			!captured.hasSameGrants(previous) || !previous.hasSameGrants(target) ||
			target.demands.maxOfOrNull { it.requestedElapsedRealtimeNanos } != boundary) {
			return@withTransaction false
		}
		val changes = previous.demands.filter { old -> target.demands.none { it.demandId == old.demandId } }
		val old = changes.singleOrNull() ?: return@withTransaction false
		val replacement = target.demands.singleOrNull { it.consumerId == old.consumerId && it.purpose == old.purpose }
			?: return@withTransaction false
		if (old.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT ||
			old.retireBootId != wal.clockDomainId ||
			old.retireElapsedRealtimeNanos != target.header.effectiveElapsedRealtimeNanos ||
			replacement.requestedElapsedRealtimeNanos != target.header.effectiveElapsedRealtimeNanos ||
			replacement.requestedAtMs != target.header.members.first().effectiveWallTimeMs ||
			!isInternalLocationReplacement(old, replacement, target.header.members.first().effectiveWallTimeMs,
				wal.capturedCollectedDataEpoch)) {
			return@withTransaction false
		}
		val nextRows = dao.nextAuthorizationRevisionBounded(LOCATION, wal.registrationGeneration,
			target.header.authorizationRevision, MEMBERS + 1)
		if (nextRows.isNotEmpty() &&
			(exact(nextRows)?.header?.effectiveElapsedRealtimeNanos ?: -1L) < boundary) {
			return@withTransaction false
		}
		val changed = dao.repairUnusedPositiveAuthorizationBoundary(LOCATION, wal.registrationGeneration,
			target.header.authorizationRevision, target.header.authorizationFingerprint, wal.clockDomainId,
			target.header.effectiveElapsedRealtimeNanos, target.header.members.first().effectiveWallTimeMs,
			boundary, previous.header.members.first().effectiveWallTimeMs)
		check(changed == 0 || changed == target.header.members.size) { "Partial Location authorization repair" }
		changed > 0
	}

private data class RepairAuthorization(val header: SourceAuthorizationSnapshot, val demands: List<SourceDemandEntity>)

private suspend fun AppDatabase.exactRepairAuthorization(
	rows: List<SourceAuthorizationEntity>, bootId: String,
): RepairAuthorization? {
	if (rows.size !in 1..MEMBERS) {
		return null
	}
	val header = runCatching { rows.toAuthorizationSnapshotOrNull() }.getOrNull() ?: return null
	val ids = header.authorizedMembers.mapNotNull { it.demandId }
	val demands = sourceBrokerDao().demandsByIds(ids)
	if (header.effectiveBootId != bootId || ids.distinct().size != ids.size || demands.size != ids.size ||
		demands.any { it.requestedBootId != bootId }) {
		return null
	}
	val expected = SourceBrokerAuthorization.rows(LOCATION, rows.first().registrationGeneration,
		header.authorizationRevision, demands, bootId, header.effectiveElapsedRealtimeNanos,
		rows.first().effectiveWallTimeMs)
	return RepairAuthorization(header, demands).takeIf {
		expected.sortedBy { it.memberId } == rows.sortedBy { it.memberId }
	}
}

// Compare each retained consent and destination field explicitly; manifest refreshes remain allowed.
@Suppress("CyclomaticComplexMethod")
private fun RepairAuthorization.hasSameGrants(other: RepairAuthorization): Boolean =
	!header.isDenied && !other.header.isDenied && header.authorizedMembers.size == other.header.authorizedMembers.size &&
		header.authorizedMembers.all { member ->
			other.header.authorizedMembers.singleOrNull {
				it.consumerId == member.consumerId && it.purpose == member.purpose
			}
				?.let { next ->
					member.sourcePolicyRevision == next.sourcePolicyRevision && member.consentEpoch == next.consentEpoch &&
						member.persistenceEligible == next.persistenceEligible &&
						member.logicalTrackingId == next.logicalTrackingId &&
						member.serviceRunId == next.serviceRunId &&
						member.liveAmbientRetentionPolicyId == next.liveAmbientRetentionPolicyId &&
						member.liveAmbientRetentionApprovalRevision == next.liveAmbientRetentionApprovalRevision
				} == true
		}

// The normalized caller proof includes every original consent, lifecycle and retirement field.
@Suppress("CyclomaticComplexMethod")
private suspend fun AppDatabase.isInternalLocationReplacement(
	old: SourceDemandEntity, replacement: SourceDemandEntity, wallTimeMs: Long, dataEpoch: Long,
): Boolean {
	val dao = sourceCallerAuthorityDao()
	val oldRows = old.sourceCallerAuthorityReference?.let { dao.rowsBounded(it, MEMBERS + 1) }.orEmpty()
	val newRows = replacement.sourceCallerAuthorityReference?.let { dao.rowsBounded(it, MEMBERS + 1) }.orEmpty()
	val before = oldRows.singleOrNull() ?: return false
	val after = newRows.singleOrNull() ?: return false
	if (!SourceCallerAcceptedAuthorityEffectChecksum.isAuthentic(oldRows) ||
		!SourceCallerAcceptedAuthorityEffectChecksum.isAuthentic(newRows)) {
		return false
	}
	return before.formatVersion == SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION &&
		after.formatVersion == SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION &&
		before.origin == "PURPOSE_OWNER" && after.origin == "PURPOSE_OWNER" &&
		before.sourceKind == LOCATION && after.sourceKind == LOCATION &&
		before.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && after.purpose == before.purpose &&
		before.acceptedPurpose == before.purpose && after.acceptedPurpose == after.purpose &&
		before.policyRevision == old.sourcePolicyRevision && after.policyRevision == before.policyRevision &&
		before.consentEpoch == old.consentEpoch && after.consentEpoch == before.consentEpoch &&
		before.collectedDataEpoch == dataEpoch && after.collectedDataEpoch == dataEpoch &&
		before.retainedFromMs == after.retainedFromMs &&
		before.status == "RETIRED" && before.retireReason == "AMBIENT_LOCATION_REPLACED" &&
		before.retiredAtMs == wallTimeMs && after.createdAtMs == wallTimeMs
}

private const val LOCATION = 1
private const val MEMBERS = 16
