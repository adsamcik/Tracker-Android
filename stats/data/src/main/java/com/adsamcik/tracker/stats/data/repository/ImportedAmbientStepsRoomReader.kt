package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AuthenticatedImportedAmbientStepsGraphRevision
import com.adsamcik.tracker.shared.base.database.AuthenticatedImportedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.AuthenticatedImportedAmbientStepsRevision
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailure
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailureReason
import com.adsamcik.tracker.shared.base.database.loadProvenGraphlessLegacyAmbientLineage
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsSourceFenceEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.authenticateAllAmbientStepsFences
import com.adsamcik.tracker.shared.base.database.hasCompletePortableOwnerLineages
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedImportedAmbientStepsGraphLineage
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_AMBIENT_STEPS_DAY_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportRetryableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveV2Sink
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface ImportedAmbientStepsSnapshot {
	data class Ready(
		val archive: PortableAmbientStepsArchiveV1,
		val products: List<AmbientStepsDayProduct>,
		val authenticatedArchive: PortableAmbientStepsArchiveV2? = null,
	) : ImportedAmbientStepsSnapshot

	data object NoData : ImportedAmbientStepsSnapshot
	data object Deleted : ImportedAmbientStepsSnapshot
	data object Retained : ImportedAmbientStepsSnapshot
	data object RetryableFailure : ImportedAmbientStepsSnapshot

	data class Unverifiable(
		val reason: ImportedAmbientStepsReadFailure,
	) : ImportedAmbientStepsSnapshot
}

internal enum class ImportedAmbientStepsReadFailure {
	COUNT_DOMAIN_GRAPH_UNAVAILABLE,
	SOURCE_EVIDENCE_UNAVAILABLE,
	CORRUPT_RETAINED_STATE,
	DEPENDENCY_OVERFLOW,
}

