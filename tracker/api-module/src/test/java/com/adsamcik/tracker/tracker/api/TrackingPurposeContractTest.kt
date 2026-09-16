package com.adsamcik.tracker.tracker.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackingPurposeContractTest {
	@Test
	fun `source purpose identity accepts only the frozen purpose matrix`() {
		TrackingSource.entries.filter { source ->
			source.supportsPurpose(TrackingPurpose.SESSION_CAPTURE)
		} shouldContainExactly TrackingSource.entries.toList()
		TrackingSource.entries.filter { source ->
			source.supportsPurpose(TrackingPurpose.CONTROL)
		} shouldContainExactly listOf(TrackingSource.ACTIVITY)
		TrackingSource.entries.filter { source ->
			source.supportsPurpose(TrackingPurpose.AMBIENT_PRODUCT)
		} shouldContainExactly listOf(
			TrackingSource.LOCATION,
			TrackingSource.WIFI,
			TrackingSource.CELL,
			TrackingSource.STEPS,
		)

		shouldThrow<IllegalArgumentException> {
			TrackingSource.STEPS.forPurpose(TrackingPurpose.CONTROL)
		}
		shouldThrow<IllegalArgumentException> {
			TrackingSource.PRESSURE.forPurpose(TrackingPurpose.AMBIENT_PRODUCT)
		}
	}

	@Test
	fun `ambient lease exposes the complete source purpose authority vector`() {
		val common = TrackingPurposeLeaseIdentity(
			sourcePurpose = TrackingSource.WIFI.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
			policyRevision = 11L,
			consentEpoch = 7L,
			collectedDataEpoch = 4L,
			rolloutRevision = 3L,
			executionRevision = 9L,
			ownerCasToken = "wifi-owner-9",
		)

		val ambient = AmbientReconciliationIdentity.from(common)

		ambient.source shouldBe AmbientTrackingSource.WIFI
		ambient.purposeLeaseIdentity shouldBe common
		AmbientReconciliationLease(ambient).purposeLeaseIdentity shouldBe common
	}

	@Test
	fun `lease rejects an incomplete or malformed authority vector`() {
		val valid = leaseIdentity(
			TrackingSource.STEPS,
			TrackingPurpose.AMBIENT_PRODUCT,
		)
		listOf(
			{ valid.copy(policyRevision = 0L) },
			{ valid.copy(consentEpoch = 0L) },
			{ valid.copy(collectedDataEpoch = -1L) },
			{ valid.copy(rolloutRevision = -1L) },
			{ valid.copy(executionRevision = -1L) },
			{ valid.copy(ownerCasToken = " ") },
		).forEach { create ->
			shouldThrow<IllegalArgumentException> { create() }
		}
	}

	@Test
	fun `existing ambient constructor remains source compatible and explicitly unbound`() {
		val ambient = AmbientReconciliationIdentity(
			source = AmbientTrackingSource.STEPS,
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = 0L,
			rolloutRevision = 0L,
			ownerCasToken = "steps-owner",
		)

		ambient.executionRevision shouldBe 0L
		ambient.sourcePurpose shouldBe
			TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT)
	}

	@Test
	fun `non ambient purpose cannot become an ambient lease`() {
		shouldThrow<IllegalArgumentException> {
			AmbientReconciliationIdentity.from(
				leaseIdentity(
					TrackingSource.ACTIVITY,
					TrackingPurpose.CONTROL,
				),
			)
		}
	}

	@Test
	fun `decision containment codes stay explicit and optional products stay separate`() {
		TrackingDecisionContainmentReason.entries
			.map(TrackingDecisionContainmentReason::stableCode) shouldContainExactly listOf(
				"AUTO_005_CONTROL_EVIDENCE_UNRESOLVED",
				"RETENTION_AUTHORITY_UNAVAILABLE",
				"CROSS_MIDNIGHT_POLICY_UNAVAILABLE",
				"EXPANDED_AMBIENT_LOCATION_UNAVAILABLE",
				"RADIO_IDENTITY_UNAVAILABLE",
				"PRESSURE_ELEVATION_UNAVAILABLE",
			)
		TrackingDecisionContainmentReason.entries
			.filter(TrackingDecisionContainmentReason::isOptionalProductDecision)
			.map(TrackingDecisionContainmentReason::stableCode) shouldContainExactly listOf(
			"CROSS_MIDNIGHT_POLICY_UNAVAILABLE",
			"EXPANDED_AMBIENT_LOCATION_UNAVAILABLE",
			"RADIO_IDENTITY_UNAVAILABLE",
			"PRESSURE_ELEVATION_UNAVAILABLE",
		)
		AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE
			.containmentReason shouldBe
			TrackingDecisionContainmentReason.RETENTION_AUTHORITY_UNAVAILABLE
		AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE.containmentReason shouldBe
			TrackingDecisionContainmentReason.RETENTION_AUTHORITY_UNAVAILABLE
	}

	private fun leaseIdentity(
		source: TrackingSource,
		purpose: TrackingPurpose,
	) = TrackingPurposeLeaseIdentity(
		sourcePurpose = source.forPurpose(purpose),
		policyRevision = 1L,
		consentEpoch = 1L,
		collectedDataEpoch = 0L,
		rolloutRevision = 0L,
		executionRevision = 0L,
		ownerCasToken = "owner",
	)
}
