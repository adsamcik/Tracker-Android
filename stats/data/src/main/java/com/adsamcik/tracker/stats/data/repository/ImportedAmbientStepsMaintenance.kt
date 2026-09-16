package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AuthenticatedImportedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailure
import com.adsamcik.tracker.shared.base.database.ImportedAmbientStepsLineageFailureReason
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsSourceFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.authenticateAllAmbientStepsFences
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsAfterConsentReset
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsAfterConsentResetRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsAfterConsentResetResult
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDay
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDayRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDayResult
import com.adsamcik.tracker.stats.api.repository.ImportedAmbientStepsMutationBlockedReason
import com.adsamcik.tracker.stats.api.repository.ImportedAmbientStepsMutationUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetention
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetentionRequest
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetentionResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ImportedAmbientStepsMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	LINEAGE_AUTHENTICATED,
	FENCE_INSERTED,
	PROTECTED_IDENTITIES_INSERTED,
	PAYLOAD_REMOVED,
}

internal class RoomDeleteImportedAmbientStepsDay internal constructor(
	private val database: AppDatabase,
	private val dao: ImportedAmbientStepsDao,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val checkpoint: suspend (ImportedAmbientStepsMaintenanceCheckpoint) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
) : DeleteImportedAmbientStepsDay {
	@Inject
	constructor(
		database: AppDatabase,
		dao: ImportedAmbientStepsDao,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, dao, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun deleteDay(
		request: DeleteImportedAmbientStepsDayRequest,
	): DeleteImportedAmbientStepsDayResult = withContext(ioDispatcher) {
		try {
			database.withPermanentAmbientStepsMaintenanceMapping {
				checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.TRANSACTION_STARTED)
				val state = requireState(request.expectedCollectedDataEpoch)
				authenticateFenceAuthority(dao, state)
				if (sourceDeletionActive(database, dao, state)) {
					blocked(ImportedAmbientStepsMutationBlockedReason.SOURCE_DELETION_PENDING)
				}
				when (dao.fence(request.dayIdentity.value)?.fenceKind) {
					ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION ->
						return@withPermanentAmbientStepsMaintenanceMapping
							DeleteImportedAmbientStepsDayResult.Retained
					null -> Unit
					else -> return@withPermanentAmbientStepsMaintenanceMapping
						DeleteImportedAmbientStepsDayResult.AlreadyDeleted
				}
				val lineage = authenticate(request.dayIdentity.value, state)
				if (lineage.revisions.isEmpty()) {
					return@withPermanentAmbientStepsMaintenanceMapping
						DeleteImportedAmbientStepsDayResult.NotFound
				}
				val removed = fenceAndDelete(
					lineage,
					state,
					ImportedAmbientStepsDayFenceEntity.FENCE_SELECTED_DELETE,
					request.deletedAtMs,
					retainedFromMs = null,
				)
				incrementImportedAmbientStepsEvidenceRevision(
					database,
					state,
					request.deletedAtMs,
				)
				DeleteImportedAmbientStepsDayResult.Deleted(removed)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedAmbientStepsMaintenanceAbort) {
			when (val result = abort.result) {
				is MaintenanceFailure.Blocked ->
					DeleteImportedAmbientStepsDayResult.Blocked(result.reason)
				is MaintenanceFailure.Unverifiable ->
					DeleteImportedAmbientStepsDayResult.Unverifiable(result.reason)
			}
		} catch (_: SQLiteConstraintException) {
			DeleteImportedAmbientStepsDayResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			DeleteImportedAmbientStepsDayResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun requireState(expectedEpoch: Long): SourceEvidenceState {
		val state = database.sourceEvidenceStateDao().get()
			?: unavailable(ImportedAmbientStepsMutationUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch != expectedEpoch) {
			blocked(ImportedAmbientStepsMutationBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		return state
	}

	private suspend fun authenticate(
		dayIdentity: String,
		state: SourceEvidenceState,
	) = authenticateLineage(dao, dayIdentity, state)

	private suspend fun fenceAndDelete(
		lineage: AuthenticatedImportedAmbientStepsLineage,
		state: SourceEvidenceState,
		kind: String,
		fencedAtMs: Long,
		retainedFromMs: Long?,
	): Int = fenceAndDeleteLineage(dao, lineage, state, kind, fencedAtMs, retainedFromMs, checkpoint)
}

internal class RoomTruncateImportedAmbientStepsRetention internal constructor(
	private val database: AppDatabase,
	private val dao: ImportedAmbientStepsDao,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val checkpoint: suspend (ImportedAmbientStepsMaintenanceCheckpoint) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
) : TruncateImportedAmbientStepsRetention {
	@Inject
	constructor(
		database: AppDatabase,
		dao: ImportedAmbientStepsDao,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, dao, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun truncateNext(
		request: TruncateImportedAmbientStepsRetentionRequest,
	): TruncateImportedAmbientStepsRetentionResult = withContext(ioDispatcher) {
		try {
			database.withPermanentAmbientStepsMaintenanceMapping {
				checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.TRANSACTION_STARTED)
				val state = database.sourceEvidenceStateDao().get()
					?: unavailable(
						ImportedAmbientStepsMutationUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
					)
				if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
					blocked(ImportedAmbientStepsMutationBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
				}
				authenticateFenceAuthority(dao, state)
				if (state.retainedFromMs != request.retainedFromMs) {
					blocked(ImportedAmbientStepsMutationBlockedReason.RETENTION_FLOOR_CHANGED)
				}
				if (sourceDeletionActive(database, dao, state)) {
					blocked(ImportedAmbientStepsMutationBlockedReason.SOURCE_DELETION_PENDING)
				}
				val candidate = dao.nextRetentionCandidate(request.retainedFromMs)
					?: return@withPermanentAmbientStepsMaintenanceMapping
						TruncateImportedAmbientStepsRetentionResult.Complete
				val lineage = authenticateLineage(dao, candidate.dayIdentity, state)
				if (lineage.latest.header != candidate ||
					candidate.structuralDayStartTimeMs >= request.retainedFromMs
				) unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
				val removed = fenceAndDeleteLineage(
					dao,
					lineage,
					state,
					ImportedAmbientStepsDayFenceEntity.FENCE_RETENTION,
					request.retainedAtMs,
					request.retainedFromMs,
					checkpoint,
				)
				incrementImportedAmbientStepsEvidenceRevision(
					database,
					state,
					request.retainedAtMs,
				)
				TruncateImportedAmbientStepsRetentionResult.Retained(removed)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedAmbientStepsMaintenanceAbort) {
			when (val result = abort.result) {
				is MaintenanceFailure.Blocked ->
					TruncateImportedAmbientStepsRetentionResult.Blocked(result.reason)
				is MaintenanceFailure.Unverifiable ->
					TruncateImportedAmbientStepsRetentionResult.Unverifiable(result.reason)
			}
		} catch (_: SQLiteConstraintException) {
			TruncateImportedAmbientStepsRetentionResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			TruncateImportedAmbientStepsRetentionResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}
}

internal class RoomDeleteImportedAmbientStepsAfterConsentReset internal constructor(
	private val database: AppDatabase,
	private val dao: ImportedAmbientStepsDao,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val checkpoint: suspend (ImportedAmbientStepsMaintenanceCheckpoint) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
) : DeleteImportedAmbientStepsAfterConsentReset {
	@Inject
	constructor(
		database: AppDatabase,
		dao: ImportedAmbientStepsDao,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, dao, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun deleteNext(
		request: DeleteImportedAmbientStepsAfterConsentResetRequest,
	): DeleteImportedAmbientStepsAfterConsentResetResult = withContext(ioDispatcher) {
		try {
			database.withPermanentAmbientStepsMaintenanceMapping {
				checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.TRANSACTION_STARTED)
				val state = database.sourceEvidenceStateDao().get()
					?: unavailable(
						ImportedAmbientStepsMutationUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
					)
				if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
					blocked(ImportedAmbientStepsMutationBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
				}
				authenticateFenceAuthority(dao, state)
				requireRevokedConsent(request.expectedRevokedConsentEpoch)
				val sourceFenceChanged = ensureSourceFence(state, request)
				val candidate = dao.nextDayCandidate()
					?: run {
						val completionChanged = completeSourceFence(
							state,
							request.expectedRevokedConsentEpoch,
							request.deletedAtMs,
						)
						if (sourceFenceChanged || completionChanged) {
							incrementImportedAmbientStepsEvidenceRevision(
								database,
								state,
								request.deletedAtMs,
							)
						}
						return@withPermanentAmbientStepsMaintenanceMapping
							DeleteImportedAmbientStepsAfterConsentResetResult.Complete
					}
				val lineage = authenticateLineage(dao, candidate.dayIdentity, state)
				if (lineage.latest.header != candidate) {
					unavailable(
						ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
				val removed = fenceAndDeleteLineage(
					dao,
					lineage,
					state,
					ImportedAmbientStepsDayFenceEntity.FENCE_CONSENT_REVOKED,
					request.deletedAtMs,
					retainedFromMs = null,
					checkpoint,
				)
				incrementImportedAmbientStepsEvidenceRevision(
					database,
					state,
					request.deletedAtMs,
				)
				DeleteImportedAmbientStepsAfterConsentResetResult.Deleted(removed)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedAmbientStepsMaintenanceAbort) {
			when (val result = abort.result) {
				is MaintenanceFailure.Blocked ->
					DeleteImportedAmbientStepsAfterConsentResetResult.Blocked(result.reason)
				is MaintenanceFailure.Unverifiable ->
					DeleteImportedAmbientStepsAfterConsentResetResult.Unverifiable(result.reason)
			}
		} catch (_: SQLiteConstraintException) {
			DeleteImportedAmbientStepsAfterConsentResetResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			DeleteImportedAmbientStepsAfterConsentResetResult.RetryableFailure(
				PortableAmbientStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun requireRevokedConsent(expectedEpoch: Long) {
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
		if (policy == null || consent == null) {
			unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (consent.epoch != expectedEpoch || consent.eligible || consent.persistenceEligible ||
			policy.ambientPersistenceEligible || policy.ambientConsentEpoch != null ||
			consent.policyRevision != policy.policyRevision
		) blocked(ImportedAmbientStepsMutationBlockedReason.CONSENT_NOT_REVOKED)
	}

	private suspend fun ensureSourceFence(
		state: SourceEvidenceState,
		request: DeleteImportedAmbientStepsAfterConsentResetRequest,
	): Boolean {
		val existing = readSourceFence(dao)
		if (existing == null) {
			dao.insertSourceFence(
				ImportedAmbientStepsSourceFenceEntity.create(
					state.collectedDataEpoch,
					request.expectedRevokedConsentEpoch,
					request.deletedAtMs,
				),
			)
			checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.FENCE_INSERTED)
			return true
		}
		if (existing.collectedDataEpoch != state.collectedDataEpoch ||
			existing.revokedConsentEpoch > request.expectedRevokedConsentEpoch ||
			request.deletedAtMs < maxOf(existing.deletedAtMs, existing.reopenedAtMs ?: 0L)
		) {
			unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (existing.revokedConsentEpoch == request.expectedRevokedConsentEpoch) return false
		val replacement = ImportedAmbientStepsSourceFenceEntity.create(
			state.collectedDataEpoch,
			request.expectedRevokedConsentEpoch,
			request.deletedAtMs,
		)
		if (!dao.replaceSourceFence(existing, replacement)) {
			unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.FENCE_INSERTED)
		return true
	}

	private suspend fun completeSourceFence(
		state: SourceEvidenceState,
		revokedConsentEpoch: Long,
		completedAtMs: Long,
	): Boolean {
		val existing = readSourceFence(dao)
			?: unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		if (existing.collectedDataEpoch != state.collectedDataEpoch ||
			existing.revokedConsentEpoch != revokedConsentEpoch
		) {
			unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (existing.deletionCompleted) return false
		val replacement = ImportedAmbientStepsSourceFenceEntity.completed(
			existing,
			maxOf(completedAtMs, existing.deletedAtMs),
		)
		if (!dao.replaceSourceFence(existing, replacement)) {
			unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.FENCE_INSERTED)
		return true
	}
}

private suspend fun authenticateLineage(
	dao: ImportedAmbientStepsDao,
	dayIdentity: String,
	state: SourceEvidenceState,
): AuthenticatedImportedAmbientStepsLineage = try {
	dao.loadAuthenticatedAmbientStepsLineage(dayIdentity, state.collectedDataEpoch)
} catch (failure: ImportedAmbientStepsLineageFailure) {
	when (failure.reason) {
		ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
		ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW,
		-> unavailable(ImportedAmbientStepsMutationUnverifiableReason.DEPENDENCY_OVERFLOW)
		ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
			unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}
} catch (_: IllegalArgumentException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
} catch (_: IllegalStateException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
} catch (_: ArithmeticException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
} catch (_: java.time.DateTimeException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
}

private suspend fun authenticateFenceAuthority(
	dao: ImportedAmbientStepsDao,
	state: SourceEvidenceState,
) {
	try {
		dao.authenticateAllAmbientStepsFences(state.collectedDataEpoch)
	} catch (failure: ImportedAmbientStepsLineageFailure) {
		when (failure.reason) {
			ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
			ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW,
			-> unavailable(ImportedAmbientStepsMutationUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
				unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	} catch (_: IllegalArgumentException) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	} catch (_: IllegalStateException) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	} catch (_: ArithmeticException) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	} catch (_: java.time.DateTimeException) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}
}

private suspend fun sourceDeletionActive(
	database: AppDatabase,
	dao: ImportedAmbientStepsDao,
	state: SourceEvidenceState,
): Boolean {
	val fence = readSourceFence(dao) ?: return false
	if (fence.collectedDataEpoch != state.collectedDataEpoch) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
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
	return policy == null || consent == null || !policy.enabled ||
		!fence.deletionCompleted ||
		!policy.ambientPersistenceEligible || policy.ambientConsentEpoch != consent.epoch ||
		!consent.eligible || !consent.persistenceEligible ||
		consent.policyRevision != policy.policyRevision ||
		consent.epoch <= fence.revokedConsentEpoch ||
		fence.reopenedConsentEpoch != consent.epoch
}

private suspend fun readSourceFence(
	dao: ImportedAmbientStepsDao,
): ImportedAmbientStepsSourceFenceEntity? = try {
	dao.sourceFence()
} catch (_: IllegalArgumentException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
}

internal suspend fun ImportedAmbientStepsDao.replaceSourceFence(
	expected: ImportedAmbientStepsSourceFenceEntity,
	replacement: ImportedAmbientStepsSourceFenceEntity,
): Boolean = replaceSourceFenceExact(
	expectedCollectedDataEpoch = expected.collectedDataEpoch,
	expectedRevokedConsentEpoch = expected.revokedConsentEpoch,
	expectedEffectChecksum = expected.effectChecksum,
	collectedDataEpoch = replacement.collectedDataEpoch,
	revokedConsentEpoch = replacement.revokedConsentEpoch,
	deletedAtMs = replacement.deletedAtMs,
	deletionCompleted = replacement.deletionCompleted,
	completedAtMs = replacement.completedAtMs,
	reopenedConsentEpoch = replacement.reopenedConsentEpoch,
	reopenedAtMs = replacement.reopenedAtMs,
	effectChecksum = replacement.effectChecksum,
) == 1

private suspend fun fenceAndDeleteLineage(
	dao: ImportedAmbientStepsDao,
	lineage: AuthenticatedImportedAmbientStepsLineage,
	state: SourceEvidenceState,
	kind: String,
	fencedAtMs: Long,
	retainedFromMs: Long?,
	checkpoint: suspend (ImportedAmbientStepsMaintenanceCheckpoint) -> Unit,
): Int {
	checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
	val latest = lineage.latest
	val latestDurableTime = maxOf(
		latest.header.receivedAtMs,
		lineage.receipts.maxOfOrNull { it.receivedAtMs } ?: 0L,
	)
	if (fencedAtMs < latestDurableTime) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}
	val existingMarkers = dao.protectedIdentitiesForDay(
		latest.header.dayIdentity,
		ImportedAmbientStepsDao.MAX_PROTECTED_IDENTITIES_PER_DAY + 1,
	)
	if (existingMarkers.isNotEmpty()) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}
	val markers = try {
		lineage.protectedIdentities()
	} catch (_: ImportedAmbientStepsLineageFailure) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}
	if (markers.size > ImportedAmbientStepsDao.MAX_PROTECTED_IDENTITIES_PER_DAY) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.DEPENDENCY_OVERFLOW)
	}
	checkCapacity(
		dao.fenceCount(),
		1L,
		ImportedAmbientStepsDao.MAX_GLOBAL_FENCES,
	)
	checkCapacity(
		dao.protectedIdentityCount(),
		markers.size.toLong(),
		ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES,
	)
	val fence = ImportedAmbientStepsDayFenceEntity.create(
		dayIdentity = latest.header.dayIdentity,
		deletionScopeIdentity = latest.header.deletionScopeIdentity,
		fenceKind = kind,
		collectedDataEpoch = state.collectedDataEpoch,
		sourceEvidenceRevision = state.revision,
		fencedAtMs = fencedAtMs,
		retainedFromMs = retainedFromMs,
		latestImportRevision = latest.header.importRevision,
		latestContentChecksum = latest.header.dayContentChecksum,
		structuralEpochDay = latest.header.structuralEpochDay,
		storedZoneId = latest.header.storedZoneId,
		structuralDayStartTimeMs = latest.header.structuralDayStartTimeMs,
		structuralDayEndTimeMs = latest.header.structuralDayEndTimeMs,
		revisionCount = lineage.revisions.size,
		archiveCount = lineage.archiveDays
			.filter { it.dayIdentity == latest.header.dayIdentity }
			.map { it.archiveIdentity }
			.distinct()
			.size,
		factRowCount = lineage.facts.size,
		gapRowCount = lineage.gaps.size,
		protectedIdentities = markers,
		lineageChecksum = lineage.lineageChecksum,
	)
	dao.insertFence(fence)
	checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.FENCE_INSERTED)
	dao.insertProtectedIdentities(markers)
	checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.PROTECTED_IDENTITIES_INSERTED)
	val removed = dao.deleteDayLineage(latest.header.dayIdentity)
	if (removed != lineage.revisions.size) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}
	dao.deleteOrphanArchives()
	checkpoint(ImportedAmbientStepsMaintenanceCheckpoint.PAYLOAD_REMOVED)
	return removed
}

private fun checkCapacity(current: Long, added: Long, maximum: Long) {
	try {
		if (current < 0L || added < 0L || Math.addExact(current, added) > maximum) {
			unavailable(ImportedAmbientStepsMutationUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
	} catch (_: ArithmeticException) {
		unavailable(ImportedAmbientStepsMutationUnverifiableReason.DEPENDENCY_OVERFLOW)
	}
}

private sealed interface MaintenanceFailure {
	data class Blocked(val reason: ImportedAmbientStepsMutationBlockedReason) : MaintenanceFailure
	data class Unverifiable(
		val reason: ImportedAmbientStepsMutationUnverifiableReason,
	) : MaintenanceFailure
}

private suspend inline fun <T> AppDatabase.withPermanentAmbientStepsMaintenanceMapping(
	crossinline block: suspend () -> T,
): T = try {
	withTransaction { block() }
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (failure: ImportedAmbientStepsLineageFailure) {
	when (failure.reason) {
		ImportedAmbientStepsLineageFailureReason.DEPENDENCY_OVERFLOW,
		ImportedAmbientStepsLineageFailureReason.REVISION_OVERFLOW,
		-> unavailable(ImportedAmbientStepsMutationUnverifiableReason.DEPENDENCY_OVERFLOW)
		ImportedAmbientStepsLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
			unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}
} catch (_: IllegalArgumentException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
} catch (_: IllegalStateException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
} catch (_: ArithmeticException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
} catch (_: java.time.DateTimeException) {
	unavailable(ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
}

private class ImportedAmbientStepsMaintenanceAbort(
	val result: MaintenanceFailure,
) : RuntimeException(null, null, false, false)

private fun blocked(reason: ImportedAmbientStepsMutationBlockedReason): Nothing =
	throw ImportedAmbientStepsMaintenanceAbort(MaintenanceFailure.Blocked(reason))

private fun unavailable(reason: ImportedAmbientStepsMutationUnverifiableReason): Nothing =
	throw ImportedAmbientStepsMaintenanceAbort(MaintenanceFailure.Unverifiable(reason))

private suspend fun incrementImportedAmbientStepsEvidenceRevision(
	database: AppDatabase,
	state: SourceEvidenceState,
	changedAtMs: Long,
) {
	check(
		database.sourceEvidenceStateDao().incrementRevision(
			maxOf(state.updatedAtMs, changedAtMs),
		) == 1,
	) { "Imported Ambient Steps maintenance could not advance source-evidence revision" }
}
