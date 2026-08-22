package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
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
			ProviderRegistrationGenerationEntity(
				sourceKind = SOURCE_KIND,
				registrationGeneration = 1L,
				sourceInstanceId = "provider-1",
				ownerScope = "source-broker:$SOURCE_KIND",
				clockDomainId = BOOT_ID,
				physicalConfigurationFingerprint = PHYSICAL_CONFIGURATION,
				collectedDataEpoch = 3L,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				reservedAtMs = 80L,
				reservedElapsedRealtimeNanos = 80L,
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
	}

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
		const val PHYSICAL_CONFIGURATION = "physical-config"
	}
}
