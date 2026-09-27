package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity

data class ProviderRegistrationObservationInterval(
	val startInclusiveNanos: Long,
	val endExclusiveNanos: Long,
) {
	init {
		require(startInclusiveNanos >= 0L)
		require(endExclusiveNanos > startInclusiveNanos)
	}
}

data class ProviderRegistrationActualObservation(
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
	val coverageEndExclusiveNanos: Long? = null,
) {
	init {
		require(elapsedRealtimeNanos >= 0L)
		require(wallTimeMs >= 0L)
		require(coverageEndExclusiveNanos == null ||
			coverageEndExclusiveNanos > elapsedRealtimeNanos)
	}

	fun overlaps(startInclusiveNanos: Long, endExclusiveNanos: Long): Boolean =
		elapsedRealtimeNanos < endExclusiveNanos &&
			(coverageEndExclusiveNanos?.let { it > startInclusiveNanos }
				?: (elapsedRealtimeNanos >= startInclusiveNanos))
}

/**
 * A derived fact window whose end is exclusive rather than an observed provider timestamp.
 *
 * [actualObservations] remain independently strict: every observation interval must overlap this
 * derived window and remain inside the provider lifetime, even when [endExclusiveNanos] is exactly
 * the retirement boundary.
 */
data class ProviderRegistrationFactWindow(
	val startInclusiveNanos: Long,
	val endExclusiveNanos: Long,
	val actualObservations: List<ProviderRegistrationActualObservation>,
) {
	init {
		require(startInclusiveNanos >= 0L)
		require(endExclusiveNanos > startInclusiveNanos)
	}
}

enum class ProviderRegistrationIntervalFailure {
	MISSING_REGISTRATION,
	CURRENT_REGISTRATION_OVERFLOW,
	REGISTRATION_IDENTITY_MISMATCH,
	INVALID_INTERVAL,
}

sealed interface ProviderRegistrationIntervalAuthentication {
	data class Authenticated(
		val registration: ProviderRegistrationGenerationEntity,
		val interval: ProviderRegistrationObservationInterval,
	) : ProviderRegistrationIntervalAuthentication

	data class Rejected(
		val reason: ProviderRegistrationIntervalFailure,
	) : ProviderRegistrationIntervalAuthentication
}

/**
 * Authenticates the elapsed-realtime interval in which a persisted provider generation existed.
 *
 * Open ACTIVE registrations are evidence only in the current boot. A prior-boot process-bound
 * registration may be boundaryless only after the exact process-incarnation reconciliation marker
 * was persisted and neither its provider nor process identity has been reused in the current boot.
 */
object ProviderRegistrationIntervalAuthenticator {
	/**
	 * Strict actual-observation authentication. Both observed elapsed boundaries and the wall
	 * timestamp must precede a finite retirement boundary.
	 */
	@Suppress("ComplexCondition", "ReturnCount")
	fun authenticate(
		registration: ProviderRegistrationGenerationEntity,
		expectedSourceKind: Int,
		expectedRegistrationGeneration: Long,
		expectedSourceInstanceId: String,
		expectedClockDomainId: String,
		expectedPhysicalConfigurationFingerprint: String,
		expectedCollectedDataEpoch: Long,
		expectedProviderResidency: String,
		observedStartNanos: Long,
		observedEndNanos: Long,
		observedWallTimeMs: Long,
		currentClockDomainId: String?,
		currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	): ProviderRegistrationIntervalAuthentication = authenticate(
		registration = registration,
		expectedSourceKind = expectedSourceKind,
		expectedRegistrationGeneration = expectedRegistrationGeneration,
		expectedSourceInstanceId = expectedSourceInstanceId,
		expectedClockDomainId = expectedClockDomainId,
		expectedPhysicalConfigurationFingerprint = expectedPhysicalConfigurationFingerprint,
		expectedCollectedDataEpoch = expectedCollectedDataEpoch,
		expectedProviderResidency = expectedProviderResidency,
		intervalStartNanos = observedStartNanos,
		intervalEndNanos = observedEndNanos,
		actualObservations = listOf(
			ProviderRegistrationActualObservation(
				elapsedRealtimeNanos = observedEndNanos,
				wallTimeMs = observedWallTimeMs,
			),
		),
		allowEndAtRetirement = false,
		currentClockDomainId = currentClockDomainId,
		currentProviderRegistrations = currentProviderRegistrations,
	)

