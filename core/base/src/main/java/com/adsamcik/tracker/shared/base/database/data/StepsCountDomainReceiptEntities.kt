package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest

/**
 * Immutable opaque evidence for one producer-owned Steps count domain.
 *
 * Raw provider/source-instance identifiers are inputs to one-way identities only and are never
 * retained here. Authority revision, coverage semantics, version, and owner effect are bound into
 * [receiptIdentity] so a receipt cannot be moved to another correction or completeness state.
 */
@Entity(
	tableName = "steps_count_domain_receipt",
	indices = [
		Index(
			value = [
				"domain_identity",
				"collected_data_epoch",
				"count_domain_version",
			],
			name = "idx_steps_count_domain_receipt_compatibility",
		),
		Index(
			value = ["owner_kind", "owner_identity", "owner_revision"],
			unique = true,
			name = "idx_steps_count_domain_receipt_owner",
		),
	],
	primaryKeys = ["receipt_identity"],
)
data class StepsCountDomainReceiptEntity(
	@ColumnInfo(name = "receipt_identity") val receiptIdentity: String,
	@ColumnInfo(name = "domain_identity") val domainIdentity: String,
	@ColumnInfo(name = "provider_domain_identity") val providerDomainIdentity: String,
	@ColumnInfo(name = "source_instance_identity") val sourceInstanceIdentity: String,
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "scope_identity") val scopeIdentity: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "authority_revision") val authorityRevision: Long,
	@ColumnInfo(name = "authority_fingerprint") val authorityFingerprint: String,
	@ColumnInfo(name = "coverage_kind") val coverageKind: String,
	@ColumnInfo(name = "coverage_version") val coverageVersion: Int,
	@ColumnInfo(name = "count_domain_version") val countDomainVersion: Int,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(StepsCountDomainReceiptIntegrity.isOpaque(receiptIdentity))
		require(StepsCountDomainReceiptIntegrity.isOpaque(domainIdentity))
		require(StepsCountDomainReceiptIntegrity.isOpaque(providerDomainIdentity))
		require(StepsCountDomainReceiptIntegrity.isOpaque(sourceInstanceIdentity))
		require(
			domainIdentity == StepsCountDomainReceiptIntegrity.nativeDomainIdentityFromOpaque(
				providerDomainIdentity,
				sourceInstanceIdentity,
			),
		)
		require(ownerKind in StepsCountDomainOwnerRevisionEntity.BINDABLE_OWNER_KINDS)
		require(StepsCountDomainReceiptIntegrity.isOpaque(scopeIdentity))
		require(StepsCountDomainReceiptIntegrity.isOpaque(ownerIdentity))
		require(ownerRevision > 0L)
		require(registrationGeneration > 0L)
		require(collectedDataEpoch >= 0L)
		require(authorityRevision > 0L)
		require(StepsCountDomainReceiptIntegrity.isDigest(authorityFingerprint))
		require(coverageKind in COVERAGE_KINDS)
		require(coverageVersion > 0)
		require(countDomainVersion == CURRENT_COUNT_DOMAIN_VERSION)
		require(StepsCountDomainReceiptIntegrity.isDigest(effectChecksum))
		require(receiptIdentity == StepsCountDomainReceiptIntegrity.receiptIdentity(this))
		require(coverageKind in coverageKindsFor(ownerKind))
	}

	companion object {
		const val COVERAGE_ADMITTED_WINDOW = "ADMITTED_WINDOW"
		const val COVERAGE_BASELINE = "BASELINE"
		const val COVERAGE_COVERED = "COVERED"
		const val COVERAGE_RESET_GAP = "RESET_GAP"
		const val COVERAGE_PARTIAL = "PARTIAL"
		const val COVERAGE_COMPLETE_RUN = "COMPLETE_RUN"
		const val COVERAGE_AMBIENT_AGGREGATE = "AMBIENT_AGGREGATE"
		const val CURRENT_COUNT_DOMAIN_VERSION = 1

		private val SESSION_FACT_COVERAGE = setOf(
			COVERAGE_BASELINE,
			COVERAGE_COVERED,
			COVERAGE_RESET_GAP,
			COVERAGE_PARTIAL,
		)
		private val COVERAGE_KINDS = SESSION_FACT_COVERAGE + setOf(
			COVERAGE_ADMITTED_WINDOW,
			COVERAGE_COMPLETE_RUN,
			COVERAGE_AMBIENT_AGGREGATE,
		)

		private fun coverageKindsFor(ownerKind: String): Set<String> = when (ownerKind) {
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL ->
				setOf(COVERAGE_ADMITTED_WINDOW)
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT ->
				SESSION_FACT_COVERAGE
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS ->
				setOf(COVERAGE_COMPLETE_RUN)
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT ->
				setOf(COVERAGE_AMBIENT_AGGREGATE)
			else -> emptySet()
		}
	}
}

