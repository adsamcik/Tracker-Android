package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionRunMarker
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import java.security.MessageDigest

internal data class WifiSelectedDeletionAuthority(
	val selectionIdentity: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val protectedIdentities: List<WifiSelectedDeletionProtectedIdentityEntity>,
	val runMarkers: List<WifiSelectedDeletionRunMarker>,
) {
	init {
		require(protectedIdentities.isNotEmpty())
		require(protectedIdentities.all { it.selectionIdentity == selectionIdentity })
		require(protectedIdentities.map {
			it.receiptOrigin
		}.distinct().size == 1)
		require(runMarkers.isNotEmpty())
		require(runMarkers.map(WifiSelectedDeletionRunMarker::runIdentity).distinct().size ==
			runMarkers.size)
		val observations = protectedIdentities.filter {
			it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION
		}
		require(observations.all { observation ->
			observation.aggregateOwnerIdentity?.let { owner ->
				observations.singleOrNull { it.protectedIdentity == owner }
					?.let { ownerMarker -> ownerMarker.aggregateOwnerIdentity == null } == true
			} != false
		})
	}
}

internal object WifiSelectedDeletionAuthorityFactory {
	fun imported(
		lineage: AuthenticatedImportedWifiLineage,
		collectedDataEpoch: Long,
		deletedAtMs: Long,
	): WifiSelectedDeletionAuthority {
		val latest = lineage.revisions.last()
		val entry = latest.entry
		val markers = mutableListOf<WifiSelectedDeletionProtectedIdentityEntity>()
		markers += marker(
			selection = entry.identity.value,
			receiptOrigin = WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
			kind = WifiSelectedDeletionProtectedIdentityEntity.KIND_ENTRY,
			identity = entry.identity.value,
			entry = entry.identity.value,
			run = null,
			scope = null,
			aggregateOwner = null,
			aggregateRevision = null,
			revisions = lineage.revisions.map { it.header.importRevision to it.header.contentChecksum },
			epoch = collectedDataEpoch,
		)
		val runsByIdentity = lineage.revisions.flatMap { revision ->
			revision.entry.runs.map { run -> revision.header.importRevision to run }
		}.groupBy { it.second.identity.value }
		val observationsByIdentity = lineage.revisions.flatMap { revision ->
			revision.entry.runs.flatMap { run ->
				run.observations.map { observation ->
					Triple(revision.header.importRevision, run, observation)
				}
			}
		}.groupBy { it.third.identity.value }
		runsByIdentity.forEach { (runIdentity, versions) ->
			val scopes = versions.map { it.second.deletionScopeDigest.value }.distinct()
			require(scopes.size == 1)
			val scope = scopes.single()
			markers += marker(
				entry.identity.value,
				WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN,
				runIdentity,
				entry.identity.value,
				runIdentity,
				scope,
				null,
				null,
				versions.map { (revision, run) -> revision to run.contentChecksum.value },
				collectedDataEpoch,
			)
			markers += marker(
				entry.identity.value,
				WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_DELETION_SCOPE,
				scope,
				entry.identity.value,
				runIdentity,
				scope,
				null,
				null,
				versions.map { (revision, run) -> revision to run.contentChecksum.value },
				collectedDataEpoch,
			)
		}
		observationsByIdentity.forEach { (observationIdentity, versions) ->
			val runIdentities = versions.map { it.second.identity.value }.distinct()
			require(runIdentities.size == 1)
			val observations = versions.map { it.third }
			val aggregateOwnerIdentities = observations.map {
				it.aggregateOwnerIdentity?.value
			}.distinct()
			require(aggregateOwnerIdentities.size == 1)
			val latestObservation = observations.maxBy { it.semanticRevision }
			markers += marker(
				entry.identity.value,
				WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION,
				observationIdentity,
				entry.identity.value,
				runIdentities.single(),
				null,
				aggregateOwnerIdentities.single(),
				latestObservation.aggregateOwnerSemanticRevision,
				versions.map { (revision, _, observation) ->
					revision to "${observation.semanticRevision}:${observation.contentChecksum.value}"
				},
				collectedDataEpoch,
			)
		}
		return WifiSelectedDeletionAuthority(
			selectionIdentity = entry.identity.value,
			startTimeMs = entry.startTimeMs,
			endTimeMs = entry.endTimeMs,
			protectedIdentities = markers.sortedWith(PROTECTED_ORDER),
			runMarkers = entry.runs.map { run ->
				WifiSelectedDeletionRunMarker(
					run.identity.value,
					entry.identity.value,
					run.deletionScopeDigest.value,
					collectedDataEpoch,
					1L,
					deletedAtMs,
				)
			},
		)
	}

