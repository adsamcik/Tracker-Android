package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity

enum class StepsCountDomainWriteResult {
	INSERTED,
	EXACT_REPLAY,
	SCHEMA_UNAVAILABLE,
	NOT_APPLICABLE,
	UNPROVEN,
	IDENTITY_CONFLICT,
	REVISION_GAP,
	TERMINALLY_RETRACTED,
}

data class StepsCountDomainOwnerLookupKey(
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
) {
	init {
		require(ownerKind in StepsCountDomainOwnerRevisionEntity.BINDABLE_OWNER_KINDS)
		require(StepsCountDomainReceiptIntegrity.isOpaque(ownerIdentity))
		require(ownerRevision > 0L)
	}
}

data class StepsCountDomainOwnerLineageKey(
	val ownerKind: String,
	val ownerIdentity: String,
)

data class StepsCountDomainStoredOwner(
	val owner: StepsCountDomainOwnerRevisionEntity,
	val receipt: StepsCountDomainReceiptEntity?,
)

sealed interface StepsCountDomainOwnerRead {
	data class Ready(
		val owners: Map<StepsCountDomainOwnerLookupKey, StepsCountDomainStoredOwner>,
		val latestRevisions: Map<StepsCountDomainOwnerLineageKey, Long>,
	) : StepsCountDomainOwnerRead

	data object SchemaUnavailable : StepsCountDomainOwnerRead
	data object Overflow : StepsCountDomainOwnerRead
	data object Unverifiable : StepsCountDomainOwnerRead
}

/**
 * Source-owned bridge used until the serialized AppDatabase owner registers the additive entities.
 *
 * Calls must occur inside the producer's existing Room transaction. Missing tables preserve the
 * pre-P5 behavior by returning SCHEMA_UNAVAILABLE; they never downgrade a present corrupt table.
 */
