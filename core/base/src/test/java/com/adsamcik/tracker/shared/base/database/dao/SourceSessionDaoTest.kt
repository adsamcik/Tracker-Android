package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
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
class SourceSessionDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: SourceSessionDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.sourceSessionDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `manifest reads are scoped to the exact service run`() = runTest {
		dao.insertManifest(manifest(revision = 1L, serviceRunId = "run-a"))
		dao.insertManifest(manifest(revision = 2L, serviceRunId = "run-b"))
		dao.insertManifest(manifest(revision = 3L, serviceRunId = "run-a"))

		dao.manifestsForServiceRun("run-a").map { it.manifestRevision } shouldContainExactly
			listOf(1L, 3L)
		dao.manifestByServiceRunRevision("run-a", 3L)?.serviceRunId shouldBe "run-a"
		dao.manifestByServiceRunRevision("run-b", 3L) shouldBe null
		dao.manifests(LOGICAL_ID).map { it.manifestRevision } shouldContainExactly listOf(1L, 2L, 3L)
	}

	@Test
	fun `completeness rows for two runs and two generations do not overwrite`() = runTest {
		val runAGeneration1 = completeness(serviceRunId = "run-a", registrationGeneration = 1L)
		val runBGeneration1 = completeness(serviceRunId = "run-b", registrationGeneration = 1L)
		val runAGeneration2 = completeness(serviceRunId = "run-a", registrationGeneration = 2L)
		dao.saveCompleteness(runAGeneration1)
		dao.saveCompleteness(runBGeneration1)
		dao.saveCompleteness(runAGeneration2)

		dao.completenessForServiceRun(LOGICAL_ID, "run-a") shouldContainExactly
			listOf(runAGeneration1, runAGeneration2)
		dao.completenessForServiceRun(LOGICAL_ID, "run-b") shouldContainExactly
			listOf(runBGeneration1)

		val corrected = runAGeneration1.copy(
			lastAdmissionOrdinal = 99L,
			updatedAtMs = 2_000L,
		)
		dao.saveCompleteness(corrected)

		dao.completeness(LOGICAL_ID) shouldContainExactly
			listOf(corrected, runAGeneration2, runBGeneration1)
	}

	@Test
	fun `legacy unattributed completeness is excluded from exact physical run reads`() = runTest {
		val legacy = completeness(
			serviceRunId = LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID,
			registrationGeneration = 4L,
		)
		dao.saveCompleteness(legacy)

		dao.legacyUnattributedCompleteness(LOGICAL_ID) shouldContainExactly listOf(legacy)
		dao.completenessForServiceRun(LOGICAL_ID, "run-a") shouldContainExactly emptyList()
	}

	@Test
	fun `legacy completeness sentinel cannot identify a service run`() {
		shouldThrow<IllegalArgumentException> { serviceRun(LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID) }
		shouldThrow<IllegalArgumentException> { serviceRun(" ") }
		shouldThrow<IllegalArgumentException> {
			manifest(revision = 1L, serviceRunId = LEGACY_V27_UNATTRIBUTED_SERVICE_RUN_ID)
		}
		shouldThrow<IllegalArgumentException> {
			completeness(serviceRunId = " ", registrationGeneration = 1L)
		}
		serviceRun("run-a").serviceRunId shouldBe "run-a"
	}

	@Test
	fun `steps capture writer provenance groups fail closed`() {
		shouldThrow<IllegalArgumentException> {
			stepsManifestSource()
		}
		shouldThrow<IllegalArgumentException> {
			stepsManifestSource(
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			)
		}
		shouldThrow<IllegalArgumentException> {
			stepsManifestSource(
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				writerOwnerGeneration = 2L,
			)
		}
		shouldThrow<IllegalArgumentException> {
			stepsManifestSource(
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				writerOwnerGeneration = 2L,
				writerProjectionId = "steps-session",
			)
		}

		stepsManifestSource(
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			writerOwnerGeneration = 1L,
		).writerProjectionId shouldBe null
		stepsManifestSource(
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			writerOwnerGeneration = 2L,
			writerProjectionId = "steps-session",
			writerProjectionVersion = 1,
			writerBindingGeneration = 3L,
		).writerBindingGeneration shouldBe 3L
	}

	private fun manifest(revision: Long, serviceRunId: String) = SessionManifestVersionEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = revision,
		serviceRunId = serviceRunId,
		sessionMode = "MANUAL",
		sourcePolicyRevision = 1L,
		acquisitionPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL",
		effectiveBootId = "boot",
		effectiveElapsedRealtimeNanos = revision,
		effectiveWallTimeMs = revision,
		zoneId = "Europe/Prague",
		automationEpoch = null,
		changeReason = "test",
		manifestChecksum = "checksum-$revision",
	)

	private fun completeness(
		serviceRunId: String,
		registrationGeneration: Long,
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = serviceRunId,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		sourceInstanceId = "step-counter",
		registrationGeneration = registrationGeneration,
		lastAdmissionOrdinal = registrationGeneration,
		lastSourceSequence = registrationGeneration,
		appDrainComplete = false,
		providerCoverage = "UNKNOWN",
		stopStatus = "INCOMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = 1_000L,
	)

	private fun serviceRun(serviceRunId: String) = SourceServiceRunEntity(
		serviceRunId = serviceRunId,
		logicalTrackingId = LOGICAL_ID,
		state = "PREPARED",
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		completedAtMs = null,
		completionReason = null,
	)

	private fun stepsManifestSource(
		outputDestination: String? = null,
		writerOwner: String? = null,
		writerOwnerGeneration: Long? = null,
		writerProjectionId: String? = null,
		writerProjectionVersion: Int? = null,
		writerBindingGeneration: Long? = null,
	) = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = 1L,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 1,
		outputDestination = outputDestination,
		writerOwner = writerOwner,
		writerOwnerGeneration = writerOwnerGeneration,
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		writerBindingGeneration = writerBindingGeneration,
	)

	private companion object {
		const val LOGICAL_ID = "logical-session"
	}
}
