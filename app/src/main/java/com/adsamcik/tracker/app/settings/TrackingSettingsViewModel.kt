package com.adsamcik.tracker.app.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.PermissionGrantHistory
import com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilities
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.preferences.tracking.LocationAdaptiveSettings
import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceOverrides
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationDebt
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationException
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.UnavailableSourcePolicyRevisionReconciliationCoordinator
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimate
import com.adsamcik.tracker.tracker.source.battery.ImpactLevel
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceStatus
import com.adsamcik.tracker.tracker.source.coordinator.PlanResolutionContext
import com.adsamcik.tracker.tracker.source.coordinator.SourceConstraint
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRuntimeStatus
import com.adsamcik.tracker.tracker.source.coordinator.TrackingSettingsPreviewEnvironment
import com.adsamcik.tracker.tracker.source.coordinator.TrackingSettingsStatusProvider
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import javax.inject.Inject

/** Consolidated UI state for the tracking settings screen. */
data class TrackingSettingsUiState(
    val isLoaded: Boolean = false,
    val currentPreset: TrackingPreset = TrackingPreset.DEFAULT,
    val currentBatteryImpact: BatteryImpact = BatteryImpact.MODERATE,
    val batteryEstimate: BatteryImpactEstimate? = null,
    val locationEnabled: Boolean = true,
    val activityEnabled: Boolean = true,
    val stepsEnabled: Boolean = true,
    val wifiEnabled: Boolean = true,
    val cellEnabled: Boolean = false,
    val barometerEnabled: Boolean = true,
	val ambientLocationEnabled: Boolean = false,
	val locationProfileVersion: Int? = null,
	val locationBalanceLevel: Int? = null,
	val balanceProfileVersion: Int? = null,
	val overallBalanceLevel: Int? = null,
	val sourceBalanceOverrides: SourceBalanceOverrides = SourceBalanceOverrides(),
	val locationBackgroundMode: LocationBackgroundMode? = null,
	val locationMotionAssistanceEnabled: Boolean = false,
	val locationSettingsSaveFailed: Boolean = false,
	val ambientStepsEnabled: Boolean = false,
	val ambientWifiEnabled: Boolean = false,
	val ambientCellEnabled: Boolean = false,
	val ambientSourceAvailability: Map<AmbientTrackingSource, AmbientSourceOperationalAvailability> =
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.ambientSources,
    val barometerAvailable: Boolean = true,
    val activityPermissionGranted: Boolean = false,
    val stepCounterAvailable: Boolean = true,
    val wifiPermissionGranted: Boolean = false,
    val cellPermissionGranted: Boolean = false,
    val permissionCapabilities: TrackingPermissionCapabilities = TrackingPermissionCapabilities.denied(),
    val autoTrackingMode: Int = 0,
    val autoTrackingEnabled: Boolean = false,
	val automaticTrackingOperational: Boolean = false,
	val automaticTrackingPermissionRequired: Boolean = false,
	val automaticControlAvailability: AutomaticTrackingOperationalAvailability =
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.automaticControl,
    val transitionDetectionEnabled: Boolean = true,
    val notificationStyled: Boolean = true,
    val minDistance: Int = 10,
    val minTime: Int = 2,
    val requiredAccuracy: Int = 50,
    val hasValidSources: Boolean = true,
    val advancedSourceControlsEnabled: Boolean = false,
    val sourceCollectionSettings: SourceCollectionSettings = SourceCollectionSettings(),
    val sourceStatuses: Map<SourceKind, EffectiveSourceStatus> = emptyMap(),
    val sourcePlans: Map<SourceKind, SourcePlan> = emptyMap(),
    val sourceFrequencyOptions: Map<SourceKind, Map<SourceCollectionFrequency, SourcePlan>> = emptyMap(),
    val trackingActive: Boolean = false,
	val sourcePolicyAvailable: Boolean = false,
	val sourcePolicyRevision: Long? = null,
	val runtimeSourcePolicyRevision: Long? = null,
    val desiredPlanRevision: Long? = null,
    val appliedPlanRevision: Long? = null,
    val runtimeFailureCode: String? = null,
    val ambientStepsSettingsFailure: AmbientStepsSettingsReconciliationFailure? = null,
	val sourcePolicyReconciliationDebt: SourcePolicyRevisionReconciliationDebt? = null,
    val runtimeTelemetry: TrackingCoordinatorMetrics = TrackingCoordinatorMetrics.ZERO,
)

