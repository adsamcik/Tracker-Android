@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOriginReader
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOriginFailure
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOriginFailureReason
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwnerKind
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwnerState
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.authenticatedGraph
import com.adsamcik.tracker.shared.base.database.insertAuthenticatedGraph
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailure
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailureReason
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsNativeReplayFootprintIntegrity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsSourceFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.authenticateAllAmbientStepsFences
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.shared.model.steps.portable.deletionScopeIdentity
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2Request
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsTransferRetryableReason
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.isActiveApproval
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Ambient Steps portable-origin admission. Parent assembly supplies the new Room DAO accessor;
 * this source does not register or activate any provider.
 */
@Singleton
internal class RoomImportPortableAmbientSteps internal constructor(
	private val database: AppDatabase,
	private val dao: ImportedAmbientStepsDao,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val checkpoint: suspend (ImportedAmbientStepsWriteCheckpoint) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
	private val localOriginSource:
		suspend (Set<String>) ->
		List<com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwner> = {
			AmbientStepsPortableLocalOriginReader(database).readInTransaction(it)
		},
	private val ensurePortableRetention: suspend () -> Boolean = { true },
	private val requirePortableRetentionAuthority: Boolean = false,
) : ImportPortableAmbientSteps, ImportPortableAmbientStepsV2 {
	internal constructor(
		database: AppDatabase,
		dao: ImportedAmbientStepsDao,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		database,
		dao,
		ioDispatcher,
		{ currentCoroutineContext().ensureActive() },
		{
			AmbientStepsPortableLocalOriginReader(database).readInTransaction(it)
		},
		{ true },
		false,
	)

	@Inject
	constructor(
		database: AppDatabase,
		dao: ImportedAmbientStepsDao,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
		retentionAuthorityProducer: RetentionAuthorityProducer,
	) : this(
		database,
		dao,
		ioDispatcher,
		{ currentCoroutineContext().ensureActive() },
		{
			AmbientStepsPortableLocalOriginReader(database).readInTransaction(it)
		},
		{
			retentionAuthorityProducer.approvePortableImport(TrackingSource.STEPS)
				.isActiveApproval()
		},
		true,
	)

	override suspend fun importArchive(
		request: ImportPortableAmbientStepsRequest,
	): ImportPortableAmbientStepsResult = importPrepared(AmbientImportEnvelope.fromV1(request))

	override suspend fun importArchive(
		request: ImportPortableAmbientStepsV2Request,
	): ImportPortableAmbientStepsResult = try {
		importPrepared(AmbientImportEnvelope.fromV2(request))
	} catch (_: IllegalArgumentException) {
		ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID,
		)
	} catch (_: ArithmeticException) {
		ImportPortableAmbientStepsResult.Unverifiable(
			PortableAmbientStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
	}

	private suspend fun importPrepared(
		request: AmbientImportEnvelope,
	): ImportPortableAmbientStepsResult = withContext(ioDispatcher) {
		if (!database.isOpen) {
			return@withContext ImportPortableAmbientStepsResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		try {
			if (!ensurePortableRetention()) {
				return@withContext ImportPortableAmbientStepsResult.Blocked(
					PortableAmbientStepsImportBlockedReason.RETENTION_POLICY_UNAVAILABLE,
				)
			}
			val snapshot = snapshot(request)
			database.withTransaction { importInTransaction(snapshot) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedAmbientStepsImportAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			ImportPortableAmbientStepsResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			ImportPortableAmbientStepsResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun importInTransaction(
		request: AmbientImportEnvelope,
	): ImportPortableAmbientStepsResult {
		checkpoint(ImportedAmbientStepsWriteCheckpoint.TRANSACTION_STARTED)
		val state = storedValue { database.sourceEvidenceStateDao().get() }
			?: unverifiable(PortableAmbientStepsImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.retainedFromMs != null && state.retainedFromMs < 0L) storedCorrupt()
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(PortableAmbientStepsImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		if (requirePortableRetentionAuthority) {
			val retention = storedValue {
				database.ambientStepsFactRevisionDao().latestRetentionAuthority(
					AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
				)
			}
			if (
				retention == null ||
				!AmbientStepsRetentionAuthorityIntegrity.isAuthentic(retention) ||
				!retention.isActive ||
				retention.collectedDataEpoch != request.expectedCollectedDataEpoch ||
				retention.retainedFromMs != state.retainedFromMs
			) {
				blocked(PortableAmbientStepsImportBlockedReason.RETENTION_POLICY_UNAVAILABLE)
			}
		}
		storedValue { dao.authenticateAllAmbientStepsFences(state.collectedDataEpoch) }
		authenticateSourceDeletionFence(state)

		val archive = request.archive
		val dayIdentities = archive.days.map { it.identity.value }
		val incomingOwnership = IncomingAmbientStepsOwnership(
			archive,
			request.sourceArchiveIdentity.value,
			ImportedAmbientStepsIdentity.receipt(
				request.receipt.jobId,
				request.receipt.archiveKey,
			),
		)
		val nativeFootprints = incomingOwnership.allIdentities
			.chunked(IDENTITY_QUERY_CHUNK_SIZE)
			.flatMap { identities ->
				storedValue {
					database.ambientStepsFactRevisionDao().nativeReplayFootprints(
						identities,
						identities.size + 1,
					)
				}
			}
		if (nativeFootprints.any {
				!AmbientStepsNativeReplayFootprintIntegrity.isAuthentic(it) ||
					it.collectedDataEpoch != state.collectedDataEpoch
			}) storedCorrupt()
		if (nativeFootprints.isNotEmpty()) {
			blocked(PortableAmbientStepsImportBlockedReason.LOCAL_ORIGIN_OVERLAP)
		}
		authenticateStructuralDayOwnership(archive)
		val fences = storedValue { dao.fences(dayIdentities) }
		if (fences.any { it.collectedDataEpoch != state.collectedDataEpoch }) storedCorrupt()
		if (fences.any { it.fenceKind == ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION }) {
			blocked(PortableAmbientStepsImportBlockedReason.RETAINED_DAY)
		}
		if (fences.isNotEmpty()) blocked(PortableAmbientStepsImportBlockedReason.DELETED_DAY)
		authenticateReceiptIdentityOwnership(request)
		val protected = incomingOwnership.allIdentities.chunked(IDENTITY_QUERY_CHUNK_SIZE).flatMap {
			values -> storedValue { dao.protectedIdentityOwners(values, values.size + 1) }
		}
		if (protected.any { it.ownerDayIdentity !in dayIdentities } || protected.isNotEmpty()) {
			blocked(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
		state.retainedFromMs?.let { floor ->
			if (archive.days.any { it.crossesLocalRetentionFloor(floor) }) {
				blocked(PortableAmbientStepsImportBlockedReason.RETENTION_BOUNDARY)
			}
		}
		authenticateNativeOverlap(archive, incomingOwnership)
		authenticateImportedIdentityOwnership(incomingOwnership)
		val graphDao = database.importedPortableStepsCountDomainDao()
		val incomingOwnerIdentities = request.incomingOwnerIdentities()
		val ownerFences = if (incomingOwnerIdentities.isEmpty()) {
			emptyList()
		} else {
			storedValue {
				graphDao.ownerFences(
					incomingOwnerIdentities,
					incomingOwnerIdentities.size + 1,
				)
			}
		}
		if (ownerFences.isNotEmpty()) {
			blocked(PortableAmbientStepsImportBlockedReason.DELETED_DAY)
		}
		val existingPortableRoots = if (incomingOwnerIdentities.isEmpty()) {
			emptyList()
		} else {
			storedValue {
				graphDao.rootsForOwners(
					incomingOwnerIdentities,
					com.adsamcik.tracker.shared.model.steps.portable
						.PortableCountDomainFormatV2.MAX_ROOTS + 1,
				)
			}
		}
		if (existingPortableRoots.size >
			com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainFormatV2.MAX_ROOTS
		) dependencyOverflow()
		if (existingPortableRoots.any { root ->
				!request.ownsRoot(
					containerIdentity = root.containerIdentity,
					productIdentity = root.productIdentity,
					ownerKind = root.ownerKind,
					ownerIdentity = root.ownerIdentity,
				)
			}
		) {
			blocked(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}

		val storedReceipt = storedValue {
			dao.receipt(request.receipt.jobId, request.receipt.archiveKey)
		}
		if (storedReceipt != null) {
			authenticateReceiptReplay(request, storedReceipt)
			return ImportPortableAmbientStepsResult.Duplicate(
				request.sourceArchiveIdentity,
				archive.days.size,
			)
		}
		val storedArchive = storedValue { dao.archive(request.sourceArchiveIdentity.value) }
		if (storedArchive != null) {
			authenticateStoredArchive(request, storedArchive, addedReceiptCount = 1)
			authenticatePostInsertReceipt(request, storedArchive)
			authenticateCapacity(NewImportedAmbientStepsRows(receipts = 1L))
			dao.insertReceipt(request.toReceiptEntity())
			checkpoint(ImportedAmbientStepsWriteCheckpoint.RECEIPT_INSERTED)
			incrementSourceEvidenceRevision(state.updatedAtMs, request.receipt.receivedAtMs)
			return ImportPortableAmbientStepsResult.Duplicate(
				request.sourceArchiveIdentity,
				archive.days.size,
			)
		}

		val plans = archive.days.map { day ->
			val lineage = storedValue {
				dao.loadAuthenticatedAmbientStepsLineage(
					day.identity.value,
					state.collectedDataEpoch,
				)
			}
			checkpoint(ImportedAmbientStepsWriteCheckpoint.LINEAGE_AUTHENTICATED)
			val baseline = lineage.revisions.firstOrNull()?.day
			if (baseline != null && !day.hasSameStructuralAuthority(baseline)) {
				blocked(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
			}
			val archiveCount = lineage.archiveDays.asSequence()
				.filter { it.dayIdentity == day.identity.value }
				.map { it.archiveIdentity }
				.distinct()
				.count()
			if (archiveCount >= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY) {
				dependencyOverflow()
			}
			if (!importedAmbientStepsLineageAdditionFits(
					existingArchiveMemberCount = lineage.archiveDays.size,
					existingReceiptCount = lineage.receipts.size,
					incomingArchiveMemberCount = archive.days.size,
				)
			) dependencyOverflow()
			val latestDurableTime = maxOf(
				lineage.revisions.lastOrNull()?.header?.receivedAtMs ?: 0L,
				lineage.receipts.maxOfOrNull { it.receivedAtMs } ?: 0L,
			)
			if (request.receipt.receivedAtMs < latestDurableTime) {
				blocked(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
			}
			val nextRevision = try {
				Math.addExact(lineage.revisions.lastOrNull()?.header?.importRevision ?: 0L, 1L)
			} catch (_: ArithmeticException) {
				unverifiable(PortableAmbientStepsImportUnverifiableReason.REVISION_OVERFLOW)
			}
			val identical = lineage.revisions.singleOrNull { it.day == day }
			val candidateRevision = identical?.header?.importRevision ?: nextRevision
			val candidateGraph = request.graphFor(day, candidateRevision)
			val existingGraph = lineage.revisions.lastOrNull()?.header?.let { latest ->
				val binding = storedValue {
					graphDao.binding(
						ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
						latest.dayIdentity,
						latest.importRevision,
					)
				}
				binding?.let {
					storedValue {
						graphDao.authenticatedGraph(
							it.graphIdentity,
							ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
						)
					}
				}
			}
			if (lineage.revisions.isNotEmpty() && existingGraph == null) storedCorrupt()
			val exactGraphReplay = identical != null && existingGraph == candidateGraph
			if (exactGraphReplay) {
				AmbientStepsDayImportPlan(
					day,
					identical.header.importRevision,
					append = false,
					graph = candidateGraph,
				)
			} else {
				if (lineage.revisions.size >= ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY) {
					unverifiable(PortableAmbientStepsImportUnverifiableReason.REVISION_OVERFLOW)
				}
				if (existingGraph != null &&
					!isContiguousPortableCorrection(existingGraph, candidateGraph)
				) {
					blocked(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
				}
				AmbientStepsDayImportPlan(day, nextRevision, append = true, graph = candidateGraph)
			}
		}
		val appended = plans.filter(AmbientStepsDayImportPlan::append)
		authenticateCapacity(
			NewImportedAmbientStepsRows(
				archives = 1L,
				receipts = 1L,
				archiveDays = plans.size.toLong(),
				dayRevisions = appended.size.toLong(),
				facts = appended.sumOf { it.day.facts.size.toLong() },
				gaps = appended.sumOf { it.day.gaps.size.toLong() },
			),
		)
		dao.insertArchive(request.toArchiveEntity())
		checkpoint(ImportedAmbientStepsWriteCheckpoint.ARCHIVE_INSERTED)
		appended.forEach { plan ->
			dao.insertDayRevision(plan.day.toEntity(request, plan.revision))
			checkpoint(ImportedAmbientStepsWriteCheckpoint.DAY_INSERTED)
			dao.insertFacts(plan.day.facts.map { fact ->
				ImportedAmbientStepsFactEntity(
					dayIdentity = plan.day.identity.value,
					dayImportRevision = plan.revision,
					factIdentity = fact.identity.value,
					contentChecksum = fact.contentChecksum.value,
					intervalStartTimeMs = fact.intervalStartTimeMs,
					intervalEndTimeMs = fact.intervalEndTimeMs,
					stepCount = fact.stepCount,
				)
			})
			checkpoint(ImportedAmbientStepsWriteCheckpoint.FACTS_INSERTED)
			if (plan.day.gaps.isNotEmpty()) {
				dao.insertGaps(plan.day.gaps.map { gap ->
					ImportedAmbientStepsGapEntity(
						dayIdentity = plan.day.identity.value,
						dayImportRevision = plan.revision,
						gapIdentity = gap.identity.value,
						contentChecksum = gap.contentChecksum.value,
						intervalStartTimeMs = gap.intervalStartTimeMs,
						intervalEndTimeMs = gap.intervalEndTimeMs,
						reason = gap.reason.name,
					)
				})
			}
			checkpoint(ImportedAmbientStepsWriteCheckpoint.GAPS_INSERTED)
		}
		dao.insertArchiveDays(plans.mapIndexed { ordinal, plan ->
			ImportedAmbientStepsArchiveDayEntity(
				archiveIdentity = request.sourceArchiveIdentity.value,
				ordinal = ordinal,
				dayIdentity = plan.day.identity.value,
				dayContentChecksum = plan.day.contentChecksum.value,
				boundDayImportRevision = plan.revision,
				factCount = plan.day.facts.size,
				gapCount = plan.day.gaps.size,
			)
		})
		checkpoint(ImportedAmbientStepsWriteCheckpoint.ARCHIVE_MEMBERS_INSERTED)
		plans.filter(AmbientStepsDayImportPlan::append).forEach { plan ->
			insertCountDomainGraph(plan, request.sourceSchemaVersion)
		}
		dao.insertReceipt(request.toReceiptEntity())
		checkpoint(ImportedAmbientStepsWriteCheckpoint.RECEIPT_INSERTED)
		incrementSourceEvidenceRevision(state.updatedAtMs, request.receipt.receivedAtMs)
		return ImportPortableAmbientStepsResult.Applied(
			archiveIdentity = request.sourceArchiveIdentity,
			appendedDayRevisionCount = appended.size,
			dayCount = archive.days.size,
			factCount = archive.days.sumOf { it.facts.size },
			gapCount = archive.days.sumOf { it.gaps.size },
		)
	}

	private suspend fun authenticateReceiptReplay(
		request: AmbientImportEnvelope,
		stored: ImportedAmbientStepsReceiptEntity,
	) {
		if (stored.importJobId != request.receipt.jobId ||
			stored.archiveKey != request.receipt.archiveKey ||
			stored.receiptIdentity != ImportedAmbientStepsIdentity.receipt(
				request.receipt.jobId,
				request.receipt.archiveKey,
			) ||
			stored.sourceName != request.receipt.sourceName ||
			stored.receivedAtMs != request.receipt.receivedAtMs ||
			stored.archiveIdentity != request.sourceArchiveIdentity.value ||
			stored.archiveContentChecksum != request.sourceArchiveContentChecksum.value ||
			stored.collectedDataEpoch != request.expectedCollectedDataEpoch
		) blocked(PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT)
		val archive = storedValue { dao.archive(stored.archiveIdentity) } ?: storedCorrupt()
		authenticateStoredArchive(request, archive, addedReceiptCount = 0)
		val stats = storedValue { dao.receiptStats(archive.archiveIdentity) }
		if (stats.receiptCount !in 1L..ImportedAmbientStepsDao.MAX_RECEIPTS_PER_ARCHIVE.toLong() ||
			stats.earliestReceivedAtMs != archive.firstReceivedAtMs
		) storedCorrupt()
	}

	private suspend fun authenticateReceiptIdentityOwnership(
		request: AmbientImportEnvelope,
	) {
		val identity = ImportedAmbientStepsIdentity.receipt(
			request.receipt.jobId,
			request.receipt.archiveKey,
		)
		val existingReceipt = storedValue { dao.receiptByIdentity(identity) }
		if (existingReceipt != null &&
			(existingReceipt.importJobId != request.receipt.jobId ||
				existingReceipt.archiveKey != request.receipt.archiveKey)
		) blocked(PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT)
		val values = listOf(identity)
		if (storedValue { dao.existingArchiveIdentities(values, 2) }.isNotEmpty() ||
			storedValue { dao.existingDayIdentityOwners(values, 2) }.isNotEmpty() ||
			storedValue { dao.existingFactIdentityOwners(values, 2) }.isNotEmpty() ||
			storedValue { dao.existingGapIdentityOwners(values, 2) }.isNotEmpty() ||
			storedValue { dao.protectedIdentityOwners(values, 2) }.isNotEmpty()
		) blocked(PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT)
	}

	private suspend fun authenticateSourceDeletionFence(state: SourceEvidenceState) {
		val fence = storedValue { dao.sourceFence() } ?: return
		if (fence.collectedDataEpoch != state.collectedDataEpoch) storedCorrupt()
		val authority = storedValue { database.sourcePolicyDao().authority() }
		val policy = authority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		}?.let {
			storedValue {
				database.sourcePolicyDao().policyAtRevision(
					it.currentPolicyRevision,
					SourceDestinationOwnerEntity.SOURCE_STEPS,
				)
			}
		}
		val consent = storedValue {
			database.sourcePolicyDao().latestConsentEpoch(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
			)
		}
		val eligiblePolicy = policy ?: blocked(PortableAmbientStepsImportBlockedReason.SOURCE_DELETED)
		val eligibleConsent = consent ?: blocked(PortableAmbientStepsImportBlockedReason.SOURCE_DELETED)
		if (!fence.deletionCompleted || !eligiblePolicy.enabled ||
			!eligiblePolicy.ambientPersistenceEligible ||
			eligiblePolicy.ambientConsentEpoch != eligibleConsent.epoch ||
			!eligibleConsent.eligible || !eligibleConsent.persistenceEligible ||
			eligibleConsent.policyRevision != eligiblePolicy.policyRevision ||
			eligibleConsent.epoch <= fence.revokedConsentEpoch
		) {
			blocked(PortableAmbientStepsImportBlockedReason.SOURCE_DELETED)
		}
		if (fence.reopenedConsentEpoch == eligibleConsent.epoch) return
		val replacement = ImportedAmbientStepsSourceFenceEntity.reopened(
			fence,
			eligibleConsent.epoch,
			maxOf(
				state.updatedAtMs,
				eligiblePolicy.effectiveWallTimeMs,
				eligibleConsent.effectiveWallTimeMs,
			),
		)
		if (!storedValue { dao.replaceSourceFence(fence, replacement) }) storedCorrupt()
	}

	private suspend fun authenticateStoredArchive(
		request: AmbientImportEnvelope,
		stored: ImportedAmbientStepsArchiveEntity,
		addedReceiptCount: Int,
	) {
		require(addedReceiptCount in 0..1)
		val archive = request.archive
		if (stored.archiveIdentity != request.sourceArchiveIdentity.value ||
			stored.contentChecksum != request.sourceArchiveContentChecksum.value ||
			stored.sourceFormat != request.sourceFormat ||
			stored.sourceSchemaVersion != request.sourceSchemaVersion ||
			stored.encodedByteCount != request.encodedByteCount ||
			stored.dayCount != archive.days.size ||
			stored.factCount != archive.days.sumOf { it.facts.size } ||
			stored.gapCount != archive.days.sumOf { it.gaps.size } ||
			stored.collectedDataEpoch != request.expectedCollectedDataEpoch
		) storedCorrupt()
		val members = storedValue { dao.archiveDays(stored.archiveIdentity) }
		if (members.size != archive.days.size ||
			members.withIndex().any { (index, member) -> member.ordinal != index }
		) storedCorrupt()
		members.zip(archive.days).forEach { (member, day) ->
			if (member.archiveIdentity != request.sourceArchiveIdentity.value ||
				member.dayIdentity != day.identity.value ||
				member.dayContentChecksum != day.contentChecksum.value ||
				member.factCount != day.facts.size || member.gapCount != day.gaps.size
			) storedCorrupt()
			val lineage = storedValue {
				dao.loadAuthenticatedAmbientStepsLineage(
					member.dayIdentity,
					request.expectedCollectedDataEpoch,
				)
			}
			val bound = lineage.revisions.singleOrNull {
				it.header.importRevision == member.boundDayImportRevision
			} ?: storedCorrupt()
			if (bound.day != day ||
				lineage.archives.singleOrNull {
					it.archiveIdentity == stored.archiveIdentity
				} != stored ||
				lineage.archiveDays.singleOrNull {
					it.archiveIdentity == stored.archiveIdentity &&
						it.dayIdentity == member.dayIdentity
				} != member
			) storedCorrupt()
			val graphBinding = storedValue {
				database.importedPortableStepsCountDomainDao().binding(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
					member.dayIdentity,
					member.boundDayImportRevision,
				)
			} ?: storedCorrupt()
			val storedGraph = storedValue {
				database.importedPortableStepsCountDomainDao().authenticatedGraph(
					graphBinding.graphIdentity,
					ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
				)
			} ?: storedCorrupt()
			if (graphBinding.sourceSchemaVersion != request.sourceSchemaVersion ||
				storedGraph != request.graphFor(day, member.boundDayImportRevision)
			) {
				blocked(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
			}
			if (!importedAmbientStepsLineageAdditionFits(
					existingArchiveMemberCount = lineage.archiveDays.size,
					existingReceiptCount = lineage.receipts.size,
					incomingArchiveMemberCount = 0,
					addedReceiptCount = addedReceiptCount,
				)
			) dependencyOverflow()
		}
	}

	private suspend fun authenticatePostInsertReceipt(
		request: AmbientImportEnvelope,
		stored: ImportedAmbientStepsArchiveEntity,
	) {
		val stats = storedValue { dao.receiptStats(stored.archiveIdentity) }
		if (stats.receiptCount < 1L ||
			stats.receiptCount >= ImportedAmbientStepsDao.MAX_RECEIPTS_PER_ARCHIVE ||
			stats.earliestReceivedAtMs != stored.firstReceivedAtMs
		) {
			if (stats.receiptCount >= ImportedAmbientStepsDao.MAX_RECEIPTS_PER_ARCHIVE) {
				dependencyOverflow()
			}
			storedCorrupt()
		}
		if (request.receipt.receivedAtMs < stored.firstReceivedAtMs) {
			blocked(PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT)
		}
	}

	private suspend fun authenticateNativeOverlap(
		archive: PortableAmbientStepsArchiveV1,
		incoming: IncomingAmbientStepsOwnership,
	) {
		val localOwners = try {
			localOriginSource(incoming.allIdentities.toSet())
		} catch (abort: ImportedAmbientStepsImportAbort) {
			throw abort
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: AmbientStepsPortableLocalOriginFailure) {
			when (failure.reason) {
				AmbientStepsPortableLocalOriginFailureReason.DEPENDENCY_OVERFLOW ->
					dependencyOverflow()
				AmbientStepsPortableLocalOriginFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
					storedCorrupt()
			}
		} catch (_: IllegalArgumentException) {
			storedCorrupt()
		} catch (_: IllegalStateException) {
			storedCorrupt()
		}
		val incomingFacts = archive.days.flatMap(PortableAmbientStepsDayV1::facts)
			.associateBy { it.identity.value }
		localOwners.forEach { owner ->
			val incomingKind = incoming.kinds[owner.identity]
				?: return@forEach
			val fact = incomingFacts[owner.identity]
			if (incomingKind == IncomingAmbientStepsIdentityKind.FACT &&
				owner.kind == AmbientStepsPortableLocalOwnerKind.FACT &&
				owner.state == AmbientStepsPortableLocalOwnerState.ACTIVE &&
				fact != null && owner.contentChecksum == fact.contentChecksum.value
			) {
				blocked(PortableAmbientStepsImportBlockedReason.LOCAL_ORIGIN_OVERLAP)
			}
			blocked(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	@Suppress("ComplexCondition")
	private suspend fun authenticateImportedIdentityOwnership(
		incoming: IncomingAmbientStepsOwnership,
	) {
		incoming.allIdentities.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { values ->
			val limit = values.size + 1
			val archives = storedValue { dao.existingArchiveIdentities(values, limit) }
			val receipts = storedValue { dao.existingReceiptIdentities(values, limit) }
			val days = storedValue { dao.existingDayIdentityOwners(values, limit) }
			val facts = storedValue { dao.existingFactIdentityOwners(values, limit) }
			val gaps = storedValue { dao.existingGapIdentityOwners(values, limit) }
			val protected = storedValue { dao.protectedIdentityOwners(values, limit) }
			if (archives.size >= limit || receipts.size >= limit || days.size >= limit || facts.size >= limit ||
				gaps.size >= limit || protected.size >= limit
			) dependencyOverflow()
			if (protected.isNotEmpty() ||
				archives.any { incoming.kinds[it] != IncomingAmbientStepsIdentityKind.ARCHIVE } ||
				receipts.any { incoming.kinds[it] != IncomingAmbientStepsIdentityKind.RECEIPT } ||
				days.any { owner ->
					val dayOwner = incoming.dayOwners[owner.dayIdentity]
					val scopeOwner = incoming.scopeOwners[owner.deletionScopeIdentity]
					incoming.kinds[owner.dayIdentity] != IncomingAmbientStepsIdentityKind.DAY ||
						incoming.kinds[owner.deletionScopeIdentity] !=
						IncomingAmbientStepsIdentityKind.DELETION_SCOPE ||
						dayOwner != owner.dayIdentity || scopeOwner != owner.dayIdentity
				} ||
				facts.any { owner ->
					incoming.kinds[owner.factIdentity] != IncomingAmbientStepsIdentityKind.FACT ||
						incoming.factOwners[owner.factIdentity] != owner.dayIdentity
				} ||
				gaps.any { owner ->
					incoming.kinds[owner.gapIdentity] != IncomingAmbientStepsIdentityKind.GAP ||
						incoming.gapOwners[owner.gapIdentity] != owner.dayIdentity
				}
			) blocked(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private suspend fun insertCountDomainGraph(
		plan: AmbientStepsDayImportPlan,
		sourceSchemaVersion: Int,
	) {
		val graphDao = database.importedPortableStepsCountDomainDao()
		val stored = graphDao.graph(plan.graph.identity.value)
		if (stored == null) {
			graphDao.insertAuthenticatedGraph(
				plan.graph,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
			)
		} else if (graphDao.authenticatedGraph(
				plan.graph.identity.value,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
			) != plan.graph
		) {
			storedCorrupt()
		}
		graphDao.insertBinding(
			ImportedPortableStepsCountDomainBindingEntity(
				productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
				productIdentity = plan.day.identity.value,
				productRevision = plan.revision,
				graphIdentity = plan.graph.identity.value,
				sourceSchemaVersion = sourceSchemaVersion,
			),
		)
	}

	private suspend fun authenticateCapacity(added: NewImportedAmbientStepsRows) {
		checkCapacity(dao.archiveCount(), added.archives, ImportedAmbientStepsDao.MAX_GLOBAL_ARCHIVES)
		checkCapacity(dao.receiptCount(), added.receipts, ImportedAmbientStepsDao.MAX_GLOBAL_RECEIPTS)
		checkCapacity(
			dao.archiveDayCount(),
			added.archiveDays,
			ImportedAmbientStepsDao.MAX_GLOBAL_ARCHIVE_DAYS,
		)
		checkCapacity(
			dao.dayRevisionCount(),
			added.dayRevisions,
			ImportedAmbientStepsDao.MAX_GLOBAL_DAY_REVISIONS,
		)
		checkCapacity(dao.factCount(), added.facts, ImportedAmbientStepsDao.MAX_GLOBAL_FACTS)
		checkCapacity(dao.gapCount(), added.gaps, ImportedAmbientStepsDao.MAX_GLOBAL_GAPS)
		checkCapacity(dao.fenceCount(), 0L, ImportedAmbientStepsDao.MAX_GLOBAL_FENCES)
		checkCapacity(
			dao.protectedIdentityCount(),
			0L,
			ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES,
		)
	}

	private suspend fun incrementSourceEvidenceRevision(
		currentUpdatedAtMs: Long,
		changedAtMs: Long,
	) {
		check(
			database.sourceEvidenceStateDao().incrementRevision(
				maxOf(currentUpdatedAtMs, changedAtMs),
			) == 1,
		) { "Ambient Steps import could not advance source-evidence revision" }
	}

	private suspend fun authenticateStructuralDayOwnership(
		archive: PortableAmbientStepsArchiveV1,
	) {
		val days = archive.days
		val firstEpochDay = days.minOf(PortableAmbientStepsDayV1::structuralEpochDay)
		val lastEpochDay = days.maxOf(PortableAmbientStepsDayV1::structuralEpochDay)
		val zoneIds = days.map(PortableAmbientStepsDayV1::storedZoneId).distinct()
		val owners = storedValue {
			dao.latestDaysForStructuralRange(
				firstEpochDay,
				lastEpochDay,
				zoneIds,
				AmbientStepsPortableFormatV1.MAX_DAYS + 1,
			)
		}
		val fences = storedValue {
			dao.fencesForStructuralRange(
				firstEpochDay,
				lastEpochDay,
				zoneIds,
				AmbientStepsPortableFormatV1.MAX_DAYS + 1,
			)
		}
		if (owners.size > AmbientStepsPortableFormatV1.MAX_DAYS ||
			fences.size > AmbientStepsPortableFormatV1.MAX_DAYS
		) {
			dependencyOverflow()
		}
		val incoming = days.associateBy(::StructuralAmbientStepsDayKey)
		val matchingFences = fences.filter { fence ->
			StructuralAmbientStepsDayKey(fence) in incoming
		}
		if (matchingFences.groupBy(::StructuralAmbientStepsDayKey).values.any { it.size != 1 }) {
			storedCorrupt()
		}
		if (matchingFences.isNotEmpty()) {
			if (matchingFences.any {
					it.fenceKind == ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION
				}
			) {
				blocked(PortableAmbientStepsImportBlockedReason.RETAINED_DAY)
			}
			blocked(PortableAmbientStepsImportBlockedReason.DELETED_DAY)
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
				blocked(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
			}
		}
	}

	private fun checkCapacity(current: Long, added: Long, maximum: Long) {
		try {
			if (current < 0L || added < 0L || Math.addExact(current, added) > maximum) {
				dependencyOverflow()
			}
		} catch (_: ArithmeticException) {
			dependencyOverflow()
		}
	}

	private fun snapshot(
		request: AmbientImportEnvelope,
	): AmbientImportEnvelope = incomingValue {
		val rawDays = request.archive.days
		if (rawDays.size !in 1..AmbientStepsPortableFormatV1.MAX_DAYS ||
			rawDays.any {
				it.facts.size !in 1..AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY ||
					it.gaps.size > AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY ||
					it.partialCauses.size > PortableAmbientStepsPartialCause.entries.size
			}
		) dependencyOverflow()
		var rawFactCount = 0L
		var rawGapCount = 0L
		rawDays.forEach { day ->
			rawFactCount = Math.addExact(rawFactCount, day.facts.size.toLong())
			rawGapCount = Math.addExact(rawGapCount, day.gaps.size.toLong())
		}
		if (rawFactCount > AmbientStepsPortableFormatV1.MAX_FACTS ||
			rawGapCount > AmbientStepsPortableFormatV1.MAX_GAPS ||
			request.dayCount != rawDays.size ||
			request.factCount.toLong() != rawFactCount ||
			request.gapCount.toLong() != rawGapCount
		) {
			if (rawFactCount > AmbientStepsPortableFormatV1.MAX_FACTS ||
				rawGapCount > AmbientStepsPortableFormatV1.MAX_GAPS
			) dependencyOverflow()
			unverifiable(PortableAmbientStepsImportUnverifiableReason.METADATA_MISMATCH)
		}
		val days = rawDays.map { day ->
			day.copy(
				partialCauses = day.partialCauses.toList(),
				facts = day.facts.map { it.copy() },
				gaps = day.gaps.map { it.copy() },
			)
		}
		val archive = request.archive.copy(days = days)
		val factCount = days.sumOf { it.facts.size }
		val gapCount = days.sumOf { it.gaps.size }
		if (request.dayCount != days.size ||
			request.factCount != factCount ||
			request.gapCount != gapCount
		) unverifiable(PortableAmbientStepsImportUnverifiableReason.METADATA_MISMATCH)
		if (days.map { StructuralAmbientStepsDayKey(it) }.distinct().size != days.size) {
			unverifiable(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
		}
		IncomingAmbientStepsOwnership(
			archive,
			ImportedAmbientStepsIdentity.receipt(
				request.receipt.jobId,
				request.receipt.archiveKey,
			),
		)
		val copiedGraphs = request.graphsByDayIdentity.mapValues { (_, graph) ->
			graph.copy(
				receipts = graph.receipts.map { it.copy() },
				ownerRevisions = graph.ownerRevisions.map { it.copy() },
				completenessMarkers = graph.completenessMarkers.map { it.copy() },
				roots = graph.roots.map { it.copy() },
			)
		}
		days.forEach { day ->
			copiedGraphs[day.identity.value]?.let { graph ->
				PortableAmbientStepsDayV2(day, graph)
			}
		}
		request.copy(
			archive = archive,
			graphsByDayIdentity = copiedGraphs,
		)
	}

	private suspend inline fun <T> storedValue(crossinline block: suspend () -> T): T = try {
		block()
	} catch (abort: ImportedAmbientStepsImportAbort) {
		throw abort
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: ImportedAmbientStepsLineageFailure) {
		when (failure.reason) {
			ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW ->
				dependencyOverflow()
			ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW ->
				unverifiable(PortableAmbientStepsImportUnverifiableReason.REVISION_OVERFLOW)
			ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
				storedCorrupt()
		}
	} catch (_: IllegalArgumentException) {
		storedCorrupt()
	} catch (_: IllegalStateException) {
		storedCorrupt()
	} catch (_: ArithmeticException) {
		storedCorrupt()
	} catch (_: java.time.DateTimeException) {
		storedCorrupt()
	}

	private inline fun <T> incomingValue(block: () -> T): T = try {
		block()
	} catch (abort: ImportedAmbientStepsImportAbort) {
		throw abort
	} catch (_: IllegalArgumentException) {
		unverifiable(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
	} catch (_: IllegalStateException) {
		unverifiable(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
	} catch (_: ArithmeticException) {
		unverifiable(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
	} catch (_: java.time.DateTimeException) {
		unverifiable(PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID)
	}

	private fun dependencyOverflow(): Nothing =
		unverifiable(PortableAmbientStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW)

	private fun storedCorrupt(): Nothing =
		unverifiable(PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

	private fun blocked(reason: PortableAmbientStepsImportBlockedReason): Nothing =
		throw ImportedAmbientStepsImportAbort(ImportPortableAmbientStepsResult.Blocked(reason))

	private fun unverifiable(reason: PortableAmbientStepsImportUnverifiableReason): Nothing =
		throw ImportedAmbientStepsImportAbort(ImportPortableAmbientStepsResult.Unverifiable(reason))

	private class ImportedAmbientStepsImportAbort(
		val result: ImportPortableAmbientStepsResult,
	) : RuntimeException(null, null, false, false)

	private companion object {
		const val IDENTITY_QUERY_CHUNK_SIZE = 256
	}
}

internal enum class ImportedAmbientStepsWriteCheckpoint {
	TRANSACTION_STARTED,
	LINEAGE_AUTHENTICATED,
	ARCHIVE_INSERTED,
	DAY_INSERTED,
	FACTS_INSERTED,
	GAPS_INSERTED,
	ARCHIVE_MEMBERS_INSERTED,
	RECEIPT_INSERTED,
}

private data class AmbientStepsDayImportPlan(
	val day: PortableAmbientStepsDayV1,
	val revision: Long,
	val append: Boolean,
	val graph: com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2,
)

private data class AmbientImportEnvelope(
	val archive: PortableAmbientStepsArchiveV1,
	val sourceArchiveIdentity:
		com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity,
	val sourceArchiveContentChecksum:
		com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest,
	val sourceFormat: String,
	val sourceSchemaVersion: Int,
	val receipt: com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportReceipt,
	val encodedByteCount: Long,
	val dayCount: Int,
	val factCount: Int,
	val gapCount: Int,
	val expectedCollectedDataEpoch: Long,
	val graphsByDayIdentity:
		Map<String, com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2>,
) {
	fun graphFor(
		day: PortableAmbientStepsDayV1,
		importRevision: Long,
	): com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2 =
		graphsByDayIdentity[day.identity.value]
			?: day.withExplicitUnprovenCountDomain(importRevision).countDomainGraph

	fun incomingOwnerIdentities(): List<String> {
		val graphs = incomingGraphs()
		return graphs.flatMap { graph -> graph.roots.map { it.ownerIdentity.value } }.distinct()
	}

	fun ownsRoot(
		containerIdentity: String,
		productIdentity: String,
		ownerKind: String,
		ownerIdentity: String,
	): Boolean = incomingGraphs().any { graph ->
		graph.roots.any { root ->
			root.containerIdentity.value == containerIdentity &&
				root.productIdentity.value == productIdentity &&
				root.ownerKind.name == ownerKind &&
				root.ownerIdentity.value == ownerIdentity
		}
	}

	private fun incomingGraphs() = if (graphsByDayIdentity.isNotEmpty()) {
		graphsByDayIdentity.values
	} else {
		archive.days.map {
			it.withExplicitUnprovenCountDomain().countDomainGraph
		}
	}

	companion object {
		fun fromV1(request: ImportPortableAmbientStepsRequest) = AmbientImportEnvelope(
			archive = request.archive,
			sourceArchiveIdentity = request.archive.identity,
			sourceArchiveContentChecksum = request.archive.contentChecksum,
			sourceFormat = request.archive.format,
			sourceSchemaVersion = request.archive.schemaVersion,
			receipt = request.receipt,
			encodedByteCount = request.metadata.encodedByteCount,
			dayCount = request.metadata.dayCount,
			factCount = request.metadata.factCount,
			gapCount = request.metadata.gapCount,
			expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
			graphsByDayIdentity = emptyMap(),
		)

		fun fromV2(request: ImportPortableAmbientStepsV2Request): AmbientImportEnvelope {
			val days = request.archive.days.map(PortableAmbientStepsDayV2::product)
			val graphMap = request.archive.days.associate {
				it.product.identity.value to it.countDomainGraph
			}
			require(graphMap.size == days.size)
			require(
				request.metadata.receiptCount ==
					graphMap.values.sumOf { it.receipts.size },
			)
			require(
				request.metadata.ownerRevisionCount ==
					graphMap.values.sumOf { it.ownerRevisions.size },
			)
			require(request.metadata.rootCount == graphMap.values.sumOf { it.roots.size })
			return AmbientImportEnvelope(
				archive = PortableAmbientStepsArchiveV1.create(days),
				sourceArchiveIdentity = request.archive.identity,
				sourceArchiveContentChecksum = request.archive.contentChecksum,
				sourceFormat = request.archive.format,
				sourceSchemaVersion = request.archive.schemaVersion,
				receipt = request.receipt,
				encodedByteCount = request.metadata.encodedByteCount,
				dayCount = request.metadata.dayCount,
				factCount = request.metadata.factCount,
				gapCount = request.metadata.gapCount,
				expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
				graphsByDayIdentity = graphMap,
			)
		}
	}
}

private fun isContiguousPortableCorrection(
	previous: com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2,
	incoming: com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2,
): Boolean {
	val previousByLineage = previous.ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }
	val incomingByLineage = incoming.ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }
	if (previousByLineage.keys != incomingByLineage.keys) return false
	if (previous.roots.map { Triple(it.productIdentity, it.ownerKind, it.ownerIdentity) }.toSet() !=
		incoming.roots.map { Triple(it.productIdentity, it.ownerKind, it.ownerIdentity) }.toSet()
	) return false
	var advanced = false
	for ((lineage, priorOwners) in previousByLineage) {
		val nextOwners = incomingByLineage.getValue(lineage)
		val legacyUnproven = priorOwners.size == 1 && nextOwners.size == 1 &&
			priorOwners.single().operation ==
			com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation.UNPROVEN &&
			nextOwners.single().operation ==
			com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation.UNPROVEN
		if (legacyUnproven) {
			if (nextOwners.single().ownerRevision != priorOwners.single().ownerRevision + 1L) {
				return false
			}
			advanced = true
		} else {
			if (nextOwners.size < priorOwners.size ||
				nextOwners.take(priorOwners.size) != priorOwners
			) return false
			if (nextOwners.size > priorOwners.size) advanced = true
		}
	}
	return advanced
}

private data class NewImportedAmbientStepsRows(
	val archives: Long = 0L,
	val receipts: Long = 0L,
	val archiveDays: Long = 0L,
	val dayRevisions: Long = 0L,
	val facts: Long = 0L,
	val gaps: Long = 0L,
)

internal fun importedAmbientStepsLineageAdditionFits(
	existingArchiveMemberCount: Int,
	existingReceiptCount: Int,
	incomingArchiveMemberCount: Int,
	addedReceiptCount: Int = 1,
): Boolean {
	require(existingArchiveMemberCount >= 0)
	require(existingReceiptCount >= 0)
	require(incomingArchiveMemberCount >= 0)
	require(addedReceiptCount >= 0)
	return existingArchiveMemberCount.toLong() + incomingArchiveMemberCount <=
		ImportedAmbientStepsDao.MAX_ARCHIVE_MEMBERS_PER_LINEAGE &&
		existingReceiptCount.toLong() + addedReceiptCount <=
		ImportedAmbientStepsDao.MAX_RECEIPTS_PER_LINEAGE
}

private data class StructuralAmbientStepsDayKey(
	val epochDay: Long,
	val zoneId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	constructor(day: PortableAmbientStepsDayV1) : this(
		day.structuralEpochDay,
		day.storedZoneId,
		day.structuralDayStartTimeMs,
		day.structuralDayEndTimeMs,
	)
}

private enum class IncomingAmbientStepsIdentityKind {
	ARCHIVE,
	DAY,
	DELETION_SCOPE,
	FACT,
	GAP,
	RECEIPT,
}

private class IncomingAmbientStepsOwnership(
	archive: PortableAmbientStepsArchiveV1,
	archiveIdentity: String,
	receiptIdentity: String,
) {
	val kinds = linkedMapOf<String, IncomingAmbientStepsIdentityKind>()
	val dayOwners = mutableMapOf<String, String>()
	val scopeOwners = mutableMapOf<String, String>()
	val factOwners = mutableMapOf<String, String>()
	val gapOwners = mutableMapOf<String, String>()

	init {
		add(archiveIdentity, IncomingAmbientStepsIdentityKind.ARCHIVE)
		add(receiptIdentity, IncomingAmbientStepsIdentityKind.RECEIPT)
		archive.days.forEach { day ->
			add(day.identity.value, IncomingAmbientStepsIdentityKind.DAY)
			dayOwners[day.identity.value] = day.identity.value
			add(day.deletionScopeIdentity.value, IncomingAmbientStepsIdentityKind.DELETION_SCOPE)
			scopeOwners[day.deletionScopeIdentity.value] = day.identity.value
			day.facts.forEach { fact ->
				add(fact.identity.value, IncomingAmbientStepsIdentityKind.FACT)
				require(factOwners.put(fact.identity.value, day.identity.value) == null)
			}
			day.gaps.forEach { gap ->
				add(gap.identity.value, IncomingAmbientStepsIdentityKind.GAP)
				require(gapOwners.put(gap.identity.value, day.identity.value) == null)
			}
		}
	}

	val allIdentities: List<String>
		get() = kinds.keys.toList()

	private fun add(identity: String, kind: IncomingAmbientStepsIdentityKind) {
		require(kinds.put(identity, kind) == null)
	}
}

private fun StructuralAmbientStepsDayKey(
	fence: ImportedAmbientStepsDayFenceEntity,
) = StructuralAmbientStepsDayKey(
	fence.structuralEpochDay,
	fence.storedZoneId,
	fence.structuralDayStartTimeMs,
	fence.structuralDayEndTimeMs,
)

private fun PortableAmbientStepsDayV1.crossesLocalRetentionFloor(floor: Long): Boolean {
	if (structuralDayEndTimeMs <= floor) return true
	if (structuralDayStartTimeMs < floor &&
		(retainedFromTimeMs == null || retainedFromTimeMs < floor)
	) return true
	return facts.any { it.intervalStartTimeMs < floor } ||
		gaps.any { it.intervalStartTimeMs < floor }
}

private fun PortableAmbientStepsDayV1.hasSameStructuralAuthorityAs(
	other: PortableAmbientStepsDayV1,
): Boolean = identity == other.identity &&
	structuralEpochDay == other.structuralEpochDay &&
	storedZoneId == other.storedZoneId &&
	structuralDayStartTimeMs == other.structuralDayStartTimeMs &&
	structuralDayEndTimeMs == other.structuralDayEndTimeMs &&
	deletionScopeIdentity == other.deletionScopeIdentity

private fun AmbientImportEnvelope.toArchiveEntity() =
	ImportedAmbientStepsArchiveEntity(
		archiveIdentity = sourceArchiveIdentity.value,
		contentChecksum = sourceArchiveContentChecksum.value,
		sourceFormat = sourceFormat,
		sourceSchemaVersion = sourceSchemaVersion,
		encodedByteCount = encodedByteCount,
		dayCount = archive.days.size,
		factCount = archive.days.sumOf { it.facts.size },
		gapCount = archive.days.sumOf { it.gaps.size },
		collectedDataEpoch = expectedCollectedDataEpoch,
		firstReceivedAtMs = receipt.receivedAtMs,
	)

private fun AmbientImportEnvelope.toReceiptEntity() =
	ImportedAmbientStepsReceiptEntity(
		importJobId = receipt.jobId,
		archiveKey = receipt.archiveKey,
		receiptIdentity = ImportedAmbientStepsIdentity.receipt(receipt.jobId, receipt.archiveKey),
		sourceName = receipt.sourceName,
		receivedAtMs = receipt.receivedAtMs,
		archiveIdentity = sourceArchiveIdentity.value,
		archiveContentChecksum = sourceArchiveContentChecksum.value,
		collectedDataEpoch = expectedCollectedDataEpoch,
	)

private fun PortableAmbientStepsDayV1.toEntity(
	request: AmbientImportEnvelope,
	revision: Long,
) = ImportedAmbientStepsDayRevisionEntity(
	dayIdentity = identity.value,
	importRevision = revision,
	supersedesImportRevision = if (revision == 1L) null else revision - 1L,
	archiveIdentity = request.sourceArchiveIdentity.value,
	dayContentChecksum = contentChecksum.value,
	deletionScopeIdentity = deletionScopeIdentity.value,
	structuralEpochDay = structuralEpochDay,
	storedZoneId = storedZoneId,
	structuralDayStartTimeMs = structuralDayStartTimeMs,
	structuralDayEndTimeMs = structuralDayEndTimeMs,
	retainedFromTimeMs = retainedFromTimeMs,
	coverage = coverage.name,
	partialCauses = ImportedAmbientStepsIdentity.encodePartialCauses(partialCauses),
	retainedStepCount = retainedStepCount,
	factCount = facts.size,
	gapCount = gaps.size,
	collectedDataEpoch = request.expectedCollectedDataEpoch,
	receivedAtMs = request.receipt.receivedAtMs,
)
