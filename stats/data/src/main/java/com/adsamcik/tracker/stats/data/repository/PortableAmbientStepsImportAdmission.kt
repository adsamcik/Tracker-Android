package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.authenticateAllAmbientStepsFences
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDayIdentityOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsFactIdentityOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsGapIdentityOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsNativeReplayFootprintIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsSourceFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainRootEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason

internal suspend fun AppDatabase.readAmbientStepsImportState(
	request: AmbientImportEnvelope,
	dao: ImportedAmbientStepsDao,
	requireRetentionAuthority: Boolean,
): SourceEvidenceState {
	val state = ambientStepsStoredValue { sourceEvidenceStateDao().get() }
		?: unverifiableAmbientStepsImport(
			PortableAmbientStepsImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
		)
	if (state.retainedFromMs?.let { it < 0L } == true) {
		corruptAmbientStepsImport()
	}
	if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
	}
	if (requireRetentionAuthority) {
		val retention = ambientStepsStoredValue {
			ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
			)
		}
		if (!retention.permitsAmbientStepsImport(request, state)) {
			blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.RETENTION_POLICY_UNAVAILABLE)
		}
	}
	// Existing saved identities can belong to a confirmed restore; this does not admit an old job.
	ambientStepsStoredValue { dao.authenticateAllAmbientStepsFences(state) }
	authenticateAmbientStepsSourceDeletionFence(dao, state)
	return state
}

private fun AmbientStepsRetentionAuthorityEntity?.permitsAmbientStepsImport(
	request: AmbientImportEnvelope,
	state: SourceEvidenceState,
): Boolean {
	if (this == null || !AmbientStepsRetentionAuthorityIntegrity.isAuthentic(this) || !isActive) {
		return false
	}
	return collectedDataEpoch == request.expectedCollectedDataEpoch && retainedFromMs == state.retainedFromMs
}

internal suspend fun AppDatabase.authenticateAmbientStepsNativeReplayFootprints(
	incoming: IncomingAmbientStepsOwnership,
	state: SourceEvidenceState,
) {
	val nativeFootprints = incoming.allIdentities
		.chunked(AMBIENT_STEPS_IMPORT_IDENTITY_CHUNK_SIZE)
		.flatMap { identities ->
			ambientStepsStoredValue {
				ambientStepsFactRevisionDao().nativeReplayFootprints(
					identities,
					identities.size + 1,
				)
			}
		}
	if (nativeFootprints.any {
			!AmbientStepsNativeReplayFootprintIntegrity.isAuthentic(it) ||
				!state.acceptsRetainedEpoch(it.collectedDataEpoch)
		}) {
		corruptAmbientStepsImport()
	}
	if (nativeFootprints.isNotEmpty()) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.LOCAL_ORIGIN_OVERLAP)
	}
}

internal suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsDayAdmission(
	archive: PortableAmbientStepsArchiveV1,
	dayIdentities: List<String>,
	state: SourceEvidenceState,
) {
	authenticateAmbientStepsStructuralDayOwnership(archive)
	val fences = ambientStepsStoredValue { fences(dayIdentities) }
	if (fences.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) }) {
		corruptAmbientStepsImport()
	}
	if (fences.any { it.fenceKind == ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION }) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.RETAINED_DAY)
	}
	if (fences.isNotEmpty()) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.DELETED_DAY)
	}
}

internal suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsProtectedIdentities(
	incoming: IncomingAmbientStepsOwnership,
	dayIdentities: List<String>,
) {
	val protected = incoming.allIdentities.chunked(AMBIENT_STEPS_IMPORT_IDENTITY_CHUNK_SIZE).flatMap {
		values -> ambientStepsStoredValue { protectedIdentityOwners(values, values.size + 1) }
	}
	if (protected.any { it.ownerDayIdentity !in dayIdentities } || protected.isNotEmpty()) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
}

internal suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsReceiptIdentityOwnership(
	request: AmbientImportEnvelope,
) {
	val identity = ImportedAmbientStepsIdentity.receipt(
		request.receipt.jobId,
		request.receipt.archiveKey,
	)
	val existingReceipt = ambientStepsStoredValue { receiptByIdentity(identity) }
	if (existingReceipt != null &&
		(existingReceipt.importJobId != request.receipt.jobId ||
			existingReceipt.archiveKey != request.receipt.archiveKey)
	) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT)
	}
	val values = listOf(identity)
	if (hasAmbientStepsReceiptIdentityCollision(values)) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT)
	}
}

private suspend fun ImportedAmbientStepsDao.hasAmbientStepsReceiptIdentityCollision(
	values: List<String>,
): Boolean {
	if (ambientStepsStoredValue { existingArchiveIdentities(values, 2) }.isNotEmpty() ||
		ambientStepsStoredValue { existingDayIdentityOwners(values, 2) }.isNotEmpty()
	) {
		return true
	}
	return ambientStepsStoredValue { existingFactIdentityOwners(values, 2) }.isNotEmpty() ||
		ambientStepsStoredValue { existingGapIdentityOwners(values, 2) }.isNotEmpty() ||
		ambientStepsStoredValue { protectedIdentityOwners(values, 2) }.isNotEmpty()
}

