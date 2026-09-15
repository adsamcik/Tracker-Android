package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalFailure
import com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalFailureReason
import com.adsamcik.tracker.shared.base.database.applyPressureSourceEraseLocalMutationInTransaction
import com.adsamcik.tracker.shared.base.database.auditPressureSourceEraseLocalAuthorityInTransaction
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.pressureSourceEraseRequiresHardwareAuthority
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ErasePressureSource
import com.adsamcik.tracker.stats.api.repository.ErasePressureSourceRequest
import com.adsamcik.tracker.stats.api.repository.ErasePressureSourceResult
import com.adsamcik.tracker.stats.api.repository.ImportedPressureMaintenanceUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrier
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseRetryableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One source-specific erase spanning local and portable-origin Pressure authority. */
@Singleton
internal class RoomErasePressureSource internal constructor(
	private val database: AppDatabase,
	private val barrier: PressureSourceEraseBarrier,
	private val ioDispatcher: CoroutineDispatcher,
	private val checkpoint: suspend (PressureSourceEraseCheckpoint) -> Unit,
	private val limits: PressureSourceEraseLimits = PressureSourceEraseLimits(),
) : ErasePressureSource {
	@Inject
	constructor(
		database: AppDatabase,
		barrier: PressureSourceEraseBarrier,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, barrier, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun erase(
		request: ErasePressureSourceRequest,
	): ErasePressureSourceResult = withContext(ioDispatcher) {
		try {
			val requiresHardwareAuthority = database.withTransaction {
				val state = database.sourceEvidenceStateDao().get()
					?: unverifiable(
						ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
					)
				if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
					state.revision != request.expectedSourceEvidenceRevision ||
					state.deletedSourceEventHighWaterOrdinal !=
					request.expectedDeletedSourceEventHighWaterOrdinal
				) blocked(PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
				database.pressureSourceEraseRequiresHardwareAuthority().also { required ->
					if (required) preflightLocalPolicy(request)
				}
			}

			if (requiresHardwareAuthority) establishBarrier(request)
			database.withTransaction { eraseInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: PressureSourceEraseAbort) {
			abort.result
		} catch (failure: PressureSourceEraseLocalFailure) {
			failure.toPublicResult()
		} catch (_: SQLiteConstraintException) {
			ErasePressureSourceResult.RetryableFailure(
				PressureSourceEraseRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			ErasePressureSourceResult.RetryableFailure(
				PressureSourceEraseRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: IllegalArgumentException) {
			ErasePressureSourceResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		} catch (_: ArithmeticException) {
			ErasePressureSourceResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW,
			)
		} catch (_: Exception) {
			ErasePressureSourceResult.RetryableFailure(
				PressureSourceEraseRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun preflightLocalPolicy(request: ErasePressureSourceRequest) {
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				it.currentPolicyRevision == request.expectedCurrentPolicyRevision
		} ?: blocked(PressureSourceEraseBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
		val policy = policyDao.policyAtRevision(
			request.expectedCurrentPolicyRevision,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		)
		val consent = policyDao.latestConsentEpoch(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SessionManifestPurposeCode.SESSION_CAPTURE,
		)
		if (policy == null || consent == null ||
			consent.epoch != request.expectedRevokedConsentEpoch
		) blocked(PressureSourceEraseBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
		if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
			consent.eligible || consent.persistenceEligible ||
			consent.policyRevision != policy.policyRevision ||
			consent.effectiveBootId != policy.effectiveBootId ||
			consent.effectiveElapsedRealtimeNanos != policy.effectiveElapsedRealtimeNanos ||
			consent.effectiveWallTimeMs != policy.effectiveWallTimeMs
		) blocked(PressureSourceEraseBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
		val demands = database.sourceBrokerDao().activeDemandsBounded(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			PREFLIGHT_DEMAND_LIMIT + 1,
		)
		if (demands.size > PREFLIGHT_DEMAND_LIMIT) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		if (demands.any {
			it.purpose == SourceBrokerPurpose.SESSION_CAPTURE || it.persistenceEligible
		}) blocked(PressureSourceEraseBlockedReason.DIRECT_DEMAND_NOT_QUIESCED)
		if (request.erasedAtMs < maxOf(
				authority.updatedAtMs,
				policy.effectiveWallTimeMs,
				consent.effectiveWallTimeMs,
			)
		) blocked(PressureSourceEraseBlockedReason.STALE_REQUEST)
	}

	private suspend fun establishBarrier(request: ErasePressureSourceRequest) {
		when (val result = barrier.establish(request.expectedCollectedDataEpoch)) {
			PressureSourceEraseBarrierResult.NoLocalProvider,
			is PressureSourceEraseBarrierResult.Established,
			-> Unit
			is PressureSourceEraseBarrierResult.Blocked -> blocked(
				when (result.reason) {
					PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE ->
						PressureSourceEraseBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED
					PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE ->
						PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED
				},
			)
			is PressureSourceEraseBarrierResult.Retryable -> retryable(
				when (result.reason) {
					PressureSourceEraseBarrierRetryableReason.CALLBACK_DRAIN_TIMED_OUT ->
						PressureSourceEraseRetryableReason.CALLBACK_DRAIN_UNAVAILABLE
					PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED ->
						PressureSourceEraseRetryableReason.STORAGE_UNAVAILABLE
				},
			)
		}
	}

	@Suppress("LongMethod", "ComplexCondition")
	private suspend fun eraseInTransaction(
		request: ErasePressureSourceRequest,
	): ErasePressureSourceResult {
		checkpoint(PressureSourceEraseCheckpoint.TRANSACTION_STARTED)
		val state = database.sourceEvidenceStateDao().get()
			?: unverifiable(ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			state.revision != request.expectedSourceEvidenceRevision ||
			state.deletedSourceEventHighWaterOrdinal !=
			request.expectedDeletedSourceEventHighWaterOrdinal
		) blocked(PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		val dao = database.importedPressureDao()
		dao.sourceErase()?.let { retained ->
			if (retained.collectedDataEpoch != state.collectedDataEpoch ||
				retained.sourceEvidenceRevision > state.revision ||
				retained.erasedAtMs > state.updatedAtMs ||
				database.pressureSourceEraseRequiresHardwareAuthority() ||
				dao.retentionCandidatePage(null, 1).isNotEmpty() ||
				dao.retentionReceiptPage(null, 1).isNotEmpty()
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			return ErasePressureSourceResult.AlreadyErased
		}

		val local = database.auditPressureSourceEraseLocalAuthorityInTransaction(
			expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
			expectedDeletedSourceEventHighWaterOrdinal =
				request.expectedDeletedSourceEventHighWaterOrdinal,
			expectedCurrentPolicyRevision = request.expectedCurrentPolicyRevision,
			expectedRevokedConsentEpoch = request.expectedRevokedConsentEpoch,
			erasedAtMs = request.erasedAtMs,
		)
		val live = scanLiveImported(state)
		val retained = scanRetainedImported(state)
		val totals = try {
			live.plus(retained)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
		if (!totals.within(limits)) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		val nextRevision = try {
			Math.addExact(state.revision, 1L)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
		if (request.erasedAtMs < maxOf(
				state.updatedAtMs,
				local.latestDurableTimeMs,
				live.latestDurableTimeMs,
				retained.latestDurableTimeMs,
			)
		) blocked(PressureSourceEraseBlockedReason.STALE_REQUEST)

		val sourceErase = ImportedPressureSourceEraseEntity.create(
			collectedDataEpoch = state.collectedDataEpoch,
			sourceEvidenceRevision = nextRevision,
			erasedAtMs = request.erasedAtMs,
			localFactRevisionCount = local.factRevisionCount,
			localWalEventCount = local.walEventCount,
			importedEntryCount = totals.entryCount,
			importedRevisionCount = totals.revisionCount,
			importedRunCount = totals.runCount,
			importedWindowCount = totals.windowCount,
			fencedLocalRunCount = local.scopes.size,
		)
		dao.insertSourceErase(sourceErase)
		checkpoint(PressureSourceEraseCheckpoint.SOURCE_FENCE_INSERTED)

		database.applyPressureSourceEraseLocalMutationInTransaction(
			audit = local,
			expectedCollectedDataEpoch = state.collectedDataEpoch,
			erasedAtMs = request.erasedAtMs,
		)
		checkpoint(PressureSourceEraseCheckpoint.LOCAL_PAYLOAD_REMOVED)
		deleteLiveImported(state, request.erasedAtMs)
		deleteRetainedImported(state, request.erasedAtMs)
		checkpoint(PressureSourceEraseCheckpoint.IMPORTED_PAYLOAD_REMOVED)
		if (dao.retentionCandidatePage(null, 1).isNotEmpty() ||
			dao.retentionReceiptPage(null, 1).isNotEmpty()
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		if (database.sourceEvidenceStateDao().incrementRevision(request.erasedAtMs) != 1) {
			throw IllegalStateException("Unable to publish Pressure source erase")
		}
		val published = database.sourceEvidenceStateDao().get()
			?: unverifiable(ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (published.collectedDataEpoch != state.collectedDataEpoch ||
			published.revision != nextRevision || published.updatedAtMs != request.erasedAtMs ||
			dao.sourceErase() != sourceErase
		) blocked(PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		checkpoint(PressureSourceEraseCheckpoint.SOURCE_FENCE_VERIFIED)
		return ErasePressureSourceResult.Erased(
			localFactRevisionCount = local.factRevisionCount,
			localWalEventCount = local.walEventCount,
			importedEntryCount = totals.entryCount,
			importedRevisionCount = totals.revisionCount,
			importedRunCount = totals.runCount,
			importedWindowCount = totals.windowCount,
			fencedLocalRunCount = local.scopes.size,
		)
	}

	private suspend fun scanLiveImported(
		state: SourceEvidenceState,
	): PressureImportedEraseTotals {
		val dao = database.importedPressureDao()
		var cursor: String? = null
		var totals = PressureImportedEraseTotals()
		while (true) {
			currentCoroutineContext().ensureActive()
			val candidate = dao.retentionCandidatePage(cursor, 1).singleOrNull() ?: break
			if (cursor?.let { candidate.identity <= it } == true) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			cursor = candidate.identity
			val lineage = authenticateLineage(candidate.identity, state.collectedDataEpoch)
			if (dao.retentionReceipt(candidate.identity) != null ||
				dao.entryDeletion(candidate.identity) != null
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			authenticateLiveIdentityOwnership(lineage)
			val runIds = lineage.revisions.flatMap { it.entry.runs }.map { it.identity.value }.distinct()
			val deletions = runIds.chunked(SQLITE_BIND_BATCH).flatMap { dao.deletionGenerations(it) }
			if (deletions.distinctBy { it.runIdentity }.size != deletions.size ||
				deletions.any {
					it.collectedDataEpoch != state.collectedDataEpoch || it.generation != 1L
				}
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			totals = totals.plus(lineage)
		}
		return totals
	}

	private suspend fun authenticateLiveIdentityOwnership(
		lineage: AuthenticatedImportedPressureLineage,
	) {
		val entryIdentity = requireNotNull(lineage.latest).header.identity
		val expected = linkedMapOf<String, String>()
		fun bind(identity: String, kind: String) {
			val previous = expected.putIfAbsent(identity, kind)
			if (previous != null && previous != kind) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
			}
		}
		bind(entryIdentity, ImportedPressureRetainedIdentityEntity.ENTRY)
		lineage.revisions.forEach { revision ->
			revision.entry.runs.forEach { run ->
				bind(run.identity.value, ImportedPressureRetainedIdentityEntity.RUN_SCOPE)
				run.windows.forEach { window ->
					bind(window.identity.value, ImportedPressureRetainedIdentityEntity.WINDOW)
				}
			}
		}
		val dao = database.importedPressureDao()
		for (batch in expected.keys.chunked(SQLITE_BIND_BATCH)) {
			val limit = batch.size + 1
			val entries = dao.existingEntryIdentities(batch, limit)
			val runs = dao.existingRunIdentityOwners(batch, limit)
			val windows = dao.existingWindowIdentityOwners(batch, limit)
			val retained = dao.retainedIdentityOwners(batch, limit)
			if (entries.size >= limit || runs.size >= limit || windows.size >= limit ||
				retained.size >= limit
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			if (retained.isNotEmpty() ||
				entries.any {
					it != entryIdentity ||
						expected[it] != ImportedPressureRetainedIdentityEntity.ENTRY
				} || runs.any {
					it.entryIdentity != entryIdentity ||
						expected[it.identity] != ImportedPressureRetainedIdentityEntity.RUN_SCOPE
				} || windows.any {
					it.entryIdentity != entryIdentity ||
						expected[it.identity] != ImportedPressureRetainedIdentityEntity.WINDOW
				}
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
		}
	}

	private suspend fun scanRetainedImported(
		state: SourceEvidenceState,
	): PressureImportedEraseTotals {
		val dao = database.importedPressureDao()
		var cursor: String? = null
		var totals = PressureImportedEraseTotals()
		while (true) {
			val receipt = dao.retentionReceiptPage(cursor, 1).singleOrNull() ?: break
			if (cursor?.let { receipt.entryIdentity <= it } == true) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			cursor = receipt.entryIdentity
			when (database.authenticateImportedPressureRetention(state, receipt)) {
				ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
				ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
				null -> Unit
			}
			totals = totals.plus(receipt)
		}
		return totals
	}

	private suspend fun deleteLiveImported(
		state: SourceEvidenceState,
		erasedAtMs: Long,
	) {
		val dao = database.importedPressureDao()
		var cursor: String? = null
		while (true) {
			val candidate = dao.retentionCandidatePage(cursor, 1).singleOrNull() ?: break
			cursor = candidate.identity
			val lineage = authenticateLineage(candidate.identity, state.collectedDataEpoch)
			val latest = requireNotNull(lineage.latest)
			val runIds = lineage.revisions.flatMap { it.entry.runs }.map { it.identity.value }.distinct()
			installImportedDeletionAuthority(
				entryIdentity = candidate.identity,
				latestRevision = latest.header.importRevision,
				runIdentities = runIds,
				state = state,
				erasedAtMs = erasedAtMs,
			)
			if (dao.deleteEntryRevisionLineage(candidate.identity) != lineage.revisions.size) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			}
		}
	}

	private suspend fun deleteRetainedImported(
		state: SourceEvidenceState,
		erasedAtMs: Long,
	) {
		val dao = database.importedPressureDao()
		var cursor: String? = null
		while (true) {
			val receipt = dao.retentionReceiptPage(cursor, 1).singleOrNull() ?: break
			cursor = receipt.entryIdentity
			val markers = dao.retainedIdentitiesForEntries(
				listOf(receipt.entryIdentity),
				receipt.protectedIdentityCount + 1,
			)
			if (!receipt.authenticates(markers)) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			val runIds = markers.filter {
				it.identityKind == ImportedPressureRetainedIdentityEntity.RUN_SCOPE
			}.map { it.protectedIdentity }
			installImportedDeletionAuthority(
				entryIdentity = receipt.entryIdentity,
				latestRevision = receipt.latestImportRevision,
				runIdentities = runIds,
				state = state,
				erasedAtMs = erasedAtMs,
			)
			if (dao.deleteRetainedIdentities(receipt.entryIdentity) != receipt.protectedIdentityCount ||
				dao.deleteRetentionReceipt(receipt.entryIdentity) != 1
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
	}

	private suspend fun installImportedDeletionAuthority(
		entryIdentity: String,
		latestRevision: Long,
		runIdentities: List<String>,
		state: SourceEvidenceState,
		erasedAtMs: Long,
	) {
		val dao = database.importedPressureDao()
		val existingRuns = runIdentities.chunked(SQLITE_BIND_BATCH).flatMap {
			dao.deletionGenerations(it)
		}.associateBy { it.runIdentity }
		if (existingRuns.size > runIdentities.size || existingRuns.values.any {
			it.collectedDataEpoch != state.collectedDataEpoch || it.generation != 1L
		}) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		runIdentities.forEach { runIdentity ->
			if (runIdentity !in existingRuns) {
				dao.insertDeletionGeneration(
					ImportedPressureDeletionGenerationEntity.create(
						runIdentity,
						state.collectedDataEpoch,
						1L,
						erasedAtMs,
					),
				)
			}
		}
		if (dao.entryDeletion(entryIdentity) != null) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		dao.insertEntryDeletion(
			ImportedPressureEntryDeletionEntity.create(
				entryIdentity,
				state.collectedDataEpoch,
				latestRevision,
				erasedAtMs,
			),
		)
		checkpoint(PressureSourceEraseCheckpoint.IMPORTED_DELETION_AUTHORITY_INSERTED)
	}

	private suspend fun authenticateLineage(
		identity: String,
		expectedCollectedDataEpoch: Long,
	): AuthenticatedImportedPressureLineage {
		val dao = database.importedPressureDao()
		return try {
			ImportedPressureLineageAuthenticator.authenticate(
				identity,
				expectedCollectedDataEpoch,
				dao.entryRevisionsForAdmission(identity),
				dao.receiptsForAdmission(identity),
				dao.allRunsForAdmission(identity),
				dao.allWindowsForAdmission(identity),
			)
		} catch (failure: ImportedPressureLineageFailure) {
			when (failure.reason) {
				ImportedPressureLineageFailureReason.DEPENDENCY_OVERFLOW,
				ImportedPressureLineageFailureReason.RUN_OVERFLOW,
				ImportedPressureLineageFailureReason.WINDOW_OVERFLOW,
				ImportedPressureLineageFailureReason.TOTAL_WINDOW_OVERFLOW,
				ImportedPressureLineageFailureReason.REVISION_OVERFLOW,
				-> unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
				ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
		}
	}

	private fun blocked(reason: PressureSourceEraseBlockedReason): Nothing =
		throw PressureSourceEraseAbort(ErasePressureSourceResult.Blocked(reason))

	private fun unverifiable(reason: ImportedPressureMaintenanceUnverifiableReason): Nothing =
		throw PressureSourceEraseAbort(ErasePressureSourceResult.Unverifiable(reason))

	private fun retryable(reason: PressureSourceEraseRetryableReason): Nothing =
		throw PressureSourceEraseAbort(ErasePressureSourceResult.RetryableFailure(reason))

	private class PressureSourceEraseAbort(
		val result: ErasePressureSourceResult,
	) : RuntimeException(null, null, false, false)
}

private data class PressureImportedEraseTotals(
	val entryCount: Int = 0,
	val revisionCount: Int = 0,
	val runCount: Int = 0,
	val windowCount: Int = 0,
	val latestDurableTimeMs: Long = 0L,
) {
	fun plus(lineage: AuthenticatedImportedPressureLineage): PressureImportedEraseTotals =
		PressureImportedEraseTotals(
			entryCount = Math.addExact(entryCount, 1),
			revisionCount = Math.addExact(revisionCount, lineage.revisions.size),
			runCount = Math.addExact(
				runCount,
				lineage.revisions.fold(0) { count, revision ->
					Math.addExact(count, revision.entry.runs.size)
				},
			),
			windowCount = Math.addExact(
				windowCount,
				lineage.revisions.fold(0) { count, revision ->
					revision.entry.runs.fold(count) { windows, run ->
						Math.addExact(windows, run.windows.size)
					}
				},
			),
			latestDurableTimeMs = maxOf(
				latestDurableTimeMs,
				lineage.revisions.maxOf { it.header.receivedAtMs },
				lineage.receipts.maxOf { it.receivedAtMs },
			),
		)

	fun plus(
		receipt: ImportedPressureRetentionReceiptEntity,
	): PressureImportedEraseTotals = PressureImportedEraseTotals(
		entryCount = Math.addExact(entryCount, 1),
		revisionCount = Math.addExact(revisionCount, receipt.revisionCount),
		runCount = Math.addExact(runCount, receipt.runRowCount),
		windowCount = Math.addExact(windowCount, receipt.windowRowCount),
		latestDurableTimeMs = maxOf(latestDurableTimeMs, receipt.retainedAtMs),
	)

	fun plus(other: PressureImportedEraseTotals): PressureImportedEraseTotals =
		PressureImportedEraseTotals(
			entryCount = Math.addExact(entryCount, other.entryCount),
			revisionCount = Math.addExact(revisionCount, other.revisionCount),
			runCount = Math.addExact(runCount, other.runCount),
			windowCount = Math.addExact(windowCount, other.windowCount),
			latestDurableTimeMs = maxOf(latestDurableTimeMs, other.latestDurableTimeMs),
		)

	fun within(limits: PressureSourceEraseLimits): Boolean =
		entryCount <= limits.maximumImportedEntries &&
			revisionCount <= limits.maximumImportedRevisions &&
			runCount <= limits.maximumImportedRuns &&
			windowCount <= limits.maximumImportedWindows
}

internal data class PressureSourceEraseLimits(
	val maximumImportedEntries: Int = 256,
	val maximumImportedRevisions: Int = 65_536,
	val maximumImportedRuns: Int = 262_144,
	val maximumImportedWindows: Int = 262_144,
) {
	init {
		listOf(
			maximumImportedEntries,
			maximumImportedRevisions,
			maximumImportedRuns,
			maximumImportedWindows,
		).forEach { require(it > 0) }
	}
}

private fun PressureSourceEraseLocalFailure.toPublicResult(): ErasePressureSourceResult =
	when (reason) {
		PressureSourceEraseLocalFailureReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED ->
			ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			)
		PressureSourceEraseLocalFailureReason.POLICY_AUTHORITY_UNAVAILABLE ->
			ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.POLICY_AUTHORITY_UNAVAILABLE,
			)
		PressureSourceEraseLocalFailureReason.CAPTURE_CONSENT_STILL_ELIGIBLE ->
			ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE,
			)
		PressureSourceEraseLocalFailureReason.DIRECT_DEMAND_NOT_QUIESCED ->
			ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.DIRECT_DEMAND_NOT_QUIESCED,
			)
		PressureSourceEraseLocalFailureReason.CAPTURE_PROVIDER_NOT_QUIESCED ->
			ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
			)
		PressureSourceEraseLocalFailureReason.DESTINATION_OWNER_CHANGED ->
			ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.DESTINATION_OWNER_CHANGED,
			)
		PressureSourceEraseLocalFailureReason.DELETION_FENCE_CONFLICT ->
			ErasePressureSourceResult.Blocked(
				PressureSourceEraseBlockedReason.DELETION_FENCE_CONFLICT,
			)
		PressureSourceEraseLocalFailureReason.STALE_REQUEST ->
			ErasePressureSourceResult.Blocked(PressureSourceEraseBlockedReason.STALE_REQUEST)
		PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED ->
			ErasePressureSourceResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE ->
			ErasePressureSourceResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
	}

internal enum class PressureSourceEraseCheckpoint {
	TRANSACTION_STARTED,
	SOURCE_FENCE_INSERTED,
	IMPORTED_DELETION_AUTHORITY_INSERTED,
	LOCAL_PAYLOAD_REMOVED,
	IMPORTED_PAYLOAD_REMOVED,
	SOURCE_FENCE_VERIFIED,
}

private const val SQLITE_BIND_BATCH = 400
private const val PREFLIGHT_DEMAND_LIMIT = 256
