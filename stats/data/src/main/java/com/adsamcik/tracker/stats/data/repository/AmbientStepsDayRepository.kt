package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientStepsStructuralDayRow
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage as ApiStepsHistoryCoverage
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Provider capability is captured before entering Room; product reads never start a provider. */
internal enum class AmbientStepsRuntimeAvailability {
	AVAILABLE,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_RESTRICTED,
}

/** Current collection availability is orthogonal to any retained historical day product. */
internal enum class AmbientStepsProductAvailability {
	AVAILABLE,
	DISABLED,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_RESTRICTED,
	HISTORICAL_EVIDENCE_MISSING,
	STORAGE_FAILED,
}

internal enum class AmbientStepsProductMaterialization {
	NOT_APPLICABLE,
	MATERIALIZING,
	READY,
	FAILED,
}

internal data class AmbientStepsDayPageCursor(
	val latestWindowEndTimeMs: Long,
	val epochDay: Long,
	val storedZoneId: String,
) {
	init {
		require(latestWindowEndTimeMs >= 0L)
		require(storedZoneId.isNotBlank())
		ZoneId.of(storedZoneId)
	}
}

internal data class AmbientStepsDayPageRequest(
	val limit: Int,
	val before: AmbientStepsDayPageCursor? = null,
) {
	init {
		require(limit in 1..MAX_AMBIENT_DAY_PAGE_SIZE)
	}
}

internal data class AmbientStepsDayPage(
	val days: List<AmbientStepsDayProduct>,
	val next: AmbientStepsDayPageCursor?,
	val availability: AmbientStepsProductAvailability,
	val materialization: AmbientStepsProductMaterialization,
)

internal enum class AmbientStepsDayDependency {
	FACTS,
	GAPS,
	SESSION_CANDIDATES,
	SESSION_HISTORY,
	IMPORTED_SESSION_ENTRIES,
	CURSORS,
}

internal sealed interface AmbientStepsDayPageResult {
	data class Snapshot(val page: AmbientStepsDayPage) : AmbientStepsDayPageResult

	/** A truncated dependency can never be presented as an exact or partial numeric day. */
	data class DependencyOverflow(
		val dependency: AmbientStepsDayDependency,
		val requestedAfter: AmbientStepsDayPageCursor?,
	) : AmbientStepsDayPageResult

	data class Unavailable(
		val availability: AmbientStepsProductAvailability,
		val materialization: AmbientStepsProductMaterialization,
	) : AmbientStepsDayPageResult
}

/**
 * Source-specific, bounded Ambient Steps read facade.
 *
 * Facts, effective gaps, exact session Steps evidence, cursor/writer authority, policy/consent,
 * deletion epoch and retention floor are observed under one Room transaction. Capability is an
 * immutable pre-read input because Android provider state is intentionally outside this module and
 * is never queried while Room holds a transaction.
 */
