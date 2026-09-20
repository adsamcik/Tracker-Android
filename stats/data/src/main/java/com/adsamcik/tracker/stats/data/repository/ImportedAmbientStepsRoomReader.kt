package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailure
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailureReason
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.authenticateAllAmbientStepsFences
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedImportedAmbientStepsGraphLineage
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_AMBIENT_STEPS_DAY_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
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
		if (state.retainedFromMs != null && state.retainedFromMs < 0L) {
			return ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
		}
		dao.authenticateAllAmbientStepsFences(state.collectedDataEpoch)
		val sourceFence = dao.sourceFence()
		if (sourceFence != null) {
			if (sourceFence.collectedDataEpoch != state.collectedDataEpoch) {
				return ImportedAmbientStepsSnapshot.Unverifiable(
					ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
				)
			}
			val authority = database.sourcePolicyDao().authority()
			val policy = authority?.takeIf {
				it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
			}?.let {
				database.sourcePolicyDao().policyAtRevision(
					it.currentPolicyRevision,
					SourceDestinationOwnerEntity.SOURCE_STEPS,
				)
			}
			val consent = database.sourcePolicyDao().latestConsentEpoch(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
			)
			val reset = sourceFence.deletionCompleted &&
				policy != null && consent != null && policy.enabled &&
				policy.ambientPersistenceEligible && policy.ambientConsentEpoch == consent.epoch &&
				consent.eligible && consent.persistenceEligible &&
				consent.policyRevision == policy.policyRevision &&
				consent.epoch > sourceFence.revokedConsentEpoch &&
				sourceFence.reopenedConsentEpoch == consent.epoch
			if (!reset) return ImportedAmbientStepsSnapshot.Deleted
		}
		val fences = dao.fencesOverlapping(
			request.fromInclusiveMs,
			request.toExclusiveMs,
			AmbientStepsPortableFormatV1.MAX_DAYS + 1,
		)
		if (fences.size > AmbientStepsPortableFormatV1.MAX_DAYS) {
			return ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW,
			)
		}
		if (fences.any { it.collectedDataEpoch != state.collectedDataEpoch }) {
			return ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
		}
		val fenceKinds = fences.mapTo(linkedSetOf(), ImportedAmbientStepsDayFenceEntity::fenceKind)
		if (fenceKinds.size > 1) {
			return ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
		}
		if (ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION in fenceKinds) {
			return ImportedAmbientStepsSnapshot.Retained
		}
		if (fenceKinds.isNotEmpty()) return ImportedAmbientStepsSnapshot.Deleted

		val candidates = dao.latestDaysOverlapping(
			request.fromInclusiveMs,
			request.toExclusiveMs,
			AmbientStepsPortableFormatV1.MAX_DAYS + 1,
		)
		if (candidates.size > AmbientStepsPortableFormatV1.MAX_DAYS) {
			return ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW,
			)
		}
		if (candidates.isEmpty()) return ImportedAmbientStepsSnapshot.NoData
		state.retainedFromMs?.let { floor ->
			if (candidates.any { candidate ->
					candidate.structuralDayEndTimeMs <= floor ||
						candidate.structuralDayStartTimeMs < floor &&
						(candidate.retainedFromTimeMs == null ||
							candidate.retainedFromTimeMs < floor)
				}
			) return ImportedAmbientStepsSnapshot.Retained
		}
		val days = mutableListOf<com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1>()
		val products = mutableListOf<AmbientStepsDayProduct>()
		val authenticatedDays = mutableListOf<PortableAmbientStepsDayV2>()
		var factCount = 0
		var gapCount = 0
		candidates.forEach { candidate ->
			currentCoroutineContext().ensureActive()
			val lineage = dao.loadAuthenticatedAmbientStepsLineage(
				candidate.dayIdentity,
				state.collectedDataEpoch,
			)
			check(lineage.latest.header == candidate)
			val revision = lineage.latest
			val portableDay = revision.day
			val graphBindings = database.importedPortableStepsCountDomainDao().bindings(
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
				listOf(revision.header.dayIdentity),
			)
			val graphRevision = if (graphBindings.isEmpty()) {
				if (includeCountDomainGraph) {
					return ImportedAmbientStepsSnapshot.Unverifiable(
						ImportedAmbientStepsReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
					)
				}
				null
			} else {
				try {
					database.loadAuthenticatedImportedAmbientStepsGraphLineage(lineage).lastOrNull()
				} catch (_: IllegalArgumentException) {
					null
				} catch (_: IllegalStateException) {
					null
				} catch (_: ArithmeticException) {
					null
				} ?: return ImportedAmbientStepsSnapshot.Unverifiable(
					ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
				)
			}
			if (graphRevision != null &&
				revision.header.importRevision !in graphRevision.productImportRevisions
			) return ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
			if (includeCountDomainGraph) {
				authenticatedDays += try {
					PortableAmbientStepsDayV2(
						portableDay,
						checkNotNull(graphRevision).graph,
					)
				} catch (_: IllegalArgumentException) {
					return ImportedAmbientStepsSnapshot.Unverifiable(
						ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
					)
				}
			}
			factCount = Math.addExact(factCount, portableDay.facts.size)
			gapCount = Math.addExact(gapCount, portableDay.gaps.size)
			if (factCount > AmbientStepsPortableFormatV1.MAX_FACTS ||
				gapCount > AmbientStepsPortableFormatV1.MAX_GAPS
			) {
				throw ImportedAmbientStepsLineageFailure(
					ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
				)
			}
			days += portableDay
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
			val countDomainGraph = graphRevision?.graph
				?: portableDay.withExplicitUnprovenCountDomain().countDomainGraph
			val countDomainOwners = countDomainGraph.ambientCountDomainOwners(
				revision.header.dayIdentity,
			) ?: return ImportedAmbientStepsSnapshot.Unverifiable(
				ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
			)
			if (countDomainOwners.keys != portableDay.facts.mapTo(linkedSetOf()) {
					it.identity.value
				}
			) {
				return ImportedAmbientStepsSnapshot.Unverifiable(
					ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE,
				)
			}
			products += composeAmbientStepsDay(
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
				when (snapshot.reason) {
					ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW ->
						PortableAmbientStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW
					ImportedAmbientStepsReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE ->
						PortableAmbientStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE
					ImportedAmbientStepsReadFailure.SOURCE_EVIDENCE_UNAVAILABLE ->
						PortableAmbientStepsExportUnverifiableReason.SOURCE_AUTHORITY_UNAVAILABLE
					ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE ->
						PortableAmbientStepsExportUnverifiableReason.CORRUPT_RETAINED_STATE
				},
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
					when (snapshot.reason) {
						ImportedAmbientStepsReadFailure.DEPENDENCY_OVERFLOW ->
							PortableAmbientStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW
						ImportedAmbientStepsReadFailure.COUNT_DOMAIN_GRAPH_UNAVAILABLE ->
							PortableAmbientStepsExportUnverifiableReason
								.COUNT_DOMAIN_GRAPH_UNAVAILABLE
						ImportedAmbientStepsReadFailure.SOURCE_EVIDENCE_UNAVAILABLE ->
							PortableAmbientStepsExportUnverifiableReason.SOURCE_AUTHORITY_UNAVAILABLE
						ImportedAmbientStepsReadFailure.CORRUPT_RETAINED_STATE ->
							PortableAmbientStepsExportUnverifiableReason.CORRUPT_RETAINED_STATE
					},
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
