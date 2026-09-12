package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceProviderPurposeScopeTest {
	@Test
	fun `shared broker and legacy owners retain all source purposes`() {
		val demands = demands()

		SourceProviderPurposeScope.selectDemands(
			SOURCE_STEPS,
			SourceProviderPurposeScope.sharedOwnerScope(SOURCE_STEPS),
			demands,
		).map { it.demandId } shouldBe listOf("capture", "ambient")
		SourceProviderPurposeScope.selectDemands(
			SOURCE_STEPS,
			"activity-registration-arbiter",
			demands,
		).map { it.demandId } shouldBe listOf("capture", "ambient")
	}

	@Test
	fun `exact broker owner selects only eligible purposes for its source`() {
		SourceProviderPurposeScope.selectDemands(
			SOURCE_STEPS,
			SourceProviderPurposeScope.exactOwnerScope(
				SOURCE_STEPS,
				SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			),
			demands(),
		).map { it.demandId } shouldBe listOf("capture")
	}

	@Test
	fun `malformed or wrong-source broker owner fails closed`() {
		val demands = demands()

		SourceProviderPurposeScope.selectDemands(
			SOURCE_STEPS,
			"source-broker:$SOURCE_STEPS:purposes=invalid",
			demands,
		) shouldBe emptyList()
		SourceProviderPurposeScope.selectDemands(
			SOURCE_STEPS,
			"source-broker:$SOURCE_STEPS:purposes=04",
			demands,
		) shouldBe emptyList()
		SourceProviderPurposeScope.selectDemands(
			SOURCE_STEPS,
			SourceProviderPurposeScope.exactOwnerScope(
				SOURCE_LOCATION,
				SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			),
			demands,
		) shouldBe emptyList()
	}

	@Test
	fun `exact broker owner rejects empty or unknown purpose masks`() {
		assertThrows(IllegalArgumentException::class.java) {
			SourceProviderPurposeScope.exactOwnerScope(SOURCE_STEPS, 0L)
		}
		assertThrows(IllegalArgumentException::class.java) {
			SourceProviderPurposeScope.exactOwnerScope(
				SOURCE_STEPS,
				SourceBrokerPurpose.ALL_MASK shl 1,
			)
		}
	}

	@Test
	fun `only canonical broker owner encodings belong to a source`() {
		val exact = SourceProviderPurposeScope.exactOwnerScope(
			SOURCE_STEPS,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		)

		SourceProviderPurposeScope.isCanonicalOwnerScope(SOURCE_STEPS, exact) shouldBe true
		SourceProviderPurposeScope.isCanonicalOwnerScope(
			SOURCE_STEPS,
			SourceProviderPurposeScope.sharedOwnerScope(SOURCE_STEPS),
		) shouldBe true
		SourceProviderPurposeScope.isCanonicalOwnerScope(
			SOURCE_STEPS,
			"source-broker:$SOURCE_STEPS:purposes=04",
		) shouldBe false
		SourceProviderPurposeScope.isCanonicalOwnerScope(SOURCE_LOCATION, exact) shouldBe false
		SourceProviderPurposeScope.isCanonicalOwnerScope(
			SOURCE_STEPS,
			"activity-registration-arbiter",
		) shouldBe false
	}

	private fun demands(): List<SourceDemandEntity> = listOf(
		demand("capture", SOURCE_STEPS, SourceBrokerPurpose.SESSION_CAPTURE),
		demand("ambient", SOURCE_STEPS, SourceBrokerPurpose.AMBIENT_PRODUCT),
		demand("location", SOURCE_LOCATION, SourceBrokerPurpose.SESSION_CAPTURE),
	)

	private fun demand(
		id: String,
		sourceKind: Int,
		purpose: String,
	) = SourceDemandEntity(
		demandId = id,
		consumerId = "consumer:$id",
		sourceKind = sourceKind,
		purpose = purpose,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 1L,
		consentEpoch = 0L,
		persistenceEligible = true,
		qosCode = 1,
		maximumAgeMs = 0L,
		desiredLatencyMs = 0L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1L,
		requestedAtMs = 1L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private companion object {
		const val SOURCE_LOCATION = 1
		const val SOURCE_STEPS = 4
	}
}