@Suppress("TooManyFunctions")
class StepsCountDomainStore(
	private val database: AppDatabase,
) {
	suspend fun recordSessionWal(row: SourceEventWalEntity): StepsCountDomainWriteResult {
		if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			row.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		if (!hasSchema()) return StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
		val logicalTrackingId = row.logicalTrackingId
			?: return StepsCountDomainWriteResult.UNPROVEN
		val serviceRunId = row.serviceRunId
			?: return StepsCountDomainWriteResult.UNPROVEN
		val providerDomain = row.physicalConfigurationFingerprint
			?: return StepsCountDomainWriteResult.UNPROVEN
		val authorityRevision = row.authorizationRevision
			?: return StepsCountDomainWriteResult.UNPROVEN
		val authorityFingerprint = row.authorizationFingerprint
			?: return StepsCountDomainWriteResult.UNPROVEN
		if (!row.hasQualifiedIntegrity()) return StepsCountDomainWriteResult.IDENTITY_CONFLICT

		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
			row.admissionOrdinal,
			row.eventId,
		)
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			logicalTrackingId,
			serviceRunId,
		)
		return appendBoundOwner(
			receipt = nativeReceipt(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = 1L,
				providerDomain = providerDomain,
				sourceInstanceId = row.sourceInstanceId,
				registrationGeneration = row.registrationGeneration,
				collectedDataEpoch = row.capturedCollectedDataEpoch,
				authorityRevision = authorityRevision,
				authorityFingerprint = authorityFingerprint,
				coverageKind = StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW,
				coverageVersion = row.payloadVersion,
				effectChecksum = row.integrityIdentity,
			),
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = row.integrityIdentity,
			linkedAtMs = row.createdAtMs,
		)
	}

	suspend fun recordSessionFact(fact: StepFactRevisionEntity): StepsCountDomainWriteResult {
		if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
			fact.operation != StepFactRevisionEntity.OPERATION_UPSERT
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		if (!hasSchema()) return StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
		if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact)) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val admissionOrdinal = requireNotNull(fact.sourceAdmissionOrdinal)
		val eventId = requireNotNull(fact.sourceEventId)
		val walOwner = loadExactOwner(
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(admissionOrdinal, eventId),
				1L,
			),
		) ?: return StepsCountDomainWriteResult.UNPROVEN
		val walReceipt = walOwner.receipt
			?: return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			requireNotNull(fact.logicalTrackingId),
			requireNotNull(fact.serviceRunId),
		)
		if (!walOwner.isAuthentic() ||
			walOwner.owner.scopeIdentity != scopeIdentity ||
			walReceipt.collectedDataEpoch != fact.collectedDataEpoch
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		)
		val coverageKind = when (fact.coverageKind) {
			StepFactRevisionEntity.COVERAGE_BASELINE ->
				StepsCountDomainReceiptEntity.COVERAGE_BASELINE
			StepFactRevisionEntity.COVERAGE_COVERED ->
				StepsCountDomainReceiptEntity.COVERAGE_COVERED
			StepFactRevisionEntity.COVERAGE_RESET_GAP ->
				StepsCountDomainReceiptEntity.COVERAGE_RESET_GAP
			StepFactRevisionEntity.COVERAGE_PARTIAL ->
				StepsCountDomainReceiptEntity.COVERAGE_PARTIAL
			else -> return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val receipt = nativeReceiptFrom(
			source = walReceipt,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			coverageKind = coverageKind,
			coverageVersion = fact.writerProjectionVersion,
			effectChecksum = fact.effectChecksum,
		)
		return appendBoundOwner(
			receipt = receipt,
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
	}

	suspend fun recordSessionCompleteness(
		row: SourceSessionCompletenessEntity,
	): StepsCountDomainWriteResult {
		if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		if (!hasSchema()) return StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			row.logicalTrackingId,
			row.serviceRunId,
		)
		val ownerEffectChecksum =
			StepsCountDomainReceiptIntegrity.completenessEffectChecksum(row)
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
			row.logicalTrackingId,
			row.serviceRunId,
			row.sourceInstanceId,
			row.registrationGeneration,
		)
		val ownerRevision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(row)
		val admissionOrdinal = row.lastAdmissionOrdinal
		if (row.registrationGeneration <= 0L || admissionOrdinal == null) {
			return appendOwner(
				receipt = null,
				owner = StepsCountDomainOwnerRevisionEntity(
					ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
					scopeIdentity = scopeIdentity,
					ownerIdentity = ownerIdentity,
					ownerRevision = ownerRevision,
					operation = StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN,
					receiptIdentity = null,
					ownerEffectChecksum = ownerEffectChecksum,
					linkedAtMs = row.updatedAtMs,
				),
			)
		}
		val wal = database.sourceEventWalDao().getByAdmissionOrdinal(admissionOrdinal)
			?: return StepsCountDomainWriteResult.UNPROVEN
		if (wal.logicalTrackingId != row.logicalTrackingId ||
			wal.serviceRunId != row.serviceRunId ||
			wal.sourceInstanceId != row.sourceInstanceId ||
			wal.registrationGeneration != row.registrationGeneration
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val walWrite = recordSessionWal(wal)
		if (walWrite !in SUCCESSFUL_OR_UNAVAILABLE) return walWrite
		if (walWrite == StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE) return walWrite
		val walOwner = loadExactOwner(
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
					admissionOrdinal,
					wal.eventId,
				),
				1L,
			),
		) ?: return StepsCountDomainWriteResult.UNPROVEN
		val walReceipt = walOwner.receipt ?: return StepsCountDomainWriteResult.UNPROVEN
		if (!walOwner.isAuthentic() || walOwner.owner.scopeIdentity != scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val receipt = nativeReceiptFrom(
			source = walReceipt,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN,
			coverageVersion = COMPLETENESS_COVERAGE_VERSION,
			effectChecksum = ownerEffectChecksum,
		)
		return appendBoundOwner(
			receipt,
			scopeIdentity,
			ownerEffectChecksum,
			row.updatedAtMs,
		)
	}

	suspend fun recordAmbientFact(
		fact: AmbientStepsFactRevisionEntity,
		providerDomain: String,
	): StepsCountDomainWriteResult {
		if (fact.operation != AmbientStepsFactRevisionEntity.OPERATION_UPSERT ||
			fact.originKind != AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		if (!hasSchema()) return StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
		if (!AmbientStepsFactIntegrity.hasValidEffectChecksum(fact)) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
			fact.writerId,
			fact.writerVersion,
			fact.logicalFactId,
		)
		val scopeIdentity =
			StepsCountDomainReceiptIntegrity.ambientFactScopeIdentity(fact.logicalFactId)
		val receipt = nativeReceipt(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			providerDomain = providerDomain,
			sourceInstanceId = requireNotNull(fact.sourceInstanceId),
			registrationGeneration = requireNotNull(fact.registrationGeneration),
			collectedDataEpoch = fact.collectedDataEpoch,
			authorityRevision = requireNotNull(fact.authorizationRevision),
			authorityFingerprint = requireNotNull(fact.authorizationFingerprint),
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_AMBIENT_AGGREGATE,
			coverageVersion = fact.writerVersion,
			effectChecksum = fact.effectChecksum,
		)
		return appendBoundOwner(
			receipt = receipt,
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
	}

	suspend fun recordSessionFactRetraction(
		retraction: StepFactRevisionEntity,
		logicalTrackingId: String,
		serviceRunId: String,
	): StepsCountDomainWriteResult {
		val expectedScopeIdentity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		if (!hasSchema()) return StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
		if (retraction.operation != StepFactRevisionEntity.OPERATION_RETRACT ||
			!StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
				retraction,
				expectedScopeIdentity,
			)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			null,
			StepsCountDomainOwnerRevisionEntity(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
				scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
					logicalTrackingId,
					serviceRunId,
				),
				ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
					retraction.writerProjectionId,
					retraction.writerProjectionVersion,
					retraction.logicalFactId,
				),
				ownerRevision = retraction.semanticRevision,
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
				receiptIdentity = null,
				ownerEffectChecksum = retraction.effectChecksum,
				linkedAtMs = retraction.appliedAtMs,
			),
		)
	}

	suspend fun recordAmbientFactRetraction(
		retraction: AmbientStepsFactRevisionEntity,
	): StepsCountDomainWriteResult {
		if (!hasSchema()) return StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
		if (retraction.operation != AmbientStepsFactRevisionEntity.OPERATION_RETRACT ||
			!AmbientStepsFactIntegrity.hasValidEffectChecksum(retraction)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			null,
			StepsCountDomainOwnerRevisionEntity(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = StepsCountDomainReceiptIntegrity.ambientFactScopeIdentity(
					retraction.logicalFactId,
				),
				ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
					retraction.writerId,
					retraction.writerVersion,
					retraction.logicalFactId,
				),
				ownerRevision = retraction.semanticRevision,
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
				receiptIdentity = null,
				ownerEffectChecksum = retraction.effectChecksum,
				linkedAtMs = retraction.appliedAtMs,
			),
		)
	}

	fun readOwners(
		keys: List<StepsCountDomainOwnerLookupKey>,
		limit: Int = MAX_OWNER_LOOKUP,
	): StepsCountDomainOwnerRead {
		val distinctKeys = keys.distinct()
		if (limit !in 1..MAX_OWNER_LOOKUP || distinctKeys.size > limit) {
			return StepsCountDomainOwnerRead.Overflow
		}
		val sqlite = database.openHelper.readableDatabase
		if (!sqlite.hasStepsCountDomainSchema()) {
			return StepsCountDomainOwnerRead.SchemaUnavailable
		}
		return try {
			val owners = distinctKeys.chunked(OWNER_QUERY_CHUNK).flatMap { chunk ->
				sqlite.queryOwnerChunk(chunk)
			}
			if (owners.size > limit) return StepsCountDomainOwnerRead.Overflow
			val ownersByKey = owners.associateBy {
				StepsCountDomainOwnerLookupKey(
					it.ownerKind,
					it.ownerIdentity,
					it.ownerRevision,
				)
			}
			if (ownersByKey.size != owners.size) return StepsCountDomainOwnerRead.Unverifiable
			val latestRevisions = distinctKeys
				.map { StepsCountDomainOwnerLineageKey(it.ownerKind, it.ownerIdentity) }
				.distinct()
				.chunked(LATEST_QUERY_CHUNK)
				.flatMap { chunk -> sqlite.queryLatestOwnerChunk(chunk) }
				.toMap()
			if (owners.any { owner ->
				StepsCountDomainOwnerLineageKey(owner.ownerKind, owner.ownerIdentity) !in
					latestRevisions
			}) {
				return StepsCountDomainOwnerRead.Unverifiable
			}
			val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity)
				.distinct()
			if (receiptIds.size > limit) return StepsCountDomainOwnerRead.Overflow
			val receipts = receiptIds.chunked(RECEIPT_QUERY_CHUNK).flatMap { chunk ->
				sqlite.queryReceiptChunk(chunk)
			}
			if (receipts.size != receiptIds.size) return StepsCountDomainOwnerRead.Unverifiable
			val receiptsById = receipts.associateBy(StepsCountDomainReceiptEntity::receiptIdentity)
			StepsCountDomainOwnerRead.Ready(
				distinctKeys.mapNotNull { key ->
					ownersByKey[key]?.let { owner ->
						key to StepsCountDomainStoredOwner(
							owner,
							owner.receiptIdentity?.let(receiptsById::get),
						)
					}
				}.toMap(),
				latestRevisions,
			)
		} catch (_: Exception) {
			StepsCountDomainOwnerRead.Unverifiable
		}
	}

	private fun appendBoundOwner(
		receipt: StepsCountDomainReceiptEntity,
		scopeIdentity: String,
		ownerEffectChecksum: String,
		linkedAtMs: Long,
	): StepsCountDomainWriteResult {
		if (receipt.scopeIdentity != scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			receipt,
			StepsCountDomainOwnerRevisionEntity(
			ownerKind = receipt.ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = receipt.ownerIdentity,
			ownerRevision = receipt.ownerRevision,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
			receiptIdentity = receipt.receiptIdentity,
			ownerEffectChecksum = ownerEffectChecksum,
			linkedAtMs = linkedAtMs,
		),
		)
	}

	private fun appendOwner(
		receipt: StepsCountDomainReceiptEntity?,
		owner: StepsCountDomainOwnerRevisionEntity,
	): StepsCountDomainWriteResult {
		val sqlite = database.openHelper.writableDatabase
		if (!sqlite.hasStepsCountDomainSchema()) {
			return StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
		}
		val latest = sqlite.queryLatestOwner(owner.ownerKind, owner.ownerIdentity)
		val exact = sqlite.queryOwner(
			owner.ownerKind,
			owner.ownerIdentity,
			owner.ownerRevision,
		)
		if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
			exact != latest
		) {
			return StepsCountDomainWriteResult.TERMINALLY_RETRACTED
		}
		if (exact != null) {
			val storedReceipt = exact.receiptIdentity?.let(sqlite::queryReceipt)
			return if (exact == owner && storedReceipt == receipt) {
				StepsCountDomainWriteResult.EXACT_REPLAY
			} else {
				StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		if (latest != null && latest.scopeIdentity != owner.scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val revisionIsValid = if (
			owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
		) {
			latest == null || owner.ownerRevision > latest.ownerRevision
		} else {
			owner.ownerRevision == (latest?.ownerRevision ?: 0L) + 1L
		}
		if (!revisionIsValid) {
			return StepsCountDomainWriteResult.REVISION_GAP
		}
		if (receipt != null && latest != null &&
			owner.ownerKind in FACT_OWNER_KINDS
		) {
			val previousReceipt = latest.receiptIdentity?.let(sqlite::queryReceipt)
				?: return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			if (!receipt.hasSameImmutableDomain(previousReceipt)) {
				return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		if (receipt != null) {
			sqlite.execSQL(INSERT_RECEIPT_SQL, receipt.bindArguments())
			if (sqlite.queryReceipt(receipt.receiptIdentity) != receipt) {
				return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		sqlite.execSQL(INSERT_OWNER_SQL, owner.bindArguments())
		return if (sqlite.queryOwner(
				owner.ownerKind,
				owner.ownerIdentity,
				owner.ownerRevision,
			) == owner
		) {
			StepsCountDomainWriteResult.INSERTED
		} else {
			StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
	}

	private fun loadExactOwner(
		key: StepsCountDomainOwnerLookupKey,
	): StepsCountDomainStoredOwner? {
		val sqlite = database.openHelper.writableDatabase
		if (!sqlite.hasStepsCountDomainSchema()) return null
		val owner = sqlite.queryOwner(
			key.ownerKind,
			key.ownerIdentity,
			key.ownerRevision,
		) ?: return null
		return StepsCountDomainStoredOwner(
			owner,
			owner.receiptIdentity?.let(sqlite::queryReceipt),
		)
	}

	private fun hasSchema(): Boolean =
		database.openHelper.writableDatabase.hasStepsCountDomainSchema()

	@Suppress("LongParameterList")
	private fun nativeReceipt(
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		providerDomain: String,
		sourceInstanceId: String,
		registrationGeneration: Long,
		collectedDataEpoch: Long,
		authorityRevision: Long,
		authorityFingerprint: String,
		coverageKind: String,
		coverageVersion: Int,
		effectChecksum: String,
	): StepsCountDomainReceiptEntity {
		val providerIdentity =
			StepsCountDomainReceiptIntegrity.nativeProviderDomainIdentity(providerDomain)
		val sourceIdentity =
			StepsCountDomainReceiptIntegrity.nativeSourceInstanceIdentity(sourceInstanceId)
		val domainIdentity = StepsCountDomainReceiptIntegrity.nativeDomainIdentity(
			providerDomain,
			sourceInstanceId,
		)
		val countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = domainIdentity,
			providerDomainIdentity = providerIdentity,
			sourceInstanceIdentity = sourceIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = countDomainVersion,
			effectChecksum = effectChecksum,
		)
		return StepsCountDomainReceiptEntity(
			receiptIdentity = receiptIdentity,
			domainIdentity = domainIdentity,
			providerDomainIdentity = providerIdentity,
			sourceInstanceIdentity = sourceIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = countDomainVersion,
			effectChecksum = effectChecksum,
		)
	}

	private fun nativeReceiptFrom(
		source: StepsCountDomainReceiptEntity,
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		coverageKind: String,
		coverageVersion: Int,
		effectChecksum: String,
	): StepsCountDomainReceiptEntity {
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = source.domainIdentity,
			providerDomainIdentity = source.providerDomainIdentity,
			sourceInstanceIdentity = source.sourceInstanceIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = source.registrationGeneration,
			collectedDataEpoch = source.collectedDataEpoch,
			authorityRevision = source.authorityRevision,
			authorityFingerprint = source.authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = source.countDomainVersion,
			effectChecksum = effectChecksum,
		)
		return StepsCountDomainReceiptEntity(
			receiptIdentity = receiptIdentity,
			domainIdentity = source.domainIdentity,
			providerDomainIdentity = source.providerDomainIdentity,
			sourceInstanceIdentity = source.sourceInstanceIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = source.registrationGeneration,
			collectedDataEpoch = source.collectedDataEpoch,
			authorityRevision = source.authorityRevision,
			authorityFingerprint = source.authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = source.countDomainVersion,
			effectChecksum = effectChecksum,
		)
	}

	private fun StepsCountDomainStoredOwner.isAuthentic(): Boolean {
		val receipt = receipt ?: return false
		return owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
			owner.receiptIdentity == receipt.receiptIdentity &&
			owner.ownerKind == receipt.ownerKind &&
			owner.scopeIdentity == receipt.scopeIdentity &&
			owner.ownerIdentity == receipt.ownerIdentity &&
			owner.ownerRevision == receipt.ownerRevision &&
			owner.ownerEffectChecksum == receipt.effectChecksum &&
			StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt)
	}

	private companion object {
		const val COMPLETENESS_COVERAGE_VERSION = 1
		const val MAX_OWNER_LOOKUP = 512
		const val OWNER_QUERY_CHUNK = 100
		const val LATEST_QUERY_CHUNK = 200
		const val RECEIPT_QUERY_CHUNK = 400
		val FACT_OWNER_KINDS = setOf(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
		)
		val SUCCESSFUL_OR_UNAVAILABLE = setOf(
			StepsCountDomainWriteResult.INSERTED,
			StepsCountDomainWriteResult.EXACT_REPLAY,
			StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE,
		)
		const val INSERT_RECEIPT_SQL =
			"INSERT OR IGNORE INTO steps_count_domain_receipt (" +
				"receipt_identity, domain_identity, provider_domain_identity, " +
					"source_instance_identity, owner_kind, scope_identity, owner_identity, owner_revision, " +
				"registration_generation, collected_data_epoch, authority_revision, " +
				"authority_fingerprint, coverage_kind, coverage_version, count_domain_version, " +
					"effect_checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
		const val INSERT_OWNER_SQL =
			"INSERT OR IGNORE INTO steps_count_domain_owner_revision (" +
				"owner_kind, scope_identity, owner_identity, owner_revision, operation, " +
				"receipt_identity, owner_effect_checksum, linked_at_ms) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
	}
}

private fun StepsCountDomainReceiptEntity.bindArguments(): Array<Any?> = arrayOf(
	receiptIdentity,
	domainIdentity,
	providerDomainIdentity,
	sourceInstanceIdentity,
	ownerKind,
	scopeIdentity,
	ownerIdentity,
	ownerRevision,
	registrationGeneration,
	collectedDataEpoch,
	authorityRevision,
	authorityFingerprint,
	coverageKind,
	coverageVersion,
	countDomainVersion,
	effectChecksum,
)

private fun StepsCountDomainOwnerRevisionEntity.bindArguments(): Array<Any?> = arrayOf(
	ownerKind,
	scopeIdentity,
	ownerIdentity,
	ownerRevision,
	operation,
	receiptIdentity,
	ownerEffectChecksum,
	linkedAtMs,
)

private fun StepsCountDomainReceiptEntity.hasSameImmutableDomain(
	other: StepsCountDomainReceiptEntity,
): Boolean = domainIdentity == other.domainIdentity &&
	providerDomainIdentity == other.providerDomainIdentity &&
	sourceInstanceIdentity == other.sourceInstanceIdentity &&
	registrationGeneration == other.registrationGeneration &&
	collectedDataEpoch == other.collectedDataEpoch &&
	countDomainVersion == other.countDomainVersion

private fun SupportSQLiteDatabase.hasStepsCountDomainSchema(): Boolean {
	val tableCount = query(
		"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN (?, ?)",
		arrayOf(StepsCountDomainSchema.RECEIPT_TABLE, StepsCountDomainSchema.OWNER_TABLE),
	).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 2 }
	if (!tableCount) return false
	return query(
		"SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN (?, ?)",
		arrayOf(
			StepsCountDomainSchema.AMBIENT_NO_RESURRECTION_TRIGGER,
			StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER,
		),
	).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 2 }
}

private fun SupportSQLiteDatabase.queryOwner(
	ownerKind: String,
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainOwnerRevisionEntity? =
	query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_kind = ? " +
			"AND owner_identity = ? AND owner_revision = ? LIMIT 1",
		arrayOf(ownerKind, ownerIdentity, ownerRevision),
	).use { cursor ->
		if (cursor.moveToFirst()) cursor.toStepsCountDomainOwner() else null
	}

private fun SupportSQLiteDatabase.queryLatestOwner(
	ownerKind: String,
	ownerIdentity: String,
): StepsCountDomainOwnerRevisionEntity? =
	query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_kind = ? " +
			"AND owner_identity = ? ORDER BY owner_revision DESC LIMIT 1",
		arrayOf(ownerKind, ownerIdentity),
	).use { cursor ->
		if (cursor.moveToFirst()) cursor.toStepsCountDomainOwner() else null
	}