	/**
	 * Authenticates a derived end-exclusive fact window. The window may end exactly at provider
	 * retirement, while any actual observation carried by the fact remains strictly before it.
	 */
	@Suppress("LongParameterList")
	fun authenticateFactWindow(
		registration: ProviderRegistrationGenerationEntity,
		expectedSourceKind: Int,
		expectedRegistrationGeneration: Long,
		expectedSourceInstanceId: String,
		expectedClockDomainId: String,
		expectedPhysicalConfigurationFingerprint: String,
		expectedCollectedDataEpoch: Long,
		expectedProviderResidency: String,
		factWindow: ProviderRegistrationFactWindow,
		currentClockDomainId: String?,
		currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	): ProviderRegistrationIntervalAuthentication = authenticate(
		registration = registration,
		expectedSourceKind = expectedSourceKind,
		expectedRegistrationGeneration = expectedRegistrationGeneration,
		expectedSourceInstanceId = expectedSourceInstanceId,
		expectedClockDomainId = expectedClockDomainId,
		expectedPhysicalConfigurationFingerprint = expectedPhysicalConfigurationFingerprint,
		expectedCollectedDataEpoch = expectedCollectedDataEpoch,
		expectedProviderResidency = expectedProviderResidency,
		intervalStartNanos = factWindow.startInclusiveNanos,
		intervalEndNanos = factWindow.endExclusiveNanos,
		actualObservations = factWindow.actualObservations,
		allowEndAtRetirement = true,
		currentClockDomainId = currentClockDomainId,
		currentProviderRegistrations = currentProviderRegistrations,
	)

