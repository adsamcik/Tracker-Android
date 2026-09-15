package com.adsamcik.tracker.tracker.api

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackingPurposeAvailabilityTest {
	@Test
	fun `safe default contains automatic control but waits for each ambient owner`() {
		val snapshot = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT

		snapshot.automaticControl shouldBe AutomaticTrackingOperationalAvailability.Unavailable(
			AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
		)
		AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE.stableCode shouldBe
			"CONTROL_RETENTION_POLICY_UNAVAILABLE"
		snapshot.ambientSources.keys shouldBe setOf(
			AmbientTrackingSource.STEPS,
			AmbientTrackingSource.LOCATION,
			AmbientTrackingSource.WIFI,
			AmbientTrackingSource.CELL,
		)
		snapshot.ambientSources.values.forEach { availability ->
			availability.isOperational.shouldBeFalse()
			availability.state shouldBe AmbientSourceOperationalState.WAITING
			availability.mechanism shouldBe null
			availability.reason shouldBe AmbientSourceUnavailableReason.RECONCILIATION_PENDING
		}
	}

	@Test
	fun `availability snapshot can replace one source without changing siblings`() {
		val initial = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT
		val steps = AmbientSourceOperationalAvailability(
			source = AmbientTrackingSource.STEPS,
			state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
			mechanism = AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			reason = AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED,
		)

		val changed = initial.copy(
			ambientSources = initial.ambientSources +
				(AmbientTrackingSource.STEPS to steps),
		)

		changed.ambientSources.getValue(AmbientTrackingSource.STEPS) shouldBe steps
		changed.ambientSources.getValue(AmbientTrackingSource.LOCATION).reason shouldBe
			AmbientSourceUnavailableReason.RECONCILIATION_PENDING
	}

	@Test
	fun `valid availability matrix preserves source specific meaning`() {
		val valid = listOf(
			ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			),
			ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			),
			ready(
				AmbientTrackingSource.LOCATION,
				AmbientAcquisitionMechanism.PASSIVE_LOCATION,
			),
			ready(
				AmbientTrackingSource.WIFI,
				AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
			),
			ready(
				AmbientTrackingSource.CELL,
				AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS,
			),
			AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.STEPS,
				state = AmbientSourceOperationalState.DEGRADED,
				mechanism = AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				reason =
					AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL,
			),
			permission(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED,
			),
			permission(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
				AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED,
			),
			permission(
				AmbientTrackingSource.LOCATION,
				AmbientAcquisitionMechanism.PASSIVE_LOCATION,
				AmbientSourceUnavailableReason.BACKGROUND_LOCATION_PERMISSION_REQUIRED,
			),
			permission(
				AmbientTrackingSource.WIFI,
				AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
				AmbientSourceUnavailableReason.WIFI_SCAN_PERMISSION_REQUIRED,
			),
			permission(
				AmbientTrackingSource.CELL,
				AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS,
				AmbientSourceUnavailableReason.CELL_SCAN_PERMISSION_REQUIRED,
			),
		)

		valid.count { availability -> availability.isOperational } shouldBe 6
	}

	@Test
	fun `invalid availability matrix is rejected`() {
		val invalid = listOf<() -> AmbientSourceOperationalAvailability>(
			{
				ready(
					AmbientTrackingSource.LOCATION,
					AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				)
			},
			{
				permission(
					AmbientTrackingSource.CELL,
					AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS,
					AmbientSourceUnavailableReason.BACKGROUND_LOCATION_PERMISSION_REQUIRED,
				)
			},
			{
				AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.DEGRADED,
					mechanism = AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
					reason = AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
				)
			},
			{
				AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.WIFI,
					state = AmbientSourceOperationalState.READY,
				)
			},
			{
				AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.UNAVAILABLE,
					reason =
						AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED,
				)
			},
		)

		invalid.forEach { create ->
			shouldThrow<IllegalArgumentException> { create() }
		}
	}

	@Test
	fun `delayed result is rejected after regrant rollout change or cancellation`() {
		val old = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-old")
		val report = AmbientSourceReconciliationReport(
			identity = old,
			availability = ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			),
		)

		acceptAmbientReconciliationReport(
			AmbientReconciliationLease(old),
			report,
		) shouldBe AmbientReconciliationReportAcceptance.Accepted
		acceptAmbientReconciliationReport(
			AmbientReconciliationLease(old.copy(policyRevision = 11L, consentEpoch = 4L)),
			report,
		) shouldBe AmbientReconciliationReportAcceptance.Rejected(
			AmbientReconciliationReportRejection.STALE_IDENTITY,
		)
		acceptAmbientReconciliationReport(
			AmbientReconciliationLease(old.copy(rolloutRevision = 5L)),
			report,
		) shouldBe AmbientReconciliationReportAcceptance.Rejected(
			AmbientReconciliationReportRejection.STALE_IDENTITY,
		)
		acceptAmbientReconciliationReport(
			AmbientReconciliationLease(old.copy(collectedDataEpoch = 3L)),
			report,
		) shouldBe AmbientReconciliationReportAcceptance.Rejected(
			AmbientReconciliationReportRejection.STALE_IDENTITY,
		)
		acceptAmbientReconciliationReport(
			AmbientReconciliationLease(old.copy(ownerCasToken = "lease-new")),
			report,
		) shouldBe AmbientReconciliationReportAcceptance.Rejected(
			AmbientReconciliationReportRejection.STALE_IDENTITY,
		)
		acceptAmbientReconciliationReport(
			AmbientReconciliationLease(old, cancelled = true),
			report,
		) shouldBe AmbientReconciliationReportAcceptance.Rejected(
			AmbientReconciliationReportRejection.CANCELLED,
		)
	}

	private fun ready(
		source: AmbientTrackingSource,
		mechanism: AmbientAcquisitionMechanism,
	) = AmbientSourceOperationalAvailability(
		source = source,
		state = AmbientSourceOperationalState.READY,
		mechanism = mechanism,
	)

	private fun permission(
		source: AmbientTrackingSource,
		mechanism: AmbientAcquisitionMechanism,
		reason: AmbientSourceUnavailableReason,
	) = AmbientSourceOperationalAvailability(
		source = source,
		state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
		mechanism = mechanism,
		reason = reason,
	)

	private fun identity(
		policy: Long,
		consent: Long,
		rollout: Long,
		token: String,
	) = AmbientReconciliationIdentity(
		source = AmbientTrackingSource.STEPS,
		policyRevision = policy,
		consentEpoch = consent,
		collectedDataEpoch = 2L,
		rolloutRevision = rollout,
		ownerCasToken = token,
	)
}