enum class LocationSettingsPermissionKind { LOCATION, MOTION }

data class LocationSettingsPermissionRequest(
	val kind: LocationSettingsPermissionKind,
	val intentGeneration: Long,
)

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackingParamsRepository: TrackingParamsRepository,
    private val trackingStatusProvider: TrackingSettingsStatusProvider,
    private val activityWatcherController: ActivityWatcherController,
    private val purposeAvailabilityReader: CurrentTrackingPurposeAvailabilityReader,
    private val sourcePolicyReconciliationCoordinator:
		SourcePolicyRevisionReconciliationCoordinator =
		UnavailableSourcePolicyRevisionReconciliationCoordinator,
	private val lifecycleCommandAuthority: TrackingLifecycleCommandAuthority? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackingSettingsUiState())
    val uiState: StateFlow<TrackingSettingsUiState> = _uiState.asStateFlow()
    private var latestParams: TrackingParamsState? = null
	private var locationIntentGeneration = 0L
	private var automaticIntentGeneration = 0L
	private val sourceIntentGenerations = mutableMapOf<TrackingSourceComponent, Long>()
	private val locationPermissionRequests = Channel<LocationSettingsPermissionRequest>(Channel.BUFFERED)
	val locationPermissionEvents = locationPermissionRequests.receiveAsFlow()
	private var latestPurposeAvailability = purposeAvailabilityReader.availability.value
    private var permissionHistory = PermissionGrantHistory()

    init {
        viewModelScope.launch {
            combine(
                trackingParamsRepository.data,
                trackingStatusProvider.runtimeStatus,
				purposeAvailabilityReader.availability,
			) { params, runtimeStatus, purposeAvailability ->
				Triple(params, runtimeStatus, purposeAvailability)
			}
                .collect { (params, runtimeStatus, purposeAvailability) ->
                latestParams = params
				latestPurposeAvailability = purposeAvailability
                updateUiState(
					params,
					runtimeStatus,
					trackingStatusProvider.telemetry.value,
					purposeAvailability,
				)
            }
        }
        viewModelScope.launch {
            trackingStatusProvider.telemetry.collect { telemetry ->
                _uiState.update { state -> state.copy(runtimeTelemetry = telemetry) }
            }
        }
		viewModelScope.launch {
			sourcePolicyReconciliationCoordinator.reconciliationState.collect { reconciliation ->
				_uiState.update { state ->
					state.copy(
						sourcePolicyReconciliationDebt =
							(reconciliation as? SourcePolicyRevisionReconciliationState.Debt)?.debt,
					)
				}
			}
		}
    }

    /** Re-evaluates permission- and hardware-gated state after returning to this screen. */
    fun refreshPermissionState() {
        latestParams?.let { params ->
            updateUiState(
                params,
                trackingStatusProvider.runtimeStatus.value,
                trackingStatusProvider.telemetry.value,
				latestPurposeAvailability,
            )
        }
    }

    fun setLocationEnabled(enabled: Boolean) {
		launchSourcePolicyMutation(markCustomPreset = true) {
			trackingParamsRepository.setLocationEnabled(enabled)
		}
    }

    fun setActivityEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        mutateSourceSelection(TrackingSourceComponent.ACTIVITY, onSaved) {
            trackingParamsRepository.setActivityEnabled(enabled)
        }
    }

    fun setStepsEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        mutateSourceSelection(TrackingSourceComponent.STEPS, onSaved) {
            trackingParamsRepository.setStepsEnabled(enabled)
        }
    }

    fun setWifiEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        mutateSourceSelection(TrackingSourceComponent.WIFI, onSaved) {
            trackingParamsRepository.setWifiEnabled(enabled)
        }
    }

    fun setCellEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        mutateSourceSelection(TrackingSourceComponent.CELL, onSaved) {
            trackingParamsRepository.setCellEnabled(enabled)
        }
    }

    /** Save participation before prompting; a superseding edit cancels the old prompt. */
    private fun mutateSourceSelection(
        source: TrackingSourceComponent,
        onSaved: () -> Unit,
        mutation: suspend () -> Unit,
    ) {
        val generation = (sourceIntentGenerations[source] ?: 0L) + 1L
        sourceIntentGenerations[source] = generation
        launchSourcePolicyMutation(
            markCustomPreset = true,
            afterCommittedMutation = {
                if (sourceIntentGenerations[source] == generation) {
                    refreshPermissionState()
                    onSaved()
                }
            },
            mutation = mutation,
        )
    }

    fun setBarometerEnabled(enabled: Boolean) {
		launchSourcePolicyMutation(markCustomPreset = true) {
			trackingParamsRepository.setBarometerEnabled(enabled)
		}
    }

	fun setAmbientLocationEnabled(enabled: Boolean) {
		mutateLocationSettings(if (enabled) LocationSettingsPermissionKind.LOCATION else null, resumeAfterUserExit = enabled) {
			if (enabled) trackingParamsRepository.enableAdaptiveBackgroundLocation()
			else trackingParamsRepository.setAmbientLocationEnabled(false)
		}
	}

	fun setLocationBackgroundMode(mode: LocationBackgroundMode) {
		mutateLocationSettings(
			LocationSettingsPermissionKind.LOCATION.takeIf { _uiState.value.ambientLocationEnabled },
			resumeAfterUserExit = mode == LocationBackgroundMode.ADAPTIVE,
		) { trackingParamsRepository.setLocationBackgroundMode(mode) }
	}

    fun adoptBalanceProfile(level: Int) {
        launchSourcePolicyMutation { trackingParamsRepository.adoptBalanceProfile(level) }
    }

    fun setSourceBalanceOverride(source: TrackingSourceComponent, level: Int?) {
        launchSourcePolicyMutation { trackingParamsRepository.setSourceBalanceOverride(source, level) }
    }

	fun adoptLocationProfile(level: Int = LocationAdaptiveSettings.DEFAULT_BALANCE_LEVEL) {
		mutateLocationSettings { trackingParamsRepository.adoptLocationProfile(level) }
	}

	fun setLocationMotionAssistanceEnabled(enabled: Boolean) {
		mutateLocationSettings(if (enabled) LocationSettingsPermissionKind.MOTION else null) {
			trackingParamsRepository.setLocationMotionAssistanceEnabled(enabled)
		}
	}

	/** Retry permissions for already saved intent; a platform result never changes source settings. */
	fun requestSavedLocationPermission(kind: LocationSettingsPermissionKind) {
		val state = _uiState.value
		val eligible = when (kind) {
			LocationSettingsPermissionKind.LOCATION -> state.ambientLocationEnabled
			LocationSettingsPermissionKind.MOTION -> state.locationProfileVersion == 1 &&
				state.locationMotionAssistanceEnabled
		}
		if (eligible) locationPermissionRequests.trySend(LocationSettingsPermissionRequest(kind, locationIntentGeneration))
	}

	fun isCurrentLocationPermissionRequest(request: LocationSettingsPermissionRequest): Boolean =
		request.intentGeneration == locationIntentGeneration

	private fun mutateLocationSettings(
		permission: LocationSettingsPermissionKind? = null,
		resumeAfterUserExit: Boolean = false,
		mutation: suspend () -> Unit,
	) {
		val generation = ++locationIntentGeneration
		val expectedExitId = lifecycleCommandAuthority?.pendingCollectionResumeExitId.takeIf { resumeAfterUserExit }
		_uiState.update { it.copy(locationSettingsSaveFailed = false) }
		viewModelScope.launch {
			try {
				mutation()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (committed: SourcePolicyRevisionReconciliationException) {
				// The policy committed. Permissions can resolve runtime debt; never resave on grant.
				publishSourcePolicyReconciliationDebt(committed)
			} catch (_: Exception) {
				if (generation == locationIntentGeneration) {
					_uiState.update { it.copy(locationSettingsSaveFailed = true) }
				}
				return@launch
			}
			if (permission != null && generation == locationIntentGeneration) {
				if (expectedExitId != null && lifecycleCommandAuthority?.resumeCollectionAfterUserExit(expectedExitId) != true) {
					_uiState.update { it.copy(locationSettingsSaveFailed = true) }
					return@launch
				}
				locationPermissionRequests.send(LocationSettingsPermissionRequest(permission, generation))
			}
		}
	}

	fun setAmbientStepsEnabled(enabled: Boolean) {
		launchSourcePolicyMutation {
			trackingParamsRepository.setAmbientStepsEnabled(enabled)
		}
	}

	fun setAmbientWifiEnabled(enabled: Boolean) {
		launchSourcePolicyMutation {
			trackingParamsRepository.setAmbientWifiEnabled(enabled)
		}
	}

	fun setAmbientCellEnabled(enabled: Boolean) {
		launchSourcePolicyMutation {
			trackingParamsRepository.setAmbientCellEnabled(enabled)
		}
	}

    fun setAdvancedSourceControlsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setAdvancedSourceControlsEnabled(enabled)
        }
    }

    fun setSourceFrequency(component: TrackingSourceComponent, frequency: SourceCollectionFrequency) {
		launchSourcePolicyMutation(markCustomPreset = true) {
			trackingParamsRepository.setSourceFrequency(component, frequency)
		}
    }

    fun onActivityPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) = refreshSourcePermissions()

    fun onStepsPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) = refreshSourcePermissions()

    fun onWifiPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) = refreshSourcePermissions()

    fun onCellPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) = refreshSourcePermissions()

    /** Platform callbacks are capability changes, never new participation intent. */
    private fun refreshSourcePermissions() {
        refreshPermissionState()
        retrySourcePolicyReconciliation()
    }

    fun setTransitionDetectionEnabled(enabled: Boolean) {
		launchSourcePolicyMutation {
            trackingParamsRepository.setTransitionDetectionEnabled(enabled)
		}
    }

    /**
     * Selects which recognized movement may start an automatic tracking session.
     *
     * The persisted values intentionally match [GroupedActivity] ordinals:
     * STILL (0) disables automation, ON_FOOT (1) starts for walking/running, and
     * IN_VEHICLE (2) accepts all known movement. [BackgroundTrackingApi] observes
     * this state, but registration remains contained until the separate control-retention policy
     * availability boundary reports that automatic control is operational.
     */
    fun setAutoTrackingMode(mode: Int, onSaved: () -> Unit = {}) {
        require(mode in AUTO_TRACKING_MODE_DISABLED..AUTO_TRACKING_MODE_ALL_MOVEMENT) {
            "Unsupported automatic tracking mode: $mode"
        }
		val intentGeneration = ++automaticIntentGeneration
		launchSourcePolicyMutation(
			afterCommittedMutation = {
				if (intentGeneration != automaticIntentGeneration) return@launchSourcePolicyMutation
				val published =
					purposeAvailabilityReader.availability.value.automaticControl as?
						AutomaticTrackingOperationalAvailability.Ready
				val current = published != null &&
					try {
						purposeAvailabilityReader.isCurrent(published.identity)
					} catch (cancelled: CancellationException) {
						throw cancelled
					} catch (_: Exception) {
						false
					}
				if (intentGeneration != automaticIntentGeneration) return@launchSourcePolicyMutation
				val effectiveMode = mode.takeIf { current && context.hasActivityPermission }
					?: AUTO_TRACKING_MODE_DISABLED
				activityWatcherController.applyAutoTrackingMode(effectiveMode)
				if (intentGeneration == automaticIntentGeneration) onSaved()
			},
		) {
            trackingParamsRepository.update { copy(autoTrackingMode = mode) }
		}
    }

    @Suppress("UNUSED_PARAMETER")
    fun onAutoTrackingPermissionResult(requestedMode: Int, granted: Boolean) {
        require(requestedMode in AUTO_TRACKING_MODE_DISABLED..AUTO_TRACKING_MODE_ALL_MOVEMENT)
        // The choice was saved before launch. A late grant is capability, never permission to
        // restore the request's old mode after the user has selected Off or another mode.
        refreshPermissionState()
        retrySourcePolicyReconciliation()
    }

    fun setNotificationStyled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setNotificationStyled(enabled)
        }
    }
    fun setMinDistance(distance: Int) {
		launchSourcePolicyMutation(markCustomPreset = true) {
			trackingParamsRepository.setMinDistanceMeters(distance)
		}
    }

    fun setMinTime(time: Int) {
		launchSourcePolicyMutation(markCustomPreset = true) {
			trackingParamsRepository.setMinTimeSeconds(time)
		}
    }

    fun setRequiredAccuracy(accuracy: Int) {
		launchSourcePolicyMutation(markCustomPreset = true) {
			trackingParamsRepository.setRequiredAccuracyMeters(accuracy)
		}
    }

    /** Compatibility action: a preset selects quality and never rewrites participation or limits. */
    fun applyPreset(preset: TrackingPreset) {
        if (preset == TrackingPreset.CUSTOM) return
        adoptBalanceProfile(when (preset) {
            TrackingPreset.POWER_SAVE -> 1
            TrackingPreset.BALANCED -> 3
            TrackingPreset.HIGH_ACCURACY -> 5
            TrackingPreset.CUSTOM -> return
        })
    }

	fun retrySourcePolicyReconciliation() {
		viewModelScope.launch {
			sourcePolicyReconciliationCoordinator.reconcileCurrentPolicyRevision()
		}
	}

    private fun updateUiState(
        params: TrackingParamsState,
        runtimeStatus: TrackingRuntimeStatus,
        telemetry: TrackingCoordinatorMetrics,
        purposeAvailability: CurrentTrackingPurposeAvailability,
    ) {
        val capabilities = context.trackingPermissionCapabilities(permissionHistory)
        permissionHistory = capabilities.recordGrants(permissionHistory)
        val wifiPermissionGranted = capabilities.hasWifiScanPermissions
        val cellPermissionGranted = context.hasCellScanPermission
        val barometerAvailable = context.hasPressureSensor
        val activityPermissionGranted = context.hasActivityPermission
        val stepCounterAvailable = context.hasStepCounterSensor
        val preview = trackingStatusProvider.preview(params, previewEnvironment(capabilities))
        val estimate = preview.batteryEstimate

        _uiState.update {
            it.copy(
                isLoaded = true,
                currentPreset = params.preset,
                currentBatteryImpact = estimate.level.toUiImpact(),
                batteryEstimate = estimate,
                locationEnabled = params.locationEnabled,
                activityEnabled = params.activityEnabled,
                stepsEnabled = params.stepsEnabled,
                wifiEnabled = params.wifiEnabled,
                cellEnabled = params.cellEnabled,
                barometerEnabled = params.barometerEnabled,
				ambientLocationEnabled = params.ambientLocationEnabled,
				locationProfileVersion = params.locationProfileVersion,
				locationBalanceLevel = params.locationBalanceLevel,
				balanceProfileVersion = params.balanceProfileVersion,
				overallBalanceLevel = params.overallBalanceLevel,
				sourceBalanceOverrides = params.sourceBalanceOverrides,
				locationBackgroundMode = params.locationBackgroundMode,
				locationMotionAssistanceEnabled = params.locationMotionAssistanceEnabled,
				ambientStepsEnabled = params.ambientStepsEnabled,
				ambientWifiEnabled = params.ambientWifiEnabled,
				ambientCellEnabled = params.ambientCellEnabled,
				ambientSourceAvailability = purposeAvailability.ambientSources,
                barometerAvailable = barometerAvailable,
                activityPermissionGranted = activityPermissionGranted,
                stepCounterAvailable = stepCounterAvailable,
                wifiPermissionGranted = wifiPermissionGranted,
                cellPermissionGranted = cellPermissionGranted,
                permissionCapabilities = capabilities,
                autoTrackingMode = params.autoTrackingMode,
                autoTrackingEnabled = params.autoTrackingMode > 0,
				automaticTrackingOperational = params.autoTrackingMode > 0 &&
					purposeAvailability.automaticControl.isOperational &&
					activityPermissionGranted,
				automaticTrackingPermissionRequired = params.autoTrackingMode > 0 &&
					purposeAvailability.automaticControl.isOperational &&
					!activityPermissionGranted,
				automaticControlAvailability = purposeAvailability.automaticControl,
                transitionDetectionEnabled = params.transitionDetectionEnabled,
                notificationStyled = params.notificationStyled,
                minDistance = params.minDistanceMeters,
                minTime = params.minTimeSeconds,
                requiredAccuracy = params.requiredAccuracyMeters,
                hasValidSources = params.hasAnyCaptureSource(
                    locationAvailable = capabilities.hasForegroundLocation,
                    activityAvailable = activityPermissionGranted,
                    stepsAvailable = activityPermissionGranted && stepCounterAvailable,
                    wifiAvailable = wifiPermissionGranted,
                    cellAvailable = cellPermissionGranted,
                    barometerAvailable = barometerAvailable,
                ),
                advancedSourceControlsEnabled = params.advancedSourceControlsEnabled,
                sourceCollectionSettings = params.sourceCollectionSettings,
                sourceStatuses = if (runtimeStatus.active) runtimeStatus.sources else preview.sources,
                sourcePlans = if (runtimeStatus.active) runtimeStatus.requestedPlans else preview.requestedPlans,
                sourceFrequencyOptions = preview.frequencyOptions,
                trackingActive = runtimeStatus.active,
				sourcePolicyAvailable = params.sourcePolicyRevision != null,
				sourcePolicyRevision = params.sourcePolicyRevision,
				runtimeSourcePolicyRevision = runtimeStatus.sourcePolicyRevision,
                desiredPlanRevision = runtimeStatus.desiredRevision,
                appliedPlanRevision = runtimeStatus.appliedRevision,
                runtimeFailureCode = runtimeStatus.failureCode,
                runtimeTelemetry = telemetry,
            )
        }
    }

    private suspend fun markCustomPreset() {
        if (_uiState.value.currentPreset != TrackingPreset.CUSTOM) {
            trackingParamsRepository.setPreset(TrackingPreset.CUSTOM)
        }
    }

	private fun launchSourcePolicyMutation(
		markCustomPreset: Boolean = false,
		afterCommittedMutation: suspend () -> Unit = { },
		onAfterCommittedMutationFailure: (Exception) -> Unit = { },
		mutation: suspend () -> Unit,
	) {
		viewModelScope.launch {
			var committed = false
			var postCommitDebt = false
			try {
				mutation()
				committed = true
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: SourcePolicyRevisionReconciliationException) {
				committed = true
				postCommitDebt = true
				publishSourcePolicyReconciliationDebt(failure)
			}
			if (!committed) return@launch
			if (markCustomPreset) {
				try {
					markCustomPreset()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (failure: SourcePolicyRevisionReconciliationException) {
					postCommitDebt = true
					publishSourcePolicyReconciliationDebt(failure)
				}
			}
			if (
				postCommitDebt ||
				sourcePolicyReconciliationCoordinator.reconciliationState.value is
					SourcePolicyRevisionReconciliationState.Debt
			) {
				return@launch
			}
			try {
				afterCommittedMutation()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: Exception) {
				onAfterCommittedMutationFailure(failure)
			}
		}
	}

	private fun publishSourcePolicyReconciliationDebt(
		failure: SourcePolicyRevisionReconciliationException,
	) {
		_uiState.update { state ->
			state.copy(
				sourcePolicyReconciliationDebt =
					SourcePolicyRevisionReconciliationDebt(
						policyRevision = state.sourcePolicyRevision,
						failures = failure.failures,
					),
			)
		}
	}

    private fun previewEnvironment(
        capabilities: TrackingPermissionCapabilities,
    ): TrackingSettingsPreviewEnvironment {
        val packageManager = context.packageManager
        val powerManager = context.getSystemService(PowerManager::class.java)
        val cellFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))
        return TrackingSettingsPreviewEnvironment(
            planEnvironment = SourcePlanEnvironment(
                locationBackend = LocationBackend.FUSED,
                preciseLocationAvailable = capabilities.hasPreciseLocation,
                subscriptionIds = emptySet(),
            ),
            resolutionContext = PlanResolutionContext(
                constraints = mapOf(
                    SourceKind.LOCATION to SourceConstraint(
                        hardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION),
                        permissionGranted = capabilities.hasForegroundLocation,
                    ),
                    SourceKind.ACTIVITY to SourceConstraint(permissionGranted = context.hasActivityPermission),
                    SourceKind.STEPS to SourceConstraint(
                        hardwareAvailable = context.hasStepCounterSensor,
                        permissionGranted = context.hasActivityPermission,
                    ),
                    SourceKind.PRESSURE to SourceConstraint(hardwareAvailable = context.hasPressureSensor),
                    SourceKind.WIFI to SourceConstraint(
                        hardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
                        permissionGranted = capabilities.hasWifiScan,
                    ),
                    SourceKind.CELL to SourceConstraint(
                        hardwareAvailable = cellFeature,
                        permissionGranted = context.hasCellScanPermission,
                    ),
                ),
                powerSaver = powerManager?.isPowerSaveMode == true,
                doze = powerManager?.isDeviceIdleMode == true,
                severeThermalPressure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    powerManager?.currentThermalStatus?.let { status ->
                        status >= PowerManager.THERMAL_STATUS_SEVERE
                    } == true,
            ),
        )
    }

    private companion object {
        const val AUTO_TRACKING_MODE_DISABLED = 0
        const val AUTO_TRACKING_MODE_ALL_MOVEMENT = 2

        fun ImpactLevel.toUiImpact(): BatteryImpact = when (this) {
            ImpactLevel.LOW -> BatteryImpact.LOW
            ImpactLevel.MODERATE -> BatteryImpact.MODERATE
            ImpactLevel.HIGH -> BatteryImpact.HIGH
        }
    }
}
