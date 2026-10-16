package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIntegrity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1

/**
 * Invalid saved imported Steps lineage or exceeded dependency/revision bounds.
 */
enum class ImportedAmbientStepsLineageFailureReason {
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	REVISION_OVERFLOW,
}

/**
 * Carries a specific imported Steps lineage failure instead of returning a partial verified day.
 */
class ImportedAmbientStepsLineageFailure(
	val reason: ImportedAmbientStepsLineageFailureReason,
) : IllegalStateException(reason.name)

/**
 * Original imported day revision header paired with its verified portable day content.
 */
data class AuthenticatedImportedAmbientStepsRevision(
	val header: ImportedAmbientStepsDayRevisionEntity,
	val day: PortableAmbientStepsDayV1,
)

/**
 * Complete verified day correction history with original archive memberships, receipts, facts and
 * gaps.
 */
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
						member.boundCountDomainGraphRevision,
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

	/**
	 * Enumerates original day/fact/gap identities and effects that must remain fenced when the
	 * imported payload is removed.
	 */
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
	/**
	 * Reconstructs and verifies the complete original day correction lineage under the expected epoch
	 * and optional retained-state floor.
	 */
	fun authenticate(
		dayIdentity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedAmbientStepsDayRevisionEntity>,
		archives: List<ImportedAmbientStepsArchiveEntity>,
		archiveDays: List<ImportedAmbientStepsArchiveDayEntity>,
		receipts: List<ImportedAmbientStepsReceiptEntity>,
		facts: List<ImportedAmbientStepsFactEntity>,
		gaps: List<ImportedAmbientStepsGapEntity>,
		retainedState: SourceEvidenceState? = null,
	): AuthenticatedImportedAmbientStepsLineage = try {
		authenticateUnchecked(
			dayIdentity,
			expectedCollectedDataEpoch,
			headers,
			archives,
			archiveDays,
			receipts,
			facts,
			gaps,
			retainedState,
		)
	} catch (failure: ImportedAmbientStepsLineageFailure) {
		throw failure
	} catch (_: IllegalArgumentException) {
		corrupt()
	} catch (_: IllegalStateException) {
		corrupt()
	} catch (_: ArithmeticException) {
		corrupt()
	} catch (_: java.time.DateTimeException) {
		corrupt()
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private fun authenticateUnchecked(
		dayIdentity: String,
		expectedCollectedDataEpoch: Long,
		headers: List<ImportedAmbientStepsDayRevisionEntity>,
		archives: List<ImportedAmbientStepsArchiveEntity>,
		archiveDays: List<ImportedAmbientStepsArchiveDayEntity>,
		receipts: List<ImportedAmbientStepsReceiptEntity>,
		facts: List<ImportedAmbientStepsFactEntity>,
		gaps: List<ImportedAmbientStepsGapEntity>,
		retainedState: SourceEvidenceState? = null,
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
			if (archives.isNotEmpty() || archiveDays.isNotEmpty() || receipts.isNotEmpty()) {
				corrupt()
			}
			if (facts.isNotEmpty() || gaps.isNotEmpty()) {
				corrupt()
			}
			return AuthenticatedImportedAmbientStepsLineage(
				emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
			)
		}
		if (!ImportedAmbientStepsIdentity.isDigest(dayIdentity) ||
			headers.any { it.dayIdentity != dayIdentity }
		) {
			corrupt()
		}
		if (headers.map { it.importRevision } != (1L..headers.size.toLong()).toList() ||
			headers.map { it.dayContentChecksum }.distinct().size != headers.size ||
			headers.any { !(retainedState?.acceptsRetainedEpoch(it.collectedDataEpoch) ?:
                (it.collectedDataEpoch == expectedCollectedDataEpoch)) }
		) {
			corrupt()
		}

		val baseline = headers.first()
		// One immutable day-structure tuple must match the original header in every revision.
		@Suppress("ComplexCondition")
		if (headers.any { header ->
				header.structuralEpochDay != baseline.structuralEpochDay ||
					header.storedZoneId != baseline.storedZoneId ||
					header.structuralDayStartTimeMs != baseline.structuralDayStartTimeMs ||
					header.structuralDayEndTimeMs != baseline.structuralDayEndTimeMs ||
					header.deletionScopeIdentity != baseline.deletionScopeIdentity
			}
		) {
			corrupt()
		}

		val archiveById = archives.associateBy(ImportedAmbientStepsArchiveEntity::archiveIdentity)
		if (archiveById.size != archives.size ||
			archives.any { !(retainedState?.acceptsRetainedEpoch(it.collectedDataEpoch) ?:
                (it.collectedDataEpoch == expectedCollectedDataEpoch)) }
		) {
			corrupt()
		}
		val membersByArchive = archiveDays.groupBy(ImportedAmbientStepsArchiveDayEntity::archiveIdentity)
		if (archiveDays.any { member -> archiveById[member.archiveIdentity] == null } ||
			archiveDays.filter { it.dayIdentity == dayIdentity }.isEmpty()
		) {
			corrupt()
		}
		membersByArchive.forEach { (archiveIdentity, members) ->
			val archive = archiveById.getValue(archiveIdentity)
			if (members.map { it.ordinal } != (0 until members.size).toList() ||
				members.map { it.dayIdentity }.distinct().size != members.size ||
				members.size != archive.dayCount
			) {
				corrupt()
			}
			if (members.sumOf { it.factCount.toLong() } != archive.factCount.toLong() ||
				members.sumOf { it.gapCount.toLong() } != archive.gapCount.toLong()
			) {
				corrupt()
			}
			if (archive.sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION) {
				val checksum = AmbientStepsPortableIntegrity.archiveChecksumForMembers(
					members.map { member ->
						AmbientStepsPortableOpaqueIdentity(member.dayIdentity) to
							AmbientStepsPortableDigest(member.dayContentChecksum)
					},
				)
				if (checksum.value != archive.contentChecksum) {
					corrupt()
				}
			} else if (archive.sourceSchemaVersion != 2) {
				corrupt()
			}
		}
		val receiptArchiveIds = receipts.map(ImportedAmbientStepsReceiptEntity::archiveIdentity).toSet()
		val receiptsByArchive = receipts.groupBy(ImportedAmbientStepsReceiptEntity::archiveIdentity)
		if (receipts.any { receipt ->
				val archive = archiveById[receipt.archiveIdentity]
				archive == null ||
					receipt.archiveContentChecksum != archive.contentChecksum ||
					!(retainedState?.acceptsRetainedEpoch(receipt.collectedDataEpoch) ?:
                        (receipt.collectedDataEpoch == expectedCollectedDataEpoch))
			}
		) {
			corrupt()
		}
		if (archiveById.keys != receiptArchiveIds ||
			receipts.groupingBy(ImportedAmbientStepsReceiptEntity::archiveIdentity)
				.eachCount().values.any { it > ImportedAmbientStepsDao.MAX_RECEIPTS_PER_ARCHIVE }
		) {
			corrupt()
		}
		archiveById.values.forEach { archive ->
			if (receiptsByArchive[archive.archiveIdentity].orEmpty()
					.minOfOrNull(ImportedAmbientStepsReceiptEntity::receivedAtMs) !=
				archive.firstReceivedAtMs
			) {
				corrupt()
			}
		}

		val factsByRevision = facts.groupBy(ImportedAmbientStepsFactEntity::dayImportRevision)
		val gapsByRevision = gaps.groupBy(ImportedAmbientStepsGapEntity::dayImportRevision)
		if (facts.any { it.dayIdentity != dayIdentity } || gaps.any { it.dayIdentity != dayIdentity } ||
			facts.map { it.dayImportRevision }.any { it !in 1L..headers.size.toLong() } ||
			gaps.map { it.dayImportRevision }.any { it !in 1L..headers.size.toLong() }
		) {
			corrupt()
		}
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
			if (portableFacts.size != header.factCount || portableGaps.size != header.gapCount) {
				corrupt()
			}
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
			// The archive member must match the saved day revision, checksum and counts.
			@Suppress("ComplexCondition")
			if (member.boundDayImportRevision != header.importRevision ||
				member.boundCountDomainGraphRevision <= 0L ||
				member.dayContentChecksum != header.dayContentChecksum ||
				member.factCount != header.factCount || member.gapCount != header.gapCount
			) {
				corrupt()
			}
			AuthenticatedImportedAmbientStepsRevision(header, day)
		}
		archiveDays.filter { it.dayIdentity == dayIdentity }.forEach { member ->
			val revision = revisions.singleOrNull {
				it.header.importRevision == member.boundDayImportRevision
			} ?: corrupt()
			if (revision.header.dayContentChecksum != member.dayContentChecksum ||
				member.boundCountDomainGraphRevision <= 0L
			) {
				corrupt()
			}
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

/**
 * Loads bounded original day/archive/receipt/fact/gap rows and verifies one complete imported
 * Steps correction lineage.
 */
suspend fun ImportedAmbientStepsDao.loadAuthenticatedAmbientStepsLineage(
	dayIdentity: String,
	expectedCollectedDataEpoch: Long,
	retainedState: SourceEvidenceState? = null,
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
	val archiveIdentities = memberships.map { it.archiveIdentity }.distinct()
	val archives = if (archiveIdentities.isEmpty()) {
		emptyList()
	} else {
		archives(archiveIdentities, archiveIdentities.size + 1)
	}
	val allMemberships = if (archiveIdentities.isEmpty()) {
		emptyList()
	} else {
		archiveDays(
			archiveIdentities,
			ImportedAmbientStepsDao.MAX_ARCHIVE_MEMBERS_PER_LINEAGE + 1,
		)
	}
	val receipts = if (archiveIdentities.isEmpty()) {
		emptyList()
	} else {
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
		retainedState = retainedState,
	)
}

/**
 * Verifies original imported Steps deletion/retention/source fences against the expected data
 * epoch and optional retained floor.
 */
suspend fun ImportedAmbientStepsDao.authenticateAllAmbientStepsFences(
	expectedCollectedDataEpoch: Long,
	retainedState: SourceEvidenceState? = null,
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
		fences.any { !(retainedState?.acceptsRetainedEpoch(it.collectedDataEpoch) ?:
            (it.collectedDataEpoch == expectedCollectedDataEpoch)) }
	) {
		corruptAmbientStepsFences()
	}
	val fencesByOwner = fences.associateBy(ImportedAmbientStepsDayFenceEntity::dayIdentity)
	if (fencesByOwner.size != fences.size) {
		corruptAmbientStepsFences()
	}
	authenticateAmbientStepsFenceMarkers(markerCount, fencesByOwner)
}

private suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsFenceMarkers(
	markerCount: Long,
	fencesByOwner: Map<String, ImportedAmbientStepsDayFenceEntity>,
) {
	var loadedMarkers = 0L
	var afterOwner: String? = null
	var afterIdentity: String? = null
	var currentOwner: String? = null
	val currentMarkers = mutableListOf<ImportedAmbientStepsProtectedIdentityEntity>()
	val authenticatedOwners = linkedSetOf<String>()

	while (true) {
		val priorOwner = afterOwner
		val priorIdentity = afterIdentity
		val page = protectedIdentityPage(
			priorOwner,
			priorIdentity,
			ImportedAmbientStepsDao.FENCE_AUDIT_PAGE_SIZE,
		)
		if (page.isEmpty()) {
			break
		}
		requireOrderedAmbientStepsFencePage(page, priorOwner, priorIdentity)
		currentOwner = appendAuthenticatedAmbientStepsFencePage(
			page, currentOwner, currentMarkers, fencesByOwner, authenticatedOwners,
		)
		loadedMarkers = checkedAmbientStepsMarkerCount(loadedMarkers, page.size)
		afterOwner = page.last().ownerDayIdentity
		afterIdentity = page.last().protectedIdentity
		if (page.size < ImportedAmbientStepsDao.FENCE_AUDIT_PAGE_SIZE) {
			break
		}
	}
	authenticateAmbientStepsFenceGroup(
		currentOwner, currentMarkers, fencesByOwner, authenticatedOwners,
	)
	if (loadedMarkers != markerCount || authenticatedOwners != fencesByOwner.keys) {
		throw ImportedAmbientStepsLineageFailure(
			ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}
}

private fun appendAuthenticatedAmbientStepsFencePage(
	page: List<ImportedAmbientStepsProtectedIdentityEntity>,
	previousOwner: String?,
	currentMarkers: MutableList<ImportedAmbientStepsProtectedIdentityEntity>,
	fencesByOwner: Map<String, ImportedAmbientStepsDayFenceEntity>,
	authenticatedOwners: MutableSet<String>,
): String? {
	var currentOwner = previousOwner
	page.forEach { marker ->
		if (currentOwner != null && marker.ownerDayIdentity != currentOwner) {
			authenticateAmbientStepsFenceGroup(
				currentOwner, currentMarkers, fencesByOwner, authenticatedOwners,
			)
		}
		currentOwner = marker.ownerDayIdentity
		currentMarkers += marker
	}
	return currentOwner
}

private fun checkedAmbientStepsMarkerCount(loadedMarkers: Long, pageSize: Int): Long {
	val nextCount = try {
		Math.addExact(loadedMarkers, pageSize.toLong())
	} catch (_: ArithmeticException) {
		throw ImportedAmbientStepsLineageFailure(
			ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (nextCount > ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES) {
		throw ImportedAmbientStepsLineageFailure(
			ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
		)
	}
	return nextCount
}

private fun authenticateAmbientStepsFenceGroup(
	currentOwner: String?,
	currentMarkers: MutableList<ImportedAmbientStepsProtectedIdentityEntity>,
	fencesByOwner: Map<String, ImportedAmbientStepsDayFenceEntity>,
	authenticatedOwners: MutableSet<String>,
) {
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

private fun requireOrderedAmbientStepsFencePage(
	page: List<ImportedAmbientStepsProtectedIdentityEntity>,
	priorOwner: String?,
	priorIdentity: String?,
) {
	if (page.size > ImportedAmbientStepsDao.FENCE_AUDIT_PAGE_SIZE) {
		corruptAmbientStepsFences()
	}
	if (page.zipWithNext().any { (left, right) ->
			left.ownerDayIdentity > right.ownerDayIdentity ||
				left.ownerDayIdentity == right.ownerDayIdentity &&
				left.protectedIdentity >= right.protectedIdentity
		}
	) {
		corruptAmbientStepsFences()
	}
	if (priorOwner != null && (
			page.first().ownerDayIdentity < priorOwner ||
				page.first().ownerDayIdentity == priorOwner &&
				page.first().protectedIdentity <= requireNotNull(priorIdentity)
			)
	) {
		corruptAmbientStepsFences()
	}
}

private fun corruptAmbientStepsFences(): Nothing =
	throw ImportedAmbientStepsLineageFailure(
		ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE,
	)

/** Only historical saved data uses restored epochs; acquisition callers retain the strict overload. */
suspend fun ImportedAmbientStepsDao.loadAuthenticatedAmbientStepsLineage(dayIdentity: String,
    state: SourceEvidenceState): AuthenticatedImportedAmbientStepsLineage =
	loadAuthenticatedAmbientStepsLineage(dayIdentity, state.collectedDataEpoch, retainedState = state)

/**
 * Verifies original imported Steps deletion/retention/source fences against the expected data
 * epoch and optional retained floor.
 */
suspend fun ImportedAmbientStepsDao.authenticateAllAmbientStepsFences(state: SourceEvidenceState) =
	authenticateAllAmbientStepsFences(state.collectedDataEpoch, retainedState = state)
