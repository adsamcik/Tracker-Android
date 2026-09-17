package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientStepsMaintenanceTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `retention removes whole crossing lineage and preserves truthful partial suffix`() = runTest {
		val fixture = seed(revoked = false, retainedFromMs = 1_500L)
		val prefix = fixture.fact(0L, 1_000L, 4L)
		val crossing = fixture.fact(1_000L, 2_000L, 5L)
		val retained = fixture.fact(2_000L, DAY_END, 6L)
		listOf(prefix, crossing, retained).forEach {
			database.ambientStepsFactRevisionDao().insert(it)
		}

		database.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
			beforeMs = 1_500L,
			collectedDataEpoch = EPOCH,
			markedAtMs = MAINTENANCE_TIME,
		) shouldBe 2

		database.ambientStepsFactRevisionDao().latestEffectiveForDay(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			0L,
			"UTC",
		) shouldContainExactly listOf(retained)
		database.ambientStepsImportStateDao().cursor(REGISTRATION) shouldBe fixture.cursor
	}

	@Test
	fun `consent reset installs redacted fences before removing payload and import authority`() = runTest {
		val fixture = seed(revoked = true)
		val first = fixture.fact(0L, 1_000L, 4L)
		val second = fixture.fact(1_000L, DAY_END, 5L)
		listOf(first, second).forEach { database.ambientStepsFactRevisionDao().insert(it) }

		database.deleteAmbientStepsAfterConsentReset(EPOCH, REVOKED_CONSENT, MAINTENANCE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Deleted(
				retractedLogicalFactCount = 2,
				removedPayloadRevisionCount = 2,
			)

		listOf(first, second).forEach { fact ->
			val revisions = database.ambientStepsFactRevisionDao().revisions(
				AmbientStepsFactRevisionEntity.WRITER_ID,
				AmbientStepsFactRevisionEntity.WRITER_VERSION,
				fact.logicalFactId,
			)
			revisions.size shouldBe 1
			val retraction = revisions.single()
			retraction.operation shouldBe AmbientStepsFactRevisionEntity.OPERATION_RETRACT
			retraction.originKind shouldBe AmbientStepsFactRevisionEntity.ORIGIN_LOCAL_DELETE
			retraction.provider shouldBe null
			retraction.windowStartTimeMs shouldBe null
			retraction.stepCount shouldBe null
			retraction.scopeDeletionGeneration shouldBe 1L
			AmbientStepsFactIntegrity.hasValidEffectChecksum(retraction) shouldBe true
		}
		database.ambientStepsImportStateDao().countCursors() shouldBe 0L
		database.ambientStepsImportStateDao().countGaps() shouldBe 0L
		database.ambientStepsImportStateDao().countAuthorityTransitions() shouldBe 0L

		// A delayed exact old UPSERT may physically reappear, but the higher redacted revision remains
		// authoritative and product discovery cannot resurrect it.
		(database.ambientStepsFactRevisionDao().insert(first) >= 0L) shouldBe true
		database.ambientStepsFactRevisionDao().latestEffectiveForDay(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			0L,
			"UTC",
		).isEmpty() shouldBe true
	}

	@Test
	fun `deletion retry cleans replayed correction lineage behind its terminal retraction`() = runTest {
		val fixture = seed(revoked = true)
		val first = fixture.fact(0L, 1_000L, 4L)
		val correction = fixture.correction(first, endTimeMs = 2_000L, stepCount = 5L)
		listOf(first, correction).forEach { database.ambientStepsFactRevisionDao().insert(it) }

		database.deleteAmbientStepsAfterConsentReset(EPOCH, REVOKED_CONSENT, MAINTENANCE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Deleted(1, 2)
		val retraction = database.ambientStepsFactRevisionDao().revisions(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			first.logicalFactId,
		).single()
		retraction.semanticRevision shouldBe 3L
		retraction.operation shouldBe AmbientStepsFactRevisionEntity.OPERATION_RETRACT
		val portableFactIdentity = com.adsamcik.tracker.shared.model.steps.portable
			.AmbientStepsPortableOpaqueIdentity.derive(
				com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind.FACT,
				first.logicalFactId,
			).value
		AmbientStepsPortableLocalOriginReader(database).readInTransaction(
			setOf(portableFactIdentity),
		).single().let { owner ->
			owner.identity shouldBe portableFactIdentity
			owner.state shouldBe AmbientStepsPortableLocalOwnerState.DELETED
			owner.contentChecksum shouldBe null
		}

		// Delayed exact rows may physically return, but the terminal higher revision remains the
		// effective state and supplies cleanup authority after source-deletion removed the cursor.
		listOf(first, correction).forEach { database.ambientStepsFactRevisionDao().insert(it) }
		database.ambientStepsFactRevisionDao().latestEffectiveForDay(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			0L,
			"UTC",
		).isEmpty() shouldBe true

		database.deleteAmbientStepsAfterConsentReset(
			EPOCH,
			REVOKED_CONSENT,
			MAINTENANCE_TIME + 1L,
		) shouldBe AmbientStepsSourceDeletionResult.Deleted(0, 2)
		database.ambientStepsFactRevisionDao().revisions(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			first.logicalFactId,
		) shouldContainExactly listOf(retraction)
		database.ambientStepsFactRevisionDao().countPayloadBearingRows() shouldBe 0L

		database.deleteAmbientStepsAfterConsentReset(
			EPOCH,
			REVOKED_CONSENT,
			MAINTENANCE_TIME + 2L,
		) shouldBe AmbientStepsSourceDeletionResult.AlreadyDeleted
	}

	@Test
	fun `retention may remove a complete replayed terminal lineage behind its floor`() = runTest {
		val fixture = seed(revoked = true, retainedFromMs = 1_500L)
		val first = fixture.fact(0L, 1_000L, 4L)
		val correction = fixture.correction(first, endTimeMs = 2_000L, stepCount = 5L)
		listOf(first, correction).forEach { database.ambientStepsFactRevisionDao().insert(it) }
		database.deleteAmbientStepsAfterConsentReset(EPOCH, REVOKED_CONSENT, MAINTENANCE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Deleted(1, 2)
		listOf(first, correction).forEach { database.ambientStepsFactRevisionDao().insert(it) }

		database.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
			beforeMs = 1_500L,
			collectedDataEpoch = EPOCH,
			markedAtMs = MAINTENANCE_TIME + 1L,
		) shouldBe 3

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.retainedFromMs shouldBe 1_500L
	}

	@Test
	fun `eligible or nonquiesced ambient authority blocks source deletion without mutation`() = runTest {
		val eligible = seed(revoked = false)
		val fact = eligible.fact(0L, DAY_END, 10L)
		database.ambientStepsFactRevisionDao().insert(fact)

		database.deleteAmbientStepsAfterConsentReset(EPOCH, ACTIVE_CONSENT, MAINTENANCE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Blocked(
				AmbientStepsSourceDeletionBlockedReason.AMBIENT_CONSENT_STILL_ELIGIBLE,
			)
		database.ambientStepsFactRevisionDao().latest(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			fact.logicalFactId,
		) shouldBe fact
	}

	@Test
	fun `active ambient demand blocks deletion after consent revocation`() = runTest {
		val fixture = seed(revoked = true)
		database.sourceBrokerDao().insertDemands(
			listOf(
				fixture.historicalDemand.copy(
					demandId = "still-active-ambient",
					consumerId = "still-active-consumer",
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)

		database.deleteAmbientStepsAfterConsentReset(EPOCH, REVOKED_CONSENT, MAINTENANCE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Blocked(
				AmbientStepsSourceDeletionBlockedReason.AMBIENT_DEMAND_NOT_QUIESCED,
			)
	}

	@Test
	fun `shared provider registration remains ambient-compatible`() = runTest {
		seed(revoked = true)
		insertNonterminalRegistration(
			generation = 2L,
			ownerScope = SourceProviderPurposeScope.sharedOwnerScope(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			),
		)

		assertAmbientProviderBlocksDeletion()
	}

	@Test
	fun `combined-purpose provider registration remains ambient-compatible`() = runTest {
		seed(revoked = true)
		insertNonterminalRegistration(
			generation = 2L,
			ownerScope = SourceProviderPurposeScope.exactOwnerScope(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceBrokerPurpose.MASK_AMBIENT_PRODUCT or
					SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			),
		)

		assertAmbientProviderBlocksDeletion()
	}

	@Test
	fun `legacy unscoped provider registration remains ambient-compatible`() = runTest {
		seed(revoked = true)
		insertNonterminalRegistration(generation = 2L, ownerScope = "legacy-steps-owner")

		assertAmbientProviderBlocksDeletion()
	}

	@Test
	fun `malformed broker provider scope fails closed during deletion`() = runTest {
		seed(revoked = true)
		insertNonterminalRegistration(
			generation = 2L,
			ownerScope = SourceProviderPurposeScope.sharedOwnerScope(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			) + ":purposes=not-a-mask",
		)

		assertAmbientProviderBlocksDeletion()
	}

	@Test
	fun `active-demand quiescence scan overflows as a typed blocked result`() = runTest {
		val fixture = seed(revoked = true)
		database.sourceBrokerDao().insertDemands(
			(1..2).map { index ->
				fixture.historicalDemand.copy(
					demandId = "overflow-demand-$index",
					consumerId = "overflow-consumer-$index",
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				)
			},
		)

		val result = database.deleteAmbientStepsAfterConsentReset(
			expectedCollectedDataEpoch = EPOCH,
			expectedRevokedConsentEpoch = REVOKED_CONSENT,
			deletedAtMs = MAINTENANCE_TIME,
			limits = AmbientStepsMaintenanceLimits(maximumActiveDemands = 1),
			checkpoint = {},
		)

		result shouldBe AmbientStepsSourceDeletionResult.Blocked(
			AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	}

	@Test
	fun `registration quiescence scans overflow before provider compatibility is inferred`() = runTest {
		seed(revoked = true)
		(2L..3L).forEach { generation ->
			insertNonterminalRegistration(
				generation = generation,
				ownerScope = SourceProviderPurposeScope.exactOwnerScope(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
				),
			)
		}

		val result = database.deleteAmbientStepsAfterConsentReset(
			expectedCollectedDataEpoch = EPOCH,
			expectedRevokedConsentEpoch = REVOKED_CONSENT,
			deletedAtMs = MAINTENANCE_TIME,
			limits = AmbientStepsMaintenanceLimits(maximumCurrentRegistrations = 1),
			checkpoint = {},
		)

		result shouldBe AmbientStepsSourceDeletionResult.Blocked(
			AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	}

	@Test
	fun `pending-removal quiescence scan is bounded independently`() = runTest {
		seed(revoked = true)
		(2L..3L).forEach { generation ->
			insertNonterminalRegistration(
				generation = generation,
				ownerScope = SourceProviderPurposeScope.exactOwnerScope(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
				),
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			)
		}

		val result = database.deleteAmbientStepsAfterConsentReset(
			expectedCollectedDataEpoch = EPOCH,
			expectedRevokedConsentEpoch = REVOKED_CONSENT,
			deletedAtMs = MAINTENANCE_TIME,
			limits = AmbientStepsMaintenanceLimits(maximumPendingProviderRemovals = 1),
			checkpoint = {},
		)

		result shouldBe AmbientStepsSourceDeletionResult.Blocked(
			AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	}

	@Test
	fun `authorization members are bounded before demand traversal`() = runTest {
		val fixture = seed(revoked = true, authorizationMemberCount = 2)
		val fact = fixture.fact(0L, DAY_END, 10L)
		database.ambientStepsFactRevisionDao().insert(fact)

		val result = database.deleteAmbientStepsAfterConsentReset(
			expectedCollectedDataEpoch = EPOCH,
			expectedRevokedConsentEpoch = REVOKED_CONSENT,
			deletedAtMs = MAINTENANCE_TIME,
			limits = AmbientStepsMaintenanceLimits(maximumAuthorizationMembersPerRevision = 1),
			checkpoint = {},
		)

		result shouldBe AmbientStepsSourceDeletionResult.Blocked(
			AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
		database.ambientStepsFactRevisionDao().latest(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			fact.logicalFactId,
		) shouldBe fact
	}

	@Test
	fun `foreign-version and malformed-operation payload block source deletion`() = runTest {
		val fixture = seed(revoked = true)
		val foreign = fixture.fact(0L, 1_000L, 4L)
		val malformed = fixture.fact(1_000L, DAY_END, 5L)
		listOf(foreign, malformed).forEach { database.ambientStepsFactRevisionDao().insert(it) }
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_fact_revision SET writer_version = 99 " +
				"WHERE logical_fact_id = ?",
			arrayOf(foreign.logicalFactId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_fact_revision SET operation = 'CORRUPT' " +
				"WHERE logical_fact_id = ?",
			arrayOf(malformed.logicalFactId),
		)

		database.deleteAmbientStepsAfterConsentReset(EPOCH, REVOKED_CONSENT, MAINTENANCE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Blocked(
				AmbientStepsSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT,
			)
		database.ambientStepsFactRevisionDao().countPayloadBearingRows() shouldBe 2L
	}

	@Test
	fun `retention fails before silently skipping malformed payload`() = runTest {
		val fixture = seed(revoked = false, retainedFromMs = 1_500L)
		val fact = fixture.fact(0L, 1_000L, 4L)
		database.ambientStepsFactRevisionDao().insert(fact)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_fact_revision SET operation = 'CORRUPT' " +
				"WHERE logical_fact_id = ?",
			arrayOf(fact.logicalFactId),
		)

		val failure = runCatching {
			database.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
				beforeMs = 1_500L,
				collectedDataEpoch = EPOCH,
				markedAtMs = MAINTENANCE_TIME,
			)
		}.exceptionOrNull()

		(failure is IllegalStateException) shouldBe true
		database.ambientStepsFactRevisionDao().countPayloadBearingRows() shouldBe 1L
	}

	@Test
	fun `authorization cache cannot hide a mismatched fact retention identity`() = runTest {
		val fixture = seed(revoked = false, retainedFromMs = 1_500L)
		val facts = listOf(
			fixture.fact(0L, 1_000L, 4L),
			fixture.fact(1_000L, 2_000L, 5L),
		).sortedBy(AmbientStepsFactRevisionEntity::logicalFactId)
		val invalidDraft = facts.last().copy(
			retentionPolicyId = "other-retention",
			effectChecksum = "0".repeat(64),
		)
		val invalid = invalidDraft.copy(
			effectChecksum = AmbientStepsFactIntegrity.effectChecksum(invalidDraft),
		)
		database.ambientStepsFactRevisionDao().insert(facts.first())
		database.ambientStepsFactRevisionDao().insert(invalid)

		val failure = runCatching {
			database.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
				beforeMs = 1_500L,
				collectedDataEpoch = EPOCH,
				markedAtMs = MAINTENANCE_TIME,
			)
		}.exceptionOrNull()

		(failure is IllegalStateException) shouldBe true
		database.ambientStepsFactRevisionDao().countPayloadBearingRows() shouldBe 2L
	}

	@Test
	fun `cancellation after deletion fences rolls the whole source mutation back`() = runTest {
		val fixture = seed(revoked = true)
		val fact = fixture.fact(0L, DAY_END, 10L)
		database.ambientStepsFactRevisionDao().insert(fact)

		val cancelled = runCatching {
			database.deleteAmbientStepsAfterConsentReset(
				expectedCollectedDataEpoch = EPOCH,
				expectedRevokedConsentEpoch = REVOKED_CONSENT,
				deletedAtMs = MAINTENANCE_TIME,
				limits = AmbientStepsMaintenanceLimits(),
				checkpoint = { checkpoint ->
					if (checkpoint == AmbientStepsMaintenanceCheckpoint.RETRACTIONS_INSTALLED) {
						throw CancellationException("cancel after fence")
					}
				},
			)
		}.exceptionOrNull()

		(cancelled is CancellationException) shouldBe true
		database.ambientStepsFactRevisionDao().revisions(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			fact.logicalFactId,
		) shouldContainExactly listOf(fact)
		database.ambientStepsImportStateDao().cursor(REGISTRATION) shouldBe fixture.cursor
		database.ambientStepsFactRevisionDao().nativeReplayFootprintCount() shouldBe 0L
	}

	private suspend fun seed(
		revoked: Boolean,
		retainedFromMs: Long? = null,
		authorizationMemberCount: Int = 1,
	): Fixture {
		require(authorizationMemberCount > 0)
		val evidenceDao = database.sourceEvidenceStateDao()
		evidenceDao.ensure()
		evidenceDao.synchronizeLifecycle(EPOCH, retainedFromMs, 0L)
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS,
				OWNER_GENERATION,
				0L,
			),
		)
		val historicalPolicy = policy(
			revision = HISTORICAL_POLICY,
			ambientConsentEpoch = ACTIVE_CONSENT,
			ambientEligible = true,
		)
		val currentPolicy = if (revoked) {
			policy(CURRENT_POLICY, ambientConsentEpoch = null, ambientEligible = false)
		} else {
			historicalPolicy
		}
		database.sourcePolicyDao().insertPolicies(
			listOf(historicalPolicy) + listOfNotNull(
				currentPolicy.takeIf { it.policyRevision != historicalPolicy.policyRevision },
			),
		)
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(
				consent(ACTIVE_CONSENT, HISTORICAL_POLICY, eligible = true),
			) + listOfNotNull(
				consent(REVOKED_CONSENT, CURRENT_POLICY, eligible = false).takeIf { revoked },
			),
		)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = currentPolicy.policyRevision,
				legacySettingsFingerprint = null,
				updatedAtMs = if (revoked) 2_000L else 0L,
			),
		)
		database.ambientStepsFactRevisionDao().insertRetentionAuthority(
			AmbientStepsRetentionAuthorityIntegrity.create(
				scope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				approvalRevision = 1L,
				state = AmbientStepsRetentionAuthorityEntity.STATE_ACTIVE,
				opaquePolicyId = RETENTION_POLICY_ID,
				sourcePolicyRevision = HISTORICAL_POLICY,
				ambientConsentEpoch = ACTIVE_CONSENT,
				collectedDataEpoch = EPOCH,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 0L,
				effectiveWallTimeMs = 0L,
			),
		)
		val demands = (1..authorizationMemberCount).map { index ->
			SourceDemandEntity(
				demandId = "ambient-demand-$index",
				consumerId = "ambient-consumer-$index",
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
				logicalTrackingId = null,
				serviceRunId = null,
				manifestRevision = null,
				lifecycleLeaseGeneration = null,
				sourcePolicyRevision = HISTORICAL_POLICY,
				consentEpoch = ACTIVE_CONSENT,
				persistenceEligible = true,
				qosCode = 1,
				minimumAcquisitionSpec = "ambient-steps:v1:LOCAL_RECORDING_STEPS",
				maximumAgeMs = 0L,
				desiredLatencyMs = 0L,
				requestedBootId = BOOT_ID,
				requestedElapsedRealtimeNanos = 0L,
				requestedAtMs = 0L,
				status = SourceDemandEntity.STATUS_RETIRED,
				retireBootId = BOOT_ID,
				retireElapsedRealtimeNanos = DAY_END,
				retiredAtMs = DAY_END,
			)
		}
		database.sourceBrokerDao().insertDemands(demands)
		val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				registrationGeneration = REGISTRATION,
				sourceInstanceId = SOURCE_INSTANCE,
				ownerScope = SourceProviderPurposeScope.exactOwnerScope(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
				),
				clockDomainId = BOOT_ID,
				physicalConfigurationFingerprint =
					"ambient-steps-provider:v1:mechanism=$PROVIDER",
				collectedDataEpoch = EPOCH,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				reservedAtMs = 0L,
				reservedElapsedRealtimeNanos = 0L,
				acceptedAtMs = 0L,
				acceptedElapsedRealtimeNanos = 0L,
				retiredAtMs = DAY_END,
				retiredElapsedRealtimeNanos = DAY_END,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				REGISTRATION,
				AUTHORIZATION,
				demands,
				BOOT_ID,
				0L,
				0L,
			),
		)
		val cursor = AmbientStepsImportCursorEntity(
			registrationGeneration = REGISTRATION,
			provider = PROVIDER,
			sourceInstanceId = SOURCE_INSTANCE,
			registrationClockDomainId = BOOT_ID,
			registrationAcceptedAtMs = 0L,
			registrationAcceptedElapsedRealtimeNanos = 0L,
			authorizationRevision = AUTHORIZATION,
			authorizationFingerprint = fingerprint,
			authorizationEffectiveBootId = BOOT_ID,
			authorizationEffectiveElapsedRealtimeNanos = 0L,
			authorizationEffectiveWallTimeMs = 0L,
			sourcePolicyRevision = HISTORICAL_POLICY,
			ambientConsentEpoch = ACTIVE_CONSENT,
			collectedDataEpoch = EPOCH,
			eligibleFromTimeMs = 0L,
			continuitySegmentGeneration = 1L,
			segmentStartTimeMs = 0L,
			importedThroughTimeMs = DAY_END,
			lastObservedAtMs = DAY_END,
			lastObservedBootId = BOOT_ID,
			lastObservedZoneId = "UTC",
			lastGapSequence = 0L,
			authorityTransitionSequence = 0L,
			cursorRevision = 2L,
			status = AmbientStepsImportCursorEntity.STATUS_RETIRED,
			updatedAtMs = DAY_END,
			retentionScope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			retentionPolicyId = RETENTION_POLICY_ID,
			retentionApprovalRevision = 1L,
		)
		database.ambientStepsImportStateDao().insertCursor(cursor)
		return Fixture(cursor, fingerprint, demands.first())
	}

	private fun policy(
		revision: Long,
		ambientConsentEpoch: Long?,
		ambientEligible: Boolean,
	) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = false,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = ambientEligible,
		captureConsentEpoch = null,
		controlConsentEpoch = null,
		ambientConsentEpoch = ambientConsentEpoch,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (ambientEligible) 0L else 2_000L,
		effectiveWallTimeMs = if (ambientEligible) 0L else 2_000L,
		changeReason = "test",
	)

	private fun consent(
		epoch: Long,
		policyRevision: Long,
		eligible: Boolean,
	) = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		epoch = epoch,
		eligible = eligible,
		persistenceEligible = eligible,
		policyRevision = policyRevision,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (eligible) 0L else 2_000L,
		effectiveWallTimeMs = if (eligible) 0L else 2_000L,
		changeReason = "test",
	)

	private data class Fixture(
		val cursor: AmbientStepsImportCursorEntity,
		val authorizationFingerprint: String,
		val historicalDemand: SourceDemandEntity,
	) {
		fun fact(startTimeMs: Long, endTimeMs: Long, stepCount: Long): AmbientStepsFactRevisionEntity {
			val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
				provider = PROVIDER,
				registrationGeneration = REGISTRATION,
				continuitySegmentGeneration = 1L,
				sourceInstanceId = SOURCE_INSTANCE,
				windowStartTimeMs = startTimeMs,
				structuralEpochDay = 0L,
				storedZoneId = "UTC",
				collectedDataEpoch = EPOCH,
			)
			val unsigned = AmbientStepsFactRevisionEntity(
				logicalFactId = logicalFactId,
				semanticRevision = 1L,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					logicalFactId,
					1L,
					AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
				writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
				writerOwnerGeneration = OWNER_GENERATION,
				operation = AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				originKind = AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
				provider = PROVIDER,
				registrationGeneration = REGISTRATION,
				continuitySegmentGeneration = 1L,
				sourceInstanceId = SOURCE_INSTANCE,
				authorizationRevision = AUTHORIZATION,
				authorizationFingerprint = authorizationFingerprint,
				windowStartTimeMs = startTimeMs,
				windowEndTimeMs = endTimeMs,
				observedAtMs = endTimeMs,
				structuralEpochDay = 0L,
				storedZoneId = "UTC",
				structuralDayStartTimeMs = 0L,
				structuralDayEndTimeMs = DAY_END,
				stepCount = stepCount,
				purpose = AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
				sourcePolicyRevision = HISTORICAL_POLICY,
				ambientConsentEpoch = ACTIVE_CONSENT,
				collectedDataEpoch = EPOCH,
				scopeDeletionGeneration = 0L,
				effectChecksum = "0".repeat(64),
				appliedAtMs = endTimeMs,
				retentionScope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				retentionPolicyId = RETENTION_POLICY_ID,
				retentionApprovalRevision = 1L,
			)
			return unsigned.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned))
		}

		fun correction(
			previous: AmbientStepsFactRevisionEntity,
			endTimeMs: Long,
			stepCount: Long,
		): AmbientStepsFactRevisionEntity {
			val revision = Math.addExact(previous.semanticRevision, 1L)
			val unsigned = previous.copy(
				semanticRevision = revision,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					previous.logicalFactId,
					revision,
					AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				windowEndTimeMs = endTimeMs,
				observedAtMs = endTimeMs,
				stepCount = stepCount,
				effectChecksum = "0".repeat(64),
				appliedAtMs = endTimeMs,
			)
			return unsigned.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned))
		}
	}

	private suspend fun insertNonterminalRegistration(
		generation: Long,
		ownerScope: String,
		status: String = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
	) {
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				registrationGeneration = generation,
				sourceInstanceId = "nonterminal-source-$generation",
				ownerScope = ownerScope,
				clockDomainId = BOOT_ID,
				physicalConfigurationFingerprint =
					"ambient-steps-provider:v1:mechanism=$PROVIDER",
				collectedDataEpoch = EPOCH,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = status,
				reservedAtMs = 2_000L,
				reservedElapsedRealtimeNanos = 2_000_000_000L,
				acceptedAtMs = 2_000L,
				acceptedElapsedRealtimeNanos = 2_000_000_000L,
				retiredAtMs = if (status == ProviderRegistrationGenerationEntity.STATUS_RETIRING) {
					3_000L
				} else {
					null
				},
				retiredElapsedRealtimeNanos =
					if (status == ProviderRegistrationGenerationEntity.STATUS_RETIRING) {
						3_000_000_000L
					} else {
						null
					},
				failureCode = null,
			),
		)
	}

	private suspend fun assertAmbientProviderBlocksDeletion() {
		database.deleteAmbientStepsAfterConsentReset(EPOCH, REVOKED_CONSENT, MAINTENANCE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Blocked(
				AmbientStepsSourceDeletionBlockedReason.AMBIENT_PROVIDER_NOT_QUIESCED,
			)
	}

	private companion object {
		const val EPOCH = 7L
		const val OWNER_GENERATION = 1L
		const val REGISTRATION = 1L
		const val AUTHORIZATION = 1L
		const val HISTORICAL_POLICY = 1L
		const val CURRENT_POLICY = 2L
		const val ACTIVE_CONSENT = 1L
		const val REVOKED_CONSENT = 2L
		const val SOURCE_INSTANCE = "ambient-maintenance-source"
		const val BOOT_ID = "ambient-maintenance-boot"
		const val RETENTION_POLICY_ID = "test-retention"
		const val PROVIDER = AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS
		const val DAY_END = 86_400_000L
		const val MAINTENANCE_TIME = 90_000_000L
	}
}