	fun local(
		entry: PortableCapturedWifiEntryV1,
		audit: WifiCapturedAudit,
		collectedDataEpoch: Long,
		deletedAtMs: Long,
	): WifiSelectedDeletionAuthority {
		val markers = mutableListOf<WifiSelectedDeletionProtectedIdentityEntity>()
		markers += marker(
			entry.identity.value,
			WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
			WifiSelectedDeletionProtectedIdentityEntity.KIND_ENTRY,
			entry.identity.value,
			entry.identity.value,
			null,
			null,
			null,
			null,
			listOf(1L to entry.contentChecksum.value),
			collectedDataEpoch,
		)
		entry.runs.forEach { run ->
			markers += marker(
				entry.identity.value,
				WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN,
				run.identity.value,
				entry.identity.value,
				run.identity.value,
				run.deletionScopeDigest.value,
				null,
				null,
				listOf(1L to run.contentChecksum.value),
				collectedDataEpoch,
			)
			markers += marker(
				entry.identity.value,
				WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_DELETION_SCOPE,
				run.deletionScopeDigest.value,
				entry.identity.value,
				run.identity.value,
				run.deletionScopeDigest.value,
				null,
				null,
				listOf(1L to run.contentChecksum.value),
				collectedDataEpoch,
			)
		}
		audit.lineages.forEach { lineage ->
			val latest = lineage.revisions.last()
			val runIdentity = PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.PHYSICAL_RUN,
				lineage.scope.serviceRunId,
			).value
			markers += marker(
				entry.identity.value,
				WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
				WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION,
				PortableWifiOpaqueIdentity.derive(
					PortableWifiIdentityKind.OBSERVATION,
					lineage.logicalFactId,
				).value,
				entry.identity.value,
				runIdentity,
				null,
				lineage.aggregateOwnerLogicalFactId?.let { owner ->
					PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION, owner).value
				},
				lineage.aggregateOwnerSemanticRevision,
				lineage.revisions.map { it.semanticRevision to it.effectChecksum },
				collectedDataEpoch,
			)
			require(latest.collectedDataEpoch == collectedDataEpoch)
		}
		return WifiSelectedDeletionAuthority(
			selectionIdentity = entry.identity.value,
			startTimeMs = entry.startTimeMs,
			endTimeMs = entry.endTimeMs,
			protectedIdentities = markers.sortedWith(PROTECTED_ORDER),
			runMarkers = entry.runs.map { run ->
				WifiSelectedDeletionRunMarker(
					run.identity.value,
					entry.identity.value,
					run.deletionScopeDigest.value,
					collectedDataEpoch,
					1L,
					deletedAtMs,
				)
			},
		)
	}

	@Suppress("LongParameterList")
	private fun marker(
		selection: String,
		receiptOrigin: String,
		kind: String,
		identity: String,
		entry: String,
		run: String?,
		scope: String?,
		aggregateOwner: String?,
		aggregateRevision: Long?,
		revisions: List<Pair<Long, String>>,
		epoch: Long,
	): WifiSelectedDeletionProtectedIdentityEntity {
		require(revisions.isNotEmpty())
		return WifiSelectedDeletionProtectedIdentityEntity.create(
			selectionIdentity = selection,
			receiptOrigin = receiptOrigin,
			identityKind = kind,
			protectedIdentity = identity,
			ownerEntryIdentity = entry,
			ownerRunIdentity = run,
			deletionScopeDigest = scope,
			aggregateOwnerIdentity = aggregateOwner,
			aggregateOwnerSemanticRevision = aggregateRevision,
			revisionCount = revisions.size,
			revisionSetChecksum = revisionChecksum(revisions),
			collectedDataEpoch = epoch,
		)
	}

	private fun revisionChecksum(revisions: List<Pair<Long, String>>): String {
		require(revisions.all { it.first > 0L })
		require(revisions.map { it.first }.distinct().size == revisions.size)
		val canonical = revisions.sortedBy { it.first }.joinToString("") { (revision, checksum) ->
			"$revision:${checksum.length}:$checksum"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }
	}

	private val PROTECTED_ORDER =
		compareBy<WifiSelectedDeletionProtectedIdentityEntity>(
			WifiSelectedDeletionProtectedIdentityEntity::identityKind,
			WifiSelectedDeletionProtectedIdentityEntity::protectedIdentity,
		)
}
