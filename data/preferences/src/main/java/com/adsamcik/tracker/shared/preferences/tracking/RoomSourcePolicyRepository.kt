package com.adsamcik.tracker.shared.preferences.tracking

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.fenceSourcePurposesInTransaction
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LegacySourceSettingsUnavailableException : IllegalStateException(
	"Legacy source settings have not completed durable migration",
)

/** Room-backed single runtime authority for source enablement, QoS, persistence, and consent. */
class RoomSourcePolicyRepository(
	private val database: AppDatabase,
	private val effectiveTimeProvider: SourcePolicyEffectiveTimeProvider,
) : SourcePolicyRepository {
	private val dao get() = database.sourcePolicyDao()

	override val states: Flow<SourcePolicyAuthorityState> = dao.observeAuthorityWithPolicies()
		.map { joined ->
			if (joined == null) {
				SourcePolicyAuthorityState.Uninitialized
			} else {
				authorityState(joined.authority, joined.policies)
			}
		}

	override suspend fun currentState(): SourcePolicyAuthorityState = database.withTransaction {
		ensureAuthority()
		authorityState(dao.authority(), dao.currentPolicies())
	}

	override suspend fun bootstrapFromLegacy(settings: TrackingParamsState): SourcePolicySnapshot {
		if (!settings.legacySettingsMigrationCompleted) {
			throw LegacySourceSettingsUnavailableException()
		}
		return database.withTransaction {
			ensureAuthority()
			val authority = requireNotNull(dao.authority())
			if (authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE) {
				return@withTransaction requireActiveSnapshot(authority, dao.currentPolicies())
			}
			check(authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_UNINITIALIZED) {
				"Unsupported source-policy bootstrap state ${authority.bootstrapState}"
			}
			check(authority.currentPolicyRevision == 0L) {
				"Uninitialized source-policy authority has a nonzero revision"
			}
			val effectiveTime = effectiveTimeProvider.now()

			val revision = 1L
			val desired = desiredCapturePolicies(settings)
			val automaticControlEligible = settings.automaticControlEligible()
			val initialConsentRows = buildList {
				TrackingSourceComponent.entries.forEach { source ->
					SourcePurpose.entries.forEach { purpose ->
						add(
							consentEntity(
								source = source,
								purpose = purpose,
								epoch = 0L,
								eligible = false,
								persistenceEligible = false,
								policyRevision = revision,
								effectiveTime = effectiveTime,
								reason = REASON_BOOTSTRAP_DENY,
							),
						)
					}
					if (desired.getValue(source).enabled) {
						add(
							consentEntity(
								source = source,
								purpose = SourcePurpose.SESSION_CAPTURE,
								epoch = 1L,
								eligible = true,
								persistenceEligible = true,
								policyRevision = revision,
								effectiveTime = effectiveTime,
								reason = REASON_LEGACY_CAPTURE_IMPORT,
							),
						)
					}
					if (source == TrackingSourceComponent.ACTIVITY && automaticControlEligible) {
						add(
							consentEntity(
								source = source,
								purpose = SourcePurpose.CONTROL,
								epoch = 1L,
								eligible = true,
								persistenceEligible = false,
								policyRevision = revision,
								effectiveTime = effectiveTime,
								reason = REASON_LEGACY_AUTOMATIC_CONTROL_IMPORT,
							),
						)
					}
				}
			}
			dao.insertConsentEpochs(initialConsentRows)
			val policies = desired.values.map { policy ->
				policyEntity(
					revision = revision,
					desired = policy,
					captureConsentEpoch = if (policy.enabled) 1L else null,
					controlConsentEpoch = 1L.takeIf {
						policy.source == TrackingSourceComponent.ACTIVITY && automaticControlEligible
					},
					ambientConsentEpoch = null,
					capturePersistenceEligible = policy.enabled,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					effectiveTime = effectiveTime,
					reason = REASON_LEGACY_BOOTSTRAP,
				)
			}
			dao.insertPolicies(policies)
			check(
				dao.compareAndSetAuthority(
					expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_UNINITIALIZED,
					expectedRevision = 0L,
					bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
					newRevision = revision,
					legacySettingsFingerprint = settingsFingerprint(settings),
					updatedAtMs = effectiveTime.wallTimeMs,
				) == 1,
			) { "Source-policy bootstrap authority CAS failed" }
			requireActiveSnapshot(requireNotNull(dao.authority()), dao.currentPolicies())
		}
	}

	override suspend fun replaceCaptureSettings(
		expectedPolicyRevision: Long,
		settings: TrackingParamsState,
		reason: String,
	): SourcePolicySnapshot {
		require(reason.isNotBlank()) { "Policy change reason must not be blank" }
		return database.withTransaction {
			val current = activeSnapshotAtExpectedRevision(expectedPolicyRevision)
			val authority = requireNotNull(dao.authority())
			val desired = desiredCapturePolicies(settings)
			val automaticControlEligible = settings.automaticControlEligible()
			val previousAutomaticControlEligible =
				current[TrackingSourceComponent.ACTIVITY].controlConsentEpoch != null
			val captureChanged = TrackingSourceComponent.entries.any { source ->
				val old = current[source]
				val next = desired.getValue(source)
				old.enabled != next.enabled ||
					old.qos != next.qos ||
					old.locationMinTimeSeconds != next.locationMinTimeSeconds ||
					old.locationMinDistanceMeters != next.locationMinDistanceMeters ||
					old.locationRequiredAccuracyMeters != next.locationRequiredAccuracyMeters
			}
			val automaticControlChanged =
				previousAutomaticControlEligible != automaticControlEligible
			val settingsFingerprint = settingsFingerprint(settings)
			val automaticControlConfigurationChanged =
				authority.legacySettingsFingerprint != settingsFingerprint
			if (!captureChanged && !automaticControlChanged && !automaticControlConfigurationChanged) {
				return@withTransaction current
			}
			val effectiveTime = effectiveTimeProvider.now()
			TrackingSourceComponent.entries
				.filter { source -> current[source].enabled && !desired.getValue(source).enabled }
				.forEach { source ->
					database.fenceSourcePurposesInTransaction(
						sourceKind = source.stableCode,
						purposes = listOf(SourceBrokerPurpose.SESSION_CAPTURE),
						bootId = effectiveTime.bootId,
						elapsedRealtimeNanos = effectiveTime.elapsedRealtimeNanos,
						wallTimeMs = effectiveTime.wallTimeMs,
					)
				}
			if (previousAutomaticControlEligible && !automaticControlEligible) {
				database.fenceSourcePurposesInTransaction(
					sourceKind = TrackingSourceComponent.ACTIVITY.stableCode,
					purposes = listOf(
						SourceBrokerPurpose.CONTROL_AUTOSTART,
						SourceBrokerPurpose.CONTROL_CONTINUATION,
					),
					bootId = effectiveTime.bootId,
					elapsedRealtimeNanos = effectiveTime.elapsedRealtimeNanos,
					wallTimeMs = effectiveTime.wallTimeMs,
				)
			}

			val nextRevision = checkedNextRevision(current.revision)
			val consentChanges = mutableMapOf<TrackingSourceComponent, Long?>()
			TrackingSourceComponent.entries.forEach { source ->
				val old = current[source]
				val next = desired.getValue(source)
				if (old.enabled == next.enabled) {
					consentChanges[source] = old.captureConsentEpoch
				} else {
					val epoch = nextConsentEpoch(source, SourcePurpose.SESSION_CAPTURE)
					dao.insertConsentEpochs(
						listOf(
							consentEntity(
								source = source,
								purpose = SourcePurpose.SESSION_CAPTURE,
								epoch = epoch,
								eligible = next.enabled,
								persistenceEligible = next.enabled,
								policyRevision = nextRevision,
								effectiveTime = effectiveTime,
								reason = reason,
							),
						),
					)
					consentChanges[source] = epoch.takeIf { next.enabled }
				}
			}
			val automaticControlEpoch = if (automaticControlChanged) {
				val epoch = nextConsentEpoch(TrackingSourceComponent.ACTIVITY, SourcePurpose.CONTROL)
				dao.insertConsentEpochs(
					listOf(
						consentEntity(
							source = TrackingSourceComponent.ACTIVITY,
							purpose = SourcePurpose.CONTROL,
							epoch = epoch,
							eligible = automaticControlEligible,
							persistenceEligible = false,
							policyRevision = nextRevision,
							effectiveTime = effectiveTime,
							reason = reason,
						),
					),
				)
				epoch.takeIf { automaticControlEligible }
			} else {
				current[TrackingSourceComponent.ACTIVITY].controlConsentEpoch
			}
			val entities = TrackingSourceComponent.entries.map { source ->
				val old = current[source]
				val next = desired.getValue(source)
				policyEntity(
					revision = nextRevision,
					desired = next,
					captureConsentEpoch = consentChanges.getValue(source),
					controlConsentEpoch = if (source == TrackingSourceComponent.ACTIVITY) {
						automaticControlEpoch
					} else {
						old.controlConsentEpoch
					},
					ambientConsentEpoch = old.ambientConsentEpoch,
					capturePersistenceEligible = next.enabled,
					controlPersistenceEligible = if (
						source == TrackingSourceComponent.ACTIVITY && automaticControlChanged
					) {
						false
					} else {
						old.controlPersistenceEligible
					},
					ambientPersistenceEligible = old.ambientPersistenceEligible,
					effectiveTime = effectiveTime,
					reason = reason,
				)
			}
			activateRevision(
				current = current,
				newRevision = nextRevision,
				policies = entities,
				settingsFingerprint = settingsFingerprint,
				effectiveTime = effectiveTime,
			)
		}
	}

	override suspend fun setNonCaptureConsent(
		expectedPolicyRevision: Long,
		source: TrackingSourceComponent,
		purpose: SourcePurpose,
		eligible: Boolean,
		persistenceEligible: Boolean,
		reason: String,
	): SourcePolicySnapshot {
		require(purpose != SourcePurpose.SESSION_CAPTURE) {
			"SESSION_CAPTURE consent is changed through capture settings"
		}
		require(!persistenceEligible || eligible) {
			"Persistence cannot be eligible when purpose consent is denied"
		}
		require(reason.isNotBlank()) { "Consent change reason must not be blank" }
		return database.withTransaction {
			val current = activeSnapshotAtExpectedRevision(expectedPolicyRevision)
			val old = current[source]
			val oldEpoch = old.consentEpoch(purpose)
			val oldPersistence = old.persistenceEligible(purpose)
			val currentlyEligible = oldEpoch != null
			if (currentlyEligible == eligible && oldPersistence == persistenceEligible) {
				return@withTransaction current
			}
			val effectiveTime = effectiveTimeProvider.now()
			if (currentlyEligible && (!eligible || oldPersistence && !persistenceEligible)) {
				val brokerPurposes = when (purpose) {
					SourcePurpose.CONTROL -> listOf(
						SourceBrokerPurpose.CONTROL_AUTOSTART,
						SourceBrokerPurpose.CONTROL_CONTINUATION,
					)
					SourcePurpose.AMBIENT_PRODUCT -> listOf(SourceBrokerPurpose.AMBIENT_PRODUCT)
					SourcePurpose.SESSION_CAPTURE -> error("Capture consent uses capture settings")
				}
				database.fenceSourcePurposesInTransaction(
					sourceKind = source.stableCode,
					purposes = brokerPurposes,
					bootId = effectiveTime.bootId,
					elapsedRealtimeNanos = effectiveTime.elapsedRealtimeNanos,
					wallTimeMs = effectiveTime.wallTimeMs,
				)
			}

			val nextRevision = checkedNextRevision(current.revision)
			val nextEpoch = nextConsentEpoch(source, purpose)
			dao.insertConsentEpochs(
				listOf(
					consentEntity(
						source = source,
						purpose = purpose,
						epoch = nextEpoch,
						eligible = eligible,
						persistenceEligible = persistenceEligible,
						policyRevision = nextRevision,
						effectiveTime = effectiveTime,
						reason = reason,
					),
				),
			)
			val entities = TrackingSourceComponent.entries.map { candidate ->
				val previous = current[candidate]
				val isTarget = candidate == source
				policyEntity(
					revision = nextRevision,
					desired = DesiredCapturePolicy(
						previous.source,
						previous.enabled,
						previous.qos,
						previous.locationMinTimeSeconds,
						previous.locationMinDistanceMeters,
						previous.locationRequiredAccuracyMeters,
					),
					captureConsentEpoch = previous.captureConsentEpoch,
					controlConsentEpoch = when {
						!isTarget || purpose != SourcePurpose.CONTROL -> previous.controlConsentEpoch
						eligible -> nextEpoch
						else -> null
					},
					ambientConsentEpoch = when {
						!isTarget || purpose != SourcePurpose.AMBIENT_PRODUCT -> previous.ambientConsentEpoch
						eligible -> nextEpoch
						else -> null
					},
					capturePersistenceEligible = previous.capturePersistenceEligible,
					controlPersistenceEligible = if (isTarget && purpose == SourcePurpose.CONTROL) {
						persistenceEligible
					} else {
						previous.controlPersistenceEligible
					},
					ambientPersistenceEligible = if (isTarget && purpose == SourcePurpose.AMBIENT_PRODUCT) {
						persistenceEligible
					} else {
						previous.ambientPersistenceEligible
					},
					effectiveTime = effectiveTime,
					reason = reason,
				)
			}
			activateRevision(
				current = current,
				newRevision = nextRevision,
				policies = entities,
				settingsFingerprint = null,
				effectiveTime = effectiveTime,
			)
		}
	}

	private suspend fun activeSnapshotAtExpectedRevision(expected: Long): SourcePolicySnapshot {
		ensureAuthority()
		val authority = requireNotNull(dao.authority())
		val current = requireActiveSnapshot(authority, dao.currentPolicies())
		if (current.revision != expected) {
			throw ConcurrentSourcePolicyMutationException(expected, current.revision)
		}
		return current
	}

	private suspend fun activateRevision(
		current: SourcePolicySnapshot,
		newRevision: Long,
		policies: List<SourcePolicyEntity>,
		settingsFingerprint: String?,
		effectiveTime: SourcePolicyEffectiveTime,
	): SourcePolicySnapshot {
		val authority = requireNotNull(dao.authority())
		dao.insertPolicies(policies)
		check(
			dao.compareAndSetAuthority(
				expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				expectedRevision = current.revision,
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				newRevision = newRevision,
				legacySettingsFingerprint = settingsFingerprint ?: authority.legacySettingsFingerprint,
				updatedAtMs = effectiveTime.wallTimeMs,
			) == 1,
		) { "Source-policy authority CAS failed" }
		return requireActiveSnapshot(requireNotNull(dao.authority()), dao.currentPolicies())
	}

	private suspend fun nextConsentEpoch(
		source: TrackingSourceComponent,
		purpose: SourcePurpose,
	): Long {
		val current = dao.latestConsentEpoch(source.stableCode, purpose.stableName)?.epoch ?: -1L
		check(current < Long.MAX_VALUE) { "Consent epoch exhausted for $source/$purpose" }
		return current + 1L
	}

	private suspend fun ensureAuthority() {
		dao.ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_UNINITIALIZED,
				currentPolicyRevision = 0L,
				legacySettingsFingerprint = null,
				updatedAtMs = 0L,
			),
		)
	}

	private suspend fun authorityState(
		authority: SourcePolicyAuthorityEntity?,
		policies: List<SourcePolicyEntity>,
	): SourcePolicyAuthorityState = when {
		authority == null -> SourcePolicyAuthorityState.Uninitialized
		authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_UNINITIALIZED ->
			SourcePolicyAuthorityState.Uninitialized
		authority.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ->
			SourcePolicyAuthorityState.Invalid("Unknown authority state ${authority.bootstrapState}")
		else -> runCatching { requireActiveSnapshot(authority, policies) }
			.fold(SourcePolicyAuthorityState::Active) { error ->
				SourcePolicyAuthorityState.Invalid(error.message ?: "Invalid source-policy snapshot")
			}
	}

	private suspend fun requireActiveSnapshot(
		authority: SourcePolicyAuthorityEntity,
		policies: List<SourcePolicyEntity>,
	): SourcePolicySnapshot {
		check(authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE)
		check(policies.size == TrackingSourceComponent.entries.size) {
			"Active source policy does not contain all supported sources"
		}
		val mapped = policies.associate { entity ->
			val source = TrackingSourceComponent.fromStableCode(entity.sourceKind)
			val policy = SourcePolicy(
				source = source,
				enabled = entity.enabled,
				qos = SourceQos.fromStableCode(entity.qosCode),
				locationMinTimeSeconds = entity.locationMinTimeSeconds,
				locationMinDistanceMeters = entity.locationMinDistanceMeters,
				locationRequiredAccuracyMeters = entity.locationRequiredAccuracyMeters,
				captureConsentEpoch = entity.captureConsentEpoch,
				controlConsentEpoch = entity.controlConsentEpoch,
				ambientConsentEpoch = entity.ambientConsentEpoch,
				capturePersistenceEligible = entity.capturePersistenceEligible,
				controlPersistenceEligible = entity.controlPersistenceEligible,
				ambientPersistenceEligible = entity.ambientPersistenceEligible,
				effectiveTime = SourcePolicyEffectiveTime(
					bootId = entity.effectiveBootId,
					elapsedRealtimeNanos = entity.effectiveElapsedRealtimeNanos,
					wallTimeMs = entity.effectiveWallTimeMs,
				),
				policyRevision = entity.policyRevision,
			)
			validateConsentReference(policy, SourcePurpose.SESSION_CAPTURE)
			validateConsentReference(policy, SourcePurpose.CONTROL)
			validateConsentReference(policy, SourcePurpose.AMBIENT_PRODUCT)
			source to policy
		}
		return SourcePolicySnapshot(authority.currentPolicyRevision, mapped)
	}

	private suspend fun validateConsentReference(policy: SourcePolicy, purpose: SourcePurpose) {
		val referencedEpoch = policy.consentEpoch(purpose)
		val latest = dao.latestConsentEpoch(policy.source.stableCode, purpose.stableName)
		check(latest != null) { "Missing consent history for ${policy.source}/$purpose" }
		if (referencedEpoch == null) {
			check(!latest.eligible) { "Unreferenced eligible consent for ${policy.source}/$purpose" }
			check(!policy.persistenceEligible(purpose)) {
				"Persistence remains eligible without consent for ${policy.source}/$purpose"
			}
			return
		}
		val referenced = dao.consentEpoch(policy.source.stableCode, purpose.stableName, referencedEpoch)
		check(referenced != null) {
			"Missing referenced consent epoch $referencedEpoch for ${policy.source}/$purpose"
		}
		check(referenced.eligible) {
			"Referenced consent epoch is ineligible for ${policy.source}/$purpose"
		}
		check(referenced.persistenceEligible == policy.persistenceEligible(purpose)) {
			"Consent persistence mismatch for ${policy.source}/$purpose"
		}
		check(latest.epoch == referencedEpoch && latest.eligible) {
			"Referenced consent epoch is fenced by newer history for ${policy.source}/$purpose"
		}
	}

	private fun desiredCapturePolicies(
		settings: TrackingParamsState,
	): Map<TrackingSourceComponent, DesiredCapturePolicy> = TrackingSourceComponent.entries.associateWith { source ->
		val booleanEnabled = settings.booleanEnabled(source)
		val frequency = settings.frequency(source)
		val enabled = booleanEnabled && frequency != SourceCollectionFrequency.OFF
		DesiredCapturePolicy(
			source = source,
			enabled = enabled,
			qos = if (enabled) frequency.toQos() else SourceQos.OFF,
			locationMinTimeSeconds = settings.minTimeSeconds.coerceAtLeast(1).takeIf {
				source == TrackingSourceComponent.LOCATION
			},
			locationMinDistanceMeters = settings.minDistanceMeters.coerceAtLeast(1).takeIf {
				source == TrackingSourceComponent.LOCATION
			},
			locationRequiredAccuracyMeters = settings.requiredAccuracyMeters.coerceAtLeast(1).takeIf {
				source == TrackingSourceComponent.LOCATION
			},
		)
	}

	private fun policyEntity(
		revision: Long,
		desired: DesiredCapturePolicy,
		captureConsentEpoch: Long?,
		controlConsentEpoch: Long?,
		ambientConsentEpoch: Long?,
		capturePersistenceEligible: Boolean,
		controlPersistenceEligible: Boolean,
		ambientPersistenceEligible: Boolean,
		effectiveTime: SourcePolicyEffectiveTime,
		reason: String,
	): SourcePolicyEntity = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = desired.source.stableCode,
		enabled = desired.enabled,
		qosCode = desired.qos.stableCode,
		locationMinTimeSeconds = desired.locationMinTimeSeconds,
		locationMinDistanceMeters = desired.locationMinDistanceMeters,
		locationRequiredAccuracyMeters = desired.locationRequiredAccuracyMeters,
		capturePersistenceEligible = capturePersistenceEligible,
		controlPersistenceEligible = controlPersistenceEligible,
		ambientPersistenceEligible = ambientPersistenceEligible,
		captureConsentEpoch = captureConsentEpoch,
		controlConsentEpoch = controlConsentEpoch,
		ambientConsentEpoch = ambientConsentEpoch,
		effectiveBootId = effectiveTime.bootId,
		effectiveElapsedRealtimeNanos = effectiveTime.elapsedRealtimeNanos,
		effectiveWallTimeMs = effectiveTime.wallTimeMs,
		changeReason = reason,
	)

	private fun consentEntity(
		source: TrackingSourceComponent,
		purpose: SourcePurpose,
		epoch: Long,
		eligible: Boolean,
		persistenceEligible: Boolean,
		policyRevision: Long,
		effectiveTime: SourcePolicyEffectiveTime,
		reason: String,
	): SourceConsentEpochEntity = SourceConsentEpochEntity(
		sourceKind = source.stableCode,
		purpose = purpose.stableName,
		epoch = epoch,
		eligible = eligible,
		persistenceEligible = persistenceEligible,
		policyRevision = policyRevision,
		effectiveBootId = effectiveTime.bootId,
		effectiveElapsedRealtimeNanos = effectiveTime.elapsedRealtimeNanos,
		effectiveWallTimeMs = effectiveTime.wallTimeMs,
		changeReason = reason,
	)

	private fun settingsFingerprint(settings: TrackingParamsState): String {
		val sourceFingerprint = TrackingSourceComponent.entries.joinToString("|") { source ->
			"${source.stableCode}:${settings.booleanEnabled(source)}:${settings.frequency(source).stableCode}"
		}
		val canonical = "$sourceFingerprint|location:${settings.minTimeSeconds}:" +
			"${settings.minDistanceMeters}:${settings.requiredAccuracyMeters}|" +
			"automatic-control:${settings.automaticControlEligible()}:" +
			"mode:${settings.autoTrackingMode}:transitions:${settings.transitionDetectionEnabled}"
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	private data class DesiredCapturePolicy(
		val source: TrackingSourceComponent,
		val enabled: Boolean,
		val qos: SourceQos,
		val locationMinTimeSeconds: Int?,
		val locationMinDistanceMeters: Int?,
		val locationRequiredAccuracyMeters: Int?,
	)

	private companion object {
		const val REASON_BOOTSTRAP_DENY = "LEGACY_BOOTSTRAP_DEFAULT_DENY"
		const val REASON_LEGACY_CAPTURE_IMPORT = "MIGRATED_LEGACY_CAPTURE"
		const val REASON_LEGACY_AUTOMATIC_CONTROL_IMPORT = "MIGRATED_LEGACY_AUTOMATIC_CONTROL"
		const val REASON_LEGACY_BOOTSTRAP = "LEGACY_SETTINGS_BOOTSTRAP"
	}
}

