package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalFailure
import com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalFailureReason
import com.adsamcik.tracker.shared.base.database.auditPressureSourceEraseLocalAuthorityInTransaction
import com.adsamcik.tracker.shared.base.database.deletePressureSourceEraseLocalPayloadInTransaction
import com.adsamcik.tracker.shared.base.database.installPressureSourceEraseLocalFencesInTransaction
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseWitnessEntity
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
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierVerification
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

			val barrierToken = if (requiresHardwareAuthority) establishBarrier(request) else null
			database.withTransaction { eraseInTransaction(request, barrierToken) }
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

	private suspend fun establishBarrier(
		request: ErasePressureSourceRequest,
	): PressureSourceEraseBarrierToken {
		val token = when (val result = barrier.establish(request.expectedCollectedDataEpoch)) {
			is PressureSourceEraseBarrierResult.NoLocalProvider -> result.token
			is PressureSourceEraseBarrierResult.Established -> result.token
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
		if (token.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		}
		return token
	}

	private suspend fun verifyBarrier(token: PressureSourceEraseBarrierToken) {
		when (val result = barrier.verifySettled(token)) {
			PressureSourceEraseBarrierVerification.Verified -> Unit
			is PressureSourceEraseBarrierVerification.Blocked -> blocked(
				when (result.reason) {
					PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE ->
						PressureSourceEraseBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED
					PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE ->
						PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED
				},
			)
			is PressureSourceEraseBarrierVerification.Retryable -> retryable(
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
		barrierToken: PressureSourceEraseBarrierToken?,
	): ErasePressureSourceResult {
		checkpoint(PressureSourceEraseCheckpoint.TRANSACTION_STARTED)
		barrierToken?.let { verifyBarrier(it) }
		val state = database.sourceEvidenceStateDao().get()
			?: unverifiable(ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			state.revision != request.expectedSourceEvidenceRevision ||
			state.deletedSourceEventHighWaterOrdinal !=
			request.expectedDeletedSourceEventHighWaterOrdinal
		) blocked(PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		val dao = database.importedPressureDao()
		when (dao.liveMaintenanceFootprint().validateGlobal(
			maximumRevisions = limits.maximumImportedRevisions.toLong(),
			maximumReceipts = limits.maximumImportedEntries.toLong() *
				com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
					.MAX_RECEIPTS_PER_ENTRY,
			maximumRuns = limits.maximumImportedRuns.toLong(),
			maximumWindows = limits.maximumImportedWindows.toLong(),
		)) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			null -> Unit
		}
		when (dao.retainedMaintenanceFootprint().validateGlobal(
			maximumReceipts = limits.maximumImportedEntries.toLong(),
			maximumMarkers = limits.maximumIdentityFences.toLong(),
		)) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			null -> Unit
		}
		when (dao.privacyMaintenanceFootprint().validateGlobal(
			maximumIdentityFences = limits.maximumIdentityFences.toLong(),
			maximumEntryDeletions = limits.maximumEntryDeletions.toLong(),
			maximumRunDeletions = limits.maximumRunDeletions.toLong(),
			maximumWitnesses = limits.maximumWitnesses.toLong(),
		)) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			null -> Unit
		}
		val previousErase = dao.sourceErase()
		val previousAuthority = previousErase?.let {
			authenticateSourceEraseAuthority(it, state)
		}

		val local = database.auditPressureSourceEraseLocalAuthorityInTransaction(
			expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
			expectedDeletedSourceEventHighWaterOrdinal =
				request.expectedDeletedSourceEventHighWaterOrdinal,
			expectedCurrentPolicyRevision = request.expectedCurrentPolicyRevision,
			expectedRevokedConsentEpoch = request.expectedRevokedConsentEpoch,
			erasedAtMs = request.erasedAtMs,
			requireRevokedAuthority = barrierToken != null,
		)
		if (local.requiresHardwareAuthority && barrierToken == null) {
			retryable(PressureSourceEraseRetryableReason.CALLBACK_DRAIN_UNAVAILABLE)
		}
		val byteBudget = ImportedPressureMaintenanceByteBudget()
		val live = prepareLiveImportedAuthority(state, request.erasedAtMs, byteBudget)
		val retained = prepareRetainedImportedAuthority(state, request.erasedAtMs, byteBudget)
		val totals = try {
			live.plus(retained)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
		if (!totals.within(limits)) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		if (previousErase != null && !local.requiresHardwareAuthority && totals.entryCount == 0) {
			return ErasePressureSourceResult.AlreadyErased
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

		database.installPressureSourceEraseLocalFencesInTransaction(
			audit = local,
			expectedCollectedDataEpoch = state.collectedDataEpoch,
			erasedAtMs = request.erasedAtMs,
		)
		val authority = collectSourceEraseAuthority(
			local,
			previousAuthority?.legacySamples.orEmpty(),
		)
		val durableBarrierToken = barrierToken ?: previousErase?.takeIf {
			it.legacyWriteFenceGeneration > 0L
		}?.let {
			PressureSourceEraseBarrierToken(
				collectedDataEpoch = it.collectedDataEpoch,
				providerRegistrationGeneration = it.providerRegistrationGeneration,
				legacyWriteFenceGeneration = it.legacyWriteFenceGeneration,
			)
		}
		val sourceErase = ImportedPressureSourceEraseEntity.create(
			collectedDataEpoch = state.collectedDataEpoch,
			sourceEvidenceRevision = nextRevision,
			erasedAtMs = request.erasedAtMs,
			providerRegistrationGeneration =
				durableBarrierToken?.providerRegistrationGeneration,
			legacyWriteFenceGeneration =
				durableBarrierToken?.legacyWriteFenceGeneration ?: 0L,
			localFactRevisionCount = local.factRevisionCount,
			localWalEventCount = local.walEventCount,
			legacySampleCount = authority.legacySamples.size,
			legacySampleWitnesses = authority.legacySamples,
			importedEntryCount = totals.entryCount,
			importedRevisionCount = totals.revisionCount,
			importedRunCount = totals.runCount,
			importedWindowCount = totals.windowCount,
			localFences = authority.localFences,
			entryDeletions = authority.entryDeletions,
			runDeletions = authority.runDeletions,
			identityFences = authority.identityFences,
		)
		if (previousErase == null) {
			dao.insertSourceErase(sourceErase)
		} else {
			dao.deleteSourceEraseWitnesses()
			if (dao.replaceSourceErase(previousErase, sourceErase) != 1) {
				retryable(PressureSourceEraseRetryableReason.CONCURRENT_STATE_CHANGE)
			}
		}
		authority.witnesses().chunked(SQLITE_BIND_BATCH).forEach {
			dao.insertSourceEraseWitnesses(it)
		}
		checkpoint(PressureSourceEraseCheckpoint.SOURCE_FENCE_INSERTED)

		database.deletePressureSourceEraseLocalPayloadInTransaction(local)
		checkpoint(PressureSourceEraseCheckpoint.LOCAL_PAYLOAD_REMOVED)
		deleteLiveImportedPayload(state)
		deleteRetainedImportedPayload(state)
		checkpoint(PressureSourceEraseCheckpoint.IMPORTED_PAYLOAD_REMOVED)
		if (dao.retentionCandidatePage(null, 1).isNotEmpty() ||
			dao.retentionReceiptPage(null, 1).isNotEmpty()
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		val remainingLive = dao.liveMaintenanceFootprint()
		val remainingRetained = dao.retainedMaintenanceFootprint()
		if (remainingLive.headerCount != 0L || remainingLive.receiptCount != 0L ||
			remainingLive.runCount != 0L || remainingLive.windowCount != 0L ||
			remainingRetained.receiptCount != 0L || remainingRetained.markerCount != 0L
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		if (database.sourceEvidenceStateDao().incrementRevision(request.erasedAtMs) != 1) {
			throw IllegalStateException("Unable to publish Pressure source erase")
		}
		val published = database.sourceEvidenceStateDao().get()
			?: unverifiable(ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (published.collectedDataEpoch != state.collectedDataEpoch ||
			published.revision != nextRevision || published.updatedAtMs != request.erasedAtMs
		) blocked(PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		authenticateSourceEraseAuthority(sourceErase, published)
		checkpoint(PressureSourceEraseCheckpoint.SOURCE_FENCE_VERIFIED)
		return ErasePressureSourceResult.Erased(
			localFactRevisionCount = local.factRevisionCount,
			localWalEventCount = local.walEventCount,
			legacySampleCount = local.legacySampleCount,
			importedEntryCount = totals.entryCount,
			importedRevisionCount = totals.revisionCount,
			importedRunCount = totals.runCount,
			importedWindowCount = totals.windowCount,
			fencedLocalRunCount = local.scopes.size,
		)
	}

	private suspend fun prepareLiveImportedAuthority(
		state: SourceEvidenceState,
		erasedAtMs: Long,
		byteBudget: ImportedPressureMaintenanceByteBudget,
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
			val lineage = authenticateLineage(
				candidate.identity,
				state.collectedDataEpoch,
				byteBudget,
			)
			if (dao.retentionReceipt(candidate.identity) != null ||
				dao.entryDeletion(candidate.identity) != null
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			authenticateLiveIdentityOwnership(lineage)
			val runIds = lineage.revisions.flatMap { it.entry.runs }.map { it.identity.value }.distinct()
			val deletions = runIds.chunked(SQLITE_BIND_BATCH).flatMap { dao.deletionGenerations(it) }
			if (deletions.isNotEmpty()) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			}
			val identityFences = lineage.identityFences(
				state.collectedDataEpoch,
				erasedAtMs,
				ImportedPressureIdentityFenceEntity.REASON_SOURCE_ERASE,
			)
			dao.insertOrAuthenticateIdentityFences(identityFences)
			val runDeletions = runIds.map { runIdentity ->
				ImportedPressureDeletionGenerationEntity.create(
					runIdentity,
					state.collectedDataEpoch,
					1L,
					erasedAtMs,
				).also { dao.insertDeletionGeneration(it) }
			}
			dao.insertEntryDeletion(
				ImportedPressureEntryDeletionEntity.create(
					entryIdentity = candidate.identity,
					collectedDataEpoch = state.collectedDataEpoch,
					deletedImportRevision = requireNotNull(lineage.latest).header.importRevision,
					deletedAtMs = erasedAtMs,
					runDeletions = runDeletions,
					identityFences = identityFences,
				),
			)
			checkpoint(PressureSourceEraseCheckpoint.IMPORTED_DELETION_AUTHORITY_INSERTED)
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
			val permanent = dao.identityFences(batch, limit)
			if (entries.size >= limit || runs.size >= limit || windows.size >= limit ||
				retained.size >= limit || permanent.size >= limit
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			if (retained.isNotEmpty() || permanent.isNotEmpty() ||
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

	private suspend fun prepareRetainedImportedAuthority(
		state: SourceEvidenceState,
		erasedAtMs: Long,
		byteBudget: ImportedPressureMaintenanceByteBudget,
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
			try {
				byteBudget.consume(
					dao.retainedFootprint(receipt.entryIdentity),
					receipt.protectedIdentityCount,
				)
			} catch (_: ImportedPressureMaintenanceFootprintFailure.DependencyOverflow) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			} catch (_: ImportedPressureMaintenanceFootprintFailure.ValueOverflow) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
			}
			when (database.authenticateImportedPressureRetention(state, receipt)) {
				ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
				ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
				null -> Unit
			}
			val identityFences = dao.identityFencesForEntry(
				receipt.entryIdentity,
				receipt.protectedIdentityCount + 1,
			)
			if (identityFences.size != receipt.protectedIdentityCount ||
				ImportedPressureIdentityFenceEntity.checksumSet(identityFences) !=
				receipt.identityFenceSetChecksum
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			val runIds = identityFences.filter {
				it.identityKind == ImportedPressureIdentityFenceEntity.RUN
			}.map { it.protectedIdentity }
			val existingRunDeletions = runIds.chunked(SQLITE_BIND_BATCH)
				.flatMap { dao.deletionGenerations(it) }
				.associateBy { it.runIdentity }
			if (existingRunDeletions.size > runIds.size ||
				existingRunDeletions.values.any { it.generation != 1L }
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			val runDeletions = runIds.map { runIdentity ->
				existingRunDeletions[runIdentity] ?: ImportedPressureDeletionGenerationEntity.create(
					runIdentity,
					state.collectedDataEpoch,
					1L,
					erasedAtMs,
				).also { dao.insertDeletionGeneration(it) }
			}
			if (dao.entryDeletion(receipt.entryIdentity) != null) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			}
			dao.insertEntryDeletion(
				ImportedPressureEntryDeletionEntity.create(
					entryIdentity = receipt.entryIdentity,
					collectedDataEpoch = state.collectedDataEpoch,
					deletedImportRevision = receipt.latestImportRevision,
					deletedAtMs = erasedAtMs,
					runDeletions = runDeletions,
					identityFences = identityFences,
				),
			)
			checkpoint(PressureSourceEraseCheckpoint.IMPORTED_DELETION_AUTHORITY_INSERTED)
			totals = totals.plus(receipt)
		}
		return totals
	}

	private suspend fun deleteLiveImportedPayload(
		state: SourceEvidenceState,
	) {
		val dao = database.importedPressureDao()
		var cursor: String? = null
		while (true) {
			val candidate = dao.retentionCandidatePage(cursor, 1).singleOrNull() ?: break
			cursor = candidate.identity
			val deletion = dao.entryDeletion(candidate.identity)
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE,
				)
			if (deletion.collectedDataEpoch != state.collectedDataEpoch ||
				dao.deleteEntryRevisionLineage(candidate.identity) !=
				deletion.deletedImportRevision.toInt()
			) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			}
		}
	}

	private suspend fun deleteRetainedImportedPayload(
		state: SourceEvidenceState,
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
			val deletion = dao.entryDeletion(receipt.entryIdentity)
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE,
				)
			if (deletion.collectedDataEpoch != state.collectedDataEpoch ||
				deletion.deletedImportRevision != receipt.latestImportRevision
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			if (dao.deleteRetainedIdentities(receipt.entryIdentity) != receipt.protectedIdentityCount ||
				dao.deleteRetentionReceipt(receipt.entryIdentity) != 1
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
	}

	private suspend fun collectSourceEraseAuthority(
		localAudit: com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalAudit,
		previousLegacySamples: List<ImportedPressureSourceEraseWitnessEntity>,
	): PressureSourceEraseAuthoritySnapshot {
		val dao = database.importedPressureDao()
		val localFences = mutableListOf<com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity>()
		var localCursor: String? = null
		while (true) {
			val page = database.pressureFactRevisionDao().sourceEraseFencePage(
				localCursor,
				AUTHORITY_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			if (localCursor?.let { page.first().scopeIdentityDigest <= it } == true) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			localFences += page
			if (localFences.size > limits.maximumLocalFences) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			localCursor = page.last().scopeIdentityDigest
			if (page.size < AUTHORITY_PAGE_SIZE) break
		}
		val entryDeletions = mutableListOf<ImportedPressureEntryDeletionEntity>()
		var entryCursor: String? = null
		while (true) {
			val page = dao.entryDeletionPage(entryCursor, AUTHORITY_PAGE_SIZE)
			if (page.isEmpty()) break
			if (entryCursor?.let { page.first().entryIdentity <= it } == true) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			entryDeletions += page
			if (entryDeletions.size > limits.maximumEntryDeletions) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			entryCursor = page.last().entryIdentity
			if (page.size < AUTHORITY_PAGE_SIZE) break
		}
		val runDeletions = mutableListOf<ImportedPressureDeletionGenerationEntity>()
		var runCursor: String? = null
		while (true) {
			val page = dao.deletionGenerationPage(runCursor, AUTHORITY_PAGE_SIZE)
			if (page.isEmpty()) break
			if (runCursor?.let { page.first().runIdentity <= it } == true) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			runDeletions += page
			if (runDeletions.size > limits.maximumRunDeletions) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			runCursor = page.last().runIdentity
			if (page.size < AUTHORITY_PAGE_SIZE) break
		}
		val identityFences = mutableListOf<ImportedPressureIdentityFenceEntity>()
		var identityCursor: String? = null
		while (true) {
			val page = dao.identityFencePage(identityCursor, AUTHORITY_PAGE_SIZE)
			if (page.isEmpty()) break
			if (identityCursor?.let { page.first().protectedIdentity <= it } == true) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			identityFences += page
			if (identityFences.size > limits.maximumIdentityFences) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			identityCursor = page.last().protectedIdentity
			if (page.size < AUTHORITY_PAGE_SIZE) break
		}
		if (database.pressureFactRevisionDao().sourceEraseFenceCount() != localFences.size.toLong() ||
			dao.entryDeletionCount() != entryDeletions.size.toLong() ||
			dao.deletionGenerationCount() != runDeletions.size.toLong() ||
			dao.identityFenceCount() != identityFences.size.toLong()
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		entryDeletions.forEach { authenticateEntryDeletionAuthority(it) }
		val legacyByIdentity = linkedMapOf<String, ImportedPressureSourceEraseWitnessEntity>()
		(previousLegacySamples + localAudit.legacySampleWitnesses.map {
				ImportedPressureSourceEraseWitnessEntity.legacy(
					it.identity,
					it.authorityChecksum,
				)
			}
			).forEach { witness ->
				val previous = legacyByIdentity.putIfAbsent(witness.witnessIdentity, witness)
				if (previous != null && previous != witness) {
					unverifiable(
						ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
			}
		val legacySamples = legacyByIdentity.values.sortedBy { it.witnessIdentity }
		if (legacySamples.size > limits.maximumLegacyWitnesses) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		return PressureSourceEraseAuthoritySnapshot(
			localFences,
			legacySamples,
			entryDeletions,
			runDeletions,
			identityFences,
		)
	}

	private suspend fun authenticateSourceEraseAuthority(
		marker: ImportedPressureSourceEraseEntity,
		state: SourceEvidenceState,
	): AuthenticatedPressureSourceEraseAuthority {
		if (database.importedPressureDao().sourceErase() != marker) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		if (marker.collectedDataEpoch != state.collectedDataEpoch ||
			marker.sourceEvidenceRevision > state.revision ||
			marker.erasedAtMs > state.updatedAtMs
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		if (marker.legacyWriteFenceGeneration > 0L) {
			verifyBarrier(
				PressureSourceEraseBarrierToken(
					collectedDataEpoch = marker.collectedDataEpoch,
					providerRegistrationGeneration = marker.providerRegistrationGeneration,
					legacyWriteFenceGeneration = marker.legacyWriteFenceGeneration,
				),
			)
		}
		val expectedWitnessCount = try {
			Math.addExact(
				Math.addExact(marker.fencedLocalRunCount, marker.entryDeletionCount),
				Math.addExact(
					marker.runDeletionCount,
					Math.addExact(marker.identityFenceCount, marker.legacySampleCount),
				),
			)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
		if (expectedWitnessCount > limits.maximumWitnesses ||
			database.importedPressureDao().sourceEraseWitnessCount() != expectedWitnessCount.toLong()
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		val witnesses = mutableListOf<ImportedPressureSourceEraseWitnessEntity>()
		var afterKind: String? = null
		var afterIdentity: String? = null
		while (true) {
			val page = database.importedPressureDao().sourceEraseWitnessPage(
				afterKind,
				afterIdentity,
				AUTHORITY_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			val previous = afterKind?.let { it to requireNotNull(afterIdentity) }
			if (previous != null && (
					page.first().witnessKind < previous.first ||
						page.first().witnessKind == previous.first &&
						page.first().witnessIdentity <= previous.second
					)
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			witnesses += page
			if (witnesses.size > expectedWitnessCount) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			}
			afterKind = page.last().witnessKind
			afterIdentity = page.last().witnessIdentity
			if (page.size < AUTHORITY_PAGE_SIZE) break
		}
		if (witnesses.size != expectedWitnessCount) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		val localWitnesses = witnesses.filter {
			it.witnessKind == ImportedPressureSourceEraseWitnessEntity.LOCAL_SCOPE
		}
		val entryWitnesses = witnesses.filter {
			it.witnessKind == ImportedPressureSourceEraseWitnessEntity.ENTRY_DELETION
		}
		val runWitnesses = witnesses.filter {
			it.witnessKind == ImportedPressureSourceEraseWitnessEntity.RUN_DELETION
		}
		val identityWitnesses = witnesses.filter {
			it.witnessKind == ImportedPressureSourceEraseWitnessEntity.IDENTITY_FENCE
		}
		val legacyWitnesses = witnesses.filter {
			it.witnessKind == ImportedPressureSourceEraseWitnessEntity.LEGACY_SAMPLE
		}
		if (localWitnesses.size != marker.fencedLocalRunCount ||
			legacyWitnesses.size != marker.legacySampleCount ||
			entryWitnesses.size != marker.entryDeletionCount ||
			runWitnesses.size != marker.runDeletionCount ||
			identityWitnesses.size != marker.identityFenceCount
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		val localFences = localWitnesses.chunked(SQLITE_BIND_BATCH).flatMap { batch ->
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
					.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = batch.map { it.witnessIdentity },
			)
		}
		val entryDeletions = entryWitnesses.chunked(SQLITE_BIND_BATCH).flatMap {
			database.importedPressureDao().entryDeletions(it.map { witness -> witness.witnessIdentity })
		}
		val runDeletions = runWitnesses.chunked(SQLITE_BIND_BATCH).flatMap {
			database.importedPressureDao().deletionGenerations(
				it.map { witness -> witness.witnessIdentity },
			)
		}
		val identityFences = identityWitnesses.chunked(SQLITE_BIND_BATCH).flatMap {
			database.importedPressureDao().identityFences(
				it.map { witness -> witness.witnessIdentity },
				it.size + 1,
			)
		}
		verifyWitnessChecksums(localWitnesses, localFences.associate {
			it.scopeIdentityDigest to it.effectChecksum
		})
		verifyWitnessChecksums(entryWitnesses, entryDeletions.associate {
			it.entryIdentity to it.effectChecksum
		})
		verifyWitnessChecksums(runWitnesses, runDeletions.associate {
			it.runIdentity to it.effectChecksum
		})
		verifyWitnessChecksums(identityWitnesses, identityFences.associate {
			it.protectedIdentity to it.effectChecksum
		})
		if (ImportedPressureSourceEraseEntity.checksumLocalFences(localFences) !=
			marker.localScopeSetChecksum ||
			ImportedPressureSourceEraseEntity.checksumLegacySamples(legacyWitnesses) !=
			marker.legacySampleSetChecksum ||
			ImportedPressureSourceEraseEntity.checksumEntryDeletions(entryDeletions) !=
			marker.entryDeletionSetChecksum ||
			ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) !=
			marker.runDeletionSetChecksum ||
			ImportedPressureIdentityFenceEntity.checksumSet(identityFences) !=
			marker.identityFenceSetChecksum
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		entryDeletions.forEach { authenticateEntryDeletionAuthority(it) }
		return AuthenticatedPressureSourceEraseAuthority(legacyWitnesses)
	}

	private fun verifyWitnessChecksums(
		witnesses: List<ImportedPressureSourceEraseWitnessEntity>,
		actual: Map<String, String>,
	) {
		if (actual.size != witnesses.size || witnesses.any {
			actual[it.witnessIdentity] != it.authorityChecksum
		}) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
	}

	private suspend fun authenticateEntryDeletionAuthority(
		deletion: ImportedPressureEntryDeletionEntity,
	) {
		val dao = database.importedPressureDao()
		val identityFences = dao.identityFencesForEntry(
			deletion.entryIdentity,
			deletion.identityFenceCount + 1,
		)
		val runFences = identityFences.filter {
			it.identityKind == ImportedPressureIdentityFenceEntity.RUN
		}
		val runDeletions = runFences.chunked(SQLITE_BIND_BATCH).flatMap {
			dao.deletionGenerations(it.map { fence -> fence.protectedIdentity })
		}
		if (identityFences.size != deletion.identityFenceCount ||
			ImportedPressureIdentityFenceEntity.checksumSet(identityFences) !=
			deletion.identityFenceSetChecksum ||
			runDeletions.size != deletion.runDeletionCount ||
			ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) !=
			deletion.runDeletionSetChecksum
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}

	private suspend fun authenticateLineage(
		identity: String,
		expectedCollectedDataEpoch: Long,
		byteBudget: ImportedPressureMaintenanceByteBudget,
	): AuthenticatedImportedPressureLineage {
		val dao = database.importedPressureDao()
		return try {
			byteBudget.consume(dao.lineageFootprint(identity))
			ImportedPressureLineageAuthenticator.authenticate(
				identity,
				expectedCollectedDataEpoch,
				dao.entryRevisionsForAdmission(identity),
				dao.receiptsForAdmission(identity),
				dao.allRunsForAdmission(identity),
				dao.allWindowsForAdmission(identity),
			)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.DependencyOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.ValueOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
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

private data class PressureSourceEraseAuthoritySnapshot(
	val localFences: List<com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity>,
	val legacySamples: List<ImportedPressureSourceEraseWitnessEntity>,
	val entryDeletions: List<ImportedPressureEntryDeletionEntity>,
	val runDeletions: List<ImportedPressureDeletionGenerationEntity>,
	val identityFences: List<ImportedPressureIdentityFenceEntity>,
) {
	fun witnesses(): List<ImportedPressureSourceEraseWitnessEntity> = buildList {
		addAll(localFences.map { ImportedPressureSourceEraseWitnessEntity.local(it) })
		addAll(legacySamples)
		addAll(entryDeletions.map { ImportedPressureSourceEraseWitnessEntity.entry(it) })
		addAll(runDeletions.map { ImportedPressureSourceEraseWitnessEntity.run(it) })
		addAll(identityFences.map { ImportedPressureSourceEraseWitnessEntity.identity(it) })
	}.sortedWith(
		compareBy(ImportedPressureSourceEraseWitnessEntity::witnessKind)
			.thenBy(ImportedPressureSourceEraseWitnessEntity::witnessIdentity),
	)
}

private data class AuthenticatedPressureSourceEraseAuthority(
	val legacySamples: List<ImportedPressureSourceEraseWitnessEntity>,
)

internal data class PressureSourceEraseLimits(
	val maximumImportedEntries: Int = 256,
	val maximumImportedRevisions: Int = 65_536,
	val maximumImportedRuns: Int = 262_144,
	val maximumImportedWindows: Int = 262_144,
	val maximumLocalFences: Int = 65_536,
	val maximumEntryDeletions: Int = 65_536,
	val maximumRunDeletions: Int = 262_144,
	val maximumIdentityFences: Int = 524_288,
	val maximumLegacyWitnesses: Int = 65_536,
	val maximumWitnesses: Int = 983_040,
) {
	init {
		listOf(
			maximumImportedEntries,
			maximumImportedRevisions,
			maximumImportedRuns,
			maximumImportedWindows,
			maximumLocalFences,
			maximumEntryDeletions,
			maximumRunDeletions,
			maximumIdentityFences,
			maximumLegacyWitnesses,
			maximumWitnesses,
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
private const val AUTHORITY_PAGE_SIZE = 256
