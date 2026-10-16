package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.hasExactRadioSessionCaptureCallbackBarrier
import com.adsamcik.tracker.tracker.source.model.SourceKind

internal suspend fun AppDatabase.requireSourceCaptureAdmissionFenced(sourceKind: Int) {
	require(isSourceCaptureAdmissionFenced(sourceKind)) {
		"Source capture must cross its demand and callback-authorization retirement barrier first"
	}
}

/** Location has one physical owner whose all-day purpose can outlive session capture. */
internal suspend fun AppDatabase.isLocationSessionCaptureAdmissionFenced(): Boolean {
	val broker = sourceBrokerDao()
	val source = SourceKind.LOCATION.stableCode
	if (broker.activeDemands(source).any { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
		return false
	}
	val epoch = sourceEvidenceStateDao().get()?.collectedDataEpoch ?: return false
	val registrations = (broker.currentPhysicalRegistrations(source) + broker.pendingProviderRemovals(source))
		.distinctBy { it.registrationGeneration }
	return registrations.all { registration ->
		val current = broker.captureFreeLocationAuthorization(registration, epoch) ?: return@all false
		broker.hasLocationCaptureCallbackBarrier(registration, current)
	}
}

private suspend fun SourceBrokerDao.captureFreeLocationAuthorization(
	registration: ProviderRegistrationGenerationEntity,
	epoch: Long,
): SourceAuthorizationSnapshot? {
	if (registration.collectedDataEpoch != epoch) {
		return null
	}
	val current = try {
		latestAuthorization(SourceKind.LOCATION.stableCode, registration.registrationGeneration)
			.toAuthorizationSnapshotOrNull()
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		null
	} ?: return null
	return current.takeIf { it.hasNoCaptureMemberFor(registration) }
}

private suspend fun SourceBrokerDao.hasLocationCaptureCallbackBarrier(
	registration: ProviderRegistrationGenerationEntity,
	current: SourceAuthorizationSnapshot,
): Boolean {
	val lastCaptureRevision = maximumLocationSessionCaptureAuthorizationRevision(registration.registrationGeneration)
	if (lastCaptureRevision == 0L) {
		return true
	}
	val removal = try {
		nextAuthorizationRevision(SourceKind.LOCATION.stableCode, registration.registrationGeneration, lastCaptureRevision)
			.toAuthorizationSnapshotOrNull()
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		null
	} ?: return false
	return removal.effectiveBootId == registration.clockDomainId &&
		removal.authorizationRevision <= current.authorizationRevision &&
		removal.authorizedMembers.none {
			it.persistenceEligible && it.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		} && registration.captureCallbackBarrierAuthorizationRevision >= removal.authorizationRevision &&
		registration.captureCallbackBarrierAuthorizationRevision <= current.authorizationRevision
}

private fun SourceAuthorizationSnapshot.hasNoCaptureMemberFor(
	registration: ProviderRegistrationGenerationEntity,
): Boolean = effectiveBootId == registration.clockDomainId && authorizedMembers.none {
	it.persistenceEligible && it.purpose == SourceBrokerPurpose.SESSION_CAPTURE
}

/** Session setup retires only shared radio CAPTURE; generic/privacy fencing remains full-purpose. */
internal suspend fun AppDatabase.isSharedRadioSessionCaptureAdmissionFenced(sourceKind: Int): Boolean {
	require(sourceKind in setOf(SourceKind.WIFI.stableCode, SourceKind.CELL.stableCode))
	val broker = sourceBrokerDao()
	val demands = broker.activeDemandsBounded(sourceKind, MAX_SHARED_RADIO_DEMANDS + 1)
	if (demands.size > MAX_SHARED_RADIO_DEMANDS || demands.any { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
		return false
	}
	val epoch = sourceEvidenceStateDao().get()?.collectedDataEpoch ?: return false
	val registrations = broker.sharedRadioCaptureRegistrations(sourceKind) ?: return false
	return registrations.all { registration ->
		val current = broker.captureFreeRadioAuthorization(sourceKind, registration, epoch) ?: return@all false
		val lastCapture = broker.maximumRadioSessionCaptureAuthorizationRevision(sourceKind,
			registration.registrationGeneration)
		lastCapture <= 0L || hasExactRadioSessionCaptureCallbackBarrier(registration, current, lastCapture)
	}
}

private suspend fun SourceBrokerDao.sharedRadioCaptureRegistrations(
	sourceKind: Int,
): List<ProviderRegistrationGenerationEntity>? {
	val active = currentPhysicalRegistrationsBounded(sourceKind, MAX_SHARED_RADIO_REGISTRATIONS + 1)
	val retiring = pendingProviderRemovalsBounded(sourceKind, MAX_SHARED_RADIO_REGISTRATIONS + 1)
	return if (active.size > MAX_SHARED_RADIO_REGISTRATIONS || retiring.size > MAX_SHARED_RADIO_REGISTRATIONS) {
		null
	} else {
		(active + retiring).distinctBy { it.registrationGeneration }
	}
}

private suspend fun SourceBrokerDao.captureFreeRadioAuthorization(
	sourceKind: Int,
	registration: ProviderRegistrationGenerationEntity,
	epoch: Long,
): SourceAuthorizationSnapshot? {
	if (registration.collectedDataEpoch != epoch) {
		return null
	}
	val rows = latestRadioAuthorizationBounded(sourceKind, registration.registrationGeneration,
		MAX_SHARED_RADIO_AUTHORIZATION_MEMBERS + 1)
	if (rows.size > MAX_SHARED_RADIO_AUTHORIZATION_MEMBERS) {
		return null
	}
	val current = try { rows.toAuthorizationSnapshotOrNull() }
	catch (@Suppress("SwallowedException") _: IllegalArgumentException) { null } ?: return null
	return current.takeIf { it.hasNoCaptureMemberFor(registration) }
}

internal suspend fun AppDatabase.isSourceCaptureAdmissionFenced(sourceKind: Int): Boolean {
	val brokerDao = sourceBrokerDao()
	val capturePurposes = setOf(
		SourceBrokerPurpose.SESSION_CAPTURE,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
	)
	if (brokerDao.activeDemands(sourceKind).any { it.purpose in capturePurposes }) {
		return false
	}

	val nonterminalRegistrations = (
		brokerDao.currentPhysicalRegistrations(sourceKind) +
			brokerDao.pendingProviderRemovals(sourceKind)
	).distinctBy { it.registrationGeneration }
	return nonterminalRegistrations.all { registration ->
		val latestAuthorization = brokerDao.latestAuthorization(
			sourceKind,
			registration.registrationGeneration,
		)
		if (latestAuthorization.isEmpty() || latestAuthorization.any { authorization ->
			authorization.persistenceEligible && authorization.purpose in capturePurposes
		}) {
			return@all false
		}
		val captureRevision = brokerDao.maximumCaptureAuthorizationRevision(
			sourceKind,
			registration.registrationGeneration,
		)
		registration.captureCallbackBarrierAuthorizationRevision >= captureRevision
	}
}

private const val MAX_SHARED_RADIO_DEMANDS = 256

private const val MAX_SHARED_RADIO_REGISTRATIONS = 64

private const val MAX_SHARED_RADIO_AUTHORIZATION_MEMBERS = 64
