package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity

/**
 * Nonempty half-open elapsed-realtime nanosecond interval covered by one original provider
 * registration.
 */
data class ProviderRegistrationObservationInterval(
	val startInclusiveNanos: Long,
	val endExclusiveNanos: Long,
) {
	init {
		require(startInclusiveNanos >= 0L)
		require(endExclusiveNanos > startInclusiveNanos)
	}
}

/**
 * Original elapsed nanosecond and UTC millisecond measurement time, with optional genuine coverage
 * end.
 */
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

	/**
	 * Checks the real point or coverage interval against half-open elapsed-realtime nanosecond
	 * bounds.
	 */
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

/**
 * Missing, excessive, mismatched or invalid provider registration interval evidence.
 */
enum class ProviderRegistrationIntervalFailure {
	MISSING_REGISTRATION,
	CURRENT_REGISTRATION_OVERFLOW,
	REGISTRATION_IDENTITY_MISMATCH,
	INVALID_INTERVAL,
}

/**
 * Verified original registration/interval or the explicit failure preventing acceptance.
 */
sealed interface ProviderRegistrationIntervalAuthentication {
	/**
	 * Original provider registration paired with the verified elapsed nanosecond observation
	 * interval.
	 */
	data class Authenticated(
		val registration: ProviderRegistrationGenerationEntity,
		val interval: ProviderRegistrationObservationInterval,
	) : ProviderRegistrationIntervalAuthentication

	/**
	 * Original source evidence failed the stated immutable-authority or hierarchy validation.
	 */
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
	 * Strict actual-observation authentication in the exact original elapsed clock. Wall
	 * timestamps are retained values and may change when the phone clock is adjusted.
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

	@Suppress("ReturnCount", "LongParameterList")
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
		if (!matchesExpectedIdentity(
			registration, expectedSourceKind, expectedRegistrationGeneration, expectedSourceInstanceId,
			expectedClockDomainId, expectedPhysicalConfigurationFingerprint, expectedCollectedDataEpoch,
			expectedProviderResidency,
		)) {
			return rejected(ProviderRegistrationIntervalFailure.REGISTRATION_IDENTITY_MISMATCH)
		}
		if (!observationsFitAcceptedInterval(
			registration, acceptedAtMs, acceptedElapsedNanos, intervalStartNanos, intervalEndNanos,
			actualObservations, allowEndAtRetirement,
		)) {
			return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
		}