	@Suppress("ComplexCondition", "ReturnCount", "LongParameterList")
	private fun authenticate(
		registration: ProviderRegistrationGenerationEntity,
		expectedSourceKind: Int,
		expectedRegistrationGeneration: Long,
		expectedSourceInstanceId: String,
		expectedClockDomainId: String,
		expectedPhysicalConfigurationFingerprint: String,
		expectedCollectedDataEpoch: Long,
		expectedProviderResidency: String,
		intervalStartNanos: Long,
		intervalEndNanos: Long,
		actualObservations: List<ProviderRegistrationActualObservation>,
		allowEndAtRetirement: Boolean,
		currentClockDomainId: String?,
		currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	): ProviderRegistrationIntervalAuthentication {
		val acceptedAtMs = registration.acceptedAtMs
			?: return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
		val acceptedElapsedNanos = registration.acceptedElapsedRealtimeNanos
			?: return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
		if (registration.sourceKind != expectedSourceKind ||
			registration.registrationGeneration != expectedRegistrationGeneration ||
			registration.sourceInstanceId != expectedSourceInstanceId ||
			registration.clockDomainId != expectedClockDomainId ||
			registration.physicalConfigurationFingerprint !=
			expectedPhysicalConfigurationFingerprint ||
			registration.collectedDataEpoch != expectedCollectedDataEpoch ||
			registration.providerResidency != expectedProviderResidency
		) {
			return rejected(ProviderRegistrationIntervalFailure.REGISTRATION_IDENTITY_MISMATCH)
		}
		if (registration.reservedAtMs > acceptedAtMs ||
			registration.reservedElapsedRealtimeNanos > acceptedElapsedNanos ||
			actualObservations.any { acceptedAtMs > it.wallTimeMs } ||
			intervalStartNanos < acceptedElapsedNanos ||
			intervalEndNanos < intervalStartNanos ||
			actualObservations.any { observation ->
				observation.elapsedRealtimeNanos < acceptedElapsedNanos ||
					allowEndAtRetirement && !observation.overlaps(
						intervalStartNanos,
						intervalEndNanos,
					)
			}
		) {
			return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
		}

		val endExclusiveNanos = when {
			registration.retiredAtMs != null ||
				registration.retiredElapsedRealtimeNanos != null -> {
				val retiredAtMs = registration.retiredAtMs
					?: return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
				val retiredElapsedNanos = registration.retiredElapsedRealtimeNanos
					?: return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
				val validStatus = when (registration.status) {
					ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
						!registration.failureCode.isNullOrBlank()
					ProviderRegistrationGenerationEntity.STATUS_RETIRED ->
						registration.failureCode?.isNotBlank() != false
					else -> false
				}
				if (!validStatus ||
					retiredAtMs < acceptedAtMs ||
					retiredElapsedNanos <= acceptedElapsedNanos ||
					actualObservations.any { it.wallTimeMs >= retiredAtMs } ||
					actualObservations.any { it.elapsedRealtimeNanos >= retiredElapsedNanos } ||
					actualObservations.any {
						it.coverageEndExclusiveNanos?.let { end -> end > retiredElapsedNanos } == true
					} ||
					if (allowEndAtRetirement) {
						intervalEndNanos > retiredElapsedNanos
					} else {
						intervalEndNanos >= retiredElapsedNanos
					}
				) {
					return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
				}
				retiredElapsedNanos
			}
			registration.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE -> {
				if (registration.failureCode != null ||
					currentClockDomainId == null ||
					registration.clockDomainId != currentClockDomainId ||
					currentProviderRegistrations.none { current ->
						current.sourceKind == registration.sourceKind &&
							current.registrationGeneration ==
							registration.registrationGeneration &&
							current.sourceInstanceId == registration.sourceInstanceId &&
							current.clockDomainId == registration.clockDomainId &&
							current.status ==
							ProviderRegistrationGenerationEntity.STATUS_ACTIVE
					}
				) {
					return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
				}
				Long.MAX_VALUE
			}
			registration.status == ProviderRegistrationGenerationEntity.STATUS_RETIRED -> {
				val processIncarnationId = registration.providerProcessIncarnationId
				?: return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
				if (registration.providerResidency !=
					ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
					registration.failureCode !=
					ProviderRegistrationGenerationEntity
						.FAILURE_PRIOR_PROCESS_INCARNATION_ENDED ||
					currentClockDomainId == null ||
					registration.clockDomainId == currentClockDomainId ||
					currentProviderRegistrations.any { current ->
						current.clockDomainId == currentClockDomainId &&
							current.sourceKind == registration.sourceKind &&
							(current.sourceInstanceId == registration.sourceInstanceId ||
								current.providerProcessIncarnationId == processIncarnationId)
					}
				) {
					return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
				}
				Long.MAX_VALUE
			}
			else -> return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
		}
		return ProviderRegistrationIntervalAuthentication.Authenticated(
			registration,
			ProviderRegistrationObservationInterval(acceptedElapsedNanos, endExclusiveNanos),
		)
	}
}