private suspend fun AppDatabase.authenticateAmbientStepsSourceDeletionFence(
	dao: ImportedAmbientStepsDao,
	state: SourceEvidenceState,
) {
	val fence = ambientStepsStoredValue { dao.sourceFence() } ?: return
	if (!state.acceptsRetainedEpoch(fence.collectedDataEpoch)) {
		corruptAmbientStepsImport()
	}
	val policy = readAmbientStepsImportPolicy()
	val consent = ambientStepsStoredValue {
		sourcePolicyDao().latestConsentEpoch(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
	}
	val eligiblePolicy = policy
		?: blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.SOURCE_DELETED)
	val eligibleConsent = consent
		?: blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.SOURCE_DELETED)
	if (!fence.deletionCompleted || !eligiblePolicy.canReopenAmbientStepsImport(eligibleConsent, fence)) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.SOURCE_DELETED)
	}
	if (fence.reopenedConsentEpoch == eligibleConsent.epoch) {
		return
	}
	val replacement = ImportedAmbientStepsSourceFenceEntity.reopened(
		fence,
		eligibleConsent.epoch,
		maxOf(
			state.updatedAtMs,
			eligiblePolicy.effectiveWallTimeMs,
			eligibleConsent.effectiveWallTimeMs,
		),
	)
	if (!ambientStepsStoredValue { dao.replaceSourceFence(fence, replacement) }) {
		corruptAmbientStepsImport()
	}
}

private suspend fun AppDatabase.readAmbientStepsImportPolicy(): SourcePolicyEntity? {
	val authority = ambientStepsStoredValue { sourcePolicyDao().authority() }
	return authority?.takeIf {
		it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
	}?.let {
		ambientStepsStoredValue {
			sourcePolicyDao().policyAtRevision(
				it.currentPolicyRevision,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			)
		}
	}
}

private fun SourcePolicyEntity.canReopenAmbientStepsImport(
	consent: SourceConsentEpochEntity,
	fence: ImportedAmbientStepsSourceFenceEntity,
): Boolean {
	val allowsPersistence = enabled && ambientPersistenceEligible && consent.eligible && consent.persistenceEligible
	return allowsPersistence && ambientConsentEpoch == consent.epoch &&
		consent.policyRevision <= policyRevision && consent.epoch > fence.revokedConsentEpoch
}

private suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsStructuralDayOwnership(
	archive: PortableAmbientStepsArchiveV1,
) {
	val days = archive.days
	val firstEpochDay = days.minOf(PortableAmbientStepsDayV1::structuralEpochDay)
	val lastEpochDay = days.maxOf(PortableAmbientStepsDayV1::structuralEpochDay)
	val zoneIds = days.map(PortableAmbientStepsDayV1::storedZoneId).distinct()
	val owners = ambientStepsStoredValue {
		latestDaysForStructuralRange(
			firstEpochDay,
			lastEpochDay,
			zoneIds,
			AmbientStepsPortableFormatV1.MAX_DAYS + 1,
		)
	}
	val fences = ambientStepsStoredValue {
		fencesForStructuralRange(
			firstEpochDay,
			lastEpochDay,
			zoneIds,
			AmbientStepsPortableFormatV1.MAX_DAYS + 1,
		)
	}
	if (owners.size > AmbientStepsPortableFormatV1.MAX_DAYS ||
		fences.size > AmbientStepsPortableFormatV1.MAX_DAYS
	) {
		ambientStepsImportOverflow()
	}
	val incoming = days.associateBy(::StructuralAmbientStepsDayKey)
	val matchingFences = fences.filter { fence ->
		structuralDayKey(fence) in incoming
	}
	if (matchingFences.groupBy(::structuralDayKey).values.any { it.size != 1 }) {
		corruptAmbientStepsImport()
	}
	if (matchingFences.isNotEmpty()) {
		if (matchingFences.any {
				it.fenceKind == ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION
			}
		) {
			blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.RETAINED_DAY)
		}
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.DELETED_DAY)
	}
	owners.forEach { owner ->
		val key = StructuralAmbientStepsDayKey(
			owner.structuralEpochDay,
			owner.storedZoneId,
			owner.structuralDayStartTimeMs,
			owner.structuralDayEndTimeMs,
		)
		val expected = incoming[key] ?: return@forEach
		if (owner.dayIdentity != expected.identity.value) {
			blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
		}
	}
}

internal suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsImportedIdentityOwnership(
	incoming: IncomingAmbientStepsOwnership,
) {
	incoming.allIdentities.chunked(AMBIENT_STEPS_IMPORT_IDENTITY_CHUNK_SIZE).forEach { values ->
		authenticateAmbientStepsIdentityChunk(incoming, values)
	}
}

private suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsIdentityChunk(
	incoming: IncomingAmbientStepsOwnership,
	values: List<String>,
) {
	val limit = values.size + 1
	val archives = ambientStepsStoredValue { existingArchiveIdentities(values, limit) }
	val receipts = ambientStepsStoredValue { existingReceiptIdentities(values, limit) }
	val days = ambientStepsStoredValue { existingDayIdentityOwners(values, limit) }
	val facts = ambientStepsStoredValue { existingFactIdentityOwners(values, limit) }
	val gaps = ambientStepsStoredValue { existingGapIdentityOwners(values, limit) }
	val protected = ambientStepsStoredValue { protectedIdentityOwners(values, limit) }
	if (listOf(archives.size, receipts.size, days.size, facts.size, gaps.size, protected.size)
		.any { it >= limit }
	) {
		ambientStepsImportOverflow()
	}
	if (protected.isNotEmpty() || !incoming.ownsAmbientStepsContainers(archives, receipts)) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
	if (days.any { !incoming.ownsAmbientStepsDay(it) }) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
	if (!incoming.ownsAmbientStepsMeasurements(facts, gaps)) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
}

private fun IncomingAmbientStepsOwnership.ownsAmbientStepsDay(
	owner: ImportedAmbientStepsDayIdentityOwner,
): Boolean {
	val dayOwner = dayOwners[owner.dayIdentity]
	val scopeOwner = scopeOwners[owner.deletionScopeIdentity]
	return kinds[owner.dayIdentity] == IncomingAmbientStepsIdentityKind.DAY &&
		kinds[owner.deletionScopeIdentity] == IncomingAmbientStepsIdentityKind.DELETION_SCOPE &&
		dayOwner == owner.dayIdentity && scopeOwner == owner.dayIdentity
}

private fun IncomingAmbientStepsOwnership.ownsAmbientStepsContainers(
	archives: List<String>,
	receipts: List<String>,
): Boolean = archives.all { kinds[it] == IncomingAmbientStepsIdentityKind.ARCHIVE } &&
	receipts.all { kinds[it] == IncomingAmbientStepsIdentityKind.RECEIPT }

private fun IncomingAmbientStepsOwnership.ownsAmbientStepsMeasurements(
	facts: List<ImportedAmbientStepsFactIdentityOwner>,
	gaps: List<ImportedAmbientStepsGapIdentityOwner>,
): Boolean = facts.all { owner ->
	kinds[owner.factIdentity] == IncomingAmbientStepsIdentityKind.FACT &&
		factOwners[owner.factIdentity] == owner.dayIdentity
} && gaps.all { owner ->
	kinds[owner.gapIdentity] == IncomingAmbientStepsIdentityKind.GAP &&
		gapOwners[owner.gapIdentity] == owner.dayIdentity
}

internal suspend fun AppDatabase.authenticateAmbientStepsCountDomainOwners(request: AmbientImportEnvelope) {
	val graphDao = importedPortableStepsCountDomainDao()
	val incomingOwnerIdentities = request.incomingOwnerIdentities()
	val ownerFences = if (incomingOwnerIdentities.isEmpty()) {
		emptyList()
	} else {
		incomingOwnerIdentities.chunked(AMBIENT_STEPS_IMPORT_IDENTITY_CHUNK_SIZE).flatMap { identities ->
			ambientStepsStoredValue {
				graphDao.ownerFences(identities, identities.size + 1)
			}
		}
	}
	if (ownerFences.isNotEmpty()) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.DELETED_DAY)
	}
	val existingPortableRoots = graphDao.readAmbientStepsPortableRoots(incomingOwnerIdentities)
	if (existingPortableRoots.size >
		com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainFormatV2.MAX_ROOTS
	) {
		ambientStepsImportOverflow()
	}
	if (existingPortableRoots.any { root ->
			!request.ownsRoot(
				containerIdentity = root.containerIdentity,
				productIdentity = root.productIdentity,
				ownerKind = root.ownerKind,
				ownerIdentity = root.ownerIdentity,
			)
		}
	) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
	}
}

private suspend fun ImportedPortableStepsCountDomainDao.readAmbientStepsPortableRoots(
	incomingOwnerIdentities: List<String>,
): List<ImportedPortableStepsCountDomainRootEntity> {
	return if (incomingOwnerIdentities.isEmpty()) {
		emptyList()
	} else {
		val result = mutableListOf<ImportedPortableStepsCountDomainRootEntity>()
		incomingOwnerIdentities.chunked(AMBIENT_STEPS_IMPORT_IDENTITY_CHUNK_SIZE).forEach { identities ->
			result += ambientStepsStoredValue {
				rootsForOwners(
					identities,
					com.adsamcik.tracker.shared.model.steps.portable
						.PortableCountDomainFormatV2.MAX_ROOTS - result.size + 1,
				)
			}
			if (result.size >
				com.adsamcik.tracker.shared.model.steps.portable
					.PortableCountDomainFormatV2.MAX_ROOTS
			) {
				ambientStepsImportOverflow()
			}
		}
		result
	}
}
