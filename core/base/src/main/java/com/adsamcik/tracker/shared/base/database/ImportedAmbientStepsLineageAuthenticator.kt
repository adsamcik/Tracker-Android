package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIntegrity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1

enum class ImportedAmbientStepsLineageFailureReason {
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	REVISION_OVERFLOW,
}

class ImportedAmbientStepsLineageFailure(
	val reason: ImportedAmbientStepsLineageFailureReason,
) : IllegalStateException(reason.name)

data class AuthenticatedImportedAmbientStepsRevision(
	val header: ImportedAmbientStepsDayRevisionEntity,
	val day: PortableAmbientStepsDayV1,
)

data class AuthenticatedImportedAmbientStepsLineage(
	val revisions: List<AuthenticatedImportedAmbientStepsRevision>,
	val archives: List<ImportedAmbientStepsArchiveEntity>,
	val archiveDays: List<ImportedAmbientStepsArchiveDayEntity>,
	val receipts: List<ImportedAmbientStepsReceiptEntity>,
	val facts: List<ImportedAmbientStepsFactEntity>,
	val gaps: List<ImportedAmbientStepsGapEntity>,
) {
	val latest: AuthenticatedImportedAmbientStepsRevision
		get() = revisions.last()

	val lineageChecksum: String
		get() = ImportedAmbientStepsIdentity.digest(
			"tracker-imported-ambient-steps-lineage-v1",
			listOf(
				revisions.map { revision ->
					listOf(
						revision.header.dayIdentity,
						revision.header.importRevision,
						revision.header.archiveIdentity,
						revision.header.dayContentChecksum,
					)
				},
				archiveDays.map { member ->
					listOf(
						member.archiveIdentity,
						member.ordinal,
						member.dayIdentity,
						member.dayContentChecksum,
						member.boundDayImportRevision,
						member.factCount,
						member.gapCount,
					)
				},
				receipts.map { receipt ->
					listOf(
						receipt.importJobId,
						receipt.archiveKey,
						receipt.receiptIdentity,
						receipt.sourceName,
						receipt.receivedAtMs,
						receipt.archiveIdentity,
						receipt.archiveContentChecksum,
						receipt.collectedDataEpoch,
					)
				},
			),
		)

	fun protectedIdentities(): List<ImportedAmbientStepsProtectedIdentityEntity> {
		val owner = latest.header.dayIdentity
		val kinds = linkedMapOf<String, String>()
		fun add(identity: String, kind: String) {
			val previous = kinds.putIfAbsent(identity, kind)
			if (previous != null && previous != kind) {
				throw ImportedAmbientStepsLineageFailure(
					ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
		}
		archiveDays.forEach {
			add(it.archiveIdentity, ImportedAmbientStepsProtectedIdentityEntity.ARCHIVE)
		}
		add(owner, ImportedAmbientStepsProtectedIdentityEntity.DAY)
		add(
			latest.header.deletionScopeIdentity,
			ImportedAmbientStepsProtectedIdentityEntity.DELETION_SCOPE,
		)
		facts.forEach {
			add(it.factIdentity, ImportedAmbientStepsProtectedIdentityEntity.FACT)
		}
		gaps.forEach {
			add(it.gapIdentity, ImportedAmbientStepsProtectedIdentityEntity.GAP)
		}
		receipts.forEach {
			add(
				it.receiptIdentity,
				ImportedAmbientStepsProtectedIdentityEntity.RECEIPT,
			)
		}
		return kinds.map { (identity, kind) ->
			ImportedAmbientStepsProtectedIdentityEntity(identity, owner, kind)
		}
	}
}

/** Pure complete-lineage authentication shared by import, read, deletion, and retention. */
object ImportedAmbientStepsLineageAuthenticator {
	@Suppress("LongMethod", "CyclomaticComplexMethod")
	fun authenticate(
		dayIdentity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedAmbientStepsDayRevisionEntity>,
		archives: List<ImportedAmbientStepsArchiveEntity>,
		archiveDays: List<ImportedAmbientStepsArchiveDayEntity>,
		receipts: List<ImportedAmbientStepsReceiptEntity>,
		facts: List<ImportedAmbientStepsFactEntity>,
		gaps: List<ImportedAmbientStepsGapEntity>,
	): AuthenticatedImportedAmbientStepsLineage {
		if (headers.size > ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY) {
			fail(ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW)
		}
		if (archiveDays.size > ImportedAmbientStepsDao.MAX_ARCHIVE_MEMBERS_PER_LINEAGE ||
			receipts.size > ImportedAmbientStepsDao.MAX_RECEIPTS_PER_LINEAGE ||
			facts.size > ImportedAmbientStepsDao.MAX_TOTAL_FACT_ROWS_PER_LINEAGE ||
			gaps.size > ImportedAmbientStepsDao.MAX_TOTAL_GAP_ROWS_PER_LINEAGE
		) {
			fail(ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW)
		}
		if (headers.isEmpty()) {
			if (archives.isNotEmpty() || archiveDays.isNotEmpty() || receipts.isNotEmpty() ||
				facts.isNotEmpty() || gaps.isNotEmpty()
			) corrupt()
			return AuthenticatedImportedAmbientStepsLineage(
				emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
			)
		}
		if (!ImportedAmbientStepsIdentity.isDigest(dayIdentity) ||
			headers.any { it.dayIdentity != dayIdentity } ||
			headers.map { it.importRevision } != (1L..headers.size.toLong()).toList() ||
			headers.map { it.dayContentChecksum }.distinct().size != headers.size ||
			headers.any { it.collectedDataEpoch != expectedCollectedDataEpoch }
		) corrupt()

		val baseline = headers.first()
		if (headers.any { header ->
				header.structuralEpochDay != baseline.structuralEpochDay ||
					header.storedZoneId != baseline.storedZoneId ||
					header.structuralDayStartTimeMs != baseline.structuralDayStartTimeMs ||
					header.structuralDayEndTimeMs != baseline.structuralDayEndTimeMs ||
					header.deletionScopeIdentity != baseline.deletionScopeIdentity
			}
		) corrupt()

		val archiveById = archives.associateBy(ImportedAmbientStepsArchiveEntity::archiveIdentity)
		if (archiveById.size != archives.size ||
			archives.any { it.collectedDataEpoch != expectedCollectedDataEpoch }
		) corrupt()
		val membersByArchive = archiveDays.groupBy(ImportedAmbientStepsArchiveDayEntity::archiveIdentity)
		if (archiveDays.any { member -> archiveById[member.archiveIdentity] == null } ||
			archiveDays.filter { it.dayIdentity == dayIdentity }.isEmpty()
		) corrupt()
		membersByArchive.forEach { (archiveIdentity, members) ->
			val archive = archiveById.getValue(archiveIdentity)
			if (members.map { it.ordinal } != (0 until members.size).toList() ||
				members.map { it.dayIdentity }.distinct().size != members.size ||
				members.size != archive.dayCount ||
				members.sumOf { it.factCount.toLong() } != archive.factCount.toLong() ||
				members.sumOf { it.gapCount.toLong() } != archive.gapCount.toLong()
			) corrupt()
			val checksum = AmbientStepsPortableIntegrity.archiveChecksumForMembers(
				members.map { member ->
					AmbientStepsPortableOpaqueIdentity(member.dayIdentity) to
						AmbientStepsPortableDigest(member.dayContentChecksum)
				},
			)
			if (checksum.value != archive.contentChecksum) corrupt()
		}
		val receiptArchiveIds = receipts.map(ImportedAmbientStepsReceiptEntity::archiveIdentity).toSet()
		val receiptsByArchive = receipts.groupBy(ImportedAmbientStepsReceiptEntity::archiveIdentity)
		if (receipts.any { receipt ->
				val archive = archiveById[receipt.archiveIdentity]
				archive == null ||
					receipt.archiveContentChecksum != archive.contentChecksum ||
					receipt.collectedDataEpoch != expectedCollectedDataEpoch
			} || archiveById.keys != receiptArchiveIds ||
			receipts.groupingBy(ImportedAmbientStepsReceiptEntity::archiveIdentity)
				.eachCount().values.any { it > ImportedAmbientStepsDao.MAX_RECEIPTS_PER_ARCHIVE }
		) corrupt()
		archiveById.values.forEach { archive ->
			if (receiptsByArchive[archive.archiveIdentity].orEmpty()
					.minOfOrNull(ImportedAmbientStepsReceiptEntity::receivedAtMs) !=
				archive.firstReceivedAtMs
			) corrupt()
		}

		val factsByRevision = facts.groupBy(ImportedAmbientStepsFactEntity::dayImportRevision)
		val gapsByRevision = gaps.groupBy(ImportedAmbientStepsGapEntity::dayImportRevision)
		if (facts.any { it.dayIdentity != dayIdentity } || gaps.any { it.dayIdentity != dayIdentity } ||
			facts.map { it.dayImportRevision }.any { it !in 1L..headers.size.toLong() } ||
			gaps.map { it.dayImportRevision }.any { it !in 1L..headers.size.toLong() }
		) corrupt()
		val revisions = headers.map { header ->
			if (archiveById[header.archiveIdentity]?.firstReceivedAtMs != header.receivedAtMs) {
				corrupt()
			}
			val portableFacts = factsByRevision[header.importRevision].orEmpty().map { row ->
				PortableAmbientStepsFactV1(
					identity = AmbientStepsPortableOpaqueIdentity(row.factIdentity),
					contentChecksum = AmbientStepsPortableDigest(row.contentChecksum),
					intervalStartTimeMs = row.intervalStartTimeMs,
					intervalEndTimeMs = row.intervalEndTimeMs,
					stepCount = row.stepCount,
				)
			}
			val portableGaps = gapsByRevision[header.importRevision].orEmpty().map { row ->
				PortableAmbientStepsGapV1(
					identity = AmbientStepsPortableOpaqueIdentity(row.gapIdentity),
					contentChecksum = AmbientStepsPortableDigest(row.contentChecksum),
					intervalStartTimeMs = row.intervalStartTimeMs,
					intervalEndTimeMs = row.intervalEndTimeMs,
					reason = PortableAmbientStepsGapReason.valueOf(row.reason),
				)
			}
			if (portableFacts.size != header.factCount || portableGaps.size != header.gapCount) corrupt()
			val day = PortableAmbientStepsDayV1(
				identity = AmbientStepsPortableOpaqueIdentity(header.dayIdentity),
				contentChecksum = AmbientStepsPortableDigest(header.dayContentChecksum),
				structuralEpochDay = header.structuralEpochDay,
				storedZoneId = header.storedZoneId,
				structuralDayStartTimeMs = header.structuralDayStartTimeMs,
				structuralDayEndTimeMs = header.structuralDayEndTimeMs,
				retainedFromTimeMs = header.retainedFromTimeMs,
				coverage = PortableAmbientStepsCoverage.valueOf(header.coverage),
				partialCauses = ImportedAmbientStepsIdentity.decodePartialCauses(header.partialCauses),
				retainedStepCount = header.retainedStepCount,
				facts = portableFacts,
				gaps = portableGaps,
			)
			val member = archiveDays.singleOrNull {
				it.archiveIdentity == header.archiveIdentity &&
					it.dayIdentity == header.dayIdentity
			} ?: corrupt()
			if (member.boundDayImportRevision != header.importRevision ||
				member.dayContentChecksum != header.dayContentChecksum ||
				member.factCount != header.factCount || member.gapCount != header.gapCount
			) corrupt()
			AuthenticatedImportedAmbientStepsRevision(header, day)
		}
		archiveDays.filter { it.dayIdentity == dayIdentity }.forEach { member ->
			val revision = revisions.singleOrNull {
				it.header.importRevision == member.boundDayImportRevision
			} ?: corrupt()
			if (revision.header.dayContentChecksum != member.dayContentChecksum) corrupt()
		}
		return AuthenticatedImportedAmbientStepsLineage(
			revisions,
			archives.sortedBy(ImportedAmbientStepsArchiveEntity::archiveIdentity),
			archiveDays.sortedWith(
				compareBy(
					ImportedAmbientStepsArchiveDayEntity::archiveIdentity,
					ImportedAmbientStepsArchiveDayEntity::ordinal,
				),
			),
			receipts.sortedWith(
				compareBy(
					ImportedAmbientStepsReceiptEntity::archiveIdentity,
					ImportedAmbientStepsReceiptEntity::importJobId,
					ImportedAmbientStepsReceiptEntity::archiveKey,
				),
			),
			facts,
			gaps,
		)
	}

	private fun corrupt(): Nothing =
		fail(ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE)

	private fun fail(reason: ImportedAmbientStepsLineageFailureReason): Nothing =
		throw ImportedAmbientStepsLineageFailure(reason)
}

suspend fun ImportedAmbientStepsDao.loadAuthenticatedAmbientStepsLineage(
	dayIdentity: String,
	expectedCollectedDataEpoch: Long,
): AuthenticatedImportedAmbientStepsLineage {
	val headers = dayRevisionsForAdmission(dayIdentity)
	val memberships = archiveDaysForDay(
		dayIdentity,
		ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
	)
	if (memberships.size > ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY) {
		throw ImportedAmbientStepsLineageFailure(
			ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
		)
	}

	suspend fun ImportedAmbientStepsDao.authenticateAllAmbientStepsFences(
		expectedCollectedDataEpoch: Long,
	) {
		val fenceCount = fenceCount()
		val markerCount = protectedIdentityCount()
		if (fenceCount < 0L || fenceCount > ImportedAmbientStepsDao.MAX_GLOBAL_FENCES ||
			markerCount < 0L ||
			markerCount > ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES
		) {
			throw ImportedAmbientStepsLineageFailure(
				ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
			)
		}
		val fences = allFences(Math.toIntExact(fenceCount + 1L))
		if (fences.size.toLong() != fenceCount ||
			fences.any { it.collectedDataEpoch != expectedCollectedDataEpoch }
		) {
			throw ImportedAmbientStepsLineageFailure(
				ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val fencesByOwner = fences.associateBy(ImportedAmbientStepsDayFenceEntity::dayIdentity)
		if (fencesByOwner.size != fences.size) {
			throw ImportedAmbientStepsLineageFailure(
				ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		var loadedMarkers = 0L
		var afterOwner: String? = null
		var afterIdentity: String? = null
		var currentOwner: String? = null
		val currentMarkers = mutableListOf<ImportedAmbientStepsProtectedIdentityEntity>()
		val authenticatedOwners = linkedSetOf<String>()

		fun authenticateCurrent() {
			val owner = currentOwner ?: return
			val fence = fencesByOwner[owner] ?: throw ImportedAmbientStepsLineageFailure(
				ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
			if (currentMarkers.size != fence.protectedIdentityCount ||
				ImportedAmbientStepsIdentity.protectedIdentitySetChecksum(currentMarkers) !=
				fence.protectedIdentitySetChecksum ||
				!authenticatedOwners.add(owner)
			) {
				throw ImportedAmbientStepsLineageFailure(
					ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			currentMarkers.clear()
		}

		while (true) {
			val priorOwner = afterOwner
			val priorIdentity = afterIdentity
			val page = protectedIdentityPage(
				priorOwner,
				priorIdentity,
				ImportedAmbientStepsDao.FENCE_AUDIT_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			if (page.size > ImportedAmbientStepsDao.FENCE_AUDIT_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) ->
					left.ownerDayIdentity > right.ownerDayIdentity ||
						left.ownerDayIdentity == right.ownerDayIdentity &&
						left.protectedIdentity >= right.protectedIdentity
				} || priorOwner != null && (
					page.first().ownerDayIdentity < priorOwner ||
						page.first().ownerDayIdentity == priorOwner &&
						page.first().protectedIdentity <= requireNotNull(priorIdentity)
					)
			) {
				throw ImportedAmbientStepsLineageFailure(
					ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			page.forEach { marker ->
				if (currentOwner != null && marker.ownerDayIdentity != currentOwner) {
					authenticateCurrent()
				}
				currentOwner = marker.ownerDayIdentity
				currentMarkers += marker
			}
			loadedMarkers = try {
				Math.addExact(loadedMarkers, page.size.toLong())
			} catch (_: ArithmeticException) {
				throw ImportedAmbientStepsLineageFailure(
					ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
				)
			}
			if (loadedMarkers > ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES) {
				throw ImportedAmbientStepsLineageFailure(
					ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
				)
			}
			afterOwner = page.last().ownerDayIdentity
			afterIdentity = page.last().protectedIdentity
			if (page.size < ImportedAmbientStepsDao.FENCE_AUDIT_PAGE_SIZE) break
		}
		authenticateCurrent()
		if (loadedMarkers != markerCount || authenticatedOwners != fencesByOwner.keys) {
			throw ImportedAmbientStepsLineageFailure(
				ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
	}
	val archiveIdentities = memberships.map { it.archiveIdentity }.distinct()
	val archives = if (archiveIdentities.isEmpty()) emptyList() else {
		archives(archiveIdentities, archiveIdentities.size + 1)
	}
	val allMemberships = if (archiveIdentities.isEmpty()) emptyList() else {
		archiveDays(
			archiveIdentities,
			ImportedAmbientStepsDao.MAX_ARCHIVE_MEMBERS_PER_LINEAGE + 1,
		)
	}
	val receipts = if (archiveIdentities.isEmpty()) emptyList() else {
		receiptsForArchives(
			archiveIdentities,
			ImportedAmbientStepsDao.MAX_RECEIPTS_PER_LINEAGE + 1,
		)
	}
	return ImportedAmbientStepsLineageAuthenticator.authenticate(
		dayIdentity = dayIdentity,
		expectedCollectedDataEpoch = expectedCollectedDataEpoch,
		headers = headers,
		archives = archives,
		archiveDays = allMemberships,
		receipts = receipts,
		facts = factsForAdmission(dayIdentity),
		gaps = gapsForAdmission(dayIdentity),
	)
}