suspend fun AppDatabase.authenticateHistoricalProviderRegistration(
	sourceKind: Int,
	registrationGeneration: Long,
	sourceInstanceId: String,
	clockDomainId: String,
	physicalConfigurationFingerprint: String,
	collectedDataEpoch: Long,
	expectedProviderResidency: String,
	observedStartNanos: Long,
	observedEndNanos: Long,
	observedWallTimeMs: Long,
	currentClockDomainId: String?,
	maximumCurrentRegistrations: Int = 64,
): ProviderRegistrationIntervalAuthentication {
	require(maximumCurrentRegistrations > 0)
	val dao = sourceBrokerDao()
	val registration = dao.registration(sourceKind, registrationGeneration)
		?: return ProviderRegistrationIntervalAuthentication.Rejected(
			ProviderRegistrationIntervalFailure.MISSING_REGISTRATION,
		)
	val current = dao.currentPhysicalRegistrationsBounded(
		sourceKind,
		maximumCurrentRegistrations + 1,
	)
	val retiring = dao.pendingProviderRemovalsBounded(
		sourceKind,
		maximumCurrentRegistrations + 1,
	)
	val currentProviders = (current + retiring)
		.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration)
	if (current.size > maximumCurrentRegistrations ||
		retiring.size > maximumCurrentRegistrations ||
		currentProviders.size > maximumCurrentRegistrations
	) {
		return ProviderRegistrationIntervalAuthentication.Rejected(
			ProviderRegistrationIntervalFailure.CURRENT_REGISTRATION_OVERFLOW,
		)
	}
	return ProviderRegistrationIntervalAuthenticator.authenticate(
		registration = registration,
		expectedSourceKind = sourceKind,
		expectedRegistrationGeneration = registrationGeneration,
		expectedSourceInstanceId = sourceInstanceId,
		expectedClockDomainId = clockDomainId,
		expectedPhysicalConfigurationFingerprint = physicalConfigurationFingerprint,
		expectedCollectedDataEpoch = collectedDataEpoch,
		expectedProviderResidency = expectedProviderResidency,
		observedStartNanos = observedStartNanos,
		observedEndNanos = observedEndNanos,
		observedWallTimeMs = observedWallTimeMs,
		currentClockDomainId = currentClockDomainId,
		currentProviderRegistrations = currentProviders,
	)
}

@Suppress("LongParameterList")
suspend fun AppDatabase.authenticateHistoricalProviderFactWindow(
	sourceKind: Int,
	registrationGeneration: Long,
	sourceInstanceId: String,
	clockDomainId: String,
	physicalConfigurationFingerprint: String,
	collectedDataEpoch: Long,
	expectedProviderResidency: String,
	factWindow: ProviderRegistrationFactWindow,
	currentClockDomainId: String?,
	maximumCurrentRegistrations: Int = 64,
): ProviderRegistrationIntervalAuthentication {
	require(maximumCurrentRegistrations > 0)
	val dao = sourceBrokerDao()
	val registration = dao.registration(sourceKind, registrationGeneration)
		?: return ProviderRegistrationIntervalAuthentication.Rejected(
			ProviderRegistrationIntervalFailure.MISSING_REGISTRATION,
		)
	val current = dao.currentPhysicalRegistrationsBounded(
		sourceKind,
		maximumCurrentRegistrations + 1,
	)
	val retiring = dao.pendingProviderRemovalsBounded(
		sourceKind,
		maximumCurrentRegistrations + 1,
	)
	val currentProviders = (current + retiring)
		.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration)
	if (current.size > maximumCurrentRegistrations ||
		retiring.size > maximumCurrentRegistrations ||
		currentProviders.size > maximumCurrentRegistrations
	) {
		return ProviderRegistrationIntervalAuthentication.Rejected(
			ProviderRegistrationIntervalFailure.CURRENT_REGISTRATION_OVERFLOW,
		)
	}
	return ProviderRegistrationIntervalAuthenticator.authenticateFactWindow(
		registration = registration,
		expectedSourceKind = sourceKind,
		expectedRegistrationGeneration = registrationGeneration,
		expectedSourceInstanceId = sourceInstanceId,
		expectedClockDomainId = clockDomainId,
		expectedPhysicalConfigurationFingerprint = physicalConfigurationFingerprint,
		expectedCollectedDataEpoch = collectedDataEpoch,
		expectedProviderResidency = expectedProviderResidency,
		factWindow = factWindow,
		currentClockDomainId = currentClockDomainId,
		currentProviderRegistrations = currentProviders,
	)
}

private fun rejected(reason: ProviderRegistrationIntervalFailure) =
	ProviderRegistrationIntervalAuthentication.Rejected(reason)