internal class AmbientStepsDayRepository @Inject constructor(
	private val database: AppDatabase,
	private val stepsSelector: StepsSegmentHistorySelector,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
	private val importedStepsReader = ImportedStepsRetainedReader(database)

	suspend fun readPage(
		request: AmbientStepsDayPageRequest,
		runtimeAvailability: AmbientStepsRuntimeAvailability,
	): AmbientStepsDayPageResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { readPageInTransaction(request, runtimeAvailability) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			AmbientStepsDayPageResult.Unavailable(
				AmbientStepsProductAvailability.STORAGE_FAILED,
				AmbientStepsProductMaterialization.FAILED,
			)
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun readPageInTransaction(
		request: AmbientStepsDayPageRequest,
		runtimeAvailability: AmbientStepsRuntimeAvailability,
	): AmbientStepsDayPageResult {
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return historicalEvidenceMissing()
		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
		)
		if (owner?.owner != SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS) {
			return historicalEvidenceMissing()
		}
		val policyAuthority = database.sourcePolicyDao().authority()
		val currentPolicy = policyAuthority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		}?.let { authority ->
			database.sourcePolicyDao().policyAtRevision(
				authority.currentPolicyRevision,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			)
		}
		val currentConsent = database.sourcePolicyDao().latestConsentEpoch(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		val activeCursors = database.ambientStepsImportStateDao().activeCursors(
			MAX_ACTIVE_AMBIENT_CURSORS + 1,
		)
		if (activeCursors.size > MAX_ACTIVE_AMBIENT_CURSORS) {
			return overflow(AmbientStepsDayDependency.CURSORS, request)
		}

		val dayRows = database.ambientStepsFactRevisionDao().discoverStructuralDayPage(
			writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
			beforeLatestWindowEndTimeMs = request.before?.latestWindowEndTimeMs,
			beforeEpochDay = request.before?.epochDay,
			beforeStoredZoneId = request.before?.storedZoneId,
			limit = request.limit + 1,
		)
		val acceptedRows = dayRows.take(request.limit)
		val dayIdentities = acceptedRows.mapNotNull(AmbientStepsStructuralDayRow::toDayOrNull)
		if (dayIdentities.size != acceptedRows.size) return historicalEvidenceMissing()
		val next = acceptedRows.lastOrNull()
			?.takeIf { dayRows.size > request.limit }
			?.toCursor()
		val currentState = currentProductState(
			policyAuthority = policyAuthority,
			policy = currentPolicy,
			consent = currentConsent,
			runtimeAvailability = runtimeAvailability,
			activeCursors = activeCursors,
			evidenceState = evidenceState,
			days = dayIdentities,
		)
		if (dayIdentities.isEmpty()) {
			return AmbientStepsDayPageResult.Snapshot(
				AmbientStepsDayPage(emptyList(), next, currentState.first, currentState.second),
			)
		}

		val fromTimeMs = dayIdentities.minOf(AmbientStepsDayIdentity::startTimeMs)
		val toTimeMs = dayIdentities.maxOf(AmbientStepsDayIdentity::endTimeMs)
		val facts = database.ambientStepsFactRevisionDao().latestEffectiveOverlapping(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			fromTimeMs,
			toTimeMs,
			MAX_AMBIENT_FACTS_PER_PAGE + 1,
		)
		if (facts.size > MAX_AMBIENT_FACTS_PER_PAGE) {
			return overflow(AmbientStepsDayDependency.FACTS, request)
		}
		val gaps = database.ambientStepsImportStateDao().effectiveGapIntervalsOverlapping(
			fromTimeMs,
			toTimeMs,
			MAX_AMBIENT_GAPS_PER_PAGE + 1,
		)
		if (gaps.size > MAX_AMBIENT_GAPS_PER_PAGE) {
			return overflow(AmbientStepsDayDependency.GAPS, request)
		}
		val segments = database.trackingHistoryReadDao().portableStepsSegmentCandidatePage(
			fromMs = fromTimeMs,
			toMs = toTimeMs,
			stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			limit = HISTORY_SEGMENT_BATCH_CAP + 1,
			afterStartTimeMs = null,
			afterSegmentId = null,
		)
		if (segments.size > HISTORY_SEGMENT_BATCH_CAP) {
			return overflow(AmbientStepsDayDependency.SESSION_CANDIDATES, request)
		}
		val importedEntries = database.importedStepsDao().entriesOverlapping(
			fromInclusiveMs = fromTimeMs,
			toExclusiveMs = toTimeMs,
			limit = ImportedStepsRetainedReader.MAX_ENTRY_BATCH + 1,
		)
		if (importedEntries.size > ImportedStepsRetainedReader.MAX_ENTRY_BATCH) {
			return overflow(AmbientStepsDayDependency.IMPORTED_SESSION_ENTRIES, request)
		}

		val selectedDayKeys = dayIdentities.associateBy(AmbientStepsDayIdentity::key)
		val selectedFacts = facts.filter { fact ->
			fact.dayKeyOrNull()?.let(selectedDayKeys::containsKey) == true
		}
		val registrationGenerations = selectedFacts.mapNotNull {
			it.registrationGeneration
		}.distinct()
		val cursors = if (registrationGenerations.isEmpty()) emptyList() else {
			database.ambientStepsImportStateDao().cursors(registrationGenerations)
		}
		if (cursors.size != registrationGenerations.size) return historicalEvidenceMissing()
		val policyRevisions = selectedFacts.mapNotNull { it.sourcePolicyRevision }.distinct()
		val policies = if (policyRevisions.isEmpty()) emptyList() else {
			database.sourcePolicyDao().policiesAtRevisions(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				policyRevisions,
			)
		}
		if (policies.size != policyRevisions.size) return historicalEvidenceMissing()
		val consentEpochs = selectedFacts.mapNotNull { it.ambientConsentEpoch }.distinct()
		val consents = if (consentEpochs.isEmpty()) emptyList() else {
			database.sourcePolicyDao().consentEpochs(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
				consentEpochs,
			)
		}
		if (consents.size != consentEpochs.size) return historicalEvidenceMissing()

		val stepsSnapshot = if (segments.isEmpty()) null else {
			loadStepsHistoryBatchSnapshot(database, segments)
		}
		if (stepsSnapshot?.dependencyOverflow != null) {
			return overflow(AmbientStepsDayDependency.SESSION_HISTORY, request)
		}
		val sessionEvidence = stepsSnapshot?.let {
			stepsSelector.selectManyWithSnapshot(segments, it)
		}.orEmpty()
		if (sessionEvidence.any { evidence ->
				evidence.segment.logicalTrackingId.isNullOrBlank() ||
					evidence.segment.serviceRunId.isNullOrBlank()
			}
		) return historicalEvidenceMissing()
		val localSessions = sessionEvidence.map { evidence ->
			val result = evidence.steps
			val logicalTrackingId = requireNotNull(evidence.segment.logicalTrackingId)
			val serviceRunId = requireNotNull(evidence.segment.serviceRunId)
			QualifiedSessionStepsWindow(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				startTimeMs = evidence.segment.startTimeMs,
				endTimeMs = evidence.segment.endTimeMs,
				stepCount = result.count.takeIf {
					result.availability == StepsHistoryAvailability.AVAILABLE &&
						result.materialization == StepsHistoryMaterialization.READY &&
						result.coverage == StepsHistoryCoverage.COMPLETE
				},
				storedZoneId = stepsSnapshot?.manifestsByRun
					?.get(serviceRunId)
					.orEmpty()
					.map { it.zoneId }
					.distinct()
					.singleOrNull(),
				compatibility = SessionAmbientCompatibility.Unproven,
			)
		}
		val importedSessions = when (val imported = importedStepsReader.readEntriesInTransaction(
			importedEntries.map { it.identity },
		)) {
			is ImportedStepsRetainedRead.Ready -> {
				val verified = imported.entries.flatMap { entry ->
					entry.runs.map { run ->
						val wire = entry.portableRunsById.getValue(run.identity)
						val history = wire.toImportedStepsHistory(
							run.identity in entry.retentionTruncatedRunIds,
						)
						QualifiedSessionStepsWindow(
							logicalTrackingId = entry.metadata.identity,
							serviceRunId = run.identity,
							startTimeMs = run.startTimeMs,
							endTimeMs = run.endTimeMs,
							stepCount = history.count.takeIf {
								history.productState == HistoryProductState.READY &&
									history.coverage == ApiStepsHistoryCoverage.COMPLETE
							},
							storedZoneId = run.storedZoneId,
							compatibility = SessionAmbientCompatibility.Unproven,
							origin = QualifiedSessionStepsOrigin.PORTABLE_IMPORT,
						)
					}
				}
				val unverified = importedEntries.filter { entry ->
					entry.identity in imported.unverifiableEntries
				}.map { entry -> entry.unverifiableImportedSession() }
				verified + unverified
			}
			is ImportedStepsRetainedRead.Unverifiable -> {
				if (imported.reason == ImportedStepsReadFailure.DEPENDENCY_OVERFLOW) {
					return overflow(AmbientStepsDayDependency.IMPORTED_SESSION_ENTRIES, request)
				}
				importedEntries.map { entry -> entry.unverifiableImportedSession() }
			}
		}
		val sessions = localSessions + importedSessions

		val cursorsByGeneration = cursors.associateBy(AmbientStepsImportCursorEntity::registrationGeneration)
		val policiesByRevision = policies.associateBy(SourcePolicyEntity::policyRevision)
		val consentsByEpoch = consents.associateBy(SourceConsentEpochEntity::epoch)
		val factsByDay = selectedFacts.groupBy { fact -> fact.dayKeyOrNull() }
		val productDays = dayIdentities.map { day ->
			val dayFacts = factsByDay[day.key].orEmpty()
			val daySessions = sessions.filter { session ->
				session.endTimeMs > day.startTimeMs && session.startTimeMs < day.endTimeMs
			}
			if (dayFacts.any { fact ->
					!fact.hasValidReadAuthority(
						day = day,
						ownerGeneration = owner.ownerGeneration,
						evidenceState = evidenceState,
						cursor = fact.registrationGeneration?.let(cursorsByGeneration::get),
						policy = fact.sourcePolicyRevision?.let(policiesByRevision::get),
						consent = fact.ambientConsentEpoch?.let(consentsByEpoch::get),
					)
				}
			) {
				return@map unavailableDay(
					day,
					daySessions,
					AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE,
				)
			}
			val qualifiedFacts = dayFacts.map { it.toQualifiedFact(day) }
			val effectiveGaps = gaps.asSequence()
				.filter { it.gapEndTimeMs > it.gapStartTimeMs }
				.filter { it.gapEndTimeMs > day.startTimeMs && it.gapStartTimeMs < day.endTimeMs }
				.map { gap -> EffectiveAmbientStepsGap(gap.gapStartTimeMs, gap.gapEndTimeMs) }
				.toList()
			composeAmbientStepsDay(day, qualifiedFacts, effectiveGaps, daySessions)
		}
		return AmbientStepsDayPageResult.Snapshot(
			AmbientStepsDayPage(productDays, next, currentState.first, currentState.second),
		)
	}

	private fun currentProductState(
		policyAuthority: SourcePolicyAuthorityEntity?,
		policy: SourcePolicyEntity?,
		consent: SourceConsentEpochEntity?,
		runtimeAvailability: AmbientStepsRuntimeAvailability,
		activeCursors: List<AmbientStepsImportCursorEntity>,
		evidenceState: SourceEvidenceState,
		days: List<AmbientStepsDayIdentity>,
	): Pair<AmbientStepsProductAvailability, AmbientStepsProductMaterialization> {
		if (policyAuthority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE || policy == null) {
			return AmbientStepsProductAvailability.HISTORICAL_EVIDENCE_MISSING to
				AmbientStepsProductMaterialization.FAILED
		}
		val eligible = policy.enabled && policy.ambientPersistenceEligible &&
			policy.ambientConsentEpoch != null && consent != null && consent.eligible &&
			consent.persistenceEligible && consent.epoch == policy.ambientConsentEpoch &&
			consent.policyRevision == policy.policyRevision
		if (!eligible) {
			return AmbientStepsProductAvailability.DISABLED to
				AmbientStepsProductMaterialization.NOT_APPLICABLE
		}
		val availability = when (runtimeAvailability) {
			AmbientStepsRuntimeAvailability.AVAILABLE -> AmbientStepsProductAvailability.AVAILABLE
			AmbientStepsRuntimeAvailability.UNSUPPORTED -> AmbientStepsProductAvailability.UNSUPPORTED
			AmbientStepsRuntimeAvailability.PERMISSION_REQUIRED ->
				AmbientStepsProductAvailability.PERMISSION_REQUIRED
			AmbientStepsRuntimeAvailability.OS_RESTRICTED -> AmbientStepsProductAvailability.OS_RESTRICTED
		}
		if (availability != AmbientStepsProductAvailability.AVAILABLE) {
			return availability to AmbientStepsProductMaterialization.NOT_APPLICABLE
		}
		val cursor = activeCursors.singleOrNull()
		if (cursor == null || cursor.collectedDataEpoch != evidenceState.collectedDataEpoch ||
			cursor.sourcePolicyRevision != policy.policyRevision ||
			cursor.ambientConsentEpoch != consent.epoch
		) {
			return availability to AmbientStepsProductMaterialization.MATERIALIZING
		}
		val materializing = days.isEmpty() || days.any { day ->
			cursor.importedThroughTimeMs < day.endTimeMs
		}
		return availability to if (materializing) {
			AmbientStepsProductMaterialization.MATERIALIZING
		} else {
			AmbientStepsProductMaterialization.READY
		}
	}

	private fun overflow(
		dependency: AmbientStepsDayDependency,
		request: AmbientStepsDayPageRequest,
	) = AmbientStepsDayPageResult.DependencyOverflow(dependency, request.before)

	private fun historicalEvidenceMissing() = AmbientStepsDayPageResult.Unavailable(
		AmbientStepsProductAvailability.HISTORICAL_EVIDENCE_MISSING,
		AmbientStepsProductMaterialization.FAILED,
	)
}

private fun AmbientStepsStructuralDayRow.toDayOrNull(): AmbientStepsDayIdentity? = runCatching {
	AmbientStepsDayIdentity(
		structuralEpochDay,
		storedZoneId,
		structuralDayStartTimeMs,
		structuralDayEndTimeMs,
	)
}.getOrNull()

private fun AmbientStepsStructuralDayRow.toCursor() = AmbientStepsDayPageCursor(
	latestWindowEndTimeMs,
	structuralEpochDay,
	storedZoneId,
)

private data class AmbientStepsDayKey(val epochDay: Long, val storedZoneId: String)

private val AmbientStepsDayIdentity.key: AmbientStepsDayKey
	get() = AmbientStepsDayKey(epochDay, storedZoneId)

private fun AmbientStepsFactRevisionEntity.dayKeyOrNull(): AmbientStepsDayKey? {
	val epochDay = structuralEpochDay ?: return null
	val zoneId = storedZoneId ?: return null
	return AmbientStepsDayKey(epochDay, zoneId)
}

private fun AmbientStepsFactRevisionEntity.hasValidReadAuthority(
	day: AmbientStepsDayIdentity,
	ownerGeneration: Long,
	evidenceState: SourceEvidenceState,
	cursor: AmbientStepsImportCursorEntity?,
	policy: SourcePolicyEntity?,
	consent: SourceConsentEpochEntity?,
): Boolean {
	val start = windowStartTimeMs ?: return false
	val end = windowEndTimeMs ?: return false
	val retainedFromMs = evidenceState.retainedFromMs
	return AmbientStepsFactIntegrity.hasValidEffectChecksum(this) &&
		writerOwnerGeneration == ownerGeneration &&
		collectedDataEpoch == evidenceState.collectedDataEpoch &&
		(retainedFromMs == null || start >= retainedFromMs) &&
		dayKeyOrNull() == day.key && structuralDayStartTimeMs == day.startTimeMs &&
		structuralDayEndTimeMs == day.endTimeMs && start >= day.startTimeMs && end <= day.endTimeMs &&
		cursor != null && cursor.provider == provider && cursor.sourceInstanceId == sourceInstanceId &&
		cursor.registrationGeneration == registrationGeneration &&
		cursor.collectedDataEpoch == collectedDataEpoch &&
		cursor.registrationAcceptedAtMs <= start &&
		cursor.importedThroughTimeMs >= end &&
		cursor.continuitySegmentGeneration >= requireNotNull(continuitySegmentGeneration) &&
		policy != null && policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		policy.policyRevision == sourcePolicyRevision && policy.enabled &&
		policy.ambientPersistenceEligible && policy.ambientConsentEpoch == ambientConsentEpoch &&
		policy.effectiveWallTimeMs <= start &&
		consent != null && consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && consent.epoch == ambientConsentEpoch &&
		consent.policyRevision == sourcePolicyRevision && consent.eligible && consent.persistenceEligible &&
		consent.effectiveWallTimeMs <= start
}

