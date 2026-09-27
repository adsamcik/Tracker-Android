package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import kotlin.test.assertIs
import org.junit.Test

class ProviderRegistrationIntervalAuthenticatorTest {
	@Test
	fun `active registration authenticates only in current boot for every source`() {
		SOURCES.forEach { source ->
			assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
				authenticate(registration(source)),
			)
			assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
				authenticate(registration(source), currentClockDomainId = CURRENT_BOOT),
			)
		}
	}

	@Test
	fun `current boot system-rearmable Activity registration authenticates`() {
		val registration = registration(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(
			providerResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null,
		)

		assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
			ProviderRegistrationIntervalAuthenticator.authenticate(
				registration = registration,
				expectedSourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				expectedRegistrationGeneration = 1L,
				expectedSourceInstanceId = INSTANCE,
				expectedClockDomainId = BOOT,
				expectedPhysicalConfigurationFingerprint = FINGERPRINT,
				expectedCollectedDataEpoch = 7L,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				observedStartNanos = 150L,
				observedEndNanos = 199L,
				observedWallTimeMs = 19L,
				currentClockDomainId = BOOT,
				currentProviderRegistrations = listOf(registration),
			),
		)
	}

	@Test
	fun `same boot retiring and retired registrations require finite boundaries before observation`() {
		SOURCES.forEach { source ->
			listOf(
				ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			).forEach { status ->
				assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
					authenticate(
						registration(source).copy(
							status = status,
							retiredAtMs = 30L,
							retiredElapsedRealtimeNanos = 300L,
							failureCode = "STOPPED",
						),
					),
				)
				assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
					authenticate(
						registration(source).copy(
							status = status,
							retiredAtMs = null,
							retiredElapsedRealtimeNanos = null,
							failureCode = "STOPPED",
						),
					),
				)
				assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
					authenticate(
						registration(source).copy(
							status = status,
							retiredAtMs = 19L,
							retiredElapsedRealtimeNanos = 199L,
							failureCode = "STOPPED",
						),
					),
				)
			}
		}
	}

	@Test
	fun `end exclusive fact window may meet retirement while actual observation remains earlier`() {
		val retired = registration(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(
			providerResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 30L,
			retiredElapsedRealtimeNanos = 300L,
		)

		assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
			ProviderRegistrationIntervalAuthenticator.authenticateFactWindow(
				registration = retired,
				expectedSourceKind = retired.sourceKind,
				expectedRegistrationGeneration = retired.registrationGeneration,
				expectedSourceInstanceId = retired.sourceInstanceId,
				expectedClockDomainId = retired.clockDomainId,
				expectedPhysicalConfigurationFingerprint =
					retired.physicalConfigurationFingerprint,
				expectedCollectedDataEpoch = retired.collectedDataEpoch,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				factWindow = ProviderRegistrationFactWindow(
					startInclusiveNanos = 150L,
					endExclusiveNanos = 300L,
					actualObservations = listOf(
						ProviderRegistrationActualObservation(
							elapsedRealtimeNanos = 299L,
							wallTimeMs = 29L,
						),
					),
				),
				currentClockDomainId = BOOT,
				currentProviderRegistrations = emptyList(),
			),
		)
	}

	@Test
	fun `Activity lookback observation may start before and overlap the clipped fact window`() {
		val retired = registration(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(
			providerResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 30L,
			retiredElapsedRealtimeNanos = 300L,
		)

		assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
			ProviderRegistrationIntervalAuthenticator.authenticateFactWindow(
				registration = retired,
				expectedSourceKind = retired.sourceKind,
				expectedRegistrationGeneration = retired.registrationGeneration,
				expectedSourceInstanceId = retired.sourceInstanceId,
				expectedClockDomainId = retired.clockDomainId,
				expectedPhysicalConfigurationFingerprint =
					retired.physicalConfigurationFingerprint,
				expectedCollectedDataEpoch = retired.collectedDataEpoch,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				factWindow = ProviderRegistrationFactWindow(
					startInclusiveNanos = 200L,
					endExclusiveNanos = 280L,
					actualObservations = listOf(
						ProviderRegistrationActualObservation(
							elapsedRealtimeNanos = 150L,
							wallTimeMs = 20L,
							coverageEndExclusiveNanos = 250L,
						),
					),
				),
				currentClockDomainId = BOOT,
				currentProviderRegistrations = emptyList(),
			),
		)
	}

	@Test
	fun `Activity lookback observation that does not overlap the fact window is rejected`() {
		val retired = registration(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(
			providerResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 30L,
			retiredElapsedRealtimeNanos = 300L,
		)

		assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
			ProviderRegistrationIntervalAuthenticator.authenticateFactWindow(
				registration = retired,
				expectedSourceKind = retired.sourceKind,
				expectedRegistrationGeneration = retired.registrationGeneration,
				expectedSourceInstanceId = retired.sourceInstanceId,
				expectedClockDomainId = retired.clockDomainId,
				expectedPhysicalConfigurationFingerprint =
					retired.physicalConfigurationFingerprint,
				expectedCollectedDataEpoch = retired.collectedDataEpoch,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				factWindow = ProviderRegistrationFactWindow(
					startInclusiveNanos = 200L,
					endExclusiveNanos = 280L,
					actualObservations = listOf(
						ProviderRegistrationActualObservation(
							elapsedRealtimeNanos = 150L,
							wallTimeMs = 20L,
							coverageEndExclusiveNanos = 200L,
						),
					),
				),
				currentClockDomainId = BOOT,
				currentProviderRegistrations = emptyList(),
			),
		)
	}

	@Test
	fun `gap-only fact window needs no fabricated observation wall time`() {
		val retired = registration(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(
			providerResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 30L,
			retiredElapsedRealtimeNanos = 300L,
		)

		assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
			ProviderRegistrationIntervalAuthenticator.authenticateFactWindow(
				registration = retired,
				expectedSourceKind = retired.sourceKind,
				expectedRegistrationGeneration = retired.registrationGeneration,
				expectedSourceInstanceId = retired.sourceInstanceId,
				expectedClockDomainId = retired.clockDomainId,
				expectedPhysicalConfigurationFingerprint =
					retired.physicalConfigurationFingerprint,
				expectedCollectedDataEpoch = retired.collectedDataEpoch,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				factWindow = ProviderRegistrationFactWindow(
					startInclusiveNanos = 150L,
					endExclusiveNanos = 300L,
					actualObservations = emptyList(),
				),
				currentClockDomainId = BOOT,
				currentProviderRegistrations = emptyList(),
			),
		)
	}

	@Test
	fun `actual observation at retirement remains invalid for end exclusive fact window`() {
		val retired = registration(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(
			providerResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 30L,
			retiredElapsedRealtimeNanos = 300L,
		)

		assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
			ProviderRegistrationIntervalAuthenticator.authenticateFactWindow(
				registration = retired,
				expectedSourceKind = retired.sourceKind,
				expectedRegistrationGeneration = retired.registrationGeneration,
				expectedSourceInstanceId = retired.sourceInstanceId,
				expectedClockDomainId = retired.clockDomainId,
				expectedPhysicalConfigurationFingerprint =
					retired.physicalConfigurationFingerprint,
				expectedCollectedDataEpoch = retired.collectedDataEpoch,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				factWindow = ProviderRegistrationFactWindow(
					startInclusiveNanos = 150L,
					endExclusiveNanos = 300L,
					actualObservations = listOf(
						ProviderRegistrationActualObservation(
							elapsedRealtimeNanos = 300L,
							wallTimeMs = 29L,
						),
					),
				),
				currentClockDomainId = BOOT,
				currentProviderRegistrations = emptyList(),
			),
		)
		assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
			ProviderRegistrationIntervalAuthenticator.authenticateFactWindow(
				registration = retired,
				expectedSourceKind = retired.sourceKind,
				expectedRegistrationGeneration = retired.registrationGeneration,
				expectedSourceInstanceId = retired.sourceInstanceId,
				expectedClockDomainId = retired.clockDomainId,
				expectedPhysicalConfigurationFingerprint =
					retired.physicalConfigurationFingerprint,
				expectedCollectedDataEpoch = retired.collectedDataEpoch,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				factWindow = ProviderRegistrationFactWindow(
					startInclusiveNanos = 150L,
					endExclusiveNanos = 300L,
					actualObservations = listOf(
						ProviderRegistrationActualObservation(
							elapsedRealtimeNanos = 299L,
							wallTimeMs = 30L,
						),
					),
				),
				currentClockDomainId = BOOT,
				currentProviderRegistrations = emptyList(),
			),
		)
	}

	@Test
	fun `prior boot active registration is rejected for every source`() {
		SOURCES.forEach { source ->
			assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
				authenticate(registration(source), currentClockDomainId = CURRENT_BOOT),
			)
		}
	}

	@Test
	fun `exact prior process reconciliation accepts boundaryless retired process provider`() {
		SOURCES.forEach { source ->
			val reconciled = registration(source).copy(
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = ProviderRegistrationGenerationEntity
					.FAILURE_PRIOR_PROCESS_INCARNATION_ENDED,
			)

			assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
				authenticate(reconciled, currentClockDomainId = CURRENT_BOOT),
			)
			assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
				authenticate(
					reconciled,
					currentClockDomainId = CURRENT_BOOT,
					currentRegistrations = listOf(
						registration(source).copy(
							registrationGeneration = 2L,
							clockDomainId = CURRENT_BOOT,
						),
					),
				),
			)
			assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
				authenticate(
					reconciled.copy(
						providerResidency =
							ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
						providerProcessIncarnationId = null,
					),
					currentClockDomainId = CURRENT_BOOT,
				),
			)
		}
	}

	@Test
	fun `wrong residency and non-observing statuses are rejected`() {
		val active = registration(SourceDestinationOwnerEntity.SOURCE_PRESSURE)
		assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
			ProviderRegistrationIntervalAuthenticator.authenticate(
				registration = active.copy(
					providerResidency =
						ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
					providerProcessIncarnationId = null,
				),
				expectedSourceKind = active.sourceKind,
				expectedRegistrationGeneration = active.registrationGeneration,
				expectedSourceInstanceId = active.sourceInstanceId,
				expectedClockDomainId = active.clockDomainId,
				expectedPhysicalConfigurationFingerprint =
					active.physicalConfigurationFingerprint,
				expectedCollectedDataEpoch = active.collectedDataEpoch,
				expectedProviderResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				observedStartNanos = 150L,
				observedEndNanos = 199L,
				observedWallTimeMs = 19L,
				currentClockDomainId = BOOT,
				currentProviderRegistrations = listOf(active),
			),
		)
		listOf(
			ProviderRegistrationGenerationEntity.STATUS_RESERVED,
			ProviderRegistrationGenerationEntity.STATUS_FAILED,
		).forEach { status ->
			assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
				authenticate(active.copy(status = status)),
			)
		}
	}

	private fun authenticate(
		registration: ProviderRegistrationGenerationEntity,
		currentClockDomainId: String = BOOT,
		currentRegistrations: List<ProviderRegistrationGenerationEntity>? = null,
	) = ProviderRegistrationIntervalAuthenticator.authenticate(
		registration = registration,
		expectedSourceKind = registration.sourceKind,
		expectedRegistrationGeneration = 1L,
		expectedSourceInstanceId = INSTANCE,
		expectedClockDomainId = BOOT,
		expectedPhysicalConfigurationFingerprint = FINGERPRINT,
		expectedCollectedDataEpoch = 7L,
		expectedProviderResidency =
			ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		observedStartNanos = 150L,
		observedEndNanos = 199L,
		observedWallTimeMs = 19L,
		currentClockDomainId = currentClockDomainId,
		currentProviderRegistrations = currentRegistrations ?: listOf(registration),
	)

	private fun registration(sourceKind: Int) = ProviderRegistrationGenerationEntity(
		sourceKind = sourceKind,
		registrationGeneration = 1L,
		sourceInstanceId = INSTANCE,
		ownerScope = "owner",
		clockDomainId = BOOT,
		physicalConfigurationFingerprint = FINGERPRINT,
		collectedDataEpoch = 7L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = PROCESS,
		status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		reservedAtMs = 5L,
		reservedElapsedRealtimeNanos = 50L,
		acceptedAtMs = 10L,
		acceptedElapsedRealtimeNanos = 100L,
		retiredAtMs = null,
		retiredElapsedRealtimeNanos = null,
		failureCode = null,
	)

	private companion object {
		const val BOOT = "boot-old"
		const val CURRENT_BOOT = "boot-current"
		const val INSTANCE = "provider-instance"
		const val PROCESS = "process-old"
		const val FINGERPRINT = "fingerprint"
		val SOURCES = listOf(
			SourceDestinationOwnerEntity.SOURCE_LOCATION,
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		)
	}
}