		val endExclusiveNanos = when {
			registration.retiredAtMs != null ||
				registration.retiredElapsedRealtimeNanos != null -> retiredIntervalEnd(
					registration, acceptedElapsedNanos, intervalEndNanos, actualObservations, allowEndAtRetirement,
				)
			else -> unretiredIntervalEnd(registration, currentClockDomainId, currentProviderRegistrations)
		} ?: return rejected(ProviderRegistrationIntervalFailure.INVALID_INTERVAL)
		return ProviderRegistrationIntervalAuthentication.Authenticated(
			registration,
			ProviderRegistrationObservationInterval(acceptedElapsedNanos, endExclusiveNanos),
		)
	}

	/** Open and prior-boot registrations resolve their end without an observed retirement boundary. */
	private fun unretiredIntervalEnd(
		registration: ProviderRegistrationGenerationEntity,
		currentClockDomainId: String?,
		currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	): Long? = when {
		registration.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
			activeIntervalEnd(registration, currentClockDomainId, currentProviderRegistrations)
		registration.status == ProviderRegistrationGenerationEntity.STATUS_RETIRED -> {
			if (registration.providerResidency ==
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE) {
				priorSystemIntervalEnd(registration, currentClockDomainId, currentProviderRegistrations)
			} else {
				priorProcessIntervalEnd(registration, currentClockDomainId, currentProviderRegistrations)
			}
		}
		else -> null
	}

	// Compare the seven original identity fields together; an identity DTO would only duplicate this API.
	@Suppress("ComplexCondition", "LongParameterList")
	private fun matchesExpectedIdentity(
		registration: ProviderRegistrationGenerationEntity,
		expectedSourceKind: Int,
		expectedRegistrationGeneration: Long,
		expectedSourceInstanceId: String,
		expectedClockDomainId: String,
		expectedPhysicalConfigurationFingerprint: String,
		expectedCollectedDataEpoch: Long,
		expectedProviderResidency: String,
	): Boolean = !(registration.sourceKind != expectedSourceKind ||
		registration.registrationGeneration != expectedRegistrationGeneration ||
		registration.sourceInstanceId != expectedSourceInstanceId ||
		registration.clockDomainId != expectedClockDomainId ||
		registration.physicalConfigurationFingerprint !=
		expectedPhysicalConfigurationFingerprint ||
		registration.collectedDataEpoch != expectedCollectedDataEpoch ||
		registration.providerResidency != expectedProviderResidency)

	// These are the existing acceptance/window inputs; keeping them explicit avoids a transient state wrapper.
	@Suppress("LongParameterList")
	private fun observationsFitAcceptedInterval(
		registration: ProviderRegistrationGenerationEntity,
		acceptedAtMs: Long,
		acceptedElapsedNanos: Long,
		intervalStartNanos: Long,
		intervalEndNanos: Long,
		actualObservations: List<ProviderRegistrationActualObservation>,
		allowEndAtRetirement: Boolean,
	): Boolean {
		if (acceptedAtMs < 0L ||
			registration.reservedElapsedRealtimeNanos > acceptedElapsedNanos ||
			intervalStartNanos < acceptedElapsedNanos ||
			intervalEndNanos < intervalStartNanos
		) {
			return false
		}
		return !actualObservations.any { observation ->
			observation.elapsedRealtimeNanos < acceptedElapsedNanos ||
				allowEndAtRetirement && !observation.overlaps(
					intervalStartNanos,
					intervalEndNanos,
				)
		}
	}

	private fun retiredIntervalEnd(
		registration: ProviderRegistrationGenerationEntity,
		acceptedElapsedNanos: Long,
		intervalEndNanos: Long,
		actualObservations: List<ProviderRegistrationActualObservation>,
		allowEndAtRetirement: Boolean,
	): Long? {
		val retiredAtMs = registration.retiredAtMs ?: return null
		val retiredElapsedNanos = registration.retiredElapsedRealtimeNanos ?: return null
		val validStatus = when (registration.status) {
			ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
				!registration.failureCode.isNullOrBlank()
			ProviderRegistrationGenerationEntity.STATUS_RETIRED ->
				registration.failureCode?.isNotBlank() != false
			else -> false
		}
		if (!validStatus || retiredAtMs < 0L || retiredElapsedNanos <= acceptedElapsedNanos) {
			return null
		}
		return if (observationsFitRetirement(
			actualObservations, retiredElapsedNanos, intervalEndNanos, allowEndAtRetirement,
		)) {
			retiredElapsedNanos
		} else {
			null
		}
	}

	private fun observationsFitRetirement(
		actualObservations: List<ProviderRegistrationActualObservation>,
		retiredElapsedNanos: Long,
		intervalEndNanos: Long,
		allowEndAtRetirement: Boolean,
	): Boolean = !(actualObservations.any { it.elapsedRealtimeNanos >= retiredElapsedNanos } ||
		actualObservations.any {
			it.coverageEndExclusiveNanos?.let { end -> end > retiredElapsedNanos } == true
		} || if (allowEndAtRetirement) {
			intervalEndNanos > retiredElapsedNanos
		} else {
			intervalEndNanos >= retiredElapsedNanos
		})

	private fun activeIntervalEnd(
		registration: ProviderRegistrationGenerationEntity,
		currentClockDomainId: String?,
		currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	): Long? {
		if (registration.failureCode != null || currentClockDomainId == null ||
			registration.clockDomainId != currentClockDomainId
		) {
			return null
		}
		return if (currentProviderRegistrations.none { current ->
			matchesActiveRegistration(current, registration)
		}) {
			null
		} else {
			Long.MAX_VALUE
		}
	}

	private fun priorSystemIntervalEnd(
		registration: ProviderRegistrationGenerationEntity,
		currentClockDomainId: String?,
		currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	): Long? {
		if (registration.sourceKind != SourceDestinationOwnerEntity.SOURCE_LOCATION ||
			registration.failureCode != ProviderRegistrationGenerationEntity.FAILURE_PRIOR_BOOT_SYSTEM_REMOVED ||
			currentClockDomainId == null || registration.clockDomainId == currentClockDomainId
		) {
			return null
		}
		return if (currentProviderRegistrations.any { current ->
			current.sourceKind == registration.sourceKind &&
				current.sourceInstanceId == registration.sourceInstanceId
		}) {
			null
		} else {
			Long.MAX_VALUE
		}
	}

	private fun priorProcessIntervalEnd(
		registration: ProviderRegistrationGenerationEntity,
		currentClockDomainId: String?,
		currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	): Long? {
		val processIncarnationId = registration.providerProcessIncarnationId ?: return null
		if (registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
			registration.failureCode != ProviderRegistrationGenerationEntity.FAILURE_PRIOR_PROCESS_INCARNATION_ENDED ||
			currentClockDomainId == null || registration.clockDomainId == currentClockDomainId
		) {
			return null
		}
		return if (currentProviderRegistrations.any { current ->
			current.clockDomainId == currentClockDomainId &&
				current.sourceKind == registration.sourceKind &&
				(current.sourceInstanceId == registration.sourceInstanceId ||
					current.providerProcessIncarnationId == processIncarnationId)
		}) {
			null
		} else {
			Long.MAX_VALUE
		}
	}
}

/**
 * Verifies the original provider identity, configuration, epoch, clock and observed interval while
 * bounding current-registration dependencies.
 */
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

/**
 * Verifies a real fact window and its actual observation clocks against the original provider
 * registration interval.
 */
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

// One exact active-provider identity tuple; keep all five fields visible together.
@Suppress("ComplexCondition")
private fun matchesActiveRegistration(
	current: ProviderRegistrationGenerationEntity,
	registration: ProviderRegistrationGenerationEntity,
): Boolean = current.sourceKind == registration.sourceKind &&
	current.registrationGeneration == registration.registrationGeneration &&
	current.sourceInstanceId == registration.sourceInstanceId &&
	current.clockDomainId == registration.clockDomainId &&
	current.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE

