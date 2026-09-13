package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientStepsDayRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var repository: AmbientStepsDayRepository

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		repository = AmbientStepsDayRepository(
			database,
			StepsSegmentHistorySelector(database, SourceProductLaneExecutionAuthority { true }),
			Dispatchers.Unconfined,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `one snapshot composes corrected fact and retraction never leaves a stale total`() = runTest {
		seedAuthority()
		val initial = fact(10L)
		database.ambientStepsFactRevisionDao().insert(initial) shouldBe 1L

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Exact(10L)

		val corrected = signed(
			initial.copy(
				semanticRevision = 2L,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					initial.logicalFactId,
					2L,
					AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				stepCount = 12L,
				observedAtMs = DAY_END + 1L,
				appliedAtMs = DAY_END + 1L,
			),
		)
		database.ambientStepsFactRevisionDao().insert(corrected) shouldBe 2L
		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Exact(12L)

		database.ambientStepsFactRevisionDao().insert(retract(corrected)) shouldBe 3L
		val afterRetraction = readSnapshot().page
		afterRetraction.days shouldBe emptyList()
		afterRetraction.materialization shouldBe AmbientStepsProductMaterialization.MATERIALIZING
	}

	@Test
	fun `current capability state does not erase an authoritative historical day`() = runTest {
		seedAuthority()
		database.ambientStepsFactRevisionDao().insert(fact(10L))

		val expectations = mapOf(
			AmbientStepsRuntimeAvailability.UNSUPPORTED to AmbientStepsProductAvailability.UNSUPPORTED,
			AmbientStepsRuntimeAvailability.PERMISSION_REQUIRED to
				AmbientStepsProductAvailability.PERMISSION_REQUIRED,
			AmbientStepsRuntimeAvailability.OS_RESTRICTED to AmbientStepsProductAvailability.OS_RESTRICTED,
		)
		expectations.forEach { (runtime, expected) ->
			val page = requireSnapshot(repository.readPage(AmbientStepsDayPageRequest(1), runtime)).page
			page.availability shouldBe expected
			page.materialization shouldBe AmbientStepsProductMaterialization.NOT_APPLICABLE
			page.days.single().total shouldBe AmbientStepsNumericValue.Exact(10L)
		}

		val disabled = policy(revision = 2L).copy(
			enabled = false,
			ambientPersistenceEligible = false,
			ambientConsentEpoch = null,
		)
		database.sourcePolicyDao().insertPolicies(listOf(disabled))
		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = 1L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = 2L,
			legacySettingsFingerprint = null,
			updatedAtMs = 2L,
		) shouldBe 1
		val disabledPage = readSnapshot().page
		disabledPage.availability shouldBe AmbientStepsProductAvailability.DISABLED
		disabledPage.days.single().total shouldBe AmbientStepsNumericValue.Exact(10L)
	}

	@Test
	fun `enabled cursor without a structural fact is materializing not covered zero`() = runTest {
		seedAuthority()

		val page = readSnapshot().page
		page.days shouldBe emptyList()
		page.availability shouldBe AmbientStepsProductAvailability.AVAILABLE
		page.materialization shouldBe AmbientStepsProductMaterialization.MATERIALIZING
	}

	@Test
	fun `fact overflow returns typed failure instead of an exact truncated day`() = runTest {
		seedAuthority()
		repeat(401) { index ->
			val start = index * 1_000L
			database.ambientStepsFactRevisionDao().insert(fact(1L, start, start + 1_000L))
		}

		repository.readPage(
			AmbientStepsDayPageRequest(1),
			AmbientStepsRuntimeAvailability.AVAILABLE,
		) shouldBe AmbientStepsDayPageResult.DependencyOverflow(
			AmbientStepsDayDependency.FACTS,
			requestedAfter = null,
		)
	}

	@Test
	fun `gap overflow returns typed failure instead of partial coverage`() = runTest {
		seedAuthority()
		database.ambientStepsFactRevisionDao().insert(fact(1L, 0L, 1_000L))
		repeat(401) { index ->
			val start = (index + 1L) * 1_000L
			database.ambientStepsImportStateDao().insertGap(gap(index + 1L, start))
		}

		repository.readPage(
			AmbientStepsDayPageRequest(1),
			AmbientStepsRuntimeAvailability.AVAILABLE,
		) shouldBe AmbientStepsDayPageResult.DependencyOverflow(
			AmbientStepsDayDependency.GAPS,
			requestedAfter = null,
		)
	}

	@Test
	fun `multi-day read exposes a stable continuation and never silently truncates`() = runTest {
		seedAuthority(
			cursor().copy(
				importedThroughTimeMs = 2L * DAY_END,
				lastObservedAtMs = 2L * DAY_END,
				updatedAtMs = 2L * DAY_END,
			),
		)
		database.ambientStepsFactRevisionDao().insert(fact(10L))
		database.ambientStepsFactRevisionDao().insert(
			fact(20L, DAY_END, 2L * DAY_END, epochDay = 1L),
		)

		val first = readSnapshot().page
		first.days.map { it.day.epochDay } shouldBe listOf(1L)
		val continuation = requireNotNull(first.next)
		val second = requireSnapshot(
			repository.readPage(
				AmbientStepsDayPageRequest(1, continuation),
				AmbientStepsRuntimeAvailability.AVAILABLE,
			),
		).page
		second.days.map { it.day.epochDay } shouldBe listOf(0L)
		second.next shouldBe null
	}

	@Test
	fun `missing durable authority and storage failure remain distinct`() = runTest {
		repository.readPage(
			AmbientStepsDayPageRequest(1),
			AmbientStepsRuntimeAvailability.AVAILABLE,
		) shouldBe AmbientStepsDayPageResult.Unavailable(
			AmbientStepsProductAvailability.HISTORICAL_EVIDENCE_MISSING,
			AmbientStepsProductMaterialization.FAILED,
		)

		database.close()
		repository.readPage(
			AmbientStepsDayPageRequest(1),
			AmbientStepsRuntimeAvailability.AVAILABLE,
		) shouldBe AmbientStepsDayPageResult.Unavailable(
			AmbientStepsProductAvailability.STORAGE_FAILED,
			AmbientStepsProductMaterialization.FAILED,
		)
	}

	private suspend fun seedAuthority(stateCursor: AmbientStepsImportCursorEntity = cursor()) {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS,
				1L,
				0L,
			),
		)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = 1L,
				legacySettingsFingerprint = null,
				updatedAtMs = 1L,
			),
		)
		database.sourcePolicyDao().insertPolicies(listOf(policy(1L)))
		database.sourcePolicyDao().insertConsentEpochs(listOf(consent()))
		database.ambientStepsImportStateDao().insertCursor(stateCursor)
	}

	private suspend fun readSnapshot(): AmbientStepsDayPageResult.Snapshot = requireSnapshot(
		repository.readPage(
			AmbientStepsDayPageRequest(1),
			AmbientStepsRuntimeAvailability.AVAILABLE,
		),
	)

	private fun requireSnapshot(result: AmbientStepsDayPageResult): AmbientStepsDayPageResult.Snapshot {
		check(result is AmbientStepsDayPageResult.Snapshot) { "Expected snapshot, got $result" }
		return result
	}

	private fun policy(revision: Long) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = false,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = true,
		captureConsentEpoch = null,
		controlConsentEpoch = null,
		ambientConsentEpoch = 1L,
		effectiveBootId = "boot-a",
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "test",
	)

	private fun consent() = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		epoch = 1L,
		eligible = true,
		persistenceEligible = true,
		policyRevision = 1L,
		effectiveBootId = "boot-a",
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "test",
	)

	private fun cursor() = AmbientStepsImportCursorEntity(
		registrationGeneration = 1L,
		provider = PROVIDER,
		sourceInstanceId = SOURCE_INSTANCE,
		registrationClockDomainId = "boot-a",
		registrationAcceptedAtMs = 0L,
		registrationAcceptedElapsedRealtimeNanos = 0L,
		authorizationRevision = 1L,
		authorizationFingerprint = "a".repeat(64),
		authorizationEffectiveBootId = "boot-a",
		authorizationEffectiveElapsedRealtimeNanos = 0L,
		authorizationEffectiveWallTimeMs = 0L,
		sourcePolicyRevision = 1L,
		ambientConsentEpoch = 1L,
		collectedDataEpoch = 7L,
		eligibleFromTimeMs = 0L,
		continuitySegmentGeneration = 1L,
		segmentStartTimeMs = 0L,
		importedThroughTimeMs = DAY_END,
		lastObservedAtMs = DAY_END,
		lastObservedBootId = "boot-a",
		lastObservedZoneId = "UTC",
		lastGapSequence = 0L,
		authorityTransitionSequence = 0L,
		cursorRevision = 1L,
		status = AmbientStepsImportCursorEntity.STATUS_ACTIVE,
		updatedAtMs = DAY_END,
	)

	private fun fact(
		count: Long,
		startTimeMs: Long = 0L,
		endTimeMs: Long = DAY_END,
		epochDay: Long = 0L,
	): AmbientStepsFactRevisionEntity {
		val dayStartTimeMs = epochDay * DAY_END
		val dayEndTimeMs = dayStartTimeMs + DAY_END
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			PROVIDER, 1L, 1L, SOURCE_INSTANCE, startTimeMs, epochDay, "UTC", 7L,
		)
		return signed(
			AmbientStepsFactRevisionEntity(
				logicalFactId = logicalFactId,
				semanticRevision = 1L,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					logicalFactId, 1L, AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
				writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
				writerOwnerGeneration = 1L,
				operation = AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				originKind = AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
				provider = PROVIDER,
				registrationGeneration = 1L,
				continuitySegmentGeneration = 1L,
				sourceInstanceId = SOURCE_INSTANCE,
				authorizationRevision = 1L,
				authorizationFingerprint = "a".repeat(64),
				windowStartTimeMs = startTimeMs,
				windowEndTimeMs = endTimeMs,
				observedAtMs = endTimeMs,
				structuralEpochDay = epochDay,
				storedZoneId = "UTC",
				structuralDayStartTimeMs = dayStartTimeMs,
				structuralDayEndTimeMs = dayEndTimeMs,
				stepCount = count,
				purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
				sourcePolicyRevision = 1L,
				ambientConsentEpoch = 1L,
				collectedDataEpoch = 7L,
				scopeDeletionGeneration = 0L,
				effectChecksum = "0".repeat(64),
				appliedAtMs = endTimeMs,
			),
		)
	}

	private fun retract(fact: AmbientStepsFactRevisionEntity): AmbientStepsFactRevisionEntity = signed(
		fact.copy(
			semanticRevision = 3L,
			mutationId = AmbientStepsFactIntegrity.mutationId(
				fact.logicalFactId, 3L, AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
			),
			operation = AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
			originKind = AmbientStepsFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			provider = null,
			registrationGeneration = null,
			continuitySegmentGeneration = null,
			sourceInstanceId = null,
			authorizationRevision = null,
			authorizationFingerprint = null,
			windowStartTimeMs = null,
			windowEndTimeMs = null,
			observedAtMs = null,
			structuralEpochDay = null,
			storedZoneId = null,
			structuralDayStartTimeMs = null,
			structuralDayEndTimeMs = null,
			stepCount = null,
			sourcePolicyRevision = null,
			ambientConsentEpoch = null,
			scopeDeletionGeneration = 1L,
			appliedAtMs = DAY_END + 2L,
		),
	)

	private fun gap(sequence: Long, startTimeMs: Long): AmbientStepsImportGapEntity {
		val endTimeMs = startTimeMs + 1_000L
		val gapId = AmbientStepsImportGapIntegrity.gapId(
			registrationGeneration = 1L,
			gapSequence = sequence,
			provider = PROVIDER,
			sourceInstanceId = SOURCE_INSTANCE,
			reason = AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
			gapStartTimeMs = startTimeMs,
			gapEndTimeMs = endTimeMs,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = "boot-a",
			nextClockDomainId = "boot-a",
			previousZoneId = "UTC",
			nextZoneId = "UTC",
			collectedDataEpoch = 7L,
		)
		return AmbientStepsImportGapEntity(
			gapId = gapId,
			registrationGeneration = 1L,
			gapSequence = sequence,
			provider = PROVIDER,
			sourceInstanceId = SOURCE_INSTANCE,
			reason = AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
			gapStartTimeMs = startTimeMs,
			gapEndTimeMs = endTimeMs,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = "boot-a",
			nextClockDomainId = "boot-a",
			previousZoneId = "UTC",
			nextZoneId = "UTC",
			collectedDataEpoch = 7L,
			recordedAtMs = endTimeMs,
		)
	}

	private fun signed(fact: AmbientStepsFactRevisionEntity) = fact.copy(
		effectChecksum = AmbientStepsFactIntegrity.effectChecksum(fact),
	)

	private companion object {
		const val DAY_END = 86_400_000L
		const val SOURCE_INSTANCE = "ambient-instance"
		const val PROVIDER = AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS
	}
}
