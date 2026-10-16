package com.adsamcik.tracker.tracker.source.runtime

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEffectChecksum
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.coEvery
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// One shared Room broker fixture keeps purpose joins, exact fencing and retirement checks comparable.
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerTest : SourceBrokerRoomTestFixture() {
	@Test
	fun `revocation fences blocked demand before foreground activation in one transaction`() =
		runTest {
			val demand = SourceDemandEntity(
				demandId = "blocked-before-revocation",
				consumerId = "session:logical",
				sourceKind = SourceKind.LOCATION.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "logical",
				serviceRunId = "run",
				manifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				sourcePolicyRevision = 1L,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 0,
				maximumAgeMs = 0L,
				desiredLatencyMs = 0L,
				requestedBootId = "boot-1",
				requestedElapsedRealtimeNanos = 10L,
				requestedAtMs = 10L,
				status = SourceDemandEntity.STATUS_BLOCKED,
				retireBootId = null,
				retireElapsedRealtimeNanos = null,
				retiredAtMs = null,
				sourceCallerAuthorityReference = "caller-ref",
			)
			database.sourceBrokerDao().insertDemands(listOf(demand))

			val activated = database.withTransaction {
				database.sourceBrokerDao().markSourcePurposesRetiring(
					SourceKind.LOCATION.stableCode,
					listOf(SourceBrokerPurpose.SESSION_CAPTURE),
					"boot-1",
					20L,
					20L,
				)
				subject.activatePreparedSessionDemandsInTransaction(
					logicalTrackingId = "logical",
					serviceRunId = "run",
					manifestRevision = 1L,
					leaseGeneration = 1L,
					sourceCallerAuthorityReference = "caller-ref",
					bootId = "boot-1",
					elapsedRealtimeNanos = 21L,
					wallTimeMs = 21L,
					currentAuthority = SourceCallerCurrentAuthorityPredicate { _, _, _ -> true },
				)
			}

			activated shouldBe false
			database.sourceBrokerDao().demandsByIds(listOf(demand.demandId))
				.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
		}

	@Test
	fun `prepared activation leaves blocked demand closed when caller authority is stale`() =
		runTest {
			val demand = SourceDemandEntity(
				demandId = "blocked-stale-authority",
				consumerId = "session:logical",
				sourceKind = SourceKind.LOCATION.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "logical",
				serviceRunId = "run",
				manifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				sourcePolicyRevision = 1L,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 0,
				maximumAgeMs = 0L,
				desiredLatencyMs = 0L,
				requestedBootId = "boot-1",
				requestedElapsedRealtimeNanos = 10L,
				requestedAtMs = 10L,
				status = SourceDemandEntity.STATUS_BLOCKED,
				retireBootId = null,
				retireElapsedRealtimeNanos = null,
				retiredAtMs = null,
				sourceCallerAuthorityReference = "caller-ref",
			)
			database.sourceBrokerDao().insertDemands(listOf(demand))

			subject.activatePreparedSessionDemandsInTransaction(
				logicalTrackingId = "logical",
				serviceRunId = "run",
				manifestRevision = 1L,
				leaseGeneration = 1L,
				sourceCallerAuthorityReference = "caller-ref",
				bootId = "boot-1",
				elapsedRealtimeNanos = 20L,
				wallTimeMs = 20L,
				currentAuthority = SourceCallerCurrentAuthorityPredicate { _, _, _ -> false },
			) shouldBe false

			database.sourceBrokerDao().demandsByIds(listOf(demand.demandId))
				.single().status shouldBe SourceDemandEntity.STATUS_BLOCKED
		}

	@Test
	fun `malformed caller authority cannot roll back blocked automatic demand retirement`() = runTest {
		val reference = "malformed-automatic"
		database.sourceCallerAuthorityDao().insert(
			SourceCallerAcceptedAuthorityEffectChecksum.seal(
				listOf(
					SourceCallerAcceptedAuthorityEntity(
						reference = reference,
						formatVersion = SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION,
						origin = "PURPOSE_OWNER",
						acceptedPurpose = "CONTROL",
						sourceKind = SourceKind.ACTIVITY.stableCode,
						purpose = "CONTROL",
						policyRevision = 1L,
						consentEpoch = 1L,
						collectedDataEpoch = 3L,
						rolloutRevision = 1L,
						executionRevision = 1L,
						ownerCasToken = "",
						logicalTrackingId = null,
						manifestRevision = null,
						status = SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE,
						createdAtMs = 1L,
						retiredAtMs = null,
						retireReason = null,
						effectChecksum = "pending",
					),
				),
			),
		)
		val demand = purposeDemand(
			id = "blocked-automatic",
			consumerId = "app:automatic-start:activity",
			source = SourceKind.ACTIVITY,
			purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
			status = SourceDemandEntity.STATUS_BLOCKED,
			reference = reference,
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))

		subject.retirePurposeDemand(
			demand.consumerId,
			demand.sourceKind,
			demand.purpose,
			"boot-1",
			20L,
			20L,
		) shouldBe true

		database.sourceBrokerDao().demandsByIds(listOf(demand.demandId))
			.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
		database.sourceCallerAuthorityDao().rows(reference).shouldBeEmpty()
	}

	@Test
	fun `caller authority storage failure cannot roll back exact demand fencing`() = runTest {
		val unavailableRepository = mockk<SourceCallerAcceptedAuthorityRepository>()
		coEvery {
			unavailableRepository.retireForTeardown(any(), any(), any())
		} throws IllegalStateException("caller authority unavailable")
		val broker = SourceBroker(
			database,
			rolloutStore,
			RejectingAmbientRadioMutationLeaseGuard,
			unavailableRepository,
			retentionReader,
		)
		val demand = purposeDemand(
			id = "active-ambient",
			consumerId = "app:ambient:steps",
			source = SourceKind.STEPS,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			status = SourceDemandEntity.STATUS_ACTIVE,
			reference = "unavailable-authority",
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))

		broker.retirePurposeDemand(
			demand.consumerId,
			demand.sourceKind,
			demand.purpose,
			"boot-1",
			30L,
			30L,
		) shouldBe false

		database.sourceBrokerDao().demandsByIds(listOf(demand.demandId))
			.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
	}

	@Test
	fun `superseded session authority retirement propagates caller cleanup failure`() = runTest {
		val reference = SourceCallerReplayReference("superseded-retirement-failure")
		val unavailableRepository = mockk<SourceCallerAcceptedAuthorityRepository>()
		coEvery {
			unavailableRepository.load(reference)
		} returns StoredSourceCallerAuthorityLoadResult.Available(mockk())
		coEvery {
			unavailableRepository.retireForTeardown(reference, any(), any())
		} returns false
		val broker = SourceBroker(
			database,
			rolloutStore,
			RejectingAmbientRadioMutationLeaseGuard,
			unavailableRepository,
			retentionReader,
		)
		database.sourceBrokerDao().insertDemands(
			listOf(
				purposeDemand(
					id = "superseded-session-demand",
					consumerId = broker.sessionConsumerId("logical"),
					source = SourceKind.STEPS,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					status = SourceDemandEntity.STATUS_ACTIVE,
					reference = reference.value,
				),
			),
		)

		database.withTransaction {
			broker.retireSupersededSessionAuthoritiesInTransaction(
				logicalTrackingId = "logical",
				currentReference = SourceCallerReplayReference("current-authority"),
				expectedSupersededReference = reference,
				wallTimeMs = 200L,
			)
		} shouldBe SourceCallerAuthorityRetirementOutcome.Retryable(
			SourceCallerAuthorityRetirementRetryReason.COMPARE_AND_SET_FAILED,
		)
	}

	@Test
	fun `superseded retirement reports terminal authority categories and idempotent completion`() =
		runTest {
			val current = SourceCallerReplayReference("current-authority")
			val expected = SourceCallerReplayReference("expected-predecessor")
			val missingRepository = mockk<SourceCallerAcceptedAuthorityRepository>()
			coEvery { missingRepository.load(expected) } returns
				StoredSourceCallerAuthorityLoadResult.Missing
			val missingBroker = SourceBroker(
				database,
				rolloutStore,
				RejectingAmbientRadioMutationLeaseGuard,
				missingRepository,
				retentionReader,
			)

			database.withTransaction {
				missingBroker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-missing",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.TerminalMissing

			val linked = purposeDemand(
				id = "expected-predecessor-demand",
				consumerId = missingBroker.sessionConsumerId("logical-linked"),
				source = SourceKind.STEPS,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				status = SourceDemandEntity.STATUS_RETIRED,
				reference = expected.value,
			)
			database.sourceBrokerDao().insertDemands(listOf(linked))
			coEvery { missingRepository.load(expected) } returnsMany listOf(
				StoredSourceCallerAuthorityLoadResult.Retired,
				StoredSourceCallerAuthorityLoadResult.Corrupt,
			)
			database.withTransaction {
				missingBroker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-linked",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.Completed
			database.withTransaction {
				missingBroker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-linked",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
		}

	@Test
	// Keep this complete contract scenario with its setup, actions and unchanged assertions.
	@Suppress("LongMethod")
	fun `superseded retirement distinguishes storage unavailability from ambiguous active history`() =
		runTest {
			val current = SourceCallerReplayReference("current-authority")
			val expected = SourceCallerReplayReference("expected-predecessor")
			val unexpected = SourceCallerReplayReference("unexpected-predecessor")
			val repository = mockk<SourceCallerAcceptedAuthorityRepository>()
			val broker = SourceBroker(
				database,
				rolloutStore,
				RejectingAmbientRadioMutationLeaseGuard,
				repository,
				retentionReader,
			)
			database.sourceBrokerDao().insertDemands(
				listOf(
					purposeDemand(
						id = "expected-history",
						consumerId = broker.sessionConsumerId("logical-storage"),
						source = SourceKind.STEPS,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						status = SourceDemandEntity.STATUS_RETIRED,
						reference = expected.value,
					),
				),
			)
			coEvery { repository.load(expected) } throws IOException("storage unavailable")

			database.withTransaction {
				broker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-storage",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.Retryable(
				SourceCallerAuthorityRetirementRetryReason.STORAGE_UNAVAILABLE,
			)

			database.sourceBrokerDao().insertDemands(
				listOf(
					purposeDemand(
						id = "expected-ambiguous",
						consumerId = broker.sessionConsumerId("logical-ambiguous"),
						source = SourceKind.STEPS,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						status = SourceDemandEntity.STATUS_RETIRED,
						reference = expected.value,
					),
					purposeDemand(
						id = "unexpected-ambiguous",
						consumerId = broker.sessionConsumerId("logical-ambiguous"),
						source = SourceKind.LOCATION,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						status = SourceDemandEntity.STATUS_RETIRED,
						reference = unexpected.value,
					),
				),
			)
			coEvery { repository.load(unexpected) } returns
				StoredSourceCallerAuthorityLoadResult.Available(mockk())

			database.withTransaction {
				broker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-ambiguous",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.TerminalAmbiguous
		}

	@Test
	fun `session retirement fences active and blocked rows despite corrupt caller metadata`() = runTest {
		val reference = "malformed-session"
		val malformed = SourceCallerAcceptedAuthorityEntity(
			reference = reference,
			formatVersion = SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION + 1,
			origin = "MANUAL",
			acceptedPurpose = "SESSION_CAPTURE",
			sourceKind = SourceKind.LOCATION.stableCode,
			purpose = "SESSION_CAPTURE",
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = 3L,
			rolloutRevision = 1L,
			executionRevision = 1L,
			ownerCasToken = "owner",
			logicalTrackingId = "logical-corrupt",
			manifestRevision = 1L,
			status = SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE,
			createdAtMs = 1L,
			retiredAtMs = null,
			retireReason = null,
			effectChecksum = "pending",
		)
		database.sourceCallerAuthorityDao().insert(
			SourceCallerAcceptedAuthorityEffectChecksum.seal(listOf(malformed)),
		)
		val active = purposeDemand(
			"session-active",
			"session:logical-corrupt",
			SourceKind.LOCATION,
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceDemandEntity.STATUS_ACTIVE,
			reference,
		).copy(
			logicalTrackingId = "logical-corrupt",
			serviceRunId = "run-active",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
		)
		val blocked = active.copy(
			demandId = "session-blocked",
			serviceRunId = "run-blocked",
			manifestRevision = 2L,
			status = SourceDemandEntity.STATUS_BLOCKED,
		)
		database.sourceBrokerDao().insertDemands(listOf(active, blocked))

		subject.retireSessionDemands(
			"logical-corrupt",
			"boot-1",
			40L,
			40L,
		)

		database.sourceBrokerDao().demandsByIds(listOf(active.demandId, blocked.demandId))
			.map(SourceDemandEntity::status)
			.toSet() shouldBe setOf(SourceDemandEntity.STATUS_RETIRED)
		database.sourceCallerAuthorityDao().rows(reference).shouldBeEmpty()
	}

	@Test
	// Keep this complete contract scenario with its setup, actions and unchanged assertions.
	@Suppress("LongMethod")
	fun `every capture subset joins and leaves without replacing background or automatic authority`() = runTest {
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val snapshot = policy.bootstrapFromLegacy(TrackingParamsState(
			legacySettingsMigrationCompleted = true, balanceProfileVersion = 1, overallBalanceLevel = 3,
			locationProfileVersion = 1, locationBalanceLevel = 3,
			locationEnabled = true, activityEnabled = true, stepsEnabled = true,
			barometerEnabled = true, wifiEnabled = true, cellEnabled = true,
		))
		val policies = database.sourcePolicyDao().policiesAtRevision(snapshot.revision).associateBy { it.sourceKind }
		val siblings = listOf(SourceKind.WIFI, SourceKind.CELL, SourceKind.ACTIVITY).map { source ->
			val purpose = if (source == SourceKind.ACTIVITY) {
				SourceBrokerPurpose.CONTROL_AUTOSTART
			}
				else {
				SourceBrokerPurpose.AMBIENT_PRODUCT
			}
			val contract = SourceDemandContractFactory.forQos(source, 0,
				if (source == SourceKind.ACTIVITY) {
					DirectSourceDemandPurpose.CONTROL_AUTOSTART
				}
				else {
					DirectSourceDemandPurpose.AMBIENT_PRODUCT
				})
			// Persisted sibling fixtures isolate the broker's replacement/retirement boundary.
			purposeDemand("independent-${source.name}", "app:independent:${source.name}", source,
				purpose, SourceDemandEntity.STATUS_ACTIVE, null).copy(
				minimumAcquisitionSpec = contract.encodeFloor(),
				maximumAgeMs = contract.maximumProviderItemAgeMs,
				desiredLatencyMs = contract.targetPlanningLatencyMs,
				requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
				adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
			)
		}
		database.sourceBrokerDao().insertDemands(siblings)
		SourceKind.entries.forEach { source ->
			database.sourceBrokerDao().insertRegistration(ProviderRegistrationGenerationEntity(
				sourceKind = source.stableCode, registrationGeneration = 1,
				sourceInstanceId = "${source.name}-shared", ownerScope = "source-broker:${source.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process", clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "${source.name}-physical", collectedDataEpoch = 3,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 90, reservedElapsedRealtimeNanos = 90,
				acceptedAtMs = 90, acceptedElapsedRealtimeNanos = 90,
				retiredAtMs = null, retiredElapsedRealtimeNanos = null, failureCode = null,
			))
			database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(
				source.stableCode, 1, 1, siblings.filter { it.sourceKind == source.stableCode },
				"boot-1", 90, 90,
			))
		}
		for (mask in 0..63) {
			val selected = SourceKind.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
			val revision = mask + 1L
			val boundary = 1_000L + mask * 10L
			val bindings = selected.map { source ->
				val row = policies.getValue(source.stableCode)
				binding(source, SourceBrokerPurpose.SESSION_CAPTURE, requireNotNull(row.captureConsentEpoch), true)
					.copy(manifestRevision = revision, qosCode = row.qosCode)
			}
			val demands = subject.buildSessionDemands("session-1", "shared-run", revision, revision,
				snapshot.revision, bindings, "boot-1", boundary, boundary, policies = policies)
			database.withTransaction {
				subject.replaceSessionDemandsInTransaction("session-1", demands, "boot-1", boundary, boundary)
			}
			database.sourceBrokerDao().currentDemands("session:session-1").map { it.sourceKind }.toSet() shouldBe
				selected.map { it.stableCode }.toSet()
			SourceKind.entries.forEach { source ->
				val expected = siblings.filter { it.sourceKind == source.stableCode } +
					demands.filter { it.sourceKind == source.stableCode }
				subject.authorizationDemands(source).map { it.demandId }.toSet() shouldBe expected.map { it.demandId }.toSet()
				val authorization = requireNotNull(database.sourceBrokerDao().latestAuthorization(source.stableCode, 1)
					.toAuthorizationSnapshotOrNull())
				authorization.authorizationFingerprint shouldBe SourceBrokerAuthorization.fingerprint(expected)
				database.sourceBrokerDao().maximumRegistrationGeneration(source.stableCode) shouldBe 1L
			}
			database.sourceBrokerDao().demandsByIds(siblings.map { it.demandId }).toSet() shouldBe siblings.toSet()
			if (SourceKind.LOCATION !in selected) {
				subject.authorizationDemands(SourceKind.LOCATION).shouldBeEmpty()
			}
		}
		// Ending the final all-source session retires its capture vector, retaining sibling authority.
		val finalDemands = database.sourceBrokerDao().currentDemands("session:session-1")
		finalDemands.size shouldBe SourceKind.entries.size
		subject.markSessionDemandsRetiring("session-1", "boot-1", 2_000, 2_000)
		val retiringDemands = database.sourceBrokerDao().currentDemands("session:session-1")
		retiringDemands.size shouldBe SourceKind.entries.size
		retiringDemands.map { it.demandId }.toSet() shouldBe finalDemands.map { it.demandId }.toSet()
		retiringDemands.all {
			it.status == SourceDemandEntity.STATUS_RETIRING
		} shouldBe true
		SourceKind.entries.forEach { source ->
			subject.authorizationDemands(source).toSet() shouldBe siblings.filter { it.sourceKind == source.stableCode }.toSet()
		}
		subject.retireSessionDemands("session-1", "boot-1", 2_001, 2_001)
		database.sourceBrokerDao().currentDemands("session:session-1").shouldBeEmpty()
		SourceKind.entries.forEach { source ->
			subject.authorizationDemands(source).toSet() shouldBe siblings.filter { it.sourceKind == source.stableCode }.toSet()
		}
		val captureHistory = database.sourceBrokerDao().demandHistory("session:session-1")
		captureHistory.size shouldBe (0..63).sumOf { Integer.bitCount(it) }
		captureHistory.all { it.status == SourceDemandEntity.STATUS_RETIRED } shouldBe true
	}

	@Test
	fun `retiring session demand remains registration eligible until drain completes`() = runTest {
		val demand = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.ACTIVITY, SourceBrokerPurpose.SESSION_CAPTURE, 15L, true)
					.copy(manifestRevision = 1L),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		).single()
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "activity-provider-1",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "physical-config",
				collectedDataEpoch = 3L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 90L,
				reservedElapsedRealtimeNanos = 90L,
				acceptedAtMs = 100L,
				acceptedElapsedRealtimeNanos = 100L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.ACTIVITY.stableCode,
				1L,
				1L,
				listOf(demand),
				"boot-1",
				100L,
				200L,
			),
		)

		subject.markSessionDemandsRetiring("session-1", "boot-1", 300L, 300L)

		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode)
			.map { it.status } shouldBe listOf(SourceDemandEntity.STATUS_RETIRING)
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			1L,
			"boot-1",
			299L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			1L,
			"boot-1",
			300L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		database.sourceBrokerDao().registration(
			SourceKind.ACTIVITY.stableCode,
			1L,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE

		subject.retireSessionDemands("session-1", "boot-1", 400L, 400L)
		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode) shouldBe emptyList()
	}

	private fun purposeDemand(
		id: String,
		consumerId: String,
		source: SourceKind,
		purpose: String,
		status: String,
		reference: String?,
	): SourceDemandEntity {
		val sessionScoped = purpose in setOf(SourceBrokerPurpose.SESSION_CAPTURE, SourceBrokerPurpose.CONTROL_CONTINUATION)
		return SourceDemandEntity(
		demandId = id,
		consumerId = consumerId,
		sourceKind = source.stableCode,
		purpose = purpose,
		logicalTrackingId = consumerId.removePrefix("session:").takeIf { sessionScoped },
		serviceRunId = "run-${consumerId.removePrefix("session:")}".takeIf { sessionScoped },
		manifestRevision = 1L.takeIf { sessionScoped },
		lifecycleLeaseGeneration = 1L.takeIf { sessionScoped },
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		persistenceEligible = purpose == SourceBrokerPurpose.AMBIENT_PRODUCT || purpose == SourceBrokerPurpose.SESSION_CAPTURE,
		qosCode = if (purpose == SourceBrokerPurpose.SESSION_CAPTURE) {
			2
		} else {
			0
		},
		maximumAgeMs = 1L,
		desiredLatencyMs = 1L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1L,
		requestedAtMs = 1L,
		status = status,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
		sourceCallerAuthorityReference = reference,
	)
	}
}
