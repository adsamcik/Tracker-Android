package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOriginFailure
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOriginFailureReason
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOriginReader
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwnerKind
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwnerState
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.isActiveApproval
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2Request
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsTransferRetryableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Imports saved all-day Steps without registering or activating a provider.
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
	): ImportPortableAmbientStepsResult {
		val prepared = try {
			AmbientImportEnvelope.fromV2(request)
		} catch (_: IllegalArgumentException) {
			return ImportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID,
			)
		} catch (_: IllegalStateException) {
			return ImportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID,
			)
		} catch (_: ArithmeticException) {
			return ImportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		}
		return importPrepared(prepared)
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
			val snapshot = snapshotAmbientStepsImport(request)
			database.withTransaction { importInTransaction(snapshot) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: PortableAmbientStepsImportAbort) {
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

	private suspend fun importInTransaction(
		request: AmbientImportEnvelope,
	): ImportPortableAmbientStepsResult {
		checkpoint(ImportedAmbientStepsWriteCheckpoint.TRANSACTION_STARTED)
		val state = database.readAmbientStepsImportState(request, dao, requirePortableRetentionAuthority)
		val archive = request.archive
		val dayIdentities = archive.days.map { it.identity.value }
		val incomingOwnership = IncomingAmbientStepsOwnership(
			archive,
			request.sourceArchiveIdentity.value,
			ImportedAmbientStepsIdentity.receipt(request.receipt.jobId, request.receipt.archiveKey),
		)
		database.authenticateAmbientStepsNativeReplayFootprints(incomingOwnership, state)
		dao.authenticateAmbientStepsDayAdmission(archive, dayIdentities, state)
		dao.authenticateAmbientStepsReceiptIdentityOwnership(request)
		dao.authenticateAmbientStepsProtectedIdentities(incomingOwnership, dayIdentities)
		state.retainedFromMs?.let { floor ->
			if (archive.days.any { it.crossesLocalRetentionFloor(floor) }) {
				blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.RETENTION_BOUNDARY)
			}
		}
		authenticateNativeOverlap(archive, incomingOwnership)
		dao.authenticateAmbientStepsImportedIdentityOwnership(incomingOwnership)
		database.authenticateAmbientStepsCountDomainOwners(request)
		replayStoredArchive(request, state)?.let { return it }
		val plans = archive.days.map { day ->
			database.prepareAmbientStepsDayImport(dao, day, request, state, checkpoint)
		}
		return persistArchive(request, state, plans)
	}

	private suspend fun replayStoredArchive(
		request: AmbientImportEnvelope,
		state: SourceEvidenceState,
	): ImportPortableAmbientStepsResult? {
		val storedReceipt = ambientStepsStoredValue {
			dao.receipt(request.receipt.jobId, request.receipt.archiveKey)
		}
		if (storedReceipt != null) {
			database.authenticateAmbientStepsReceiptReplay(dao, request, storedReceipt)
			return ImportPortableAmbientStepsResult.Duplicate(
				request.sourceArchiveIdentity,
				request.archive.days.size,
			)
		}
		val storedArchive = ambientStepsStoredValue { dao.archive(request.sourceArchiveIdentity.value) }
		if (storedArchive != null) {
			database.authenticateAmbientStepsStoredArchive(dao, request, storedArchive, addedReceiptCount = 1)
			dao.authenticateAmbientStepsPostInsertReceipt(request, storedArchive)
			dao.authenticateAmbientStepsCapacity(NewImportedAmbientStepsRows(receipts = 1L))
			dao.insertReceipt(request.toReceiptEntity())
			checkpoint(ImportedAmbientStepsWriteCheckpoint.RECEIPT_INSERTED)
			database.incrementAmbientStepsImportRevision(state.updatedAtMs, request.receipt.receivedAtMs)
			return ImportPortableAmbientStepsResult.Duplicate(
				request.sourceArchiveIdentity,
				request.archive.days.size,
			)
		}
		return null
	}

	private suspend fun persistArchive(
		request: AmbientImportEnvelope,
		state: SourceEvidenceState,
		plans: List<AmbientStepsDayImportPlan>,
	): ImportPortableAmbientStepsResult {
		val appendedProducts = plans.filter(AmbientStepsDayImportPlan::appendProduct)
		dao.authenticateAmbientStepsCapacity(
			NewImportedAmbientStepsRows(
				archives = 1L,
				receipts = 1L,
				archiveDays = plans.size.toLong(),
				dayRevisions = appendedProducts.size.toLong(),
				facts = appendedProducts.sumOf { it.day.facts.size.toLong() },
				gaps = appendedProducts.sumOf { it.day.gaps.size.toLong() },
			),
		)
		dao.insertArchive(request.toArchiveEntity())
		checkpoint(ImportedAmbientStepsWriteCheckpoint.ARCHIVE_INSERTED)
		appendedProducts.forEach { plan ->
			insertDayProduct(plan, request)
		}
		dao.insertArchiveDays(plans.mapIndexed { ordinal, plan ->
			ImportedAmbientStepsArchiveDayEntity(
				archiveIdentity = request.sourceArchiveIdentity.value,
				ordinal = ordinal,
				dayIdentity = plan.day.identity.value,
				dayContentChecksum = plan.day.contentChecksum.value,
				boundDayImportRevision = plan.productRevision,
				factCount = plan.day.facts.size,
				gapCount = plan.day.gaps.size,
				boundCountDomainGraphRevision = plan.graphRevision,
			)
		})
		checkpoint(ImportedAmbientStepsWriteCheckpoint.ARCHIVE_MEMBERS_INSERTED)
		plans.filter(AmbientStepsDayImportPlan::appendGraph).forEach { plan ->
			database.insertAmbientStepsCountDomainGraph(plan, request)
		}
		dao.insertReceipt(request.toReceiptEntity())
		checkpoint(ImportedAmbientStepsWriteCheckpoint.RECEIPT_INSERTED)
		database.incrementAmbientStepsImportRevision(state.updatedAtMs, request.receipt.receivedAtMs)
		return ImportPortableAmbientStepsResult.Applied(
			archiveIdentity = request.sourceArchiveIdentity,
			appendedDayRevisionCount = appendedProducts.size,
			dayCount = request.archive.days.size,
			factCount = request.archive.days.sumOf { it.facts.size },
			gapCount = request.archive.days.sumOf { it.gaps.size },
		)
	}

	private suspend fun insertDayProduct(
		plan: AmbientStepsDayImportPlan,
		request: AmbientImportEnvelope,
	) {
		dao.insertDayRevision(plan.day.toEntity(request, plan.productRevision))
		checkpoint(ImportedAmbientStepsWriteCheckpoint.DAY_INSERTED)
		dao.insertFacts(plan.day.facts.map { fact ->
			ImportedAmbientStepsFactEntity(
				dayIdentity = plan.day.identity.value,
				dayImportRevision = plan.productRevision,
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
					dayImportRevision = plan.productRevision,
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

	private suspend fun authenticateNativeOverlap(
		archive: PortableAmbientStepsArchiveV1,
		incoming: IncomingAmbientStepsOwnership,
	) {
		val localOwners = readLocalOwners(incoming)
		val incomingFacts = archive.days.flatMap(PortableAmbientStepsDayV1::facts)
			.associateBy { it.identity.value }
		localOwners.forEach { owner ->
			val incomingKind = incoming.kinds[owner.identity]
				?: return@forEach
			val fact = incomingFacts[owner.identity]
			val activeFact = owner.kind == AmbientStepsPortableLocalOwnerKind.FACT &&
				owner.state == AmbientStepsPortableLocalOwnerState.ACTIVE
			if (incomingKind == IncomingAmbientStepsIdentityKind.FACT && activeFact &&
				fact != null && owner.contentChecksum == fact.contentChecksum.value
			) {
				blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.LOCAL_ORIGIN_OVERLAP)
			}
			blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private suspend fun readLocalOwners(
		incoming: IncomingAmbientStepsOwnership,
	): List<com.adsamcik.tracker.shared.base.database.AmbientStepsPortableLocalOwner> =
		try {
			localOriginSource(incoming.allIdentities.toSet())
		} catch (abort: PortableAmbientStepsImportAbort) {
			throw abort
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: AmbientStepsPortableLocalOriginFailure) {
			when (failure.reason) {
				AmbientStepsPortableLocalOriginFailureReason.DEPENDENCY_OVERFLOW ->
					ambientStepsImportOverflow()
				AmbientStepsPortableLocalOriginFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
					corruptAmbientStepsImport()
			}
		} catch (_: IllegalArgumentException) {
			corruptAmbientStepsImport()
		} catch (_: IllegalStateException) {
			corruptAmbientStepsImport()
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