private fun SupportSQLiteDatabase.queryReceipt(
	receiptIdentity: String,
): StepsCountDomainReceiptEntity? =
	query(
		"SELECT * FROM steps_count_domain_receipt WHERE receipt_identity = ? LIMIT 1",
		arrayOf(receiptIdentity),
	).use { cursor ->
		if (cursor.moveToFirst()) cursor.toStepsCountDomainReceipt() else null
	}

private fun SupportSQLiteDatabase.queryOwnerChunk(
	keys: List<StepsCountDomainOwnerLookupKey>,
): List<StepsCountDomainOwnerRevisionEntity> {
	if (keys.isEmpty()) return emptyList()
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity, it.ownerRevision) }
	return query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE $predicate " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT ${keys.size + 1}",
		arguments.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.toStepsCountDomainOwner())
		}
	}
}

private fun SupportSQLiteDatabase.queryReceiptChunk(
	identities: List<String>,
): List<StepsCountDomainReceiptEntity> {
	if (identities.isEmpty()) return emptyList()
	val placeholders = List(identities.size) { "?" }.joinToString()
	return query(
		"SELECT * FROM steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"ORDER BY receipt_identity LIMIT ${identities.size + 1}",
		identities.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.toStepsCountDomainReceipt())
		}
	}
}

private fun SupportSQLiteDatabase.queryLatestOwnerChunk(
	keys: List<StepsCountDomainOwnerLineageKey>,
): List<Pair<StepsCountDomainOwnerLineageKey, Long>> {
	if (keys.isEmpty()) return emptyList()
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity) }
	return query(
		"SELECT owner_kind, owner_identity, MAX(owner_revision) AS latest_revision " +
			"FROM steps_count_domain_owner_revision WHERE $predicate " +
			"GROUP BY owner_kind, owner_identity LIMIT ${keys.size + 1}",
		arguments.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					StepsCountDomainOwnerLineageKey(
						cursor.string("owner_kind"),
						cursor.string("owner_identity"),
					) to cursor.long("latest_revision"),
				)
			}
		}
	}
}

private fun android.database.Cursor.toStepsCountDomainOwner() =
	StepsCountDomainOwnerRevisionEntity(
		ownerKind = string("owner_kind"),
		scopeIdentity = string("scope_identity"),
		ownerIdentity = string("owner_identity"),
		ownerRevision = long("owner_revision"),
		operation = string("operation"),
		receiptIdentity = nullableString("receipt_identity"),
		ownerEffectChecksum = string("owner_effect_checksum"),
		linkedAtMs = long("linked_at_ms"),
	)

private fun android.database.Cursor.toStepsCountDomainReceipt() =
	StepsCountDomainReceiptEntity(
		receiptIdentity = string("receipt_identity"),
		domainIdentity = string("domain_identity"),
		providerDomainIdentity = string("provider_domain_identity"),
		sourceInstanceIdentity = string("source_instance_identity"),
		ownerKind = string("owner_kind"),
		scopeIdentity = string("scope_identity"),
		ownerIdentity = string("owner_identity"),
		ownerRevision = long("owner_revision"),
		registrationGeneration = long("registration_generation"),
		collectedDataEpoch = long("collected_data_epoch"),
		authorityRevision = long("authority_revision"),
		authorityFingerprint = string("authority_fingerprint"),
		coverageKind = string("coverage_kind"),
		coverageVersion = long("coverage_version").toInt(),
		countDomainVersion = long("count_domain_version").toInt(),
		effectChecksum = string("effect_checksum"),
	)

private fun android.database.Cursor.string(column: String): String =
	getString(getColumnIndexOrThrow(column))

private fun android.database.Cursor.nullableString(column: String): String? {
	val index = getColumnIndexOrThrow(column)
	return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.long(column: String): Long =
	getLong(getColumnIndexOrThrow(column))
