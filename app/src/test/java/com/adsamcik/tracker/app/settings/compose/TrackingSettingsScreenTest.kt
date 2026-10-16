package com.adsamcik.tracker.app.settings.compose

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import java.io.File
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.tracking.TrackingSettingsContent
import com.adsamcik.tracker.app.settings.tracking.shouldRequestStepsMotionPermission
import androidx.lifecycle.Lifecycle
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationDebt
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationFailure
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimate
import com.adsamcik.tracker.tracker.source.battery.EstimateConfidence
import com.adsamcik.tracker.tracker.source.battery.EstimateTarget
import com.adsamcik.tracker.tracker.source.battery.EvidenceSource
import com.adsamcik.tracker.tracker.source.battery.ImpactAssumption
import com.adsamcik.tracker.tracker.source.battery.ImpactDriver
import com.adsamcik.tracker.tracker.source.battery.ImpactLevel
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.TrackingPurpose
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.TrackingSource
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for the real [TrackingSettingsContent] composable.
 * Uses the extracted content composable with injectable state to verify
 * UI rendering and interactions without requiring Hilt/ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// One real settings composable fixture keeps source choices, repair actions and screen semantics
// comparable.
@Suppress("LargeClass")
class TrackingSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val defaultUiState = TrackingSettingsUiState(
        isLoaded = true,
        currentPreset = TrackingPreset.BALANCED,
        currentBatteryImpact = BatteryImpact.MODERATE,
        locationEnabled = true,
        activityEnabled = true,
        stepsEnabled = true,
        wifiEnabled = true,
        cellEnabled = false,
        autoTrackingMode = 1,
        autoTrackingEnabled = true,
		automaticTrackingOperational = true,
		automaticControlAvailability = readyAutomaticControl(),
        transitionDetectionEnabled = true,
        notificationStyled = true,
        minDistance = 10,
        minTime = 2,
        requiredAccuracy = 50,
        hasValidSources = true,
		sourcePolicyAvailable = true,
		sourcePolicyRevision = 1L,
    )

    private fun scrollTo(text: String) {
        composeTestRule.onNodeWithTag("trackingSettingsList")
            .performScrollToNode(hasText(text, substring = true))
    }

    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        composeTestRule.onNodeWithTag("trackingSettingsList")
            .performScrollToNode(hasTestTag(tag))
        return composeTestRule.onNodeWithTag(tag)
    }

	private fun readyAutomaticControl() = AutomaticTrackingOperationalAvailability.Ready(
		TrackingPurposeLeaseIdentity(
			source = TrackingSource.ACTIVITY,
			purpose = TrackingPurpose.CONTROL,
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = 0L,
			rolloutRevision = 0L,
			executionRevision = 1L,
			ownerCasToken = "settings-screen-test",
		),
	)

	private fun ambientPurposeIdentity(source: AmbientTrackingSource) =
		TrackingPurposeLeaseIdentity(
			sourcePurpose = source.canonicalSource.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = 0L,
			rolloutRevision = 0L,
			executionRevision = 1L,
			ownerCasToken = "${source.name.lowercase()}-settings-test",
		)

    @Test
    fun fiveLevelBalanceIsExplicitAndDoesNotChangeParticipation() {
        var selected: Int? = null
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(defaultUiState, onBalanceSelected = { selected = it }) }
        }
        composeTestRule.onNodeWithTag("trackingSettingsList")
            .performScrollToNode(hasTestTag("trackingBalance"))
        composeTestRule.onNodeWithTag("trackingBalance").performClick()
        (1..5).forEach { composeTestRule.onNodeWithTag("trackingBalance-$it").assertIsDisplayed() }
        composeTestRule.onNodeWithTag("trackingBalance-2").performClick()
        selected shouldBe 2
    }

    @Test
    fun unavailablePressureCanStillBeTurnedOff() {
        var chosen: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(defaultUiState.copy(barometerAvailable = false, barometerEnabled = true),
                    onBarometerEnabledChanged = { chosen = it })
            }
        }
        scrollTo("Barometer")
        composeTestRule.onNodeWithText("Barometer").performClick()
        chosen shouldBe false
    }

    @Test
    fun failedSettingsSaveKeepsSourceChoiceAndProvidesRetry() {
        var retries = 0
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(defaultUiState.copy(settingsSaveFailed = true, cellEnabled = true),
                    onRetrySettingsSave = { retries += 1 })
            }
        }
        composeTestRule.onNodeWithTag("trackingSettingsSaveFailure").assertIsDisplayed()
        composeTestRule.onNodeWithTag("trackingSettingsSaveRetry").performClick()
        retries shouldBe 1
    }

    @Test
    fun savedChoicesWithRuntimeFailureOfferRepairWithoutChangingParticipation() {
        var retries = 0
        var choiceChanges = 0
        val debt = SourcePolicyRevisionReconciliationDebt(1L,
            listOf(SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable))
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(defaultUiState.copy(sourcePolicyReconciliationDebt = debt),
                    onRetrySourceRecovery = { retries += 1 },
                    onLocationEnabledChanged = { choiceChanges += 1 })
            }
        }
        composeTestRule.onNodeWithTag("trackingSourceRecoveryFailure").assertIsDisplayed()
        composeTestRule.onNodeWithTag("trackingSourceRecoveryRetry").performClick()
        retries shouldBe 1
        choiceChanges shouldBe 0
    }

    @Test
    fun unavailableSettingsOfferTheExistingRepairAction() {
        var retries = 0
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(defaultUiState.copy(sourcePolicyAvailable = false),
                    onRetrySourceRecovery = { retries += 1 })
            }
        }
        composeTestRule.onNodeWithTag("trackingSourceRecoveryRetry").performClick()
        retries shouldBe 1
    }

    @Test
    fun offSourceOverrideDoesNotEnableLiveOrBackgroundCollection() {
        var selected: Pair<TrackingSourceComponent, Int?>? = null
        var participationChanges = 0
        val state = defaultUiState.copy(balanceProfileVersion = 1, overallBalanceLevel = 3,
            locationProfileVersion = 1, locationBalanceLevel = 3, wifiEnabled = false,
            ambientWifiEnabled = false, advancedSourceControlsEnabled = true)
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(state, onSourceBalanceSelected = { source, level -> selected = source to level },
                onWifiEnabledChanged = { participationChanges++ }, onAmbientWifiEnabledChanged = { participationChanges++ }) }
        }
        composeTestRule.onNodeWithTag("trackingSettingsList")
            .performScrollToNode(hasTestTag("sourceBalance-WIFI"))
        composeTestRule.onNodeWithTag("sourceBalance-WIFI").performClick()
        composeTestRule.onNodeWithTag("sourceBalance-WIFI-inherit").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sourceBalance-WIFI-5").performClick()
        selected shouldBe (TrackingSourceComponent.WIFI to 5)
        participationChanges shouldBe 0
    }

	@Test
	fun unavailablePolicyShowsAnHonestNonEditableState() {
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						sourcePolicyAvailable = false,
						sourcePolicyRevision = null,
					),
				)
			}
		}

		composeTestRule.onNodeWithTag("trackingSettingsUnavailable").assertIsDisplayed()
		composeTestRule.onNodeWithText("Tracking settings unavailable").assertIsDisplayed()
		composeTestRule.onNodeWithTag("trackingSettingsList").assertDoesNotExist()
	}

    @Test
    fun trackingNoticeIsHiddenWhileIdle() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        composeTestRule.onNodeWithText("Changes update current tracking", substring = true).assertDoesNotExist()
    }

    @Test
    fun trackingNoticeAppearsDuringAnActiveTrip() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(uiState = defaultUiState.copy(trackingActive = true))
            }
        }
        composeTestRule.onNodeWithText("Changes update current tracking", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysToggleSettings() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        scrollTo("Battery-efficient motion detection")
        composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true).assertIsDisplayed()
        scrollTo("Colored notifications")
        composeTestRule.onNodeWithText("Colored notifications", substring = true).assertIsDisplayed()
    }

    @Test
    fun automaticTrackingModeIsVisibleAndExplainsCurrentBehavior() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }

        scrollTo("Automatic tracking")
        composeTestRule.onNodeWithTag("automaticTrackingMode").assertIsDisplayed()
        scrollTo("Automatically track walking and running.")
        composeTestRule.onNodeWithText("Automatically track walking and running.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun automaticTrackingModeSelectorChangesModeDirectly() {
        var selectedMode: Int? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState,
                    onAutoTrackingModeChanged = { selectedMode = it },
                )
            }
        }

        scrollTo("Automatically track all movement")
        composeTestRule.onNodeWithTag("automaticTrackingModeOption-2").performClick()
        composeTestRule.waitForIdle()

        selectedMode shouldBe 2
    }

	@Test
	@GraphicsMode(GraphicsMode.Mode.NATIVE)
	fun automaticSetupFailureOffersRetryWithoutBlamingGrantedPermissions() {
		var retries = 0
		lateinit var renderedView: View
		composeTestRule.setContent {
			renderedView = LocalView.current
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						activityPermissionGranted = true,
						automaticTrackingOperational = false,
						automaticControlAvailability =
							AutomaticTrackingOperationalAvailability.Unavailable(
								AutomaticTrackingUnavailableReason
									.CONTROL_RETENTION_POLICY_UNAVAILABLE,
							),
					),
					onRetrySourceRecovery = { retries++ },
				)
			}
		}

		scrollToTag("automaticTrackingUnavailable")
		composeTestRule.onNodeWithTag("automaticTrackingUnavailable").assertIsDisplayed()
		composeTestRule.onNodeWithText(
			"CONTROL_RETENTION_POLICY_UNAVAILABLE",
			substring = true,
		).assertDoesNotExist()
		composeTestRule.onNodeWithText("Tracker couldn’t prepare automatic tracking. Try again.")
			.assertIsDisplayed()
		composeTestRule.onNodeWithTag("automaticAllowAccess").assertDoesNotExist()
		composeTestRule.onNodeWithTag("automaticTrackingRetry").performClick()
		retries shouldBe 1
		composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true)
			.assertDoesNotExist()
		if (System.getenv("TRACKER_CAPTURE_SETTINGS") == "true") {
			val bounds = composeTestRule.onNodeWithTag("automaticTrackingUnavailable")
				.fetchSemanticsNode().boundsInRoot
			val image = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
			composeTestRule.runOnIdle {
				val canvas = Canvas(image)
				canvas.translate(-bounds.left, -bounds.top)
				renderedView.draw(canvas)
			}
			val output = File("build/reports/tracking-settings/automatic-tracking-recovery.png")
			checkNotNull(output.parentFile).mkdirs()
			output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
		}
	}

	@Test
	fun unfinishedRegistrationOffersRetryWithoutClaimingPermissionIsMissing() {
		var retries = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(uiState = defaultUiState.copy(
					activityPermissionGranted = true, automaticTrackingOperational = false,
					automaticControlAvailability = AutomaticTrackingOperationalAvailability.Unavailable(
						AutomaticTrackingUnavailableReason.REGISTRATION_PENDING)),
					onRetrySourceRecovery = { retries++ })
			}
		}
		scrollTo("Motion detection isn’t ready. Try again.")
		composeTestRule.onNodeWithTag("automaticAllowAccess").assertDoesNotExist()
		composeTestRule.onNodeWithTag("automaticTrackingRetry").performClick()
		retries shouldBe 1
	}

	@Test
	fun disabledTransitionsOfferAnExplicitRepairWithoutChangingSavedMode() {
		var repaired: Boolean? = null
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(uiState = defaultUiState.copy(
					transitionDetectionEnabled = false, automaticTrackingOperational = false,
					automaticControlAvailability = AutomaticTrackingOperationalAvailability.Unavailable(
						AutomaticTrackingUnavailableReason.TRANSITION_DETECTION_DISABLED)),
					onTransitionDetectionChanged = { repaired = it })
			}
		}
		scrollTo("Enable motion detection")
		composeTestRule.onNodeWithTag("automaticEnableTransitions").performClick()
		repaired shouldBe true
	}


    @Test
    fun transitionDetectionIsHiddenWhenAutomaticTrackingIsDisabled() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        autoTrackingMode = 0,
                        autoTrackingEnabled = false,
						automaticTrackingOperational = false,
                    ),
                )
            }
        }

        scrollTo("Automatic tracking")
        composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText("Start and stop tracking manually.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun transitionDetectionToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState,
                    onTransitionDetectionChanged = { newValue = it },
                )
            }
        }
        scrollTo("Battery-efficient motion detection")
        composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true).performClick()
        newValue shouldBe false // Was true, toggling makes false
    }

    @Test
    fun notificationToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState,
                    onNotificationStyledChanged = { newValue = it },
                )
            }
        }
        scrollTo("Colored notifications")
        composeTestRule.onNodeWithText("Colored notifications", substring = true).performClick()
        newValue shouldBe false // Was true, toggling makes false
    }

    @Test
    fun validationWarningShownWhenNoValidSources() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(hasValidSources = false),
                )
            }
        }
        scrollTo("Enable at least one tracking")
        composeTestRule.onNodeWithText("Enable at least one tracking", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun validationWarningHiddenWhenSourcesValid() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(hasValidSources = true),
                )
            }
        }
        composeTestRule.onNodeWithText("Enable at least one tracking", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun settingsOrganizedIntoVisibleSections() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        // Everyday source controls are visible first; detailed frequency controls are
        // an explicitly labelled drill-down rather than a switch with unclear effects.
        scrollTo("Data collected")
        composeTestRule.onNodeWithText("Data collected", substring = true).assertIsDisplayed()
        scrollTo("Advanced controls")
        composeTestRule.onNodeWithText("Advanced controls", substring = true).assertIsDisplayed()
    }

    @Test
    fun dataSourceTogglesVisibleWithoutExpanding() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        scrollTo("Location")
        composeTestRule.onNodeWithText("Location").assertIsDisplayed()
        scrollTo("Activity")
        composeTestRule.onNodeWithText("Activity").assertIsDisplayed()
        scrollTo("Barometer")
        composeTestRule.onNodeWithText("Barometer").assertIsDisplayed()
    }

	@Test
	fun nonStepsAmbientSourceSettingsAreIndependentDefaultOffSwitchesWithGapAndRetentionCopy() {
		composeTestRule.setContent {
			AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
		}

		mapOf(
			"location" to "All-day Location",
			"wifi" to "Wi-Fi outside trips",
			"cell" to "Cell outside trips",
		).forEach { (source, title) ->
			scrollTo(title)
			composeTestRule.onNodeWithTag("ambientSource-$source")
				.assertIsDisplayed()
				.assertIsOff()
		}
		scrollTo("does not promise a provider")
		composeTestRule.onNodeWithText("does not promise a provider", substring = true)
			.assertIsDisplayed()
		scrollTo("does not claim always-on collection")
		composeTestRule.onNodeWithTag("ambientRetentionExplanation").assertIsDisplayed()
		composeTestRule.onAllNodesWithText(
			"Current collection status is waiting for source-owner reconciliation",
			substring = true,
		).onFirst().assertExists()
		composeTestRule.onNodeWithText(
			"Collection stays off because the product retention policy",
			substring = true,
		).assertDoesNotExist()
	}

	@Test
	fun stepsChoiceShowsAllDayAvailabilityWithoutASecondToggle() {
		var requested: Boolean? = null
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(stepsEnabled = true),
					onStepsEnabledChanged = { requested = it },
				)
			}
		}

		scrollTo("Step count")
		composeTestRule.onNodeWithText("Steps outside trips").assertDoesNotExist()
		composeTestRule.onNodeWithText("Step count").performClick()
		requested shouldBe false
		composeTestRule.onNodeWithText("Checking step collection.", substring = true)
			.assertIsDisplayed()
	}

	@Test
	fun actualRuntimeRetentionFailureIsDistinctFromPendingDefault() {
		val availability = defaultUiState.ambientSourceAvailability +
			(
				AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.UNAVAILABLE,
					reason = AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
				)
			)
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						stepsEnabled = true,
						ambientSourceAvailability = availability,
					),
				)
			}
		}

		scrollTo("retention policy is not yet available")
		composeTestRule.onNodeWithText(
			"retention policy is not yet available",
			substring = true,
		).assertIsDisplayed()
	}

	@Test
	fun turnOffTrackingExplainsThatChoicesAndHistoryRemain() {
		val availability = defaultUiState.ambientSourceAvailability +
			(
				AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.UNAVAILABLE,
					reason = AmbientSourceUnavailableReason.USER_TURNED_OFF,
				)
			)
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						stepsEnabled = true,
						ambientSourceAvailability = availability,
					),
				)
			}
		}

		scrollTo("Tracking is off. Your choices and saved data are kept.")
		composeTestRule.onNodeWithText(
			"Tracking is off. Your choices and saved data are kept.",
			substring = true,
		).assertIsDisplayed()
	}

	@Test
	fun turnOffTrackingActionIsAvailableEvenWhenSettingsCannotLoad() {
		var clicks = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(sourcePolicyAvailable = false),
					onTurnOffTracking = { clicks++ },
				)
			}
		}
		composeTestRule.onNodeWithTag("turnOffTracking").performScrollTo().performClick()
		clicks shouldBe 1
	}

	@Test
	fun turnOffTrackingShowsAStatusInsteadOfOfferingASecondStop() {
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(defaultUiState.copy(trackingTurnedOff = true))
			}
		}
		scrollTo("Tracking is off. Your choices and saved data are kept.")
		composeTestRule.onNodeWithTag("turnOffTracking").assertDoesNotExist()
	}

	@Test
	fun missingHealthConnectGrantShowsRepairOnTheSingleStepsChoice() {
		var repairRequested = false
		val availability = defaultUiState.ambientSourceAvailability +
			(
				AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
					mechanism = AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
					reason =
						AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED,
				)
			)
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						stepsEnabled = true,
						stepCounterAvailable = true,
						activityPermissionGranted = false,
						ambientSourceAvailability = availability,
					),
					onStepsProviderPermission = { repairRequested = true },
				)
			}
		}

		scrollTo("Allow Steps access in Health Connect")
		composeTestRule.onNodeWithText(
			"to fill gaps when Tracker is closed",
			substring = true,
		).assertIsDisplayed()
		scrollToTag("stepsHealthConnectRepair").performClick()
		repairRequested shouldBe true
		composeTestRule.onNodeWithTag("stepsMotionRepair").assertDoesNotExist()
		defaultUiState.copy(ambientSourceAvailability = availability).shouldRequestStepsMotionPermission(
			34, Lifecycle.State.RESUMED, motionPermissionGranted = false,
		) shouldBe false
	}

	@Test
	fun localRecordingReadinessAppearsOnTheSingleStepsChoice() {
		val availability = defaultUiState.ambientSourceAvailability +
			(
				AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.READY,
					mechanism = AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
					operationalIdentity = ambientPurposeIdentity(AmbientTrackingSource.STEPS),
				)
			)
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						stepsEnabled = true,
						ambientSourceAvailability = availability,
					),
				)
			}
		}

		scrollTo("Step recording is ready")
		composeTestRule.onNodeWithText(
			"Some periods may still have gaps",
			substring = true,
		).assertIsDisplayed()
	}

	@Test
	fun localRecordingMotionRepairWorksWithoutDirectSensorInBasicSettings() {
		var repairs = 0
		var choiceChanges = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = localRecordingMotionPermissionState(),
					onStepsProviderPermission = { repairs++ },
					onStepsEnabledChanged = { choiceChanges++ },
					onActivityEnabledChanged = { choiceChanges++ },
					onAutoTrackingModeChanged = { choiceChanges++ },
				)
			}
		}

		scrollToTag("stepsMotionRepair")
			.assertIsDisplayed()
			.assertContentDescriptionEquals("Allow motion access for Steps")
			.performClick()
		composeTestRule.onNodeWithText("Step count").assertIsOn()
		repairs shouldBe 1
		choiceChanges shouldBe 0
		composeTestRule.onNodeWithTag("stepsHealthConnectRepair").assertDoesNotExist()
	}

	@Test
	fun localRecordingMotionRepairWorksWithoutDirectSensorInAdvancedSettings() {
		var repairs = 0
		var frequencyChanges = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = localRecordingMotionPermissionState().copy(
						advancedSourceControlsEnabled = true,
					),
					onStepsProviderPermission = { repairs++ },
					onSourceFrequencyChanged = { _, _ -> frequencyChanges++ },
				)
			}
		}

		scrollToTag("stepsMotionRepair")
			.assertContentDescriptionEquals("Allow motion access for Steps")
			.performClick()
		repairs shouldBe 1
		frequencyChanges shouldBe 0
		composeTestRule.onNodeWithTag("stepsHealthConnectRepair").assertDoesNotExist()
	}

	@Test
	fun deselectingStepsRemovesTheMotionRepairWithoutChangingOtherChoices() {
		val current = mutableStateOf(localRecordingMotionPermissionState())
		var repairs = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = current.value,
					onStepsProviderPermission = { repairs++ },
				)
			}
		}
		scrollToTag("stepsMotionRepair").assertIsDisplayed()
		composeTestRule.runOnIdle {
			current.value = current.value.copy(stepsEnabled = false, ambientStepsEnabled = false)
		}

		composeTestRule.onNodeWithTag("stepsMotionRepair").assertDoesNotExist()
		current.value.shouldRequestStepsMotionPermission(
			34, Lifecycle.State.RESUMED, motionPermissionGranted = false,
		) shouldBe false
		current.value.activityEnabled shouldBe false
		current.value.autoTrackingMode shouldBe 0
		repairs shouldBe 0
	}

	@Test
	fun changedStepsProviderUsesTheCurrentHealthConnectRepairInsteadOfMotion() {
		val current = mutableStateOf(localRecordingMotionPermissionState())
		var repairs = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(current.value, onStepsProviderPermission = { repairs++ })
			}
		}
		scrollToTag("stepsMotionRepair").assertIsDisplayed()
		composeTestRule.runOnIdle {
			current.value = current.value.copy(
				ambientSourceAvailability = current.value.ambientSourceAvailability +
					(AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
						source = AmbientTrackingSource.STEPS,
						state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
						mechanism = AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
						reason = AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED,
					)),
			)
		}

		composeTestRule.onNodeWithTag("stepsMotionRepair").assertDoesNotExist()
		current.value.shouldRequestStepsMotionPermission(
			34, Lifecycle.State.RESUMED, motionPermissionGranted = false,
		) shouldBe false
		scrollToTag("stepsHealthConnectRepair").performClick()
		repairs shouldBe 1
	}

	@Test
	fun motionRepairKeepsTurnOffTrackingAndSavedChoicesIntact() {
		val state = localRecordingMotionPermissionState().copy(trackingTurnedOff = true)
		var repairs = 0
		var settingChanges = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = state,
					onStepsProviderPermission = { repairs++ },
					onStepsEnabledChanged = { settingChanges++ },
					onActivityEnabledChanged = { settingChanges++ },
					onAutoTrackingModeChanged = { settingChanges++ },
					onTurnOffTracking = { settingChanges++ },
				)
			}
		}
		scrollToTag("stepsMotionRepair").performClick()

		repairs shouldBe 1
		settingChanges shouldBe 0
		state.trackingTurnedOff shouldBe true
		state.stepsEnabled shouldBe true
	}

	@Test
	fun localRecordingRepairFailureUsesExistingProviderCopyAndKeepsRetryAvailable() {
		var repairs = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = localRecordingMotionPermissionState().copy(stepsProviderRepairFailed = true),
					onStepsProviderPermission = { repairs++ },
				)
			}
		}
		scrollToTag("stepsMotionRepairError").assertIsDisplayed()
		composeTestRule.onNodeWithText("Could not check the step provider. Try again later.")
			.assertIsDisplayed()
		composeTestRule.onNodeWithTag("stepsHealthConnectRepairError").assertDoesNotExist()
		scrollToTag("stepsMotionRepair").performClick()
		repairs shouldBe 1
	}

	@Test
	fun enablingStepsCanRequestMotionForEitherDirectSensorOrLocalRecording() {
		val providerOnly = localRecordingMotionPermissionState()
		providerOnly.shouldRequestStepsMotionPermission(
			34, Lifecycle.State.RESUMED, motionPermissionGranted = false,
		) shouldBe true
		defaultUiState.copy(stepCounterAvailable = true, activityPermissionGranted = false)
			.shouldRequestStepsMotionPermission(
				34, Lifecycle.State.RESUMED, motionPermissionGranted = false,
			) shouldBe true
		providerOnly.activityEnabled shouldBe false
		providerOnly.autoTrackingMode shouldBe 0
	}

	@Test
	fun stepsMotionPermissionRequiresCurrentSelectionAndroidPermissionAndResumedScreen() {
		val state = localRecordingMotionPermissionState()
		state.copy(stepsEnabled = false).shouldRequestStepsMotionPermission(
			34, Lifecycle.State.RESUMED, motionPermissionGranted = false,
		) shouldBe false
		state.shouldRequestStepsMotionPermission(
			Build.VERSION_CODES.P, Lifecycle.State.RESUMED, motionPermissionGranted = false,
		) shouldBe false
		state.shouldRequestStepsMotionPermission(
			Build.VERSION_CODES.Q, Lifecycle.State.RESUMED, motionPermissionGranted = false,
		) shouldBe true
		listOf(Lifecycle.State.DESTROYED, Lifecycle.State.INITIALIZED,
			Lifecycle.State.CREATED, Lifecycle.State.STARTED).forEach { lifecycle ->
			state.shouldRequestStepsMotionPermission(
				34, lifecycle, motionPermissionGranted = false,
			) shouldBe false
		}
		state.shouldRequestStepsMotionPermission(
			34, Lifecycle.State.RESUMED, motionPermissionGranted = true,
		) shouldBe false
	}

	@Test
	fun currentMotionGrantOverridesBothStaleSettingsGrantStates() {
		localRecordingMotionPermissionState().copy(activityPermissionGranted = true)
			.shouldRequestStepsMotionPermission(
				34, Lifecycle.State.RESUMED, motionPermissionGranted = false,
			) shouldBe true
		localRecordingMotionPermissionState().copy(activityPermissionGranted = false)
			.shouldRequestStepsMotionPermission(
				34, Lifecycle.State.RESUMED, motionPermissionGranted = true,
			) shouldBe false
	}

	@Test
	fun unavailableProviderWithoutDirectSensorDoesNotRequestMotion() {
		val state = localRecordingMotionPermissionState()
		listOf(AmbientSourceUnavailableReason.LOCAL_RECORDING_UNAVAILABLE,
			AmbientSourceUnavailableReason.HEALTH_CONNECT_UNAVAILABLE,
			AmbientSourceUnavailableReason.RECONCILIATION_PENDING).forEach { reason ->
			state.copy(ambientSourceAvailability = state.ambientSourceAvailability +
				(AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = if (reason == AmbientSourceUnavailableReason.RECONCILIATION_PENDING) {
						AmbientSourceOperationalState.WAITING
					} else AmbientSourceOperationalState.UNAVAILABLE,
					reason = reason,
				)))
				.shouldRequestStepsMotionPermission(
					34, Lifecycle.State.RESUMED, motionPermissionGranted = false,
				) shouldBe false
		}
	}

	private fun localRecordingMotionPermissionState() = defaultUiState.copy(
		stepsEnabled = true,
		activityEnabled = false,
		autoTrackingMode = 0,
		autoTrackingEnabled = false,
		activityPermissionGranted = false,
		stepCounterAvailable = false,
		ambientSourceAvailability = defaultUiState.ambientSourceAvailability +
			(AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.STEPS,
				state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
				mechanism = AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
				reason = AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED,
			)),
	)

	@Test
	fun HealthConnectForegroundReadyStateExplainsOptionalBackgroundGaps() {
		var repairRequested = false
		val availability = defaultUiState.ambientSourceAvailability +
			(
				AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.DEGRADED,
					mechanism = AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
					reason =
						AmbientSourceUnavailableReason
							.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL,
					operationalIdentity = ambientPurposeIdentity(AmbientTrackingSource.STEPS),
				)
			)
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						stepsEnabled = true,
						activityPermissionGranted = true,
						ambientSourceAvailability = availability,
					),
					onStepsProviderPermission = { repairRequested = true },
				)
			}
		}

		scrollTo("Background access may reduce gaps")
		composeTestRule.onNodeWithText("Step count").assertExists()
		composeTestRule.onNodeWithText(
			"Background access may reduce gaps",
			substring = true,
		).assertIsDisplayed()
		scrollTo("Allow background step updates")
		scrollToTag("stepsHealthConnectRepair").performClick()
		repairRequested shouldBe true
	}

	@Test
	fun unsupportedHealthConnectDoesNotClaimAmbientStepsAreOperational() {
		val availability = defaultUiState.ambientSourceAvailability +
			(
				AmbientTrackingSource.STEPS to AmbientSourceOperationalAvailability(
					source = AmbientTrackingSource.STEPS,
					state = AmbientSourceOperationalState.UNAVAILABLE,
					reason = AmbientSourceUnavailableReason.HEALTH_CONNECT_UNAVAILABLE,
				)
			)
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						stepsEnabled = true,
						ambientSourceAvailability = availability,
					),
				)
			}
		}

		scrollTo("No supported all-day step provider is available")
		composeTestRule.onNodeWithText("Step count").assertExists()
		composeTestRule.onNodeWithText(
			"No supported all-day step provider is available",
			substring = true,
		).assertIsDisplayed()
		composeTestRule.onNodeWithTag("stepsHealthConnectRepair").assertDoesNotExist()
	}

    @Test
    fun effectiveStatusExplainsThatIdlePreviewIsNotAnAppliedPlan() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }

        scrollTo("Technical status")
        composeTestRule.onNodeWithTag("technicalStatusToggle").performClick()
        scrollTo("Requested and effective status")
        composeTestRule.onNodeWithTag("effectiveTrackingStatus").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tracking is not active", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun advancedSourceControlPublishesSemanticFrequencyChoice() {
        var selected: Pair<TrackingSourceComponent, SourceCollectionFrequency>? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        advancedSourceControlsEnabled = true,
                        sourceCollectionSettings = SourceCollectionSettings(),
                    ),
                    onSourceFrequencyChanged = { source, frequency -> selected = source to frequency },
                )
            }
        }

        scrollTo("Location")
        composeTestRule.onNodeWithTag("sourceFrequency-Location").performClick()
        composeTestRule.onNodeWithText("Responsive").performClick()
        selected shouldBe (TrackingSourceComponent.LOCATION to SourceCollectionFrequency.RESPONSIVE)
    }

    @Test
    fun advancedSourcesShowTheirActualRequestedCadence() {
        val responsive = SourceCollectionSettings(
            location = SourceCollectionFrequency.RESPONSIVE,
            activity = SourceCollectionFrequency.RESPONSIVE,
            steps = SourceCollectionFrequency.RESPONSIVE,
            pressure = SourceCollectionFrequency.RESPONSIVE,
            wifi = SourceCollectionFrequency.RESPONSIVE,
            cell = SourceCollectionFrequency.RESPONSIVE,
        )
        val plans = SemanticAcquisitionPlanFactory().create(
            settings = TrackingParamsState(
                locationEnabled = true,
                activityEnabled = true,
                stepsEnabled = true,
                barometerEnabled = true,
                wifiEnabled = true,
                cellEnabled = true,
                sourceCollectionSettings = responsive,
            ),
            revision = 0L,
            createdAtMs = 0L,
            environment = SourcePlanEnvironment(LocationBackend.FUSED, true, emptySet()),
        ).plans
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        advancedSourceControlsEnabled = true,
                        sourceCollectionSettings = responsive,
                        sourcePlans = plans,
                    ),
                )
            }
        }

        listOf(
            "1 sec updates",
            "Continuous",
            "≤ 5 sec batching",
            "20 samples/sec",
            "Active scan",
            "Network changes",
        ).forEach { cadence ->
            scrollTo(cadence)
            composeTestRule.onNodeWithText(cadence, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun batteryCardShowsConfidenceAndDoesNotInventHoursOrPercentages() {
        val estimate = BatteryImpactEstimate(
            level = ImpactLevel.MODERATE,
            estimatedPercentPerHour = null,
            estimateTarget = EstimateTarget.QUALITATIVE_RELATIVE_TRACKER_IMPACT,
            candidatePlanId = "candidate",
            comparisonBaselineId = "balanced",
            evidenceSource = EvidenceSource.GENERIC_PRIOR,
            sampleCount = 0,
            observationDurationMs = 0,
            confidence = EstimateConfidence.LOW,
            uncertainty = null,
            dominantDrivers = listOf(ImpactDriver.LOCATION),
            assumptions = listOf(ImpactAssumption("generic_device_prior")),
            calibrationVersion = 0,
        )
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(uiState = defaultUiState.copy(batteryEstimate = estimate))
            }
        }

        composeTestRule.onNodeWithTag("batteryEstimateCard").assertDoesNotExist()
        scrollTo("Technical status")
        composeTestRule.onNodeWithTag("technicalStatusToggle").performClick()
        scrollTo("Estimated current battery use")
        composeTestRule.onNodeWithTag("batteryEstimateCard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confidence: Low").assertDoesNotExist()
        composeTestRule.onNodeWithText("How this estimate works").performClick()
        scrollTo("Confidence: Low")
        composeTestRule.onNodeWithText("Confidence: Low").assertIsDisplayed()
        scrollTo("No trustworthy percentage")
        composeTestRule.onNodeWithText("No trustworthy percentage", substring = true).assertIsDisplayed()
    }

    @Test
    fun activeStatusShowsPayloadFreeRuntimeTelemetry() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        trackingActive = true,
                        desiredPlanRevision = 8L,
                        appliedPlanRevision = 8L,
							runtimeTelemetry = TrackingCoordinatorMetrics.ZERO.copy(
								projectedEventCount = 9L,
								planRevisionCount = 2L,
								sourceTimerWakeupCount = 3L,
								sourceTimerRequestCount = 5L,
                        ),
                    ),
                )
            }
        }

        scrollTo("Technical status")
        composeTestRule.onNodeWithTag("technicalStatusToggle").performClick()
        scrollTo("Local runtime telemetry")
		composeTestRule.onNodeWithText("Projected events: 9", substring = true)
			.performScrollTo()
			.assertIsDisplayed()
		composeTestRule.onNodeWithText("source timer wakeups: 3", substring = true)
			.assertIsDisplayed()
    }

    @Test
    fun locationFiltersHiddenWhenLocationSourceIsDisabled() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        advancedSourceControlsEnabled = true,
                        locationEnabled = false,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Location filters", substring = true).assertDoesNotExist()
    }

    @Test
    fun legacyLocationFiltersUseTheAppLocaleForNumbersAndUnits() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = application.createConfigurationContext(
            Configuration(application.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag("ar-SA")))
            },
        )
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                AppTheme {
                    TrackingSettingsContent(
                        uiState = defaultUiState.copy(
                            advancedSourceControlsEnabled = true,
                            balanceProfileVersion = null,
                            locationProfileVersion = null,
                        ),
                    )
                }
            }
        }
        val distance = context.getString(
            com.adsamcik.tracker.shared.preferences.R.string.meter_abbr, "١٠",
        )
        val seconds = context.getString(com.adsamcik.tracker.shared.base.R.string.second_short, 2)
        scrollTo(distance)
        composeTestRule.onNodeWithText(distance).assertIsDisplayed()
        scrollTo(seconds)
        composeTestRule.onNodeWithText(seconds).assertIsDisplayed()
    }

    @Test
    fun backgroundRadioNotificationsHaveIndependentAccessibleChoices() {
		val context: Context = ApplicationProvider.getApplicationContext()
		val wifiName = context.getString(com.adsamcik.tracker.tracker.R.string.notification_background_source_wifi)
		val cellName = context.getString(com.adsamcik.tracker.tracker.R.string.notification_background_source_cell)
        var wifi: Boolean? = null
        var cell: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(uiState = defaultUiState.copy(ambientWifiEnabled = true, ambientCellEnabled = true,
                    backgroundWifiNotificationEnabled = false, backgroundCellNotificationEnabled = true),
                    onWifiNotificationChanged = { wifi = it }, onCellNotificationChanged = { cell = it })
            }
        }
        composeTestRule.onNodeWithTag("trackingSettingsList").performScrollToNode(hasTestTag("backgroundNotification-wifi"))
        composeTestRule.onNodeWithTag("backgroundNotification-wifi")
			.assertContentDescriptionEquals(wifiName).assertIsOff().performClick()
        wifi shouldBe true
        cell shouldBe null
        composeTestRule.onNodeWithTag("trackingSettingsList").performScrollToNode(hasTestTag("backgroundNotification-cell"))
        composeTestRule.onNodeWithTag("backgroundNotification-cell")
			.assertContentDescriptionEquals(cellName).performClick()
        cell shouldBe false
    }

    @Test
    fun disabledBackgroundRadioHidesItsDormantNotificationChoice() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState.copy(ambientWifiEnabled = false,
                ambientCellEnabled = false, backgroundWifiNotificationEnabled = true, backgroundCellNotificationEnabled = true)) }
        }
        scrollTo("Outside trips (optional)")
        composeTestRule.onNodeWithTag("backgroundNotification-wifi").assertDoesNotExist()
        composeTestRule.onNodeWithTag("backgroundNotification-cell").assertDoesNotExist()
    }

    @Test
    fun notLoadedShowsNothing() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(isLoaded = false),
                )
            }
        }
        composeTestRule.onNodeWithText("Balanced", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true)
            .assertDoesNotExist()
    }

	@Test
	fun chosenSourcesHaveIndependentPermissionRepairsInSimpleSettings() {
		assertSourcePermissionRepairs(advanced = false)
	}

	@Test
	fun chosenSourcesHaveIndependentPermissionRepairsInAdvancedSettings() {
		assertSourcePermissionRepairs(advanced = true)
	}

	private fun assertSourcePermissionRepairs(advanced: Boolean) {
		val repairs = mutableListOf<TrackingSourceComponent>()
		var changes = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(defaultUiState.copy(
					advancedSourceControlsEnabled = advanced, balanceProfileVersion = null,
					locationEnabled = true, activityEnabled = true, wifiEnabled = true, cellEnabled = true,
					activityPermissionGranted = false, wifiPermissionGranted = false, cellPermissionGranted = false,
					trackingTurnedOff = true,
				), onSourcePermission = { repairs += it },
					onLocationEnabledChanged = { changes++ }, onActivityEnabledChanged = { changes++ },
					onWifiEnabledChanged = { changes++ }, onCellEnabledChanged = { changes++ },
					onTurnOffTracking = { changes++ })
			}
		}
		val sources = listOf(TrackingSourceComponent.LOCATION, TrackingSourceComponent.ACTIVITY,
			TrackingSourceComponent.WIFI, TrackingSourceComponent.CELL)
		sources.forEach { source ->
			val tag = "sourcePermission-${source.name.lowercase()}"
			composeTestRule.onNodeWithTag("trackingSettingsList").performScrollToNode(hasTestTag(tag))
			composeTestRule.onNodeWithTag(tag).performClick()
		}
		repairs shouldBe sources
		changes shouldBe 0
	}

	@Test
	fun automaticPermissionRepairDoesNotEnableActivityRecording() {
		var repairs = 0
		var changes = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(defaultUiState.copy(activityEnabled = false,
					automaticTrackingOperational = false, automaticTrackingPermissionRequired = true,
					activityPermissionGranted = false),
					onAutomaticPermission = { repairs++ }, onActivityEnabledChanged = { changes++ },
					onAutoTrackingModeChanged = { changes++ })
			}
		}
		scrollToTag("automaticAllowAccess").performClick()
		repairs shouldBe 1
		changes shouldBe 0
	}

	@Test
	fun directStepsMotionRepairStaysAvailableWhileTrackingIsOff() {
		var repairs = 0
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(defaultUiState.copy(stepsEnabled = true,
					stepCounterAvailable = true, activityPermissionGranted = false, trackingTurnedOff = true),
					onStepsProviderPermission = { repairs++ })
			}
		}
		scrollToTag("stepsMotionRepair").performClick()
		repairs shouldBe 1
	}

	@Test
	fun independentlySelectedBackgroundRadiosHavePermissionRepair() {
		val repairs = mutableListOf<TrackingSourceComponent>()
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(defaultUiState.copy(wifiEnabled = false, cellEnabled = false,
					ambientWifiEnabled = true, ambientCellEnabled = true,
					wifiPermissionGranted = false, cellPermissionGranted = false),
					onSourcePermission = { repairs += it })
			}
		}
		listOf("wifi", "cell").forEach { source ->
			scrollToTag("ambientSourcePermission-$source").performClick()
		}
		repairs shouldBe listOf(TrackingSourceComponent.WIFI, TrackingSourceComponent.CELL)
	}
}
