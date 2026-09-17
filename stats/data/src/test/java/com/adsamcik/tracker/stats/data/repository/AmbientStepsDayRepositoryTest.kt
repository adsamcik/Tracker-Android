package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryValue
import com.adsamcik.tracker.stats.api.repository.AmbientStepsStructuralDay
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
			RoomStepsCountDomainCompatibilityQuery(database),
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
	fun `public native history remains exact after current ambient collection is disabled`() = runTest {
		val fingerprint = seedPublicAuthority()
		database.ambientStepsFactRevisionDao().insert(
			fact(10L, authorizationFingerprint = fingerprint),
		)
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

		val read = historyRepository().readRange(
			AmbientStepsHistoryRangeRequest(listOf(AmbientStepsStructuralDay(0L, "UTC"))),
		) as AmbientStepsHistoryRead.Snapshot

		read.days.single().total shouldBe AmbientStepsHistoryValue.Exact(10L)
		read.days.single().factOrigins.single().correctionRevision shouldBe 1L
	}

	@Test
	fun `public recent history uses stable structural keyset with opaque day identity`() = runTest {
		val fingerprint = seedPublicAuthority(
			cursor().copy(
				importedThroughTimeMs = 2L * DAY_END,
				lastObservedAtMs = 2L * DAY_END,
				updatedAtMs = 2L * DAY_END,
			),
		)
		database.ambientStepsFactRevisionDao().insert(
			fact(10L, authorizationFingerprint = fingerprint),
		)
		database.ambientStepsFactRevisionDao().insert(
			fact(
				20L,
				DAY_END,
				2L * DAY_END,
				epochDay = 1L,
				authorizationFingerprint = fingerprint,
			),
		)
		val first = historyRepository().readRecent(AmbientStepsHistoryRecentRequest(1)) as
			AmbientStepsHistoryRecentRead.Page
		first.days.single().day.epochDay shouldBe 1L
		requireNotNull(first.next).publicDayIdentity.startsWith("sha256:") shouldBe true

		val second = historyRepository().readRecent(
			AmbientStepsHistoryRecentRequest(1, requireNotNull(first.next)),
		) as AmbientStepsHistoryRecentRead.Page
		second.days.single().day.epochDay shouldBe 0L
	}

	@Test
	fun `enabled cursor without a structural fact is materializing not covered zero`() = runTest {
		seedPublicAuthority()

		val page = readSnapshot().page
		page.days shouldBe emptyList()
		page.availability shouldBe AmbientStepsProductAvailability.AVAILABLE
		page.materialization shouldBe AmbientStepsProductMaterialization.MATERIALIZING
		val public = historyRepository().readRange(
			AmbientStepsHistoryRangeRequest(listOf(AmbientStepsStructuralDay(0L, "UTC"))),
		) as AmbientStepsHistoryRead.Snapshot
		public.days.single().total shouldBe AmbientStepsHistoryValue.Unavailable(
			setOf(com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause.MATERIALIZING),
		)
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

	@Test
	fun `full immutable gap authority is required before a day can be partial`() = runTest {
		seedAuthority(cursorWithGap())
		database.ambientStepsFactRevisionDao().insert(fact(5L, 0L, GAP_START))
		database.ambientStepsFactRevisionDao().insert(
			fact(5L, GAP_END, DAY_END, continuitySegmentGeneration = 2L),
		)
		database.ambientStepsImportStateDao().insertGap(gap(1L, GAP_START))

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Partial(
			10L,
			setOf(
				AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL,
				AmbientStepsDayCause.AMBIENT_GAP,
			),
		)

		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_import_gap SET gap_id = ? WHERE registration_generation = 1",
			arrayOf("sha256:${"f".repeat(64)}"),
		)
		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	@Test
	fun `stale epoch and foreign gap origin fail closed`() = runTest {
		seedAuthority(cursorWithGap())
		database.ambientStepsFactRevisionDao().insert(fact(5L, 0L, GAP_START))
		database.ambientStepsFactRevisionDao().insert(
			fact(5L, GAP_END, DAY_END, continuitySegmentGeneration = 2L),
		)
		database.ambientStepsImportStateDao().insertGap(gap(1L, GAP_START, collectedDataEpoch = 6L))
		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)

		database.ambientStepsImportStateDao().deleteAllGaps()
		database.ambientStepsImportStateDao().insertGap(
			gap(1L, GAP_START, sourceInstanceId = "foreign-instance"),
		)
		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	@Test
	fun `gap entirely before retention floor cannot downgrade retained day`() = runTest {
		seedAuthority(
			stateCursor = cursorWithGap().copy(segmentStartTimeMs = 1_000L),
			evidenceState = SourceEvidenceState(collectedDataEpoch = 7L, retainedFromMs = GAP_START),
		)
		database.ambientStepsFactRevisionDao().insert(
			fact(10L, GAP_START, DAY_END, continuitySegmentGeneration = 2L),
		)
		database.ambientStepsImportStateDao().insertGap(gap(1L, 0L))

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Partial(
			10L,
			setOf(AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL),
		)
	}

	@Test
	fun `facts on both sides of authorization rotation bind to exact historical phases`() = runTest {
		seedAuthority(rotatedCursor())
		database.ambientStepsImportStateDao().insertAuthorityTransition(authorityTransition())
		database.ambientStepsFactRevisionDao().insert(fact(4L, 0L, AUTHORITY_BOUNDARY))
		database.ambientStepsFactRevisionDao().insert(
			fact(
				6L,
				AUTHORITY_BOUNDARY,
				DAY_END,
				continuitySegmentGeneration = 2L,
				authorizationRevision = 2L,
				authorizationFingerprint = "b".repeat(64),
			),
		)

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Exact(10L)
	}

	@Test
	fun `post-rotation fact cannot retain predecessor authorization`() = runTest {
		seedAuthority(rotatedCursor())
		database.ambientStepsImportStateDao().insertAuthorityTransition(authorityTransition())
		database.ambientStepsFactRevisionDao().insert(fact(4L, 0L, AUTHORITY_BOUNDARY))
		database.ambientStepsFactRevisionDao().insert(
			fact(6L, AUTHORITY_BOUNDARY, DAY_END, continuitySegmentGeneration = 2L),
		)

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	@Test
	fun `pre-rotation fact cannot cross the exact transition boundary`() = runTest {
		seedAuthority(rotatedCursor())
		database.ambientStepsImportStateDao().insertAuthorityTransition(authorityTransition())
		database.ambientStepsFactRevisionDao().insert(fact(10L, 0L, AUTHORITY_BOUNDARY + 1_000L))

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	@Test
	fun `historical authorization rows must match the captured fact fingerprint`() = runTest {
		seedAuthority()
		database.ambientStepsFactRevisionDao().insert(fact(10L))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_authorization SET authorization_fingerprint = ? " +
				"WHERE source_kind = ? AND registration_generation = 1 AND authorization_revision = 1",
			arrayOf("f".repeat(64), SourceDestinationOwnerEntity.SOURCE_STEPS),
		)

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	@Test
	fun `pre-rotation fact cannot precede its historical authorization boundary`() = runTest {
		seedAuthority(
			stateCursor = rotatedCursor(),
			initialAuthorizationEffectiveTimeMs = 1_000L,
		)
		database.ambientStepsImportStateDao().insertAuthorityTransition(authorityTransition())
		database.ambientStepsFactRevisionDao().insert(fact(10L, 0L, AUTHORITY_BOUNDARY))

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	@Test
	fun `historical authorization from a foreign registration clock domain fails closed`() = runTest {
		seedAuthority(
			stateCursor = rotatedCursor(),
			initialAuthorizationBootId = "foreign-boot",
		)
		database.ambientStepsImportStateDao().insertAuthorityTransition(authorityTransition())
		database.ambientStepsFactRevisionDao().insert(fact(4L, 0L, AUTHORITY_BOUNDARY))

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	@Test
	fun `historical authorization elapsed boundary cannot follow its successor`() = runTest {
		seedAuthority(
			stateCursor = rotatedCursor(),
			initialAuthorizationEffectiveElapsedRealtimeNanos =
				AUTHORITY_BOUNDARY * 1_000_000L + 1L,
		)
		database.ambientStepsImportStateDao().insertAuthorityTransition(authorityTransition())
		database.ambientStepsFactRevisionDao().insert(fact(4L, 0L, AUTHORITY_BOUNDARY))

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	@Test
	fun `successor wall time cannot move backwards behind the same clamped boundary`() = runTest {
		val registrationWallTimeMs = AUTHORITY_BOUNDARY
		val predecessorWallTimeMs = AUTHORITY_BOUNDARY - 100L
		val successorWallTimeMs = AUTHORITY_BOUNDARY - 200L
		seedAuthority(
			stateCursor = rotatedCursor(
				registrationAcceptedAtMs = registrationWallTimeMs,
				authorizationEffectiveWallTimeMs = successorWallTimeMs,
			),
			initialAuthorizationEffectiveTimeMs = predecessorWallTimeMs,
		)
		database.ambientStepsImportStateDao().insertAuthorityTransition(
			authorityTransition(
				registrationAcceptedAtMs = registrationWallTimeMs,
				toAuthorizationEffectiveWallTimeMs = successorWallTimeMs,
			),
		)
		database.ambientStepsFactRevisionDao().insert(
			fact(
				4L,
				AUTHORITY_BOUNDARY,
				DAY_END,
				continuitySegmentGeneration = 2L,
				authorizationRevision = 2L,
				authorizationFingerprint = "b".repeat(64),
			),
		)

		readSnapshot().page.days.single().total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_AUTHORITY_UNVERIFIABLE),
		)
	}

	private suspend fun seedAuthority(
		stateCursor: AmbientStepsImportCursorEntity = cursor(),
		evidenceState: SourceEvidenceState = SourceEvidenceState(collectedDataEpoch = 7L),
		initialAuthorizationEffectiveTimeMs: Long = 0L,
		initialAuthorizationBootId: String = "boot-a",
		initialAuthorizationEffectiveElapsedRealtimeNanos: Long =
			initialAuthorizationEffectiveTimeMs * 1_000_000L,
		initialAuthorizationFingerprint: String = "a".repeat(64),
	) {
		database.sourceEvidenceStateDao().ensure(evidenceState)
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
		database.ambientStepsFactRevisionDao().insertRetentionAuthority(
			AmbientStepsRetentionAuthorityIntegrity.create(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				1L,
				AmbientStepsRetentionAuthorityEntity.STATE_ACTIVE,
				RETENTION_POLICY_ID,
				1L,
				1L,
				7L,
				initialAuthorizationBootId,
				initialAuthorizationEffectiveElapsedRealtimeNanos,
				initialAuthorizationEffectiveTimeMs,
				evidenceState.retainedFromMs,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			buildList {
				add(
					authorization(
						1L,
						initialAuthorizationFingerprint,
						initialAuthorizationEffectiveTimeMs,
						initialAuthorizationBootId,
						initialAuthorizationEffectiveElapsedRealtimeNanos,
					),
				)
				if (stateCursor.authorizationRevision != 1L) {
					add(
						authorization(
							stateCursor.authorizationRevision,
							stateCursor.authorizationFingerprint,
							stateCursor.authorizationEffectiveWallTimeMs,
						),
					)
				}
			},
		)
		database.ambientStepsImportStateDao().insertCursor(stateCursor)
	}

	private suspend fun seedPublicAuthority(
		stateCursor: AmbientStepsImportCursorEntity = cursor(),
	): String {
		val demand = SourceDemandEntity(
			demandId = "ambient-1",
			consumerId = "ambient-importer",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = 1L,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 1,
			minimumAcquisitionSpec = "ambient-steps:v1:mechanism=$PROVIDER;" +
				"coverage=OPPORTUNISTIC;record_freshness=SOURCE_NATIVE_CURSOR",
			maximumAgeMs = 0L,
			desiredLatencyMs = 0L,
			requestedBootId = "boot-a",
			requestedElapsedRealtimeNanos = 0L,
			requestedAtMs = 0L,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		val fingerprint = SourceBrokerAuthorization.fingerprint(listOf(demand))
		seedAuthority(
			stateCursor = stateCursor.copy(authorizationFingerprint = fingerprint),
			initialAuthorizationFingerprint = fingerprint,
		)
		return fingerprint
	}

	private suspend fun readSnapshot(): AmbientStepsDayPageResult.Snapshot = requireSnapshot(
		repository.readPage(
			AmbientStepsDayPageRequest(1),
			AmbientStepsRuntimeAvailability.AVAILABLE,
		),
	)

	private fun historyRepository() = DefaultAmbientStepsHistoryRepository(
		database,
		database.importedAmbientStepsDao(),
		StepsSegmentHistorySelector(database, SourceProductLaneExecutionAuthority { true }),
		RoomStepsCountDomainCompatibilityQuery(database),
		Dispatchers.Unconfined,
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

	private fun authorization(
		revision: Long,
		fingerprint: String,
		effectiveWallTimeMs: Long,
		effectiveBootId: String = "boot-a",
		effectiveElapsedRealtimeNanos: Long = effectiveWallTimeMs * 1_000_000L,
	) = SourceAuthorizationEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		registrationGeneration = 1L,
		authorizationRevision = revision,
		memberId = "demand:ambient-$revision",
		authorizationFingerprint = fingerprint,
		purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		demandId = "ambient-$revision",
		consumerId = "ambient-importer",
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		persistenceEligible = true,
		effectiveBootId = effectiveBootId,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = effectiveWallTimeMs,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
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
		retentionScope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		retentionPolicyId = RETENTION_POLICY_ID,
		retentionApprovalRevision = 1L,
	)

	private fun cursorWithGap() = cursor().copy(
		continuitySegmentGeneration = 2L,
		segmentStartTimeMs = GAP_END,
		lastGapSequence = 1L,
		cursorRevision = 2L,
	)

	private fun rotatedCursor(
		registrationAcceptedAtMs: Long = 0L,
		authorizationEffectiveWallTimeMs: Long = AUTHORITY_BOUNDARY,
	) = cursor().copy(
		registrationAcceptedAtMs = registrationAcceptedAtMs,
		authorizationRevision = 2L,
		authorizationFingerprint = "b".repeat(64),
		authorizationEffectiveElapsedRealtimeNanos = AUTHORITY_BOUNDARY * 1_000_000L,
		authorizationEffectiveWallTimeMs = authorizationEffectiveWallTimeMs,
		eligibleFromTimeMs = maxOf(registrationAcceptedAtMs, authorizationEffectiveWallTimeMs),
		continuitySegmentGeneration = 2L,
		segmentStartTimeMs = maxOf(registrationAcceptedAtMs, authorizationEffectiveWallTimeMs),
		authorityTransitionSequence = 1L,
		cursorRevision = 2L,
	)

	private fun authorityTransition(
		registrationAcceptedAtMs: Long = 0L,
		toAuthorizationEffectiveWallTimeMs: Long = AUTHORITY_BOUNDARY,
	): AmbientStepsImportAuthorityTransitionEntity {
		val effectiveBoundaryTimeMs = maxOf(
			registrationAcceptedAtMs,
			toAuthorizationEffectiveWallTimeMs,
		)
		val transitionId = AmbientStepsImportAuthorityTransitionIntegrity.transitionId(
			1L,
			1L,
			PROVIDER,
			SOURCE_INSTANCE,
			7L,
			1L,
			2L,
			1L,
			"a".repeat(64),
			1L,
			1L,
			2L,
			"b".repeat(64),
			"boot-a",
			AUTHORITY_BOUNDARY * 1_000_000L,
			toAuthorizationEffectiveWallTimeMs,
			1L,
			1L,
			registrationAcceptedAtMs,
			effectiveBoundaryTimeMs,
		)
		return AmbientStepsImportAuthorityTransitionEntity(
			transitionId,
			1L,
			1L,
			PROVIDER,
			SOURCE_INSTANCE,
			7L,
			1L,
			2L,
			1L,
			"a".repeat(64),
			1L,
			1L,
			2L,
			"b".repeat(64),
			"boot-a",
			AUTHORITY_BOUNDARY * 1_000_000L,
			toAuthorizationEffectiveWallTimeMs,
			1L,
			1L,
			registrationAcceptedAtMs,
			effectiveBoundaryTimeMs,
			DAY_END,
		)
	}

	private fun fact(
		count: Long,
		startTimeMs: Long = 0L,
		endTimeMs: Long = DAY_END,
		epochDay: Long = 0L,
		continuitySegmentGeneration: Long = 1L,
		authorizationRevision: Long = 1L,
		authorizationFingerprint: String = "a".repeat(64),
	): AmbientStepsFactRevisionEntity {
		val dayStartTimeMs = epochDay * DAY_END
		val dayEndTimeMs = dayStartTimeMs + DAY_END
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			PROVIDER, 1L, continuitySegmentGeneration, SOURCE_INSTANCE, startTimeMs, epochDay, "UTC", 7L,
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
				continuitySegmentGeneration = continuitySegmentGeneration,
				sourceInstanceId = SOURCE_INSTANCE,
				authorizationRevision = authorizationRevision,
				authorizationFingerprint = authorizationFingerprint,
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
				retentionScope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				retentionPolicyId = RETENTION_POLICY_ID,
				retentionApprovalRevision = 1L,
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
			retentionScope = null,
			retentionPolicyId = null,
			retentionApprovalRevision = null,
		),
	)

	private fun gap(
		sequence: Long,
		startTimeMs: Long,
		collectedDataEpoch: Long = 7L,
		sourceInstanceId: String = SOURCE_INSTANCE,
	): AmbientStepsImportGapEntity {
		val endTimeMs = startTimeMs + 1_000L
		val gapId = AmbientStepsImportGapIntegrity.gapId(
			registrationGeneration = 1L,
			gapSequence = sequence,
			provider = PROVIDER,
			sourceInstanceId = sourceInstanceId,
			reason = AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
			gapStartTimeMs = startTimeMs,
			gapEndTimeMs = endTimeMs,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = "boot-a",
			nextClockDomainId = "boot-a",
			previousZoneId = "UTC",
			nextZoneId = "UTC",
			collectedDataEpoch = collectedDataEpoch,
		)
		return AmbientStepsImportGapEntity(
			gapId = gapId,
			registrationGeneration = 1L,
			gapSequence = sequence,
			provider = PROVIDER,
			sourceInstanceId = sourceInstanceId,
			reason = AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
			gapStartTimeMs = startTimeMs,
			gapEndTimeMs = endTimeMs,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = "boot-a",
			nextClockDomainId = "boot-a",
			previousZoneId = "UTC",
			nextZoneId = "UTC",
			collectedDataEpoch = collectedDataEpoch,
			recordedAtMs = endTimeMs,
		)
	}

	private fun signed(fact: AmbientStepsFactRevisionEntity) = fact.copy(
		effectChecksum = AmbientStepsFactIntegrity.effectChecksum(fact),
	)

	private companion object {
		const val RETENTION_POLICY_ID = "test-retention"
		const val DAY_END = 86_400_000L
		const val GAP_START = 10_000L
		const val GAP_END = 11_000L
		const val AUTHORITY_BOUNDARY = 43_200_000L
		const val SOURCE_INSTANCE = "ambient-instance"
		const val PROVIDER = AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS
	}
}