/** One-transaction portable-origin read; callers may compose these products with native facts. */
internal class ImportedAmbientStepsRoomReader @Inject constructor(
	private val database: AppDatabase,
	private val dao: ImportedAmbientStepsDao,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
	suspend fun read(
		request: ExportPortableAmbientStepsRequest,
		includeCountDomainGraph: Boolean = false,
	): ImportedAmbientStepsSnapshot = withContext(ioDispatcher) {
		try {
			database.withTransaction { readInTransaction(request, includeCountDomainGraph) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedAmbientStepsLineageFailure) {
			ImportedAmbientStepsSnapshot.Unverifiable(
				when (failure.reason) {
					ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
					ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW,
					-> ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW
					ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
						ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE
				},
			)
		} catch (_: IllegalArgumentException) {
			ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
		} catch (_: IllegalStateException) {
			ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
		} catch (_: ArithmeticException) {
			ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
		} catch (_: java.time.DateTimeException) {
			ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
		} catch (_: SQLiteException) {
			ImportedAmbientStepsSnapshot.RetryableFailure
		}
	}

	private suspend fun readInTransaction(
		request: ExportPortableAmbientStepsRequest,
		includeCountDomainGraph: Boolean,
	): ImportedAmbientStepsSnapshot {
		val state = database.sourceEvidenceStateDao().get()
			?: return ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.SOURCE_EVIDENCE_UNAVAILABLE,
			)
		return if (state.retainedFromMs?.let { it < 0L } == true) {
			ImportedAmbientStepsSnapshot.Unverifiable(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
		} else {
			readSourceFenceOutcome(state)
				?: readDayFenceOutcome(request, state)
				?: readCandidateDays(request, state, includeCountDomainGraph)
		}
	}

	private suspend fun readSourceFenceOutcome(state: SourceEvidenceState): ImportedAmbientStepsSnapshot? {
		dao.authenticateAllAmbientStepsFences(state)
		val sourceFence = dao.sourceFence()
		return when {
			sourceFence == null -> null
			!state.acceptsRetainedEpoch(sourceFence.collectedDataEpoch) ->
				ImportedAmbientStepsSnapshot.Unverifiable(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
			!database.hasSavedAmbientStepsReopen(sourceFence) -> ImportedAmbientStepsSnapshot.Deleted
			else -> null
		}
	}

	private suspend fun readDayFenceOutcome(
		request: ExportPortableAmbientStepsRequest,
		state: SourceEvidenceState,
	): ImportedAmbientStepsSnapshot? {
		val fences = request.selectedDayIdentities?.let {
			dao.fencesForIdentities(it.toList(), AmbientStepsPortableFormatV1.MAX_DAYS + 1)
		} ?: dao.fencesOverlapping(
			request.fromInclusiveMs,
			request.toExclusiveMs,
			AmbientStepsPortableFormatV1.MAX_DAYS + 1,
		)
		return when {
			fences.size > AmbientStepsPortableFormatV1.MAX_DAYS ->
				ImportedAmbientStepsSnapshot.Unverifiable(ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW)
			fences.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) } ->
				ImportedAmbientStepsSnapshot.Unverifiable(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
			else -> dayFenceOutcome(fences)
		}
	}

	private fun dayFenceOutcome(fences: List<ImportedAmbientStepsDayFenceEntity>): ImportedAmbientStepsSnapshot? {
		val kinds = fences.mapTo(linkedSetOf(), ImportedAmbientStepsDayFenceEntity::fenceKind)
		return when {
			kinds.size > 1 -> ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
			ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION in kinds -> ImportedAmbientStepsSnapshot.Retained
			kinds.isNotEmpty() -> ImportedAmbientStepsSnapshot.Deleted
			else -> null
		}
	}

	private suspend fun readCandidateDays(
		request: ExportPortableAmbientStepsRequest,
		state: SourceEvidenceState,
		includeCountDomainGraph: Boolean,
	): ImportedAmbientStepsSnapshot {
		val candidates = request.selectedDayIdentities?.let {
			dao.latestDaysForIdentities(it.toList(), AmbientStepsPortableFormatV1.MAX_DAYS + 1)
		} ?: dao.latestDaysOverlapping(
			request.fromInclusiveMs,
			request.toExclusiveMs,
			AmbientStepsPortableFormatV1.MAX_DAYS + 1,
		)
		return candidateDaysOutcome(request, state, candidates)
			?: readSavedDays(candidates, state, includeCountDomainGraph)
	}

	private fun candidateDaysOutcome(
		request: ExportPortableAmbientStepsRequest,
		state: SourceEvidenceState,
		candidates: List<ImportedAmbientStepsDayRevisionEntity>,
	): ImportedAmbientStepsSnapshot? = when {
		candidates.size > AmbientStepsPortableFormatV1.MAX_DAYS -> ImportedAmbientStepsSnapshot.Unverifiable(
			ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW,
		)
		candidates.isEmpty() -> ImportedAmbientStepsSnapshot.NoData
		request.selectedDayIdentities != null &&
			candidates.map { it.dayIdentity }.toSet() != request.selectedDayIdentities ->
			ImportedAmbientStepsSnapshot.Unverifiable(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
		state.retainedFromMs?.let { floor -> candidates.any { it.crossesImportRetentionFloor(floor) } } == true ->
			ImportedAmbientStepsSnapshot.Retained
		else -> null
	}

	private fun ImportedAmbientStepsDayRevisionEntity.crossesImportRetentionFloor(floor: Long): Boolean =
		structuralDayEndTimeMs <= floor || structuralDayStartTimeMs < floor &&
			(retainedFromTimeMs?.let { it < floor } ?: true)

	private suspend fun readSavedDays(
		candidates: List<ImportedAmbientStepsDayRevisionEntity>,
		state: SourceEvidenceState,
		includeCountDomainGraph: Boolean,
	): ImportedAmbientStepsSnapshot {
		val days = mutableListOf<PortableAmbientStepsDayV1>()
		val products = mutableListOf<AmbientStepsDayProduct>()
		val authenticatedDays = mutableListOf<PortableAmbientStepsDayV2>()
		var factCount = 0
		var gapCount = 0
		candidates.forEach { candidate ->
			currentCoroutineContext().ensureActive()
			val lineage = dao.loadAuthenticatedAmbientStepsLineage(candidate.dayIdentity, state)
			check(lineage.latest.header == candidate)
			val revision = lineage.latest
			val portableDay = revision.day
			val graphRevision = readCountDomainRevision(lineage, includeCountDomainGraph) { reason ->
				return ImportedAmbientStepsSnapshot.Unverifiable(reason)
			}
			if (includeCountDomainGraph) {
				authenticatedDays += authenticatedDay(portableDay, checkNotNull(graphRevision)) { reason ->
					return ImportedAmbientStepsSnapshot.Unverifiable(reason)
				}
			}
			factCount = Math.addExact(factCount, portableDay.facts.size)
			gapCount = Math.addExact(gapCount, portableDay.gaps.size)
			if (factCount > AmbientStepsPortableFormatV1.MAX_FACTS ||
				gapCount > AmbientStepsPortableFormatV1.MAX_GAPS
			) {
				throw ImportedAmbientStepsLineageFailure(ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW)
			}
			days += portableDay
			products += composeImportedDay(revision, graphRevision) { reason ->
				return ImportedAmbientStepsSnapshot.Unverifiable(reason)
			}
		}
		days.sortWith(PORTABLE_AMBIENT_STEPS_DAY_ORDER)
		check(days.map { StructuralImportedAmbientStepsDayKey(it) }.distinct().size == days.size)
		val archive = PortableAmbientStepsArchiveV1.create(days)
		return ImportedAmbientStepsSnapshot.Ready(
			archive,
			products.sortedBy { it.day.startTimeMs },
			if (includeCountDomainGraph) {
				PortableAmbientStepsArchiveV2.create(authenticatedDays)
			} else {
				null
			},
		)
	}

	private suspend inline fun readCountDomainRevision(
		lineage: AuthenticatedImportedAmbientStepsLineage,
		includeCountDomainGraph: Boolean,
		onFailure: (ImportedAmbientStepsReadFailure) -> Nothing,
	): AuthenticatedImportedAmbientStepsGraphRevision? {
		val revision = lineage.latest
		val bindings = database.importedPortableStepsCountDomainDao().bindingsForProduct(
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			revision.header.dayIdentity,
			ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
		)
		if (bindings.size > ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY) {
			onFailure(ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW)
		}
		val graphRevision = if (bindings.isEmpty()) {
			val graphless = database.loadProvenGraphlessLegacyAmbientLineage(lineage).lastOrNull()
			if (includeCountDomainGraph) {
				onFailure(ImportedAmbientStepsReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE)
			}
			graphless
		} else {
			try {
				database.loadAuthenticatedImportedAmbientStepsGraphLineage(lineage).lastOrNull()
			} catch (_: IllegalArgumentException) {
				null
			} catch (_: IllegalStateException) {
				null
			} catch (_: ArithmeticException) {
				null
			} ?: onFailure(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
		}
		if (graphRevision != null && revision.header.importRevision !in graphRevision.productImportRevisions) {
			onFailure(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
		}
		return graphRevision
	}

	private inline fun authenticatedDay(
		portableDay: PortableAmbientStepsDayV1,
		graphRevision: AuthenticatedImportedAmbientStepsGraphRevision,
		onFailure: (ImportedAmbientStepsReadFailure) -> Nothing,
	): PortableAmbientStepsDayV2 {
		if (!graphRevision.graph.hasCompletePortableOwnerLineages()) {
			onFailure(ImportedAmbientStepsReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE)
		}
		return try {
			PortableAmbientStepsDayV2(portableDay, graphRevision.graph)
		} catch (_: IllegalArgumentException) {
			onFailure(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
		}
	}

	private inline fun composeImportedDay(
		revision: AuthenticatedImportedAmbientStepsRevision,
		graphRevision: AuthenticatedImportedAmbientStepsGraphRevision?,
		onFailure: (ImportedAmbientStepsReadFailure) -> Nothing,
	): AmbientStepsDayProduct {
		val portableDay = revision.day
		val day = AmbientStepsDayIdentity(
			epochDay = portableDay.structuralEpochDay,
			storedZoneId = portableDay.storedZoneId,
			startTimeMs = portableDay.structuralDayStartTimeMs,
			endTimeMs = portableDay.structuralDayEndTimeMs,
		)
		val provenance = ImportedAmbientStepsFactProvenance(
			archiveIdentity = revision.header.archiveIdentity,
			dayIdentity = revision.header.dayIdentity,
			dayImportRevision = revision.header.importRevision,
		)
		val countDomainGraph = checkNotNull(graphRevision).graph
		val countDomainOwners = countDomainGraph.ambientCountDomainOwners(
			revision.header.dayIdentity,
		) ?: onFailure(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
		if (countDomainOwners.keys != portableDay.facts.mapTo(linkedSetOf()) {
				it.identity.value
			}
		) {
			onFailure(ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE)
		}
		return composeAmbientStepsDay(
			day = day,
			facts = portableDay.facts.map { fact ->
				QualifiedAmbientStepsFact(
					logicalFactId = fact.identity.value,
					day = day,
					startTimeMs = fact.intervalStartTimeMs,
					endTimeMs = fact.intervalEndTimeMs,
					stepCount = fact.stepCount,
					provenance = null,
					portableIdentity = fact.identity.value,
					origin = QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT,
					importedProvenance = provenance,
					correctionRevision = revision.header.importRevision,
					contentChecksum = fact.contentChecksum.value,
					countDomainOwner = countDomainOwners[fact.identity.value],
				)
			},
			gaps = portableDay.gaps.map { gap ->
				EffectiveAmbientStepsGap(gap.intervalStartTimeMs, gap.intervalEndTimeMs)
			},
			sessions = emptyList(),
			sourceCauses = portableDay.toAmbientStepsProductCauses(),
		)
	}

}

/** Imported-only re-export primitive. Parent assembly owns native/imported range union. */
internal class RoomReexportImportedAmbientSteps @Inject constructor(
	private val reader: ImportedAmbientStepsRoomReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ReexportImportedAmbientSteps {
	override suspend fun export(
		request: ExportPortableAmbientStepsRequest,
		sink: PortableAmbientStepsArchiveSink,
	): ExportPortableAmbientStepsResult = withContext(ioDispatcher) {
		when (val snapshot = reader.read(request)) {
			ImportedAmbientStepsSnapshot.NoData -> ExportPortableAmbientStepsResult.NoData
			ImportedAmbientStepsSnapshot.Deleted -> ExportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsExportUnverifiableReason.IMPORTED_DAY_DELETED,
			)
			ImportedAmbientStepsSnapshot.Retained -> ExportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsExportUnverifiableReason.IMPORTED_DAY_RETAINED,
			)
			ImportedAmbientStepsSnapshot.RetryableFailure ->
				ExportPortableAmbientStepsResult.RetryableFailure(
					PortableAmbientStepsExportRetryableReason.STORAGE_UNAVAILABLE,
				)
			is ImportedAmbientStepsSnapshot.Unverifiable -> ExportPortableAmbientStepsResult.Unverifiable(
				snapshot.reason.toPortableAmbientStepsExportReason(),
			)
			is ImportedAmbientStepsSnapshot.Ready -> {
				currentCoroutineContext().ensureActive()
				sink.emit(snapshot.archive)
				ExportPortableAmbientStepsResult.Exported(
					dayCount = snapshot.archive.days.size,
					factCount = snapshot.archive.days.sumOf { it.facts.size },
					gapCount = snapshot.archive.days.sumOf { it.gaps.size },
				)
			}
		}
	}
}

internal class RoomReexportImportedAmbientStepsV2 @Inject constructor(
	private val reader: ImportedAmbientStepsRoomReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ReexportImportedAmbientStepsV2 {
	override suspend fun export(
		request: ExportPortableAmbientStepsRequest,
		sink: PortableAmbientStepsArchiveV2Sink,
	): ExportPortableAmbientStepsResult = withContext(ioDispatcher) {
		when (val snapshot = reader.read(request, includeCountDomainGraph = true)) {
			ImportedAmbientStepsSnapshot.NoData -> ExportPortableAmbientStepsResult.NoData
			ImportedAmbientStepsSnapshot.Deleted -> ExportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsExportUnverifiableReason.IMPORTED_DAY_DELETED,
			)
			ImportedAmbientStepsSnapshot.Retained -> ExportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsExportUnverifiableReason.IMPORTED_DAY_RETAINED,
			)
			ImportedAmbientStepsSnapshot.RetryableFailure ->
				ExportPortableAmbientStepsResult.RetryableFailure(
					PortableAmbientStepsExportRetryableReason.STORAGE_UNAVAILABLE,
				)
			is ImportedAmbientStepsSnapshot.Unverifiable ->
				ExportPortableAmbientStepsResult.Unverifiable(
					snapshot.reason.toPortableAmbientStepsExportReason(),
				)
			is ImportedAmbientStepsSnapshot.Ready -> {
				val archive = snapshot.authenticatedArchive
					?: return@withContext ExportPortableAmbientStepsResult.Unverifiable(
						PortableAmbientStepsExportUnverifiableReason.CORRUPT_RETAINED_STATE,
					)
				currentCoroutineContext().ensureActive()
				sink.emit(archive)
				ExportPortableAmbientStepsResult.Exported(
					dayCount = archive.days.size,
					factCount = archive.days.sumOf { it.product.facts.size },
					gapCount = archive.days.sumOf { it.product.gaps.size },
				)
			}
		}
	}
}

private fun ImportedAmbientStepsReadFailure.toPortableAmbientStepsExportReason():
	PortableAmbientStepsExportUnverifiableReason = when (this) {
	ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW ->
		PortableAmbientStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedAmbientStepsReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE ->
		PortableAmbientStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE
	ImportedAmbientStepsReadFailure.SOURCE_EVIDENCE_UNAVAILABLE ->
		PortableAmbientStepsExportUnverifiableReason.SOURCE_AUTHORITY_UNAVAILABLE
	ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE ->
		PortableAmbientStepsExportUnverifiableReason.CORRUPT_RETAINED_STATE
}

private data class StructuralImportedAmbientStepsDayKey(
	val epochDay: Long,
	val zoneId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	constructor(day: com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1) : this(
		day.structuralEpochDay,
		day.storedZoneId,
		day.structuralDayStartTimeMs,
		day.structuralDayEndTimeMs,
	)
}

/** A completed import reopening stays valid when current collection choices later change. */
@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
internal suspend fun AppDatabase.hasSavedAmbientStepsReopen(
	fence: ImportedAmbientStepsSourceFenceEntity,
): Boolean {
	if (!fence.deletionCompleted) {
		return false
	}
	val epoch = fence.reopenedConsentEpoch ?: return false
	val reopenedAtMs = fence.reopenedAtMs ?: return false
	val consent = sourcePolicyDao().consentEpoch(
		SourceDestinationOwnerEntity.SOURCE_STEPS, SourceBrokerPurpose.AMBIENT_PRODUCT, epoch,
	) ?: return false
	val policy = sourcePolicyDao().policyAtRevision(
		consent.policyRevision, SourceDestinationOwnerEntity.SOURCE_STEPS,
	) ?: return false
	return epoch > fence.revokedConsentEpoch && policy.enabled &&
		policy.hasExactEligibleAmbientConsentReference(consent) &&
		policy.effectiveWallTimeMs <= reopenedAtMs && consent.effectiveWallTimeMs <= reopenedAtMs
}