private fun AmbientStepsFactRevisionEntity.toQualifiedFact(
	day: AmbientStepsDayIdentity,
) = QualifiedAmbientStepsFact(
	logicalFactId = logicalFactId,
	day = day,
	startTimeMs = requireNotNull(windowStartTimeMs),
	endTimeMs = requireNotNull(windowEndTimeMs),
	stepCount = requireNotNull(stepCount),
	provenance = AmbientStepsProviderProvenance(
		provider = requireNotNull(provider),
		sourceInstanceId = requireNotNull(sourceInstanceId),
		registrationGeneration = requireNotNull(registrationGeneration),
		continuitySegmentGeneration = requireNotNull(continuitySegmentGeneration),
	),
)

private fun unavailableDay(
	day: AmbientStepsDayIdentity,
	sessions: List<QualifiedSessionStepsWindow>,
	cause: AmbientStepsDayCause,
): AmbientStepsDayProduct {
	val unavailable = AmbientStepsNumericValue.Unavailable(setOf(cause))
	return AmbientStepsDayProduct(day, unavailable, sessions, unavailable)
}

private fun com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
	.unverifiableImportedSession() = QualifiedSessionStepsWindow(
	logicalTrackingId = identity,
	serviceRunId = identity,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	stepCount = null,
	storedZoneId = null,
	compatibility = SessionAmbientCompatibility.Unproven,
	origin = QualifiedSessionStepsOrigin.PORTABLE_IMPORT,
)

internal const val MAX_AMBIENT_DAY_PAGE_SIZE = 31
private const val MAX_ACTIVE_AMBIENT_CURSORS = 1
private const val MAX_AMBIENT_FACTS_PER_PAGE = 400
private const val MAX_AMBIENT_GAPS_PER_PAGE = 400
