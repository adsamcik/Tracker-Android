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
	// Keep the retirement boundary and its adjacent elapsed/wall-time acceptance assertions together.
	@Suppress("LongMethod")
	fun `elapsed retirement rejects actual observation while equal wall milliseconds remain valid`() {
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
	fun `prior boot Location system request keeps historical proof only after exact removal`() {
		val removed = registration(SourceDestinationOwnerEntity.SOURCE_LOCATION).copy(
			providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			failureCode = ProviderRegistrationGenerationEntity.FAILURE_PRIOR_BOOT_SYSTEM_REMOVED,
		)
		fun verify(row: ProviderRegistrationGenerationEntity,
			current: List<ProviderRegistrationGenerationEntity> = emptyList(),
		) = ProviderRegistrationIntervalAuthenticator.authenticate(
			registration = row, expectedSourceKind = row.sourceKind,
			expectedRegistrationGeneration = row.registrationGeneration,
			expectedSourceInstanceId = row.sourceInstanceId,
			expectedClockDomainId = row.clockDomainId,
			expectedPhysicalConfigurationFingerprint = row.physicalConfigurationFingerprint,
			expectedCollectedDataEpoch = row.collectedDataEpoch,
			expectedProviderResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			observedStartNanos = 150, observedEndNanos = 199, observedWallTimeMs = 19,
			currentClockDomainId = CURRENT_BOOT, currentProviderRegistrations = current)
		assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(verify(removed))
		assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(verify(removed.copy(
			failureCode = "REMOVAL_UNCONFIRMED")))
		assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(verify(removed,
			listOf(removed.copy(clockDomainId = CURRENT_BOOT,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE))))
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

	@Test
	fun `active original interval survives backward and forward phone clock changes`() {
		SOURCES.forEach { source ->
			val row = registration(source).copy(reservedAtMs = 2_000L, acceptedAtMs = 1_000L)
			listOf(50L, 5_000L).forEach { wall ->
				val result = assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
					authenticate(row, observedWallTimeMs = wall))
				org.junit.Assert.assertEquals(row, result.registration)
				org.junit.Assert.assertEquals(100L, result.interval.startInclusiveNanos)
			}
		}
	}

	@Test
	fun `retired original interval accepts shifted and equal wall milliseconds before elapsed cutoff`() {
		SOURCES.forEach { source ->
			listOf(ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				ProviderRegistrationGenerationEntity.STATUS_RETIRED).forEach { status ->
				val row = registration(source).copy(status = status, reservedAtMs = 2_000L,
					acceptedAtMs = 1_000L, retiredAtMs = 500L,
					retiredElapsedRealtimeNanos = 300L, failureCode = "STOPPED")
				listOf(50L, 500L, 5_000L).forEach { wall ->
					val result = assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(
						authenticate(row, observedWallTimeMs = wall))
					org.junit.Assert.assertEquals(row, result.registration)
					org.junit.Assert.assertEquals(300L, result.interval.endExclusiveNanos)
				}
			}
		}
	}

	@Test
	fun `clock changes cannot lend authority before acceptance or at retirement`() {
		SOURCES.forEach { source ->
			val row = registration(source).copy(status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				reservedAtMs = 2_000L, acceptedAtMs = 1_000L, retiredAtMs = 500L,
				retiredElapsedRealtimeNanos = 199L, failureCode = "STOPPED")
			assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
				authenticate(row, observedWallTimeMs = 100L))
			assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(
				authenticate(row.copy(acceptedElapsedRealtimeNanos = 151L,
					retiredElapsedRealtimeNanos = 300L), observedWallTimeMs = 100L))
		}
	}

	@Test
	fun `wall clock changes do not allow negative original registration times`() {
		val row = registration(SourceDestinationOwnerEntity.SOURCE_PRESSURE)
		kotlin.test.assertFailsWith<IllegalArgumentException> { row.copy(reservedAtMs = -1L) }
		kotlin.test.assertFailsWith<IllegalArgumentException> { row.copy(reservedElapsedRealtimeNanos = -1L) }
		listOf(row.copy(acceptedAtMs = -1L), row.copy(acceptedElapsedRealtimeNanos = -1L),
			row.copy(status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				retiredAtMs = -1L, retiredElapsedRealtimeNanos = 300L)).forEach { invalid ->
			assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(authenticate(invalid))
		}
	}

	@Test
	fun `derived window uses original elapsed retirement despite shifted wall clock`() {
		val row = registration(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			reservedAtMs = 2_000L, acceptedAtMs = 1_000L, retiredAtMs = 500L,
			retiredElapsedRealtimeNanos = 300L)
		fun verify(observed: Long, coverageEnd: Long? = null) =
			ProviderRegistrationIntervalAuthenticator.authenticateFactWindow(
				registration = row, expectedSourceKind = row.sourceKind,
				expectedRegistrationGeneration = row.registrationGeneration,
				expectedSourceInstanceId = row.sourceInstanceId, expectedClockDomainId = row.clockDomainId,
				expectedPhysicalConfigurationFingerprint = row.physicalConfigurationFingerprint,
				expectedCollectedDataEpoch = row.collectedDataEpoch, expectedProviderResidency = row.providerResidency,
				factWindow = ProviderRegistrationFactWindow(150L, 300L,
					listOf(ProviderRegistrationActualObservation(observed, 5_000L, coverageEnd))),
				currentClockDomainId = BOOT, currentProviderRegistrations = emptyList())
		assertIs<ProviderRegistrationIntervalAuthentication.Authenticated>(verify(299L))
		assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(verify(300L))
		assertIs<ProviderRegistrationIntervalAuthentication.Rejected>(verify(299L, 301L))
	}

	private fun authenticate(
		registration: ProviderRegistrationGenerationEntity,
		currentClockDomainId: String = BOOT,
		currentRegistrations: List<ProviderRegistrationGenerationEntity>? = null,
		observedWallTimeMs: Long = 19L,
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
		observedWallTimeMs = observedWallTimeMs,
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
