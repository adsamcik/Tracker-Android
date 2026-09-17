package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import java.security.MessageDigest

/**
 * Immutable opaque evidence for one producer-owned Steps count domain.
 *
 * The provider-issued [domainIdentity] is independent of QoS configuration, report latency,
 * registration generation, and purpose-specific ownership. Authority revision, coverage semantics,
 * completion evidence, version, and owner effect are bound into [receiptIdentity].
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
	@ColumnInfo(name = "completion_evidence_checksum")
	val completionEvidenceChecksum: String?,
) {
	init {
		require(StepsCountDomainReceiptIntegrity.isOpaque(receiptIdentity))
		require(StepsCountDomainReceiptIntegrity.isOpaque(domainIdentity))
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
		require(
			(ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS) ==
				StepsCountDomainReceiptIntegrity.isDigest(completionEvidenceChecksum),
		)
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
 * receipt. UNPROVEN permits only byte-exact replay or a later source deletion RETRACT; it never
 * upgrades into compatibility authority.
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
		Index(
			value = ["operation", "linked_at_ms", "owner_kind", "owner_identity"],
			name = "idx_steps_count_domain_owner_terminal_age",
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
				ownerKind in BINDABLE_OWNER_KINDS && receiptIdentity == null,
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

/** Exact retirement evidence retained only for a session-completeness owner revision. */
@Entity(
	tableName = "steps_count_domain_completeness_marker",
	primaryKeys = ["owner_identity", "owner_revision"],
	foreignKeys = [
		ForeignKey(
			entity = StepsCountDomainOwnerRevisionEntity::class,
			parentColumns = ["owner_kind", "owner_identity", "owner_revision"],
			childColumns = ["owner_kind", "owner_identity", "owner_revision"],
			onDelete = ForeignKey.CASCADE,
			onUpdate = ForeignKey.NO_ACTION,
			deferred = true,
		),
	],
	indices = [
		Index(
			value = ["owner_kind", "owner_identity", "owner_revision"],
			unique = true,
			name = "idx_steps_count_domain_completeness_owner",
		),
	],
)
data class StepsCountDomainCompletenessMarkerEntity(
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
	@ColumnInfo(name = "terminal_state") val terminalState: String,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long?,
	@ColumnInfo(name = "provider_flush_outcome") val providerFlushOutcome: String,
	@ColumnInfo(name = "registration_removal_outcome") val registrationRemovalOutcome: String,
	@ColumnInfo(name = "registration_timeline_checksum") val registrationTimelineChecksum: String,
	@ColumnInfo(name = "evidence_checksum") val evidenceChecksum: String,
) {
	init {
		require(ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS)
		require(StepsCountDomainReceiptIntegrity.isOpaque(ownerIdentity))
		require(ownerRevision > 0L)
		require(terminalState in TERMINAL_STATES)
		require((lastAdmissionOrdinal == null) == (lastSourceSequence == null))
		require(lastAdmissionOrdinal == null || lastAdmissionOrdinal > 0L)
		require(lastSourceSequence == null || lastSourceSequence >= 0L)
		require(providerFlushOutcome.isNotBlank())
		require(registrationRemovalOutcome.isNotBlank())
		require(StepsCountDomainReceiptIntegrity.isDigest(registrationTimelineChecksum))
		require(StepsCountDomainReceiptIntegrity.isDigest(evidenceChecksum))
		require(
			evidenceChecksum == StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(this),
		)
		if (terminalState == STATE_COMPLETE) {
			require(lastAdmissionOrdinal != null && lastSourceSequence != null)
			require(providerFlushOutcome in COMPLETE_FLUSH_OUTCOMES)
			require(registrationRemovalOutcome in COMPLETE_REMOVAL_OUTCOMES)
		}
	}

	companion object {
		const val STATE_COMPLETE = "COMPLETE"
		const val STATE_UNPROVEN = "UNPROVEN"
		private val TERMINAL_STATES = setOf(STATE_COMPLETE, STATE_UNPROVEN)
		private val COMPLETE_FLUSH_OUTCOMES =
			setOf("COMPLETE", "NOT_SUPPORTED", "NOT_REQUESTED")
		private val COMPLETE_REMOVAL_OUTCOMES = setOf("REMOVED", "NOT_REGISTERED")
	}
}

