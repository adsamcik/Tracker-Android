package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface AppliedPlanRead {
	data class Available(val plan: AcquisitionPlanRevision) : AppliedPlanRead
	data object Missing : AppliedPlanRead
	data class Invalid(val code: String) : AppliedPlanRead
}

@Singleton
class RoomSourcePlanStore @Inject constructor(
	private val database: AppDatabase,
	private val codec: SourcePlanCodec,
) {
	suspend fun persistDesired(plan: AcquisitionPlanRevision, status: DesiredPlanStatus) {
		database.withTransaction {
			val dao = database.sourcePlanStateDao()
			val existing = dao.revision(plan.revision)
			val encoded = plan.plans.values.sortedBy { it.source.stableCode }.map { sourcePlan ->
				val value = codec.encode(sourcePlan)
				SourceDesiredPlanEntity(
					plan.revision,
					sourcePlan.source.stableCode,
					SourcePlanCodec.FORMAT_VERSION,
					value.bytes,
					value.checksum,
				)
			}
			if (existing == null) {
				dao.insertRevision(
					AcquisitionPlanRevisionEntity(
						plan.revision,
						plan.planId,
						plan.createdAtMs,
						status.name,
						plan.sourcePolicyRevision,
					),
				)
				dao.insertDesiredPlans(encoded)
			} else {
				check(existing.planId == plan.planId) { "Plan revision identity collision" }
				check(existing.createdAtMs == plan.createdAtMs) { "Plan revision creation-time collision" }
				check(existing.sourcePolicyRevision == plan.sourcePolicyRevision) {
					"Plan revision source-policy collision"
				}
				val stored = dao.desiredPlans(plan.revision)
				check(stored.map { it.sourceKind to it.payloadChecksum } ==
					encoded.map { it.sourceKind to it.payloadChecksum }) {
					"Plan revision payload collision"
				}
				dao.updateRevisionStatus(plan.revision, status.name)
			}
		}
	}

	suspend fun load(revision: Long): AcquisitionPlanRevision? = database.withTransaction {
		val dao = database.sourcePlanStateDao()
		val header = dao.revision(revision) ?: return@withTransaction null
		val plans = linkedMapOf<SourceKind, SourcePlan>()
		for (entity in dao.desiredPlans(revision)) {
			if (entity.payloadVersion != SourcePlanCodec.FORMAT_VERSION ||
				entity.payload.size !in 1..SourcePlanCodec.MAX_PAYLOAD_BYTES ||
				!entity.payloadChecksum.matches(LOWERCASE_SHA_256)
			) return@withTransaction null
			val sourcePlan = try {
				codec.decode(entity.payload)
			} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
				return@withTransaction null
			}
			val reencoded = codec.encode(sourcePlan)
			if (sourcePlan.source.stableCode != entity.sourceKind ||
				sourcePlan.revision != revision ||
				reencoded.checksum != entity.payloadChecksum ||
				!reencoded.bytes.contentEquals(entity.payload)
			) return@withTransaction null
			plans[sourcePlan.source] = sourcePlan
		}
		AcquisitionPlanRevision(
			header.revision,
			header.planId,
			header.createdAtMs,
			plans,
			header.sourcePolicyRevision,
		)
	}

	suspend fun saveApplied(
		state: AppliedSourcePlan,
		effectivePlan: SourcePlan?,
		updatedAtMs: Long,
	) {
		database.withTransaction {
			val dao = database.sourcePlanStateDao()
			val current = dao.appliedStates().singleOrNull { row ->
				row.sourceKind == state.source.stableCode
			}
			val encoded = effectivePlan?.let { plan ->
				require(plan.source == state.source) { "Applied plan source mismatch" }
				require(plan.revision == state.appliedRevision) { "Applied plan revision mismatch" }
				require(state.status in setOf(SourceApplyStatus.APPLIED, SourceApplyStatus.DEGRADED)) {
					"Only an accepted applied state may persist an effective plan"
				}
				require(
					if (plan.enabled) {
						state.sourceInstanceId?.value?.isNotBlank() == true &&
							state.registrationGeneration?.let { generation -> generation > 0L } == true
					} else {
						state.sourceInstanceId == null && state.registrationGeneration == null
					},
				) { "Applied plan and runtime identity are inconsistent" }
				codec.encode(plan)
			}
			val preserved = current?.takeIf { row ->
				encoded == null &&
					state.appliedRevision != null &&
					row.appliedRevision == state.appliedRevision &&
					row.sourceInstanceId == state.sourceInstanceId?.value &&
					row.registrationGeneration == state.registrationGeneration &&
					row.hasCompleteAppliedPayload()
			}
			dao.saveAppliedState(
				SourceAppliedPlanStateEntity(
					sourceKind = state.source.stableCode,
					desiredRevision = state.desiredRevision,
					appliedRevision = state.appliedRevision,
					sourceInstanceId = state.sourceInstanceId?.value,
					registrationGeneration = state.registrationGeneration,
					appliedAtElapsedNanos = state.appliedAtElapsedRealtimeNanos,
					status = state.status.name,
					degradedReasons = state.degradedReasons
						.map(SourceDegradedReason::name)
						.sorted()
						.joinToString(","),
					updatedAtMs = updatedAtMs,
					appliedPayloadVersion = encoded?.let { SourcePlanCodec.FORMAT_VERSION }
						?: preserved?.appliedPayloadVersion,
					appliedPayload = encoded?.bytes ?: preserved?.appliedPayload,
					appliedPayloadChecksum = encoded?.checksum ?: preserved?.appliedPayloadChecksum,
				),
			)
		}
	}

	/**
	 * Reconstructs the exact effective plans accepted for [desiredRevision].
	 *
	 * Any unknown version, malformed payload, checksum mismatch, source mismatch, or revision
	 * mismatch invalidates the whole aggregate instead of falling back to the requested plan.
	 */
	suspend fun loadApplied(desiredRevision: Long): AppliedPlanRead = database.withTransaction {
		val dao = database.sourcePlanStateDao()
		val activeStates = dao.appliedStates().filter(SourceAppliedPlanStateEntity::isActive)
		when (val activeRead = decodeAppliedStates(activeStates)) {
			is AppliedPlanRead.Invalid -> return@withTransaction activeRead
			is AppliedPlanRead.Available,
			AppliedPlanRead.Missing,
			-> Unit
		}
		val header = dao.revision(desiredRevision) ?: return@withTransaction AppliedPlanRead.Missing
		val states = activeStates.filter { state -> state.desiredRevision == desiredRevision }
		when (val read = decodeAppliedStates(states)) {
			is AppliedPlanRead.Available -> {
				if (read.plan.revision != desiredRevision) {
					AppliedPlanRead.Invalid("APPLIED_SOURCE_PLAN_REVISION_MISMATCH")
				} else {
					AppliedPlanRead.Available(
						read.plan.copy(
							planId = "applied-runtime-$desiredRevision",
							createdAtMs = header.createdAtMs,
							sourcePolicyRevision = header.sourcePolicyRevision,
						),
					)
				}
			}
			is AppliedPlanRead.Invalid -> read
			AppliedPlanRead.Missing -> AppliedPlanRead.Missing
		}
	}

	suspend fun loadApplied(
		states: Collection<SourceAppliedPlanStateEntity>,
	): AppliedPlanRead = decodeAppliedStates(states)

	private fun decodeAppliedStates(
		states: Collection<SourceAppliedPlanStateEntity>,
	): AppliedPlanRead {
		if (states.isEmpty()) return AppliedPlanRead.Missing
		if (states.size > SourceKind.entries.size ||
			states.map(SourceAppliedPlanStateEntity::sourceKind).toSet().size != states.size
		) {
			return AppliedPlanRead.Invalid("APPLIED_SOURCE_PLAN_COUNT_INVALID")
		}
		val decoded = linkedMapOf<SourceKind, SourcePlan>()
		for (state in states.sortedBy(SourceAppliedPlanStateEntity::sourceKind)) {
			if (!state.isActive()) {
				return AppliedPlanRead.Invalid("APPLIED_SOURCE_PLAN_STATUS_INVALID")
			}
			val plan = state.decodeAppliedPlanOrNull(codec)
				?: return AppliedPlanRead.Invalid("APPLIED_SOURCE_PLAN_PAYLOAD_INVALID")
			val appliedRevision = state.appliedRevision
				?: return AppliedPlanRead.Invalid("APPLIED_SOURCE_PLAN_REVISION_MISSING")
			if (plan.revision != appliedRevision) {
				return AppliedPlanRead.Invalid("APPLIED_SOURCE_PLAN_REVISION_MISMATCH")
			}
			if (!state.hasRuntimeIdentityFor(plan)) {
				return AppliedPlanRead.Invalid(
					"APPLIED_SOURCE_PLAN_RUNTIME_IDENTITY_INVALID",
				)
			}
			decoded[plan.source] = plan
		}
		val revision = decoded.values.first().revision
		if (decoded.values.any { plan -> plan.revision != revision }) {
			return AppliedPlanRead.Invalid("APPLIED_SOURCE_PLAN_GENERATION_MIXED")
		}
		return AppliedPlanRead.Available(
			AcquisitionPlanRevision(
				revision = revision,
				planId = "applied-runtime-$revision",
				createdAtMs = 0L,
				plans = decoded,
				sourcePolicyRevision = null,
			),
		)
	}

	suspend fun updateStatus(revision: Long, status: DesiredPlanStatus) {
		check(database.sourcePlanStateDao().updateRevisionStatus(revision, status.name) == 1)
	}
}

private fun SourceAppliedPlanStateEntity.isActive(): Boolean =
	status == SourceApplyStatus.APPLIED.name || status == SourceApplyStatus.DEGRADED.name

private fun SourceAppliedPlanStateEntity.hasCompleteAppliedPayload(): Boolean =
	appliedPayloadVersion != null && appliedPayload != null && appliedPayloadChecksum != null

private fun SourceAppliedPlanStateEntity.hasRuntimeIdentityFor(plan: SourcePlan): Boolean =
	if (plan.enabled) {
		sourceInstanceId?.isNotBlank() == true &&
			registrationGeneration?.let { generation -> generation > 0L } == true
	} else {
		sourceInstanceId == null && registrationGeneration == null
	}

private fun SourceAppliedPlanStateEntity.decodeAppliedPlanOrNull(
	codec: SourcePlanCodec,
): SourcePlan? {
	if (!hasCompleteAppliedPayload() ||
		appliedPayloadVersion != SourcePlanCodec.FORMAT_VERSION ||
		appliedPayloadChecksum?.matches(LOWERCASE_SHA_256) != true
	) return null
	val payload = requireNotNull(appliedPayload)
	if (payload.size !in 1..SourcePlanCodec.MAX_PAYLOAD_BYTES) return null
	return try {
		val decoded = codec.decode(payload)
		val reencoded = codec.encode(decoded)
		decoded.takeIf { plan ->
			plan.source.stableCode == sourceKind &&
				reencoded.checksum == appliedPayloadChecksum &&
				reencoded.bytes.contentEquals(payload)
		}
	} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
		null
	}
}

enum class DesiredPlanStatus { DESIRED, APPLYING, EFFECTIVE, DEGRADED, FAILED, SUPERSEDED }

private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