/**
 * Append-only exact owner membership.
 *
 * A correction appends the next owner revision. A RETRACT is terminal and deliberately has no
 * receipt. UNPROVEN records that terminal completeness existed without enough native receipt
 * evidence; it never upgrades itself into compatibility authority.
 */
@Entity(
	tableName = "steps_count_domain_owner_revision",
	primaryKeys = ["owner_kind", "owner_identity", "owner_revision"],
	foreignKeys = [
		ForeignKey(
			entity = StepsCountDomainReceiptEntity::class,
			parentColumns = ["receipt_identity"],
			childColumns = ["receipt_identity"],
			onDelete = ForeignKey.RESTRICT,
			onUpdate = ForeignKey.NO_ACTION,
			deferred = true,
		),
	],
	indices = [
		Index(
			value = ["owner_kind", "scope_identity", "owner_identity", "owner_revision"],
			name = "idx_steps_count_domain_owner_scope",
		),
		Index(
			value = ["receipt_identity"],
			name = "idx_steps_count_domain_owner_receipt",
		),
	],
)
data class StepsCountDomainOwnerRevisionEntity(
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "scope_identity") val scopeIdentity: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
	@ColumnInfo(name = "operation") val operation: String,
	@ColumnInfo(name = "receipt_identity") val receiptIdentity: String?,
	@ColumnInfo(name = "owner_effect_checksum") val ownerEffectChecksum: String,
	@ColumnInfo(name = "linked_at_ms") val linkedAtMs: Long,
) {
	init {
		require(ownerKind in OWNER_KINDS)
		require(StepsCountDomainReceiptIntegrity.isOpaque(scopeIdentity))
		require(StepsCountDomainReceiptIntegrity.isOpaque(ownerIdentity))
		require(ownerRevision > 0L)
		require(operation in OPERATIONS)
		require(StepsCountDomainReceiptIntegrity.isDigest(ownerEffectChecksum))
		require(linkedAtMs >= 0L)
		when (operation) {
			OPERATION_BIND -> require(
				ownerKind in BINDABLE_OWNER_KINDS &&
					StepsCountDomainReceiptIntegrity.isOpaque(receiptIdentity),
			)
			OPERATION_RETRACT -> require(
				ownerKind in RETRACTABLE_OWNER_KINDS && receiptIdentity == null,
			)
			OPERATION_UNPROVEN -> require(
				ownerKind == OWNER_SESSION_COMPLETENESS && receiptIdentity == null,
			)
		}
	}

	companion object {
		const val OWNER_SESSION_WAL = "SESSION_WAL"
		const val OWNER_SESSION_FACT = "SESSION_FACT"
		const val OWNER_SESSION_COMPLETENESS = "SESSION_COMPLETENESS"
		const val OWNER_AMBIENT_FACT = "AMBIENT_FACT"
		const val OPERATION_BIND = "BIND"
		const val OPERATION_RETRACT = "RETRACT"
		const val OPERATION_UNPROVEN = "UNPROVEN"

		val BINDABLE_OWNER_KINDS = setOf(
			OWNER_SESSION_WAL,
			OWNER_SESSION_FACT,
			OWNER_SESSION_COMPLETENESS,
			OWNER_AMBIENT_FACT,
		)
		private val RETRACTABLE_OWNER_KINDS = setOf(
			OWNER_SESSION_FACT,
			OWNER_AMBIENT_FACT,
		)
		private val OWNER_KINDS = BINDABLE_OWNER_KINDS
		private val OPERATIONS = setOf(OPERATION_BIND, OPERATION_RETRACT, OPERATION_UNPROVEN)
	}
}

/** Stable identities and checksums shared by the native Steps receipt producers and readers. */
object StepsCountDomainReceiptIntegrity {
	fun nativeProviderDomainIdentity(providerDomain: String): String {
		require(providerDomain.isNotBlank())
		return opaqueDigest("steps-count-provider-domain-v1", providerDomain)
	}

	fun nativeSourceInstanceIdentity(sourceInstanceId: String): String {
		require(sourceInstanceId.isNotBlank())
		return opaqueDigest("steps-count-source-instance-v1", sourceInstanceId)
	}

	fun nativeDomainIdentity(
		providerDomain: String,
		sourceInstanceId: String,
	): String = nativeDomainIdentityFromOpaque(
		nativeProviderDomainIdentity(providerDomain),
		nativeSourceInstanceIdentity(sourceInstanceId),
	)

	fun nativeDomainIdentityFromOpaque(
		providerDomainIdentity: String,
		sourceInstanceIdentity: String,
	): String {
		require(isOpaque(providerDomainIdentity))
		require(isOpaque(sourceInstanceIdentity))
		return opaqueDigest(
			"steps-count-native-domain-v1",
			providerDomainIdentity,
			sourceInstanceIdentity,
		)
	}