/** Required sentinel so partially installed e500-era tables can never activate the corrected proof. */
@Entity(tableName = "steps_count_domain_schema_marker")
data class StepsCountDomainSchemaMarkerEntity(
	@PrimaryKey @ColumnInfo(name = "id") val id: Int = REQUIRED_ID,
	@ColumnInfo(name = "contract_version") val contractVersion: Int,
	@ColumnInfo(name = "token_semantics") val tokenSemantics: String,
	@ColumnInfo(name = "terminal_unproven") val terminalUnproven: Boolean,
) {
	init {
		require(id == REQUIRED_ID)
		require(contractVersion == REQUIRED_CONTRACT_VERSION)
		require(tokenSemantics == REQUIRED_TOKEN_SEMANTICS)
		require(terminalUnproven)
	}

	companion object {
		const val REQUIRED_ID = 1
		const val REQUIRED_CONTRACT_VERSION = 2
		const val REQUIRED_TOKEN_SEMANTICS = "PROVIDER_COUNTER_EPOCH_V1"
	}
}

/** Stable identities and checksums shared by the native Steps receipt producers and readers. */
object StepsCountDomainReceiptIntegrity {
	fun counterDomainIdentity(token: StepsCounterDomainToken): String = token.encoded

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
		completionEvidenceChecksum = receipt.completionEvidenceChecksum,
	)

	@Suppress("LongParameterList")
	fun receiptIdentity(
		domainIdentity: String,
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
		completionEvidenceChecksum: String?,
	): String = opaqueDigest(
		"steps-count-domain-receipt-v2",
		domainIdentity,
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
		completionEvidenceChecksum,
	)

	fun hasValidReceipt(receipt: StepsCountDomainReceiptEntity): Boolean =
		receipt.receiptIdentity == receiptIdentity(receipt)

	fun completenessMarkerChecksum(
		marker: StepsCountDomainCompletenessMarkerEntity,
	): String = completenessMarkerChecksum(
		ownerKind = marker.ownerKind,
		ownerIdentity = marker.ownerIdentity,
		ownerRevision = marker.ownerRevision,
		terminalState = marker.terminalState,
		lastAdmissionOrdinal = marker.lastAdmissionOrdinal,
		lastSourceSequence = marker.lastSourceSequence,
		providerFlushOutcome = marker.providerFlushOutcome,
		registrationRemovalOutcome = marker.registrationRemovalOutcome,
		registrationTimelineChecksum = marker.registrationTimelineChecksum,
	)

	@Suppress("LongParameterList")
	fun completenessMarkerChecksum(
		ownerKind: String,
		ownerIdentity: String,
		ownerRevision: Long,
		terminalState: String,
		lastAdmissionOrdinal: Long?,
		lastSourceSequence: Long?,
		providerFlushOutcome: String,
		registrationRemovalOutcome: String,
		registrationTimelineChecksum: String,
	): String = digest(
		"steps-count-completeness-marker-v1",
		ownerKind,
		ownerIdentity,
		ownerRevision,
		terminalState,
		lastAdmissionOrdinal,
		lastSourceSequence,
		providerFlushOutcome,
		registrationRemovalOutcome,
		registrationTimelineChecksum,
	)

	fun registrationTimelineChecksum(
		rows: Collection<SourceSessionCompletenessEntity>,
	): String {
		val values = buildList<Any?> {
			add("steps-count-registration-timeline-v1")
			rows.sortedBy(SourceSessionCompletenessEntity::registrationGeneration).forEach { row ->
				add(row.logicalTrackingId)
				add(row.serviceRunId)
				add(row.sourceKind)
				add(row.sourceInstanceId)
				add(row.registrationGeneration)
				add(row.lastAdmissionOrdinal)
				add(row.lastSourceSequence)
				add(row.appDrainComplete)
				add(row.providerCoverage)
				add(row.stopStatus)
				add(row.unresolvedSequenceStart)
				add(row.unresolvedSequenceEnd)
				add(row.updatedAtMs)
			}
		}
		return digest(*values.toTypedArray())
	}

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
