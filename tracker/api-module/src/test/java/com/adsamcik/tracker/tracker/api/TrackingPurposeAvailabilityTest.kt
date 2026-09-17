package com.adsamcik.tracker.tracker.api

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
		AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE
			.containmentReason shouldBe
			TrackingDecisionContainmentReason.RETENTION_AUTHORITY_UNAVAILABLE
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
	fun `automatic control issue report cancel and regrant remain exact lease bound`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val first = automaticIdentity(policy = 8L, consent = 3L, token = "control-first")
		val started = store.beginOrReplaceAutomaticControlLease(first)
			.shouldBeInstanceOf<AutomaticControlLeaseStartResult.Started>()
		started.lease.identity shouldBe first
		started.snapshot.automaticControl shouldBe
			AutomaticTrackingOperationalAvailability.Unavailable(
				AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
			)

		val unavailable = AutomaticControlReconciliationReport(
			first,
			AutomaticTrackingOperationalAvailability.Unavailable(
				AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				first,
			),
		)
		store.tryAccept(unavailable)
			.shouldBeInstanceOf<AutomaticControlPublicationAcceptance.Accepted>()
		store.tryAccept(unavailable) shouldBe AutomaticControlPublicationAcceptance.Rejected(
			TrackingPurposePublicationRejection.TOKEN_CONSUMED,
		)

		val regrant = first.copy(
			policyRevision = 9L,
			consentEpoch = 4L,
			ownerCasToken = "control-regrant",
		)
		store.beginOrReplaceAutomaticControlLease(regrant)
			.shouldBeInstanceOf<AutomaticControlLeaseStartResult.Started>()
		store.tryAccept(unavailable) shouldBe AutomaticControlPublicationAcceptance.Rejected(
			TrackingPurposePublicationRejection.STALE_IDENTITY,
		)
		store.cancelAutomaticControlLease(regrant)
			.shouldBeInstanceOf<AutomaticControlPublicationAcceptance.Accepted>()
		store.tryAccept(
			AutomaticControlReconciliationReport(
				regrant,
				AutomaticTrackingOperationalAvailability.Unavailable(
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
					regrant,
				),
			),
		) shouldBe AutomaticControlPublicationAcceptance.Rejected(
			TrackingPurposePublicationRejection.CANCELLED,
		)
	}

	@Test
	fun `automatic ready report requires the exact current identity`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val current = automaticIdentity(
			policy = 8L,
			consent = 3L,
			token = "control-ready",
			execution = 5L,
		)
		store.beginOrReplaceAutomaticControlLease(current)
			.shouldBeInstanceOf<AutomaticControlLeaseStartResult.Started>()

		store.tryAccept(
			AutomaticControlReconciliationReport(
				current,
				AutomaticTrackingOperationalAvailability.Ready(current),
			),
		).shouldBeInstanceOf<AutomaticControlPublicationAcceptance.Accepted>()
		store.availability.value.automaticControl shouldBe
			AutomaticTrackingOperationalAvailability.Ready(current)

		shouldThrow<IllegalArgumentException> {
			AutomaticControlReconciliationReport(
				current,
				AutomaticTrackingOperationalAvailability.Ready(
					current.copy(ownerCasToken = "different"),
				),
			)
		}
	}

	@Test
	fun `trusted authority loss removes published readiness and rejects the old callback`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val automatic = automaticIdentity(
			policy = 8L,
			consent = 3L,
			token = "control-ready",
			execution = 5L,
		)
		store.beginOrReplaceAutomaticControlLease(automatic)
		store.tryAccept(
			AutomaticControlReconciliationReport(
				automatic,
				AutomaticTrackingOperationalAvailability.Ready(automatic),
			),
		)

		store.invalidateAutomaticControl()

		store.availability.value.automaticControl shouldBe
			AutomaticTrackingOperationalAvailability.Unavailable(
				AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				automatic,
			)
		store.tryAccept(
				AutomaticControlReconciliationReport(
					automatic,
					AutomaticTrackingOperationalAvailability.Ready(automatic),
				),
		) shouldBe AutomaticControlPublicationAcceptance.Rejected(
			TrackingPurposePublicationRejection.CANCELLED,
		)
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
				operationalIdentity = purposeIdentity(AmbientTrackingSource.STEPS),
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
	fun `operational readiness requires exact bound authority while non-ready retains history only`() {
		val ambientIdentity = purposeIdentity(AmbientTrackingSource.WIFI)
		val ready = ready(
			AmbientTrackingSource.WIFI,
			AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
			ambientIdentity,
		)
		ready.operationalIdentity shouldBe ambientIdentity
		ready.lastIdentity shouldBe null

		val unavailable = AmbientSourceOperationalAvailability(
			source = AmbientTrackingSource.WIFI,
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
			lastIdentity = ambientIdentity,
		)
		unavailable.isOperational.shouldBeFalse()
		unavailable.operationalIdentity shouldBe null
		unavailable.lastIdentity shouldBe ambientIdentity

		val automaticIdentity = TrackingPurposeLeaseIdentity(
			source = TrackingSource.ACTIVITY,
			purpose = TrackingPurpose.CONTROL,
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = 0L,
			rolloutRevision = 0L,
			executionRevision = 1L,
			ownerCasToken = "bound-control",
		)
		val automaticReady = AutomaticTrackingOperationalAvailability.Ready(automaticIdentity)
		automaticReady.authorityIdentityOrNull shouldBe automaticIdentity
		AutomaticTrackingOperationalAvailability.Unavailable(
			AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
			lastIdentity = automaticIdentity,
		).authorityIdentityOrNull shouldBe null

		shouldThrow<IllegalArgumentException> {
			AutomaticTrackingOperationalAvailability.Ready(
				automaticIdentity.copy(executionRevision = 0L),
			)
		}
		shouldThrow<IllegalArgumentException> {
			AutomaticTrackingOperationalAvailability.Ready(
				ambientIdentity,
			)
		}
	}

	@Test
	fun `first report is accepted and the same terminal token cannot publish twice`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val old = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-old")
		val report = AmbientSourceReconciliationReport(
			identity = old,
			availability = ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				old.purposeLeaseIdentity,
			),
		)
		store.beginOrReplaceAmbientLease(old).shouldBeInstanceOf<AmbientLeaseStartResult.Started>()

		store.tryAccept(report).shouldBeInstanceOf<AmbientPublicationAcceptance.Accepted>()
		store.tryAccept(
			report.copy(
				availability = AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.UNAVAILABLE,
					reason = AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
				),
			),
		) shouldBe AmbientPublicationAcceptance.Rejected(
			AmbientPublicationRejection.TOKEN_CONSUMED,
		)
		store.beginOrReplaceAmbientLease(old) shouldBe AmbientLeaseStartResult.Rejected(
			AmbientPublicationRejection.TOKEN_CONSUMED,
		)
		store.beginOrReplaceAmbientLease(
			old.copy(policyRevision = 11L, consentEpoch = 4L),
		) shouldBe AmbientLeaseStartResult.Rejected(
			AmbientPublicationRejection.TOKEN_CONSUMED,
		)
		store.cancelAmbientLease(old) shouldBe AmbientPublicationAcceptance.Rejected(
			AmbientPublicationRejection.TOKEN_CONSUMED,
		)
		store.availability.value.ambientSources.getValue(AmbientTrackingSource.STEPS) shouldBe
			report.availability
	}

	@Test
	fun `cancel atomically clears an active lease and is terminal`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val current = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-current")
		val report = AmbientSourceReconciliationReport(
			identity = current,
			availability = ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				current.purposeLeaseIdentity,
			),
		)
		store.beginOrReplaceAmbientLease(current)
			.shouldBeInstanceOf<AmbientLeaseStartResult.Started>()

		store.cancelAmbientLease(current).shouldBeInstanceOf<AmbientPublicationAcceptance.Accepted>()

		store.availability.value.ambientSources.getValue(AmbientTrackingSource.STEPS) shouldBe
			AmbientSourceOperationalAvailability.reconciliationPending(AmbientTrackingSource.STEPS)
		store.tryAccept(report) shouldBe AmbientPublicationAcceptance.Rejected(
			AmbientPublicationRejection.CANCELLED,
		)
		store.cancelAmbientLease(current) shouldBe AmbientPublicationAcceptance.Rejected(
			AmbientPublicationRejection.CANCELLED,
		)
		store.beginOrReplaceAmbientLease(current) shouldBe AmbientLeaseStartResult.Rejected(
			AmbientPublicationRejection.CANCELLED,
		)
	}

	@Test
	fun `old completion is rejected after policy regrant rollout or owner token replacement`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val old = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-old")
		val report = AmbientSourceReconciliationReport(
			identity = old,
			availability = ready(
				AmbientTrackingSource.STEPS,
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				old.purposeLeaseIdentity,
			),
		)
		store.beginOrReplaceAmbientLease(old).shouldBeInstanceOf<AmbientLeaseStartResult.Started>()

		listOf(
			old.copy(
				policyRevision = 11L,
				consentEpoch = 4L,
				ownerCasToken = "lease-regrant",
			),
			old.copy(rolloutRevision = 5L, ownerCasToken = "lease-rollout"),
			old.copy(executionRevision = 7L, ownerCasToken = "lease-execution"),
			old.copy(collectedDataEpoch = 3L, ownerCasToken = "lease-data"),
			old.copy(ownerCasToken = "lease-new"),
		).forEach { replacement ->
			store.beginOrReplaceAmbientLease(replacement)
				.shouldBeInstanceOf<AmbientLeaseStartResult.Started>()
			store.tryAccept(report) shouldBe AmbientPublicationAcceptance.Rejected(
				AmbientPublicationRejection.STALE_IDENTITY,
			)
		}
	}

	@Test
	fun `changed authority vector cannot reuse an active owner CAS token`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val current = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-current")
		store.beginOrReplaceAmbientLease(current)
			.shouldBeInstanceOf<AmbientLeaseStartResult.Started>()

		store.beginOrReplaceAmbientLease(
			current.copy(policyRevision = 11L, consentEpoch = 4L, executionRevision = 6L),
		) shouldBe AmbientLeaseStartResult.Rejected(
			AmbientPublicationRejection.TOKEN_REUSED,
		)
	}

	@Test
	fun `source mismatch cannot be shaped into a terminal report`() {
		val identity = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-source")

		shouldThrow<IllegalArgumentException> {
			AmbientSourceReconciliationReport(
				identity = identity,
				availability = ready(
					AmbientTrackingSource.WIFI,
					AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
				),
			)
		}
	}

	@Test
	fun `same-source ready report cannot substitute a different authority vector`() {
		val identity = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-source")

		shouldThrow<IllegalArgumentException> {
			AmbientSourceReconciliationReport(
				identity = identity,
				availability = ready(
					AmbientTrackingSource.STEPS,
					AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
					identity.copy(policyRevision = 11L).purposeLeaseIdentity,
				),
			)
		}
	}

	@Test
	fun `operational status without an issued lease grants no publication authority`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val identity = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-unissued")
		val ready = ready(
			AmbientTrackingSource.STEPS,
			AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			identity.purposeLeaseIdentity,
		)

		store.tryAccept(
			AmbientSourceReconciliationReport(identity, ready),
		) shouldBe AmbientPublicationAcceptance.Rejected(
			AmbientPublicationRejection.STALE_IDENTITY,
		)
		store.availability.value.ambientSources.getValue(AmbientTrackingSource.STEPS) shouldBe
			AmbientSourceOperationalAvailability.reconciliationPending(AmbientTrackingSource.STEPS)
	}

	@Test
	fun `replacement lease atomically clears the previous ready publication`() {
		val store = AtomicTrackingPurposeAvailabilityStore()
		val old = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-old")
		val fresh = old.copy(policyRevision = 11L, consentEpoch = 4L, ownerCasToken = "lease-fresh")
		store.beginOrReplaceAmbientLease(old).shouldBeInstanceOf<AmbientLeaseStartResult.Started>()
		store.tryAccept(
			AmbientSourceReconciliationReport(
				identity = old,
				availability = ready(
					AmbientTrackingSource.STEPS,
					AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
					old.purposeLeaseIdentity,
				),
			),
		).shouldBeInstanceOf<AmbientPublicationAcceptance.Accepted>()

		val started = store.beginOrReplaceAmbientLease(fresh)
			.shouldBeInstanceOf<AmbientLeaseStartResult.Started>()

		started.snapshot.ambientSources.getValue(AmbientTrackingSource.STEPS) shouldBe
			AmbientSourceOperationalAvailability.reconciliationPending(
				AmbientTrackingSource.STEPS,
				old.purposeLeaseIdentity,
			)
		store.tryAccept(
			AmbientSourceReconciliationReport(
				identity = old,
				availability = ready(
					AmbientTrackingSource.STEPS,
					AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
					old.purposeLeaseIdentity,
				),
			),
		) shouldBe AmbientPublicationAcceptance.Rejected(
			AmbientPublicationRejection.STALE_IDENTITY,
		)
	}

	@Test
	fun `new process store never recreates a stale ready publication`() {
		val firstProcess = AtomicTrackingPurposeAvailabilityStore()
		val identity = identity(policy = 10L, consent = 3L, rollout = 4L, token = "lease-ready")
		firstProcess.beginOrReplaceAmbientLease(identity)
			.shouldBeInstanceOf<AmbientLeaseStartResult.Started>()
		firstProcess.tryAccept(
			AmbientSourceReconciliationReport(
				identity = identity,
				availability = ready(
					AmbientTrackingSource.STEPS,
					AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
					identity.purposeLeaseIdentity,
				),
			),
		).shouldBeInstanceOf<AmbientPublicationAcceptance.Accepted>()

		val recreated = AtomicTrackingPurposeAvailabilityStore()

		recreated.availability.value.ambientSources.getValue(AmbientTrackingSource.STEPS) shouldBe
			AmbientSourceOperationalAvailability.reconciliationPending(AmbientTrackingSource.STEPS)
		recreated.tryAccept(
			AmbientSourceReconciliationReport(
				identity = identity,
				availability = ready(
					AmbientTrackingSource.STEPS,
					AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
					identity.purposeLeaseIdentity,
				),
			),
		) shouldBe AmbientPublicationAcceptance.Rejected(
			AmbientPublicationRejection.STALE_IDENTITY,
		)
	}

	private fun ready(
		source: AmbientTrackingSource,
		mechanism: AmbientAcquisitionMechanism,
		identity: TrackingPurposeLeaseIdentity = purposeIdentity(source),
	) = AmbientSourceOperationalAvailability(
		source = source,
		state = AmbientSourceOperationalState.READY,
		mechanism = mechanism,
		operationalIdentity = identity,
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
		executionRevision = 6L,
	)

	private fun purposeIdentity(source: AmbientTrackingSource) = TrackingPurposeLeaseIdentity(
		sourcePurpose = source.canonicalSource.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
		policyRevision = 1L,
		consentEpoch = 1L,
		collectedDataEpoch = 0L,
		rolloutRevision = 0L,
		executionRevision = 1L,
		ownerCasToken = "${source.name.lowercase()}-owner",
	)

	private fun automaticIdentity(
		policy: Long,
		consent: Long,
		token: String,
		execution: Long = 0L,
	) = TrackingPurposeLeaseIdentity(
		source = TrackingSource.ACTIVITY,
		purpose = TrackingPurpose.CONTROL,
		policyRevision = policy,
		consentEpoch = consent,
		collectedDataEpoch = 2L,
		rolloutRevision = 4L,
		executionRevision = execution,
		ownerCasToken = token,
	)
}
