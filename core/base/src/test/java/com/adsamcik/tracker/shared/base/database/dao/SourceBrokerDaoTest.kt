package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerDaoTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `observed time selects authorization revision and physical retirement is half open`() = runTest {
		val dao = database.sourceBrokerDao()
		val demand = captureDemand()
		dao.insertRegistration(
			providerRegistration(
				sourceKind = SOURCE_KIND,
				registrationGeneration = 1L,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				acceptedAtMs = 90L,
				acceptedElapsedRealtimeNanos = 90L,
				retiredAtMs = 300L,
				retiredElapsedRealtimeNanos = 300L,
				failureCode = "HANDOFF",
			),
		)
		dao.insertAuthorizations(
			SourceBrokerAuthorization.rows(SOURCE_KIND, 1L, 1L, listOf(demand), BOOT_ID, 100L, 100L),
		)
		dao.insertAuthorizations(
			SourceBrokerAuthorization.rows(SOURCE_KIND, 1L, 2L, emptyList(), BOOT_ID, 200L, 200L),
		)

		dao.authorizationAt(SOURCE_KIND, 1L, BOOT_ID, 99L) shouldBe emptyList()
		dao.authorizationAt(SOURCE_KIND, 1L, BOOT_ID, 199L)
			.toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
		dao.authorizationAt(SOURCE_KIND, 1L, BOOT_ID, 200L)
			.toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		dao.authorizationAt(SOURCE_KIND, 1L, BOOT_ID, 201L)
			.toAuthorizationSnapshotOrNull()?.isDenied shouldBe true

		dao.registrationAtObservedTime(
			SOURCE_KIND,
			1L,
			"provider-1",
			BOOT_ID,
			PHYSICAL_CONFIGURATION,
			299L,
		)?.registrationGeneration shouldBe 1L
		dao.registrationAtObservedTime(
			SOURCE_KIND,
			1L,
			"provider-1",
			BOOT_ID,
			PHYSICAL_CONFIGURATION,
			300L,
		) shouldBe null
		val stored = requireNotNull(dao.registration(SOURCE_KIND, 1L))
		stored.providerResidency shouldBe ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
		stored.providerProcessIncarnationId shouldBe PRIOR_PROCESS_ID
	}

	@Test
	fun `provider residency requires exactly one process identity for process bound providers`() {
		val processBound = providerRegistration()

		shouldThrow<IllegalArgumentException> {
			processBound.copy(providerProcessIncarnationId = null)
		}
		shouldThrow<IllegalArgumentException> {
			processBound.copy(
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			)
		}
		processBound.copy(
			providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null,
		).providerProcessIncarnationId shouldBe null
	}

	@Test
	fun `prior process reconciliation is status aware boot aware and idempotent`() = runTest {
		val dao = database.sourceBrokerDao()
		val priorRows = listOf(
			providerRegistration(
				registrationGeneration = 1L,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
			),
			providerRegistration(registrationGeneration = 2L),
			providerRegistration(
				registrationGeneration = 3L,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				retiredAtMs = 150L,
				retiredElapsedRealtimeNanos = 150L,
				failureCode = "ORDERLY_STOP",
			),
			providerRegistration(
				registrationGeneration = 4L,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				clockDomainId = PRIOR_BOOT_ID,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
			),
			providerRegistration(
				registrationGeneration = 5L,
				clockDomainId = PRIOR_BOOT_ID,
			),
			providerRegistration(
				registrationGeneration = 6L,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				clockDomainId = PRIOR_BOOT_ID,
				retiredAtMs = 140L,
				retiredElapsedRealtimeNanos = 140L,
				failureCode = "POLICY_REVOKED",
			),
		)
		val excludedRows = listOf(
			providerRegistration(
				registrationGeneration = 7L,
				providerProcessIncarnationId = CURRENT_PROCESS_ID,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
			),
			providerRegistration(
				registrationGeneration = 8L,
				providerProcessIncarnationId = CURRENT_PROCESS_ID,
			),
			providerRegistration(
				registrationGeneration = 9L,
				providerProcessIncarnationId = CURRENT_PROCESS_ID,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				retiredAtMs = 145L,
				retiredElapsedRealtimeNanos = 145L,
				failureCode = "CURRENT_PROCESS_STOP",
			),
			providerRegistration(
				registrationGeneration = 10L,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
			),
			providerRegistration(
				registrationGeneration = 11L,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
			),
			providerRegistration(
				registrationGeneration = 12L,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				retiredAtMs = 135L,
				retiredElapsedRealtimeNanos = 135L,
				failureCode = "SYSTEM_REARMABLE_STOP",
			),
			providerRegistration(
				registrationGeneration = 13L,
				status = ProviderRegistrationGenerationEntity.STATUS_FAILED,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
				retiredAtMs = 130L,
				retiredElapsedRealtimeNanos = 130L,
				failureCode = "PREEXISTING_FAILURE",
			),
			providerRegistration(
				registrationGeneration = 14L,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				retiredAtMs = 120L,
				retiredElapsedRealtimeNanos = 120L,
				failureCode = "PREEXISTING_RETIREMENT",
			),
		)
		(priorRows + excludedRows).forEach { dao.insertRegistration(it) }

		dao.hasNonterminalProcessBoundRegistrationsFromAnotherIncarnation(
			CURRENT_PROCESS_ID,
		) shouldBe true

		val result = dao.reconcilePriorProcessRegistrations(
			currentProcessId = CURRENT_PROCESS_ID,
			currentBootId = BOOT_ID,
			reconciledAtMs = 500L,
			reconciledElapsedRealtimeNanos = 400L,
			reason = "PRIOR_PROCESS_ENDED",
		)
		result shouldBe PriorProcessRegistrationReconciliationResult(
			failedReservations = 2,
			retiredActiveRegistrations = 2,
			completedRetirements = 2,
		)
		result.affectedRegistrations shouldBe 6

		registration(dao, 1L).shouldHaveState(
			status = ProviderRegistrationGenerationEntity.STATUS_FAILED,
			retiredAtMs = 500L,
			retiredElapsedRealtimeNanos = 400L,
			failureCode = "PRIOR_PROCESS_ENDED",
		)
		registration(dao, 2L).shouldHaveState(
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 500L,
			retiredElapsedRealtimeNanos = 400L,
			failureCode = "PRIOR_PROCESS_ENDED",
		)
		registration(dao, 3L).shouldHaveState(
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 150L,
			retiredElapsedRealtimeNanos = 150L,
			failureCode = "ORDERLY_STOP",
		)
		registration(dao, 4L).shouldHaveState(
			status = ProviderRegistrationGenerationEntity.STATUS_FAILED,
			retiredAtMs = null,
			retiredElapsedRealtimeNanos = null,
			failureCode = "PRIOR_PROCESS_ENDED",
		)
		registration(dao, 5L).shouldHaveState(
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = null,
			retiredElapsedRealtimeNanos = null,
			failureCode = "PRIOR_PROCESS_ENDED",
		)
		registration(dao, 6L).shouldHaveState(
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 140L,
			retiredElapsedRealtimeNanos = 140L,
			failureCode = "POLICY_REVOKED",
		)
		excludedRows.forEach { expected ->
			registration(dao, expected.registrationGeneration) shouldBe expected
		}

		dao.hasNonterminalProcessBoundRegistrationsFromAnotherIncarnation(
			CURRENT_PROCESS_ID,
		) shouldBe false
		dao.reconcilePriorProcessRegistrations(
			currentProcessId = CURRENT_PROCESS_ID,
			currentBootId = BOOT_ID,
			reconciledAtMs = 600L,
			reconciledElapsedRealtimeNanos = 500L,
			reason = "SECOND_RECONCILIATION",
		) shouldBe PriorProcessRegistrationReconciliationResult(0, 0, 0)
		registration(dao, 2L).retiredElapsedRealtimeNanos shouldBe 400L
		registration(dao, 3L).failureCode shouldBe "ORDERLY_STOP"
	}

	@Test
	fun `current process retirement CAS is exact fenced and keeps its first boundary`() = runTest {
		val dao = database.sourceBrokerDao()
		listOf(
			providerRegistration(
				registrationGeneration = 1L,
				providerProcessIncarnationId = CURRENT_PROCESS_ID,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
			),
			providerRegistration(
				registrationGeneration = 2L,
				providerProcessIncarnationId = CURRENT_PROCESS_ID,
			),
			providerRegistration(
				registrationGeneration = 3L,
				providerProcessIncarnationId = CURRENT_PROCESS_ID,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				retiredAtMs = 150L,
				retiredElapsedRealtimeNanos = 140L,
				failureCode = "FIRST_STOP",
			),
			providerRegistration(
				registrationGeneration = 4L,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
			),
			providerRegistration(
				registrationGeneration = 5L,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
			),
			providerRegistration(
				registrationGeneration = 6L,
				providerProcessIncarnationId = CURRENT_PROCESS_ID,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
			),
		).forEach { dao.insertRegistration(it) }

		dao.failUnacceptedCurrentProcessReservation(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 1L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			failedAtMs = 110L,
			failedElapsedRealtimeNanos = 100L,
			failureCode = "NOT_ACCEPTED",
		) shouldBe 1
		dao.failUnacceptedCurrentProcessReservation(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 1L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			failedAtMs = 120L,
			failedElapsedRealtimeNanos = 110L,
			failureCode = "RETRY",
		) shouldBe 0
		dao.failUnacceptedCurrentProcessReservation(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 2L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			failedAtMs = 120L,
			failedElapsedRealtimeNanos = 110L,
			failureCode = "MUST_NOT_FAIL_ACTIVE",
		) shouldBe 0
		dao.failUnacceptedCurrentProcessReservation(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 4L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			failedAtMs = 120L,
			failedElapsedRealtimeNanos = 110L,
			failureCode = "MUST_NOT_FAIL_PRIOR_PROCESS",
		) shouldBe 0

		val activeRetirement = requireNotNull(
			dao.beginCurrentProcessRegistrationRetirement(
				sourceKind = SOURCE_KIND,
				registrationGeneration = 2L,
				sourceInstanceId = "provider-1",
				currentProcessId = CURRENT_PROCESS_ID,
				retiredAtMs = 200L,
				retiredElapsedRealtimeNanos = 190L,
				reason = "ORDERLY_STOP",
			),
		)
		val repeatedRetirement = requireNotNull(
			dao.beginCurrentProcessRegistrationRetirement(
				sourceKind = SOURCE_KIND,
				registrationGeneration = 2L,
				sourceInstanceId = "provider-1",
				currentProcessId = CURRENT_PROCESS_ID,
				retiredAtMs = 300L,
				retiredElapsedRealtimeNanos = 290L,
				reason = "MOVED_BOUNDARY",
			),
		)
		activeRetirement shouldBe repeatedRetirement
		activeRetirement.shouldHaveState(
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 190L,
			failureCode = "ORDERLY_STOP",
		)

		val reservedRetirement = requireNotNull(
			dao.beginCurrentProcessRegistrationRetirement(
				sourceKind = SOURCE_KIND,
				registrationGeneration = 6L,
				sourceInstanceId = "provider-1",
				currentProcessId = CURRENT_PROCESS_ID,
				retiredAtMs = 210L,
				retiredElapsedRealtimeNanos = 200L,
				reason = "UNCONFIRMED_PROVIDER_CLEANUP",
			),
		)
		reservedRetirement.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RETIRING
		dao.beginCurrentProcessRegistrationRetirement(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 5L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			retiredAtMs = 210L,
			retiredElapsedRealtimeNanos = 200L,
			reason = "MUST_NOT_TOUCH_SYSTEM_REARMABLE",
		) shouldBe null

		dao.hasPendingCurrentProcessProviderRemoval(SOURCE_KIND, CURRENT_PROCESS_ID) shouldBe true
		dao.pendingCurrentProcessProviderRemovals(SOURCE_KIND, CURRENT_PROCESS_ID)
			.map { it.registrationGeneration } shouldBe listOf(2L, 3L, 6L)

		dao.completeCurrentProcessRegistrationRetirement(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 2L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			retiredAtMs = 201L,
			retiredElapsedRealtimeNanos = 190L,
		) shouldBe 0
		dao.completeCurrentProcessRegistrationRetirement(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 2L,
			sourceInstanceId = "provider-1",
			currentProcessId = PRIOR_PROCESS_ID,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 190L,
		) shouldBe 0
		dao.completeCurrentProcessRegistrationRetirement(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 2L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 190L,
		) shouldBe 1
		dao.completeCurrentProcessRegistrationRetirement(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 2L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 190L,
		) shouldBe 0
		dao.isCurrentProcessRegistrationRetirementComplete(
			sourceKind = SOURCE_KIND,
			registrationGeneration = 2L,
			sourceInstanceId = "provider-1",
			currentProcessId = CURRENT_PROCESS_ID,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 190L,
		) shouldBe true
		registration(dao, 2L).status shouldBe ProviderRegistrationGenerationEntity.STATUS_RETIRED
		registration(dao, 4L).status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
		registration(dao, 5L).status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
	}

	private suspend fun registration(
		dao: SourceBrokerDao,
		registrationGeneration: Long,
	): ProviderRegistrationGenerationEntity = requireNotNull(
		dao.registration(SOURCE_KIND, registrationGeneration),
	)

	private fun ProviderRegistrationGenerationEntity.shouldHaveState(
		status: String,
		retiredAtMs: Long?,
		retiredElapsedRealtimeNanos: Long?,
		failureCode: String?,
	) {
		this.status shouldBe status
		this.retiredAtMs shouldBe retiredAtMs
		this.retiredElapsedRealtimeNanos shouldBe retiredElapsedRealtimeNanos
		this.failureCode shouldBe failureCode
	}

	private fun providerRegistration(
		sourceKind: Int = SOURCE_KIND,
		registrationGeneration: Long = 1L,
		providerResidency: String = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId: String? = PRIOR_PROCESS_ID,
		clockDomainId: String = BOOT_ID,
		status: String = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		acceptedAtMs: Long? = 90L,
		acceptedElapsedRealtimeNanos: Long? = 90L,
		retiredAtMs: Long? = null,
		retiredElapsedRealtimeNanos: Long? = null,
		failureCode: String? = null,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = sourceKind,
		registrationGeneration = registrationGeneration,
		sourceInstanceId = "provider-1",
		ownerScope = "source-broker:$sourceKind",
		providerResidency = providerResidency,
		providerProcessIncarnationId = providerProcessIncarnationId,
		clockDomainId = clockDomainId,
		physicalConfigurationFingerprint = PHYSICAL_CONFIGURATION,
		collectedDataEpoch = 3L,
		status = status,
		reservedAtMs = 80L,
		reservedElapsedRealtimeNanos = 80L,
		acceptedAtMs = acceptedAtMs,
		acceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
		retiredAtMs = retiredAtMs,
		retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
		failureCode = failureCode,
	)

	private fun captureDemand() = SourceDemandEntity(
		demandId = "capture-1",
		consumerId = "session:track-1",
		sourceKind = SOURCE_KIND,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = "track-1",
		serviceRunId = "run-1",
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = 2L,
		consentEpoch = 3L,
		persistenceEligible = true,
		qosCode = 2,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 1_000L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = 100L,
		requestedAtMs = 100L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private companion object {
		const val SOURCE_KIND = 1
		const val BOOT_ID = "boot-1"
		const val PRIOR_BOOT_ID = "boot-0"
		const val PRIOR_PROCESS_ID = "process-prior"
		const val CURRENT_PROCESS_ID = "process-current"
		const val PHYSICAL_CONFIGURATION = "physical-config"
	}
}