private fun checkedNextRevision(current: Long): Long {
	check(current < Long.MAX_VALUE) { "Source-policy revision is exhausted" }
	return current + 1L
}

private fun TrackingParamsState.booleanEnabled(source: TrackingSourceComponent): Boolean = when (source) {
	TrackingSourceComponent.LOCATION -> locationEnabled
	TrackingSourceComponent.ACTIVITY -> activityEnabled
	TrackingSourceComponent.STEPS -> stepsEnabled
	TrackingSourceComponent.PRESSURE -> barometerEnabled
	TrackingSourceComponent.WIFI -> wifiEnabled
	TrackingSourceComponent.CELL -> cellEnabled
}

private fun TrackingParamsState.frequency(source: TrackingSourceComponent): SourceCollectionFrequency =
	when (source) {
		TrackingSourceComponent.LOCATION -> sourceCollectionSettings.location
		TrackingSourceComponent.ACTIVITY -> sourceCollectionSettings.activity
		TrackingSourceComponent.STEPS -> sourceCollectionSettings.steps
		TrackingSourceComponent.PRESSURE -> sourceCollectionSettings.pressure
		TrackingSourceComponent.WIFI -> sourceCollectionSettings.wifi
		TrackingSourceComponent.CELL -> sourceCollectionSettings.cell
	}

private fun TrackingParamsState.automaticControlEligible(): Boolean = autoTrackingMode > 0

private fun SourceCollectionFrequency.toQos(): SourceQos = SourceQos.fromStableCode(stableCode)