	/**
	 * Future portable extension point only.
	 *
	 * This derives an opaque domain value from already-authenticated portable evidence. It does not
	 * create a receipt, persist an owner, or grant native/import authority.
	 */
	fun portableDomainIdentity(
		portableNamespace: String,
		portableOpaqueDomain: String,
	): String {
		require(portableNamespace.isNotBlank())
		require(isOpaque(portableOpaqueDomain))
		return opaqueDigest(
			"steps-count-portable-domain-v1",
			portableNamespace,
			portableOpaqueDomain,
		)
	}

	fun sessionRunScopeIdentity(
		logicalTrackingId: String,
		serviceRunId: String,
	): String {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		return opaqueDigest("steps-count-session-run-v1", logicalTrackingId, serviceRunId)
	}

	fun sessionWalOwnerIdentity(admissionOrdinal: Long, eventId: String): String {
		require(admissionOrdinal > 0L)
		require(eventId.isNotBlank())
		return opaqueDigest("steps-count-session-wal-owner-v1", admissionOrdinal, eventId)
	}

	fun sessionFactOwnerIdentity(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
	): String {
		require(writerId.isNotBlank())
		require(writerVersion > 0)
		require(logicalFactId.isNotBlank())
		return opaqueDigest(
			"steps-count-session-fact-owner-v1",
			writerId,
			writerVersion,
			logicalFactId,
		)
	}

	fun sessionCompletenessOwnerIdentity(
		logicalTrackingId: String,
		serviceRunId: String,
		sourceInstanceId: String,
		registrationGeneration: Long,
	): String {
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration >= 0L)
		return opaqueDigest(
			"steps-count-session-completeness-owner-v1",
			logicalTrackingId,
			serviceRunId,
			sourceInstanceId,
			registrationGeneration,
		)
	}

	fun completenessOwnerRevision(row: SourceSessionCompletenessEntity): Long =
		Math.addExact(row.updatedAtMs, 1L)

	fun ambientFactScopeIdentity(logicalFactId: String): String {
		require(isOpaque(logicalFactId))
		return logicalFactId
	}

	fun ambientFactOwnerIdentity(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
	): String {
		require(writerId.isNotBlank())
		require(writerVersion > 0)
		require(isOpaque(logicalFactId))
		require(writerId == AmbientStepsFactRevisionEntity.WRITER_ID)
		require(writerVersion == AmbientStepsFactRevisionEntity.WRITER_VERSION)
		return logicalFactId
	}

	fun completenessEffectChecksum(row: SourceSessionCompletenessEntity): String = digest(
		"steps-count-completeness-effect-v1",
		row.logicalTrackingId,
		row.serviceRunId,
		row.sourceKind,
		row.sourceInstanceId,
		row.registrationGeneration,
		row.lastAdmissionOrdinal,
		row.lastSourceSequence,
		row.appDrainComplete,
		row.providerCoverage,
		row.stopStatus,
		row.unresolvedSequenceStart,
		row.unresolvedSequenceEnd,
		row.updatedAtMs,
	)

	fun receiptIdentity(receipt: StepsCountDomainReceiptEntity): String = receiptIdentity(
		domainIdentity = receipt.domainIdentity,
		providerDomainIdentity = receipt.providerDomainIdentity,
		sourceInstanceIdentity = receipt.sourceInstanceIdentity,
		ownerKind = receipt.ownerKind,
		scopeIdentity = receipt.scopeIdentity,
		ownerIdentity = receipt.ownerIdentity,
		ownerRevision = receipt.ownerRevision,
		registrationGeneration = receipt.registrationGeneration,
		collectedDataEpoch = receipt.collectedDataEpoch,
		authorityRevision = receipt.authorityRevision,
		authorityFingerprint = receipt.authorityFingerprint,
		coverageKind = receipt.coverageKind,
		coverageVersion = receipt.coverageVersion,
		countDomainVersion = receipt.countDomainVersion,
		effectChecksum = receipt.effectChecksum,
	)

	@Suppress("LongParameterList")
	fun receiptIdentity(
		domainIdentity: String,
		providerDomainIdentity: String,
		sourceInstanceIdentity: String,
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		registrationGeneration: Long,
		collectedDataEpoch: Long,
		authorityRevision: Long,
		authorityFingerprint: String,
		coverageKind: String,
		coverageVersion: Int,
		countDomainVersion: Int,
		effectChecksum: String,
	): String = opaqueDigest(
		"steps-count-domain-receipt-v1",
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

	fun hasValidReceipt(receipt: StepsCountDomainReceiptEntity): Boolean =
		receipt.receiptIdentity == receiptIdentity(receipt)

	internal fun isOpaque(value: String?): Boolean =
		value != null && OPAQUE_IDENTITY.matches(value)

	internal fun isDigest(value: String?): Boolean =
		value != null && DIGEST.matches(value)

	private fun opaqueDigest(vararg values: Any?): String = "sha256:${digest(*values)}"

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private val OPAQUE_IDENTITY = Regex("sha256:[0-9a-f]{64}")
	private val DIGEST = Regex("[0-9a-f]{64}")
}
