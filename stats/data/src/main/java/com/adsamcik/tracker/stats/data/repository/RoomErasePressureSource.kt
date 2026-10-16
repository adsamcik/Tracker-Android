package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalAudit
import com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalFailure
import com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalFailureReason
import com.adsamcik.tracker.shared.base.database.PressureSourceEraseWriterFence
import com.adsamcik.tracker.shared.base.database.auditPressureSourceEraseLocalAuthorityInTransaction
import com.adsamcik.tracker.shared.base.database.deletePressureSourceEraseLocalPayloadInTransaction
import com.adsamcik.tracker.shared.base.database.installPressureSourceEraseLocalFencesInTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
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
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseFenceOwner
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
// One erase owner keeps its source barrier, deletion transaction, witnesses and publication together.
@Suppress("LargeClass", "TooManyFunctions")
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
			val preflight = preflightErase(request)

			val barrierToken = if (preflight.isFirstErase) {
				establishBarrier(request)
			} else {
				preflight.existingBarrierToken
			}
			database.withTransaction {
				eraseInTransaction(
					request,
					barrierToken,
					preflight.requiresRevokedAuthority,
				)
			}
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

	private suspend fun preflightErase(
		request: ErasePressureSourceRequest,
	): PressureErasePreflight = database.withTransaction {
		requestedSourceState(request)
		val previousErase = database.importedPressureDao().sourceErase()
		val existingBarrierToken = previousErase?.toBarrierToken()
		val requiresHardwareAuthority =
			database.pressureSourceEraseRequiresHardwareAuthority(
				existingBarrierToken?.toWriterFence(),
			).also { required ->
			if (required) {
				preflightLocalPolicy(request)
			}
		}
		PressureErasePreflight(
			isFirstErase = previousErase == null,
			existingBarrierToken = existingBarrierToken,
			requiresRevokedAuthority = requiresHardwareAuthority,
		)
	}

	private suspend fun requestedSourceState(request: ErasePressureSourceRequest): SourceEvidenceState {
		val state = database.sourceEvidenceStateDao().get()
			?: unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
			)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			state.revision != request.expectedSourceEvidenceRevision ||
			state.deletedSourceEventHighWaterOrdinal !=
			request.expectedDeletedSourceEventHighWaterOrdinal
		) {
			blocked(PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		}
		return state
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
		) {
			blocked(PressureSourceEraseBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
		}
		requireRevokedPolicyConsent(policy, consent)
		val demands = database.sourceBrokerDao().activeDemandsBounded(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			PREFLIGHT_DEMAND_LIMIT + 1,
		)
		if (demands.size > PREFLIGHT_DEMAND_LIMIT) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		if (demands.any {
			it.purpose == SourceBrokerPurpose.SESSION_CAPTURE || it.persistenceEligible
		}) {
			blocked(PressureSourceEraseBlockedReason.DIRECT_DEMAND_NOT_QUIESCED)
		}
		if (request.erasedAtMs < maxOf(
				authority.updatedAtMs,
				policy.effectiveWallTimeMs,
				consent.effectiveWallTimeMs,
			)
		) {
			blocked(PressureSourceEraseBlockedReason.STALE_REQUEST)
		}
	}

	private fun requireRevokedPolicyConsent(
		policy: com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity,
		consent: com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity,
	) {
		if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
			consent.eligible || consent.persistenceEligible
		) {
			blocked(PressureSourceEraseBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
		}
		if (consent.policyRevision != policy.policyRevision ||
			consent.effectiveBootId != policy.effectiveBootId ||
			consent.effectiveElapsedRealtimeNanos != policy.effectiveElapsedRealtimeNanos ||
			consent.effectiveWallTimeMs != policy.effectiveWallTimeMs
		) {
			blocked(PressureSourceEraseBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
		}
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

	private suspend fun eraseInTransaction(
		request: ErasePressureSourceRequest,
		barrierToken: PressureSourceEraseBarrierToken?,
		requireRevokedAuthority: Boolean,
	): ErasePressureSourceResult {
		checkpoint(PressureSourceEraseCheckpoint.TRANSACTION_STARTED)
		val verifiedBarrierToken = barrierToken ?: unverifiable(
			ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		verifyBarrier(verifiedBarrierToken)
		val state = requestedSourceState(request)
		val writerFence = verifiedBarrierToken.toWriterFence()
		val requiresRevokedAuthorityNow =
			database.pressureSourceEraseRequiresHardwareAuthority(writerFence)
		if (requiresRevokedAuthorityNow && !requireRevokedAuthority) {
			preflightLocalPolicy(request)
		}
		val effectiveRequireRevokedAuthority =
			requireRevokedAuthority || requiresRevokedAuthorityNow
		val dao = database.importedPressureDao()
		requireEraseFootprints(dao)
		val previousErase = dao.sourceErase()
		val previousAuthority = authenticatePreviousPressureErase(previousErase, state, verifiedBarrierToken)

		val local = database.auditPressureSourceEraseLocalAuthorityInTransaction(
			expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
			expectedDeletedSourceEventHighWaterOrdinal =
				request.expectedDeletedSourceEventHighWaterOrdinal,
			expectedCurrentPolicyRevision = request.expectedCurrentPolicyRevision,
			expectedRevokedConsentEpoch = request.expectedRevokedConsentEpoch,
			erasedAtMs = request.erasedAtMs,
			verifiedWriterFence = writerFence,
			requireRevokedAuthority = effectiveRequireRevokedAuthority,
		)
		val totals = prepareImportedAuthority(state, request.erasedAtMs)
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
				totals.latestDurableTimeMs,
			)
		) {
			blocked(PressureSourceEraseBlockedReason.STALE_REQUEST)
		}

		return dao.commitSourceErase(request, state, nextRevision, verifiedBarrierToken,
			local, totals, previousErase, previousAuthority)
	}

	private suspend fun authenticatePreviousPressureErase(
		previousErase: ImportedPressureSourceEraseEntity?, state: SourceEvidenceState,
		verifiedBarrierToken: PressureSourceEraseBarrierToken,
	): AuthenticatedPressureSourceEraseAuthority? {
		val previousAuthority = previousErase?.let {
			val authority = authenticateSourceEraseAuthority(it, state)
			collectLocalSourceEraseFences()
			collectPermanentImportedAuthority()
			authority
		}
		if (previousErase != null && previousErase.toBarrierToken() != verifiedBarrierToken) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return previousAuthority
	}

	// Commit with the already audited inputs; do not reread mutable authority or add a carrier.
	@Suppress("LongParameterList")
	private suspend fun ImportedPressureDao.commitSourceErase(
		request: ErasePressureSourceRequest, state: SourceEvidenceState, nextRevision: Long,
		verifiedBarrierToken: PressureSourceEraseBarrierToken, local: PressureSourceEraseLocalAudit,
		totals: PressureImportedEraseTotals, previousErase: ImportedPressureSourceEraseEntity?,
		previousAuthority: AuthenticatedPressureSourceEraseAuthority?,
	): ErasePressureSourceResult {
		val dao = this
		database.installPressureSourceEraseLocalFencesInTransaction(
			audit = local,
			expectedCollectedDataEpoch = state.collectedDataEpoch,
			erasedAtMs = request.erasedAtMs,
		)
		val authority = collectSourceEraseAuthority(
			local,
			previousAuthority?.legacySamples.orEmpty(),
		)
		val sourceErase = ImportedPressureSourceEraseEntity.create(
			collectedDataEpoch = state.collectedDataEpoch,
			sourceEvidenceRevision = nextRevision,
			erasedAtMs = request.erasedAtMs,
			providerRegistrationGeneration =
				verifiedBarrierToken.providerRegistrationGeneration,
			legacyWriteFenceOwner = verifiedBarrierToken.legacyWriteFenceOwner.storageValue,
			legacyWriteFenceGeneration = verifiedBarrierToken.legacyWriteFenceGeneration,
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
		persistSourceEraseFence(dao, previousErase, sourceErase, authority)

		removeErasedPressurePayload(dao, local, state)
		publishSourceErase(request, state, nextRevision, sourceErase)
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

	private suspend fun prepareImportedAuthority(
		state: SourceEvidenceState, erasedAtMs: Long,
	): PressureImportedEraseTotals {
		val byteBudget = ImportedPressureMaintenanceByteBudget()
		val live = prepareLiveImportedAuthority(state, erasedAtMs, byteBudget)
		val retained = prepareRetainedImportedAuthority(state, erasedAtMs, byteBudget)
		val totals = try {
			live.plus(retained)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
		if (!totals.within(limits)) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		return totals
	}

	private suspend fun requireEraseFootprints(dao: ImportedPressureDao) {
		requireGlobalFootprint(dao.liveMaintenanceFootprint().validateGlobal(
			maximumRevisions = limits.maximumImportedRevisions.toLong(),
			maximumReceipts = limits.maximumImportedEntries.toLong() *
				com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
					.MAX_RECEIPTS_PER_ENTRY,
			maximumRuns = limits.maximumImportedRuns.toLong(),
			maximumWindows = limits.maximumImportedWindows.toLong(),
		))
		requireGlobalFootprint(dao.retainedMaintenanceFootprint().validateGlobal(
			maximumReceipts = limits.maximumImportedEntries.toLong(),
			maximumMarkers = limits.maximumIdentityFences.toLong(),
		))
		val privacyFootprint = dao.privacyMaintenanceFootprint()
		requireGlobalFootprint(privacyFootprint.validateGlobal(
			maximumIdentityFences = limits.maximumIdentityFences.toLong(),
			maximumEntryDeletions = limits.maximumEntryDeletions.toLong(),
			maximumRunDeletions = limits.maximumRunDeletions.toLong(),
			maximumWitnesses = limits.maximumWitnesses.toLong(),
		))
		val localFenceFootprint = database.pressureFactRevisionDao()
			.sourceEraseFenceGlobalFootprint()
		try {
			if (localFenceFootprint.rowCount !in 0L..limits.maximumLocalFences.toLong() ||
				localFenceFootprint.textBytes < 0L ||
				Math.addExact(
					privacyFootprint.totalTextBytes,
					localFenceFootprint.textBytes,
				) > com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
					.MAX_MAINTENANCE_TEXT_BYTES
			) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
		} catch (_: ArithmeticException) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
	}

	private fun requireGlobalFootprint(failure: ImportedPressureRetentionAuthorityFailure?) {
		when (failure) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			null -> Unit
		}
	}

	private suspend fun persistSourceEraseFence(
		dao: ImportedPressureDao,
		previousErase: ImportedPressureSourceEraseEntity?,
		sourceErase: ImportedPressureSourceEraseEntity,
		authority: PressureSourceEraseAuthoritySnapshot,
	) {
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
	}

	private suspend fun removeErasedPressurePayload(
		dao: ImportedPressureDao,
		local: PressureSourceEraseLocalAudit,
		state: SourceEvidenceState,
	) {
		database.deletePressureSourceEraseLocalPayloadInTransaction(local)
		checkpoint(PressureSourceEraseCheckpoint.LOCAL_PAYLOAD_REMOVED)
		deleteLiveImportedPayload(state)
		deleteRetainedImportedPayload(state)
		checkpoint(PressureSourceEraseCheckpoint.IMPORTED_PAYLOAD_REMOVED)
		requireErasedImportedPayload(dao)
	}

	private suspend fun requireErasedImportedPayload(dao: ImportedPressureDao) {
		if (dao.liveOwnerRowIdPage(0L, 1).isNotEmpty() ||
			dao.retainedOwnerRowIdPage(0L, 1).isNotEmpty()
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		val remainingLive = dao.liveMaintenanceFootprint()
		val remainingRetained = dao.retainedMaintenanceFootprint()
		if (remainingLive.headerCount != 0L || remainingLive.receiptCount != 0L ||
			remainingLive.runCount != 0L || remainingLive.windowCount != 0L
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		if (remainingRetained.receiptCount != 0L || remainingRetained.markerCount != 0L) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
	}

	private suspend fun publishSourceErase(
		request: ErasePressureSourceRequest,
		state: SourceEvidenceState,
		nextRevision: Long,
		sourceErase: ImportedPressureSourceEraseEntity,
	) {
		if (database.sourceEvidenceStateDao().incrementRevision(request.erasedAtMs) != 1) {
			throw IllegalStateException("Unable to publish Pressure source erase")
		}
		val published = database.sourceEvidenceStateDao().get()
			?: unverifiable(ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (!state.acceptsRetainedEpoch(published.collectedDataEpoch) ||
			published.revision != nextRevision || published.updatedAtMs != request.erasedAtMs
		) {
			blocked(PressureSourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		}
		authenticateSourceEraseAuthority(sourceErase, published)
		checkpoint(PressureSourceEraseCheckpoint.SOURCE_FENCE_VERIFIED)
	}

	private suspend fun prepareLiveImportedAuthority(
		state: SourceEvidenceState,
		erasedAtMs: Long,
		byteBudget: ImportedPressureMaintenanceByteBudget,
	): PressureImportedEraseTotals {
		val dao = database.importedPressureDao()
		var ownerRowId = 0L
		var totals = PressureImportedEraseTotals()
		while (true) {
			currentCoroutineContext().ensureActive()
			val nextOwnerRowId = dao.liveOwnerRowIdPage(ownerRowId, 1).singleOrNull() ?: break
			if (nextOwnerRowId <= ownerRowId) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			consumeFootprint(byteBudget, dao.liveOwnerFootprint(nextOwnerRowId))
			val candidate = dao.liveCandidateByOwnerRowId(nextOwnerRowId)
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			ownerRowId = nextOwnerRowId
			val lineage = authenticateLineage(
				candidate.identity,
				state.collectedDataEpoch,
			)
			if (dao.retentionReceipt(candidate.identity) != null ||
				dao.entryDeletion(candidate.identity) != null
			) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			}
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
		val expected = expectedLiveIdentityKinds(entryIdentity, lineage)
		val dao = database.importedPressureDao()
		for (batch in expected.keys.chunked(SQLITE_BIND_BATCH)) {
			authenticateLiveIdentityBatch(dao, entryIdentity, expected, batch)
		}
	}

	private fun expectedLiveIdentityKinds(
		entryIdentity: String, lineage: AuthenticatedImportedPressureLineage,
	): Map<String, String> {
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
		return expected
	}

	private suspend fun authenticateLiveIdentityBatch(
		dao: ImportedPressureDao, entryIdentity: String, expected: Map<String, String>, batch: List<String>,
	) {
		val limit = batch.size + 1
		val entries = dao.existingEntryIdentities(batch, limit)
		val runs = dao.existingRunIdentityOwners(batch, limit)
		val windows = dao.existingWindowIdentityOwners(batch, limit)
		val retained = dao.retainedIdentityOwners(batch, limit)
		val permanent = dao.identityFences(batch, limit)
		if (listOf(entries.size, runs.size, windows.size, retained.size, permanent.size).any { it >= limit }) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		if (retained.isNotEmpty() || permanent.isNotEmpty()) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
		}
		if (entries.any {
			!hasExpectedLiveIdentityOwner(it, it, ImportedPressureRetainedIdentityEntity.ENTRY,
				entryIdentity, expected)
		} || runs.any {
			!hasExpectedLiveIdentityOwner(it.identity, it.entryIdentity,
				ImportedPressureRetainedIdentityEntity.RUN_SCOPE, entryIdentity, expected)
		} || windows.any {
			!hasExpectedLiveIdentityOwner(it.identity, it.entryIdentity,
				ImportedPressureRetainedIdentityEntity.WINDOW, entryIdentity, expected)
		}) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
		}
	}

	private fun hasExpectedLiveIdentityOwner(
		identity: String, ownerEntryIdentity: String, kind: String,
		expectedEntryIdentity: String, expected: Map<String, String>,
	): Boolean = ownerEntryIdentity == expectedEntryIdentity && expected[identity] == kind

	private suspend fun prepareRetainedImportedAuthority(
		state: SourceEvidenceState,
		erasedAtMs: Long,
		byteBudget: ImportedPressureMaintenanceByteBudget,
	): PressureImportedEraseTotals {
		val dao = database.importedPressureDao()
		var ownerRowId = 0L
		var totals = PressureImportedEraseTotals()
		while (true) {
			val nextOwnerRowId = dao.retainedOwnerRowIdPage(ownerRowId, 1).singleOrNull() ?: break
			if (nextOwnerRowId <= ownerRowId) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			val receipt = retainedEraseOwner(dao, nextOwnerRowId, byteBudget)
			ownerRowId = nextOwnerRowId
			requireRetainedEraseAuthority(state, receipt)
			installRetainedEraseAuthority(dao, state, erasedAtMs, receipt)
			totals = totals.plus(receipt)
		}
		return totals
	}

	private suspend fun retainedEraseOwner(
		dao: ImportedPressureDao, nextOwnerRowId: Long, byteBudget: ImportedPressureMaintenanceByteBudget,
	): ImportedPressureRetentionReceiptEntity {
		val footprint = dao.retainedOwnerFootprint(nextOwnerRowId)
		if (footprint.receiptCount != 1L) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val markerCount = Math.toIntExact(footprint.markerCount)
		consumeFootprint(byteBudget, footprint, markerCount)
		val receipt = dao.retentionReceiptByOwnerRowId(nextOwnerRowId)
			?: unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		if (receipt.protectedIdentityCount != markerCount) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return receipt
	}

	private suspend fun requireRetainedEraseAuthority(
		state: SourceEvidenceState, receipt: ImportedPressureRetentionReceiptEntity,
	) {
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
	}

	private suspend fun installRetainedEraseAuthority(
		dao: ImportedPressureDao, state: SourceEvidenceState, erasedAtMs: Long,
		receipt: ImportedPressureRetentionReceiptEntity,
	) {
		val identityFences = dao.identityFencesForEntry(
			receipt.entryIdentity,
			receipt.protectedIdentityCount + 1,
		)
		if (identityFences.size != receipt.protectedIdentityCount ||
			ImportedPressureIdentityFenceEntity.checksumSet(identityFences) !=
			receipt.identityFenceSetChecksum
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val runIds = identityFences.filter {
			it.identityKind == ImportedPressureIdentityFenceEntity.RUN
		}.map { it.protectedIdentity }
		val existingRunDeletions = runIds.chunked(SQLITE_BIND_BATCH)
			.flatMap { dao.deletionGenerations(it) }
			.associateBy { it.runIdentity }
		if (existingRunDeletions.size > runIds.size ||
			existingRunDeletions.values.any { it.generation != 1L }
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
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
	}

	private suspend fun deleteLiveImportedPayload(
		state: SourceEvidenceState,
	) {
		val dao = database.importedPressureDao()
		while (true) {
			val ownerRowId = dao.liveOwnerRowIdPage(0L, 1).singleOrNull() ?: break
			val candidate = dao.liveCandidateByOwnerRowId(ownerRowId)
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE,
				)
			val deletion = dao.entryDeletion(candidate.identity)
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE,
				)
			if (!state.acceptsRetainedEpoch(deletion.collectedDataEpoch) ||
				dao.liveOwnerHeaderCount(ownerRowId) != deletion.deletedImportRevision ||
				dao.deleteLiveOwnerByRowId(ownerRowId) != deletion.deletedImportRevision.toInt()
			) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			}
		}
	}

	private suspend fun deleteRetainedImportedPayload(
		state: SourceEvidenceState,
	) {
		val dao = database.importedPressureDao()
		while (true) {
			val ownerRowId = dao.retainedOwnerRowIdPage(0L, 1).singleOrNull() ?: break
			deleteRetainedEraseOwner(dao, state, ownerRowId)
		}
	}

	private suspend fun deleteRetainedEraseOwner(
		dao: ImportedPressureDao, state: SourceEvidenceState, ownerRowId: Long,
	) {
		val footprint = dao.retainedOwnerFootprint(ownerRowId)
		if (footprint.receiptCount != 1L) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		val receipt = dao.retentionReceiptByOwnerRowId(ownerRowId)
			?: unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE,
			)
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
		if (!state.acceptsRetainedEpoch(deletion.collectedDataEpoch) ||
			deletion.deletedImportRevision != receipt.latestImportRevision
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		if (dao.deleteRetainedOwnerMarkersByRowId(ownerRowId) !=
			receipt.protectedIdentityCount ||
			dao.deleteRetentionReceiptByOwnerRowId(ownerRowId) != 1
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
	}

	private suspend fun collectSourceEraseAuthority(
		localAudit: com.adsamcik.tracker.shared.base.database.PressureSourceEraseLocalAudit,
		previousLegacySamples: List<ImportedPressureSourceEraseWitnessEntity>,
	): PressureSourceEraseAuthoritySnapshot {
		val dao = database.importedPressureDao()
		val localFences = collectLocalSourceEraseFences()
		val permanent = collectPermanentImportedAuthority()
		val entryDeletions = permanent.entryDeletions
		val runDeletions = permanent.runDeletions
		val identityFences = permanent.identityFences
		if (database.pressureFactRevisionDao().sourceEraseFenceCount() != localFences.size.toLong() ||
			dao.entryDeletionCount() != entryDeletions.size.toLong() ||
			dao.deletionGenerationCount() != runDeletions.size.toLong() ||
			dao.identityFenceCount() != identityFences.size.toLong()
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
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

	private suspend fun collectLocalSourceEraseFences():
		List<com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity> {
		val dao = database.pressureFactRevisionDao()
		val expectedFenceCount = localEraseFenceCount(dao)
		val fences =
			mutableListOf<com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity>()
		var ownerRowId = 0L
		while (true) {
			val nextOwnerRowId = dao.sourceEraseFenceOwnerRowIdPage(ownerRowId, 1)
				.singleOrNull() ?: break
			if (nextOwnerRowId <= ownerRowId) {
				unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			val footprint = dao.sourceEraseFenceOwnerFootprint(nextOwnerRowId)
			if (footprint.rowCount != 1L) {
				unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			requireOwnerTextWithinBounds(footprint.textBytes)
			fences += dao.sourceEraseFenceByOwnerRowId(nextOwnerRowId)
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			ownerRowId = nextOwnerRowId
		}
		if (fences.size.toLong() != expectedFenceCount) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return fences
	}

	private suspend fun localEraseFenceCount(
		dao: com.adsamcik.tracker.shared.base.database.dao.PressureFactRevisionDao,
	): Long {
		val global = dao.sourceEraseFenceGlobalFootprint()
		if (global.rowCount !in 0L..limits.maximumLocalFences.toLong()) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		val privacyFootprint = database.importedPressureDao().privacyMaintenanceFootprint()
		requireGlobalFootprint(privacyFootprint.validateGlobal(
			maximumIdentityFences = limits.maximumIdentityFences.toLong(),
			maximumEntryDeletions = limits.maximumEntryDeletions.toLong(),
			maximumRunDeletions = limits.maximumRunDeletions.toLong(),
			maximumWitnesses = limits.maximumWitnesses.toLong(),
		))
		try {
			if (global.textBytes < 0L ||
				Math.addExact(global.textBytes, privacyFootprint.totalTextBytes) >
				com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
					.MAX_MAINTENANCE_TEXT_BYTES
			) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
		} catch (_: ArithmeticException) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
		return global.rowCount
	}

	private suspend fun collectPermanentImportedAuthority():
		PressurePermanentAuthoritySnapshot {
		val dao = database.importedPressureDao()
		val owners = collectDeletedEntryOwners(dao)
		appendStandalonePermanentOwners(dao, owners)
		val entryDeletions = owners.mapNotNull(PressurePermanentAuthorityOwner::entryDeletion)
		val identityFences = owners.flatMap(PressurePermanentAuthorityOwner::identityFences)
		val runDeletions = owners.flatMap(PressurePermanentAuthorityOwner::runDeletions)
		if (entryDeletions.map { it.entryIdentity }.distinct().size != entryDeletions.size ||
			identityFences.map { it.protectedIdentity }.distinct().size != identityFences.size ||
			runDeletions.map { it.runIdentity }.distinct().size != runDeletions.size
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (dao.entryDeletionCount() != entryDeletions.size.toLong() ||
			dao.identityFenceCount() != identityFences.size.toLong() ||
			dao.deletionGenerationCount() != runDeletions.size.toLong()
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return PressurePermanentAuthoritySnapshot(entryDeletions, runDeletions, identityFences)
	}

	private suspend fun collectDeletedEntryOwners(
		dao: ImportedPressureDao,
	): MutableList<PressurePermanentAuthorityOwner> {
		val owners = mutableListOf<PressurePermanentAuthorityOwner>()
		var ownerRowId = 0L
		while (true) {
			val nextOwnerRowId = dao.entryDeletionOwnerRowIdPage(ownerRowId, 1)
				.singleOrNull() ?: break
			if (nextOwnerRowId <= ownerRowId) {
				unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			val footprint = dao.entryDeletionOwnerFootprint(nextOwnerRowId)
			requirePermanentOwnerFootprint(footprint, requireEntryDeletion = true)
			val deletion = dao.entryDeletionByOwnerRowId(nextOwnerRowId)
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			owners += loadPermanentAuthorityOwner(
				footprint = footprint,
				entryIdentity = deletion.entryIdentity,
				expectedEntryDeletion = deletion,
			)
			ownerRowId = nextOwnerRowId
		}
		return owners
	}

	private suspend fun appendStandalonePermanentOwners(
		dao: ImportedPressureDao, owners: MutableList<PressurePermanentAuthorityOwner>,
	) {
		var ownerRowId = 0L
		while (true) {
			val nextOwnerRowId = dao.standalonePermanentOwnerRowIdPage(ownerRowId, 1)
				.singleOrNull() ?: break
			if (nextOwnerRowId <= ownerRowId) {
				unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			val footprint = dao.standalonePermanentOwnerFootprint(nextOwnerRowId)
			requirePermanentOwnerFootprint(footprint, requireEntryDeletion = false)
			val entryMarker = dao.identityFenceByOwnerRowId(nextOwnerRowId)
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			owners += loadPermanentAuthorityOwner(
				footprint = footprint,
				entryIdentity = entryMarker.entryIdentity,
				expectedEntryDeletion = null,
			)
			ownerRowId = nextOwnerRowId
		}
	}

	private suspend fun loadPermanentAuthorityOwner(
		footprint:
			com.adsamcik.tracker.shared.base.database.dao.ImportedPressurePermanentOwnerFootprint,
		entryIdentity: String,
		expectedEntryDeletion: ImportedPressureEntryDeletionEntity?,
	): PressurePermanentAuthorityOwner {
		val dao = database.importedPressureDao()
		val fenceCount = Math.toIntExact(footprint.identityFenceCount)
		val identityFences = dao.identityFencesForEntry(entryIdentity, fenceCount + 1)
		requirePermanentOwnerFences(identityFences, fenceCount, entryIdentity)
		val runIds = identityFences.filter {
			it.identityKind == ImportedPressureIdentityFenceEntity.RUN
		}.map { it.protectedIdentity }
		val runDeletions = runIds.chunked(SQLITE_BIND_BATCH).flatMap {
			dao.deletionGenerations(it)
		}
		if (runDeletions.size.toLong() != footprint.runDeletionCount ||
			runDeletions.map { it.runIdentity }.distinct().size != runDeletions.size ||
			runDeletions.any { it.runIdentity !in runIds }
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val entryDeletion = dao.entryDeletion(entryIdentity)
		if (entryDeletion != expectedEntryDeletion) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		entryDeletion?.let {
			authenticateEntryDeletionAuthority(it, identityFences, runDeletions)
		}
		return PressurePermanentAuthorityOwner(entryDeletion, identityFences, runDeletions)
	}

	private fun requirePermanentOwnerFences(
		identityFences: List<ImportedPressureIdentityFenceEntity>, fenceCount: Int, entryIdentity: String,
	) {
		if (identityFences.size != fenceCount) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (identityFences.count {
				it.identityKind == ImportedPressureIdentityFenceEntity.ENTRY &&
					it.protectedIdentity == entryIdentity &&
					it.runIdentity == null
			} != 1 ||
			identityFences.any { it.entryIdentity != entryIdentity }
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun requirePermanentOwnerFootprint(
		footprint:
			com.adsamcik.tracker.shared.base.database.dao.ImportedPressurePermanentOwnerFootprint,
		requireEntryDeletion: Boolean,
	) {
		requireOwnerTextWithinBounds(footprint.totalTextBytes)
		if (footprint.entryDeletionCount != (if (requireEntryDeletion) {
			1L
		} else {
			0L
		}) ||
			footprint.entryMarkerCount != 1L ||
			footprint.identityFenceCount !in 1L..limits.maximumIdentityFences.toLong() ||
			footprint.runDeletionCount !in 0L..limits.maximumRunDeletions.toLong()
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun requireOwnerTextWithinBounds(textBytes: Long) {
		if (textBytes < 0L) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
		if (textBytes > com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
				.MAX_LINEAGE_TEXT_BYTES
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
	}

	private suspend fun authenticateSourceEraseAuthority(
		marker: ImportedPressureSourceEraseEntity,
		state: SourceEvidenceState,
	): AuthenticatedPressureSourceEraseAuthority {
		if (database.importedPressureDao().sourceErase() != marker) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		if (!state.acceptsRetainedEpoch(marker.collectedDataEpoch) ||
			marker.sourceEvidenceRevision > state.revision ||
			marker.erasedAtMs > state.updatedAtMs
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		verifyBarrier(
			marker.toBarrierToken() ?: unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			),
		)
		val witnesses = sourceEraseWitnesses(marker)
		return authenticateSourceEraseWitnesses(marker, witnesses)
	}

	private suspend fun sourceEraseWitnessCount(marker: ImportedPressureSourceEraseEntity): Int {
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
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		return expectedWitnessCount
	}

	private suspend fun sourceEraseWitnesses(
		marker: ImportedPressureSourceEraseEntity,
	): List<ImportedPressureSourceEraseWitnessEntity> {
		val expectedWitnessCount = sourceEraseWitnessCount(marker)
		val witnesses = mutableListOf<ImportedPressureSourceEraseWitnessEntity>()
		var afterKind: String? = null
		var afterIdentity: String? = null
		while (true) {
			val page = database.importedPressureDao().sourceEraseWitnessPage(
				afterKind,
				afterIdentity,
				AUTHORITY_PAGE_SIZE,
			)
			if (page.isEmpty()) {
				break
			}
			val previous = afterKind?.let { it to requireNotNull(afterIdentity) }
			if (!page.first().followsWitnessCursor(previous)) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			witnesses += page
			if (witnesses.size > expectedWitnessCount) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			}
			afterKind = page.last().witnessKind
			afterIdentity = page.last().witnessIdentity
			if (page.size < AUTHORITY_PAGE_SIZE) {
				break
			}
		}
		if (witnesses.size != expectedWitnessCount) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		return witnesses
	}

	private fun ImportedPressureSourceEraseWitnessEntity.followsWitnessCursor(
		previous: Pair<String, String>?,
	): Boolean = previous == null || witnessKind > previous.first ||
		(witnessKind == previous.first && witnessIdentity > previous.second)

	private suspend fun authenticateSourceEraseWitnesses(
		marker: ImportedPressureSourceEraseEntity, witnesses: List<ImportedPressureSourceEraseWitnessEntity>,
	): AuthenticatedPressureSourceEraseAuthority {
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
		if (!marker.hasWitnessCounts(localWitnesses.size, legacyWitnesses.size, entryWitnesses.size,
			runWitnesses.size, identityWitnesses.size)) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		val authority = loadSourceEraseWitnessAuthority(localWitnesses, legacyWitnesses, entryWitnesses,
			runWitnesses, identityWitnesses)
		verifyWitnessChecksums(localWitnesses, authority.localFences.associate {
			it.scopeIdentityDigest to it.effectChecksum
		})
		verifyWitnessChecksums(entryWitnesses, authority.entryDeletions.associate {
			it.entryIdentity to it.effectChecksum
		})
		verifyWitnessChecksums(runWitnesses, authority.runDeletions.associate {
			it.runIdentity to it.effectChecksum
		})
		verifyWitnessChecksums(identityWitnesses, authority.identityFences.associate {
			it.protectedIdentity to it.effectChecksum
		})
		requireSourceEraseSetChecksums(marker, authority)
		authority.entryDeletions.forEach { authenticateEntryDeletionAuthority(it) }
		return AuthenticatedPressureSourceEraseAuthority(legacyWitnesses)
	}

	private suspend fun loadSourceEraseWitnessAuthority(
		localWitnesses: List<ImportedPressureSourceEraseWitnessEntity>,
		legacyWitnesses: List<ImportedPressureSourceEraseWitnessEntity>,
		entryWitnesses: List<ImportedPressureSourceEraseWitnessEntity>,
		runWitnesses: List<ImportedPressureSourceEraseWitnessEntity>,
		identityWitnesses: List<ImportedPressureSourceEraseWitnessEntity>,
	): PressureSourceEraseAuthoritySnapshot {
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
		return PressureSourceEraseAuthoritySnapshot(localFences, legacyWitnesses, entryDeletions,
			runDeletions, identityFences)
	}

	private fun ImportedPressureSourceEraseEntity.hasWitnessCounts(
		local: Int, legacy: Int, entries: Int, runs: Int, identities: Int,
	): Boolean {
		val localCountsMatch = local == fencedLocalRunCount && legacy == legacySampleCount
		return localCountsMatch && entries == entryDeletionCount && runs == runDeletionCount &&
			identities == identityFenceCount
	}

	private fun requireSourceEraseSetChecksums(
		marker: ImportedPressureSourceEraseEntity,
		authority: PressureSourceEraseAuthoritySnapshot,
	) {
		if (ImportedPressureSourceEraseEntity.checksumLocalFences(authority.localFences) !=
			marker.localScopeSetChecksum ||
			ImportedPressureSourceEraseEntity.checksumLegacySamples(authority.legacySamples) !=
			marker.legacySampleSetChecksum
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (ImportedPressureSourceEraseEntity.checksumEntryDeletions(authority.entryDeletions) !=
			marker.entryDeletionSetChecksum ||
			ImportedPressureRetentionReceiptEntity.checksumRunDeletions(authority.runDeletions) !=
			marker.runDeletionSetChecksum ||
			ImportedPressureIdentityFenceEntity.checksumSet(authority.identityFences) !=
			marker.identityFenceSetChecksum
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun verifyWitnessChecksums(
		witnesses: List<ImportedPressureSourceEraseWitnessEntity>,
		actual: Map<String, String>,
	) {
		if (actual.size != witnesses.size || witnesses.any {
			actual[it.witnessIdentity] != it.authorityChecksum
		}) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
	}

	private fun consumeFootprint(
		budget: ImportedPressureMaintenanceByteBudget,
		footprint: com.adsamcik.tracker.shared.base.database.dao.ImportedPressureLineageFootprint,
	) {
		try {
			budget.consume(footprint)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.DependencyOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.ValueOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
	}

	private fun consumeFootprint(
		budget: ImportedPressureMaintenanceByteBudget,
		footprint: com.adsamcik.tracker.shared.base.database.dao.ImportedPressureRetainedFootprint,
		expectedMarkerCount: Int,
	) {
		try {
			budget.consume(footprint, expectedMarkerCount)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.DependencyOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.ValueOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
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
		authenticateEntryDeletionAuthority(deletion, identityFences, runDeletions)
	}

	private fun authenticateEntryDeletionAuthority(
		deletion: ImportedPressureEntryDeletionEntity,
		identityFences: List<ImportedPressureIdentityFenceEntity>,
		runDeletions: List<ImportedPressureDeletionGenerationEntity>,
	) {
		if (identityFences.size != deletion.identityFenceCount ||
			ImportedPressureIdentityFenceEntity.checksumSet(identityFences) !=
			deletion.identityFenceSetChecksum ||
			runDeletions.size != deletion.runDeletionCount ||
			ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) !=
			deletion.runDeletionSetChecksum
		) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
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
				retainedState = checkNotNull(database.sourceEvidenceStateDao().get()).also {
					check(it.collectedDataEpoch == expectedCollectedDataEpoch)
				},
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

	private fun ImportedPressureSourceEraseEntity.toBarrierToken():
		PressureSourceEraseBarrierToken? {
		if (legacyWriteFenceGeneration <= 0L) {
			return null
		}
		val owner = legacyWriteFenceOwner
			?.let { PressureSourceEraseFenceOwner.fromStorageValue(it) }
			?: return null
		return PressureSourceEraseBarrierToken(
			collectedDataEpoch = collectedDataEpoch,
			providerRegistrationGeneration = providerRegistrationGeneration,
			legacyWriteFenceOwner = owner,
			legacyWriteFenceGeneration = legacyWriteFenceGeneration,
		)
	}

	private fun PressureSourceEraseBarrierToken.toWriterFence() =
		PressureSourceEraseWriterFence(
			collectedDataEpoch = collectedDataEpoch,
			owner = legacyWriteFenceOwner.storageValue,
			ownerGeneration = legacyWriteFenceGeneration,
		)

	private data class PressureErasePreflight(
		val isFirstErase: Boolean,
		val existingBarrierToken: PressureSourceEraseBarrierToken?,
		val requiresRevokedAuthority: Boolean,
	)

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

private data class PressurePermanentAuthorityOwner(
	val entryDeletion: ImportedPressureEntryDeletionEntity?,
	val identityFences: List<ImportedPressureIdentityFenceEntity>,
	val runDeletions: List<ImportedPressureDeletionGenerationEntity>,
)

private data class PressurePermanentAuthoritySnapshot(
	val entryDeletions: List<ImportedPressureEntryDeletionEntity>,
	val runDeletions: List<ImportedPressureDeletionGenerationEntity>,
	val identityFences: List<ImportedPressureIdentityFenceEntity>,
)

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

// Keep every local erase failure in one exhaustive public-result map.
@Suppress("CyclomaticComplexMethod")
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
