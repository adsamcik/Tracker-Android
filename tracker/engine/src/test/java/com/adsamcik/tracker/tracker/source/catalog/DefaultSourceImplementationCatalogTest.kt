package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.TrackingStartFailureDisposition
import com.adsamcik.tracker.tracker.api.TrackingDecisionContainmentReason
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsCapability
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportAccess
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsPermission
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProvider
import com.adsamcik.tracker.tracker.source.ambient.steps.HealthConnectAmbientStepsAvailability
import com.adsamcik.tracker.tracker.source.ambient.steps.LocalRecordingAmbientStepsAvailability
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.coordinator.toStartPrerequisiteFailure
import com.adsamcik.tracker.tracker.source.coordinator.validateCatalogStartPrerequisites
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.source.runtime.ClaimedSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.LocationDeviceState
import com.adsamcik.tracker.tracker.source.runtime.LocationDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.LocationPrerequisiteEvaluator
import com.adsamcik.tracker.tracker.source.runtime.OwnedSourceShutdown
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeClaim
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultSourceImplementationCatalogTest {
	@Test
	fun `catalog covers exactly six sources and all eighteen source purpose pairs`() {
		val fixture = fixture()

		fixture.catalog.implementations.map(SourceImplementation::source).toSet() shouldBe
			TrackingSource.entries.toSet()
		fixture.catalog.implementations.map(SourceImplementation::runtimeSource).toSet() shouldBe
			SourceKind.entries.toSet()

		val bindings = fixture.catalog.bindings
		bindings.size shouldBe 18
		bindings.count { it is SourcePurposeBinding.Executable } shouldBe 11
		bindings.count { it is SourcePurposeBinding.Unsupported } shouldBe 7

		bindings.forEach { binding ->
			val supported = binding.source.supports(binding.purpose)
			(binding is SourcePurposeBinding.Executable) shouldBe supported
			if (!supported) {
				(binding as SourcePurposeBinding.Unsupported).reason shouldBe
					UnsupportedSourcePurposeReason.NOT_CANONICALLY_SUPPORTED
			}
		}
	}

	@Test
	fun `catalog runtime set is exactly the registered six source owners`() {
		val fixture = fixture()

		fixture.catalog.implementations.map(SourceImplementation::runtimeSource).toSet() shouldBe
			fixture.registry.registeredSources()
		fixture.catalog.implementations.forEach { implementation ->
			implementation.runtime shouldBe fixture.registry.runtime(implementation.runtimeSource)
			fixture.catalog.implementation(implementation.runtimeSource) shouldBe implementation
		}
	}

	@Test
	fun `all four semantic tiers delegate every source plan to the shared plan factory`() {
		val fixture = fixture()
		val expectedFactory = SemanticAcquisitionPlanFactory()
		val environment = SourcePlanEnvironment(
			locationBackend = LocationBackend.FRAMEWORK,
			preciseLocationAvailable = true,
			subscriptionIds = setOf(1),
		)

		SourceCollectionFrequency.entries.forEachIndexed { index, frequency ->
			val settings = TrackingParamsState(
				sourcePolicyRevision = index.toLong() + 1L,
				sourceCollectionSettings = SourceCollectionSettings(
					location = frequency,
					activity = frequency,
					steps = frequency,
					pressure = frequency,
					wifi = frequency,
					cell = frequency,
				),
			)
			val revision = index.toLong() + 10L
			val expected = expectedFactory.create(settings, revision, 100L + index, environment)
			fixture.catalog.create(settings, revision, 100L + index, environment) shouldBe expected
			TrackingSource.entries.forEach { source ->
				fixture.catalog.implementation(source).acquisitionPlans.create(
					settings,
					revision,
					100L + index,
					environment,
				) shouldBe expected.plans.getValue(source.toRuntimeSourceKind())
			}
		}

		fixture.acquisitionCalls() shouldBe
			SourceCollectionFrequency.entries.size * (SourceKind.entries.size + 1)
	}

	@Test
	fun `provider limitation variants remain distinct`() = runTest {
		runtimeAvailability(SourceDegradedReason.PERMISSION_MISSING) shouldBe
			SourceProviderAvailability.PermissionRequired(
				setOf(SourceProviderPermission.RuntimePermission(SourceKind.LOCATION)),
			)
		runtimeAvailability(SourceDegradedReason.PROVIDER_UNAVAILABLE) shouldBe
			SourceProviderAvailability.ProviderUnavailable(
				SourceProviderAvailabilityEvidence.Runtime(
					SourceKind.LOCATION,
					setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
				),
			)
		runtimeAvailability(SourceDegradedReason.PLATFORM_THROTTLED) shouldBe
			SourceProviderAvailability.OsLimited(
				SourceProviderAvailabilityEvidence.Runtime(
					SourceKind.LOCATION,
					setOf(SourceDegradedReason.PLATFORM_THROTTLED),
				),
			)
		runtimeAvailability(SourceDegradedReason.HARDWARE_UNAVAILABLE) shouldBe
			SourceProviderAvailability.HardwareUnavailable(
				SourceProviderAvailabilityEvidence.Runtime(
					SourceKind.LOCATION,
					setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE),
				),
			)

		AmbientStepsCapability.PermissionRequired(
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
			setOf(AmbientStepsPermission.HEALTH_CONNECT_READ_STEPS),
		).toSourceProviderAvailability() shouldBe SourceProviderAvailability.PermissionRequired(
			setOf(
				SourceProviderPermission.AmbientStepsPermissionGrant(
					AmbientStepsPermission.HEALTH_CONNECT_READ_STEPS,
				),
			),
		)
		AmbientStepsCapability.Unavailable(
			HealthConnectAmbientStepsAvailability.PLATFORM_TOO_OLD,
			LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING,
		).toSourceProviderAvailability() shouldBe SourceProviderAvailability.OsLimited(
			SourceProviderAvailabilityEvidence.AmbientSteps(
				HealthConnectAmbientStepsAvailability.PLATFORM_TOO_OLD,
				LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING,
			),
		)
		AmbientStepsCapability.Unavailable(
			HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE,
			LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_DISABLED,
		).toSourceProviderAvailability() shouldBe SourceProviderAvailability.ProviderUnavailable(
			SourceProviderAvailabilityEvidence.AmbientSteps(
				HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE,
				LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_DISABLED,
			),
		)
		AmbientStepsCapability.ReadyForRegistration(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			AmbientStepsImportAccess.BACKGROUND_ALLOWED,
		).toSourceProviderAvailability() shouldBe SourceProviderAvailability.Available()
		containedAvailability(
			TrackingDecisionContainmentReason.EXPANDED_AMBIENT_LOCATION_UNAVAILABLE,
		).read(
			availabilityRequest(
				SourceKind.LOCATION,
				TrackingPurpose.AMBIENT_PRODUCT,
			),
		) shouldBe SourceProviderAvailability.Contained(
			TrackingDecisionContainmentReason.EXPANDED_AMBIENT_LOCATION_UNAVAILABLE,
		)

		val runtime = FakeRuntime(SourceKind.PRESSURE)
		runtime.capabilities.value = SourceCapabilities(
			available = false,
			batchingSupported = false,
			flushSupported = false,
			maximumBatchSize = null,
			minimumDelayMs = null,
			degradedReasons = emptySet(),
		)
		RuntimeSourceProviderAvailabilityReader(
			TrackingSource.PRESSURE,
			TrackingPurpose.SESSION_CAPTURE,
			runtime,
		).read(
			availabilityRequest(SourceKind.PRESSURE),
		) shouldBe
			SourceProviderAvailability.HardwareUnavailable(
				SourceProviderAvailabilityEvidence.Runtime(
					SourceKind.PRESSURE,
					setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE),
				),
			)
		runtime.lifecycleCalls shouldBe 0
	}

	@Test
	fun `activity and steps session availability reads current permission and activity provider`() = runTest {
		var state = ActivityRecognitionSourceState(permissionGranted = false, providerAvailable = true)
		val stateProvider = ActivityRecognitionSourceStateProvider { state }
		val activityRuntime = FakeRuntime(SourceKind.ACTIVITY)
		val stepsRuntime = FakeRuntime(SourceKind.STEPS)
		val activityReader = ActivitySessionSourceProviderAvailabilityReader(activityRuntime, stateProvider)
		val stepsReader = StepsSessionSourceProviderAvailabilityReader(stepsRuntime, stateProvider)

		activityReader.read(availabilityRequest(SourceKind.ACTIVITY)) shouldBe
			SourceProviderAvailability.PermissionRequired(
				setOf(SourceProviderPermission.RuntimePermission(SourceKind.ACTIVITY)),
			)
		stepsReader.read(availabilityRequest(SourceKind.STEPS)) shouldBe
			SourceProviderAvailability.PermissionRequired(
				setOf(SourceProviderPermission.RuntimePermission(SourceKind.STEPS)),
			)

		state = ActivityRecognitionSourceState(permissionGranted = true, providerAvailable = false)
		activityReader.read(availabilityRequest(SourceKind.ACTIVITY)) shouldBe
			SourceProviderAvailability.ProviderUnavailable(
				SourceProviderAvailabilityEvidence.Runtime(
					SourceKind.ACTIVITY,
					setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
				),
			)
		stepsReader.read(availabilityRequest(SourceKind.STEPS)) shouldBe
			SourceProviderAvailability.Available()
		activityRuntime.lifecycleCalls shouldBe 0
		stepsRuntime.lifecycleCalls shouldBe 0
	}

	@Test
	fun `location availability reports fused fallback and preserves framework blockers`() = runTest {
		var state = availableLocationState().copy(fusedProviderAvailable = false)
		val stateProvider = object : LocationDeviceStateProvider {
			override fun snapshot(): LocationDeviceState = state
		}
		val reader = LocationSourceProviderAvailabilityReader(
			stateProvider,
			LocationPrerequisiteEvaluator(),
		)
		val fusedRequest = availabilityRequest(SourceKind.LOCATION)
		val degraded = reader.read(fusedRequest) as SourceProviderAvailability.Degraded
		(degraded.effectivePlan as LocationPlan).backend shouldBe LocationBackend.FRAMEWORK
		degraded.evidence shouldBe SourceProviderAvailabilityEvidence.Location(
			requestedBackend = LocationBackend.FUSED,
			effectiveBackend = LocationBackend.FRAMEWORK,
			reasons = setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
		)

		state = state.copy(locationServicesEnabled = false)
		reader.read(
			fusedRequest.copy(
				plan = (fusedRequest.plan as LocationPlan).copy(backend = LocationBackend.FRAMEWORK),
			),
		) shouldBe SourceProviderAvailability.ProviderUnavailable(
			SourceProviderAvailabilityEvidence.Location(
				requestedBackend = LocationBackend.FRAMEWORK,
				effectiveBackend = LocationBackend.FRAMEWORK,
				reasons = setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
			),
		)

		state = state.copy(
			locationServicesEnabled = true,
			coarsePermission = false,
			finePermission = false,
		)
		reader.read(fusedRequest) shouldBe SourceProviderAvailability.PermissionRequired(
			setOf(SourceProviderPermission.RuntimePermission(SourceKind.LOCATION)),
		)

		state = availableLocationState().copy(foregroundServiceLocationCapability = false)
		reader.read(
			fusedRequest.copy(
				plan = (fusedRequest.plan as LocationPlan).copy(backend = LocationBackend.FRAMEWORK),
			),
		) shouldBe SourceProviderAvailability.OsLimited(
			SourceProviderAvailabilityEvidence.Location(
				requestedBackend = LocationBackend.FRAMEWORK,
				effectiveBackend = LocationBackend.FRAMEWORK,
				reasons = setOf(SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING),
			),
		)
	}

	@Test
	fun `session start prerequisite reads every catalog binding before rejecting without starts`() = runTest {
		val fixture = fixture { request ->
			when (request.source) {
				SourceKind.ACTIVITY -> SourceProviderAvailability.PermissionRequired(
					setOf(SourceProviderPermission.RuntimePermission(SourceKind.ACTIVITY)),
				)
				SourceKind.STEPS -> SourceProviderAvailability.ProviderUnavailable(
					SourceProviderAvailabilityEvidence.Runtime(
						SourceKind.STEPS,
						setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
					),
				)
				else -> SourceProviderAvailability.Available()
			}
		}
		val plan = com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision(
			revision = 1L,
			planId = "catalog-readiness",
			createdAtMs = 1L,
			plans = mapOf(
				SourceKind.ACTIVITY to ActivityPlan(
					1L,
					ActivityMode.CONTINUOUS_RECOGNITION,
					1_000L,
					60,
					emptySet(),
				),
				SourceKind.STEPS to StepsPlan(1L, true, 1_000L, 1_000L, false),
			),
		)

		fixture.registry.validateCatalogStartPrerequisites(
			plan,
			SourceAvailabilityTier.MANUAL_FOREGROUND_START,
		) shouldBe com.adsamcik.tracker.tracker.source.coordinator.CatalogStartPrerequisiteFailure(
			"SOURCE_CATALOG_ACTIVITY_SESSION_CAPTURE_PERMISSION_REQUIRED",
			TrackingStartFailureDisposition.TERMINAL,
		)
		fixture.availabilityReads() shouldBe 2
		fixture.runtimes.sumOf(FakeRuntime::lifecycleCalls) shouldBe 0
	}

	@Test
	fun `unsupported provider and degraded catalog results map without provider starts`() {
		SourceCatalogAvailability.Unsupported(
			TrackingSource.ACTIVITY,
			TrackingPurpose.SESSION_CAPTURE,
			UnsupportedSourcePurposeReason.NOT_CANONICALLY_SUPPORTED,
		).toStartPrerequisiteFailure(SourceKind.ACTIVITY) shouldBe
			com.adsamcik.tracker.tracker.source.coordinator.CatalogStartPrerequisiteFailure(
				"SOURCE_CATALOG_ACTIVITY_SESSION_CAPTURE_UNSUPPORTED",
				TrackingStartFailureDisposition.TERMINAL,
			)
		SourceCatalogAvailability.Executable(
			SourceProviderAvailability.ProviderUnavailable(
				SourceProviderAvailabilityEvidence.Runtime(
					SourceKind.ACTIVITY,
					setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
				),
			),
		).toStartPrerequisiteFailure(SourceKind.ACTIVITY) shouldBe
			com.adsamcik.tracker.tracker.source.coordinator.CatalogStartPrerequisiteFailure(
				"SOURCE_CATALOG_ACTIVITY_SESSION_CAPTURE_PROVIDER_UNAVAILABLE",
				TrackingStartFailureDisposition.RETRYABLE,
			)
		val plan = availabilityRequest(SourceKind.LOCATION).plan
		SourceCatalogAvailability.Executable(
			SourceProviderAvailability.Degraded(
				plan,
				SourceProviderAvailabilityEvidence.Runtime(
					SourceKind.LOCATION,
					setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
				),
			),
		).toStartPrerequisiteFailure(SourceKind.LOCATION) shouldBe null
	}

	@Test
	fun `ambient bindings are default off and contained products stay explicit`() {
		val fixture = fixture()
		TrackingSource.entries.filter { it.supports(TrackingPurpose.AMBIENT_PRODUCT) }
			.forEach { source ->
				val binding = fixture.catalog.binding(
					source,
					TrackingPurpose.AMBIENT_PRODUCT,
				) as SourcePurposeBinding.Executable
				binding.activationDefault shouldBe SourceActivationDefault.AMBIENT_OFF
			}

		val ambientLocation = fixture.catalog.binding(
			TrackingSource.LOCATION,
			TrackingPurpose.AMBIENT_PRODUCT,
		) as SourcePurposeBinding.Executable
		ambientLocation.products.capability shouldBe SourceProductCapability.Contained(
			TrackingDecisionContainmentReason.EXPANDED_AMBIENT_LOCATION_UNAVAILABLE,
		)
		val control = fixture.catalog.binding(
			TrackingSource.ACTIVITY,
			TrackingPurpose.CONTROL,
		) as SourcePurposeBinding.Executable
		control.activationDefault shouldBe SourceActivationDefault.CONTROL_OFF
		control.products.capability shouldBe SourceProductCapability.Contained(
			TrackingDecisionContainmentReason.AUTO_005_CONTROL_EVIDENCE_UNRESOLVED,
		)
		control.products.writer shouldBe SourceWriterContract.NotApplicable(
			SourceWriterUnsupportedReason.CONTROL_HAS_NO_PRODUCT_WRITER,
		)
		control.products.projection shouldBe SourceProjectionContract.ActivityControl(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)
	}

	@Test
	fun `construction and lookup do not acquire register activate or grant retention`() {
		val fixture = fixture()

		TrackingSource.entries.forEach { source ->
			fixture.catalog.implementation(source)
			TrackingPurpose.entries.forEach { purpose ->
				fixture.catalog.binding(source, purpose)
			}
		}

		fixture.acquisitionCalls() shouldBe 0
		fixture.availabilityReads() shouldBe 0
		fixture.runtimes.sumOf(FakeRuntime::lifecycleCalls) shouldBe 0
	}

	@Test
	fun `location exposes only the protected writer projection and query route`() {
		val fixture = fixture()
		val locationBindings = listOf(
			TrackingPurpose.SESSION_CAPTURE,
			TrackingPurpose.AMBIENT_PRODUCT,
		).map { purpose ->
			fixture.catalog.binding(TrackingSource.LOCATION, purpose) as SourcePurposeBinding.Executable
		}

		locationBindings.forEach { binding ->
			(binding.products.writer is SourceWriterContract.ProtectedLocation) shouldBe true
			binding.products.drain shouldBe SourceDrainContract.ProtectedLocation
			(binding.products.projection is
				SourceProjectionContract.ProtectedLocationCanonicalHandoff) shouldBe true
			binding.products.query shouldBe SourceQueryContract.LocationSamples
		}

		executableLanes(fixture.catalog).map(ExecutableSourceLaneBinding::source)
			.contains(SourceKind.LOCATION) shouldBe false
	}

	@Test
	fun `query portable projection and retention contracts are source explicit`() {
		val fixture = fixture()

		val portableContracts = executableBindings(fixture.catalog).associate { binding ->
			SourcePurposeKey(binding.source, binding.purpose) to binding.products.portable
		}
		portableContracts shouldBe mapOf(
			key(TrackingSource.LOCATION, TrackingPurpose.SESSION_CAPTURE) to
				SourcePortableContract.Unsupported(
					SourcePortableUnsupportedReason.LOCATION_SOURCE_PORTABLE_CONTRACT_UNAVAILABLE,
				),
			key(TrackingSource.ACTIVITY, TrackingPurpose.SESSION_CAPTURE) to
				SourcePortableContract.CapturedActivity,
			key(TrackingSource.STEPS, TrackingPurpose.SESSION_CAPTURE) to
				SourcePortableContract.SessionStepsV2,
			key(TrackingSource.PRESSURE, TrackingPurpose.SESSION_CAPTURE) to
				SourcePortableContract.PressureV1,
			key(TrackingSource.WIFI, TrackingPurpose.SESSION_CAPTURE) to
				SourcePortableContract.CapturedWifiV1,
			key(TrackingSource.CELL, TrackingPurpose.SESSION_CAPTURE) to
				SourcePortableContract.CapturedCell,
			key(TrackingSource.ACTIVITY, TrackingPurpose.CONTROL) to
				SourcePortableContract.Unsupported(
					SourcePortableUnsupportedReason.CONTROL_EVIDENCE_IS_NOT_PORTABLE,
				),
			key(TrackingSource.LOCATION, TrackingPurpose.AMBIENT_PRODUCT) to
				SourcePortableContract.Unsupported(
					SourcePortableUnsupportedReason.AMBIENT_LOCATION_PORTABLE_CONTRACT_UNAVAILABLE,
				),
			key(TrackingSource.STEPS, TrackingPurpose.AMBIENT_PRODUCT) to
				SourcePortableContract.AmbientStepsV2,
			key(TrackingSource.WIFI, TrackingPurpose.AMBIENT_PRODUCT) to
				SourcePortableContract.AmbientWifiV1,
			key(TrackingSource.CELL, TrackingPurpose.AMBIENT_PRODUCT) to
				SourcePortableContract.AmbientCellV1,
		)

		val queryContracts = executableBindings(fixture.catalog).associate { binding ->
			SourcePurposeKey(binding.source, binding.purpose) to binding.products.query
		}
		queryContracts shouldBe mapOf(
			key(TrackingSource.LOCATION, TrackingPurpose.SESSION_CAPTURE) to
				SourceQueryContract.LocationSamples,
			key(TrackingSource.ACTIVITY, TrackingPurpose.SESSION_CAPTURE) to
				SourceQueryContract.ActivityHistory,
			key(TrackingSource.STEPS, TrackingPurpose.SESSION_CAPTURE) to
				SourceQueryContract.StepsSessionHistory,
			key(TrackingSource.PRESSURE, TrackingPurpose.SESSION_CAPTURE) to
				SourceQueryContract.PressureSessionHistory,
			key(TrackingSource.WIFI, TrackingPurpose.SESSION_CAPTURE) to
				SourceQueryContract.WifiHistory,
			key(TrackingSource.CELL, TrackingPurpose.SESSION_CAPTURE) to
				SourceQueryContract.CellHistory,
			key(TrackingSource.ACTIVITY, TrackingPurpose.CONTROL) to
				SourceQueryContract.Unsupported(
					SourceQueryUnsupportedReason.CONTROL_HAS_NO_PRODUCT_QUERY,
				),
			key(TrackingSource.LOCATION, TrackingPurpose.AMBIENT_PRODUCT) to
				SourceQueryContract.LocationSamples,
			key(TrackingSource.STEPS, TrackingPurpose.AMBIENT_PRODUCT) to
				SourceQueryContract.AmbientStepsHistory,
			key(TrackingSource.WIFI, TrackingPurpose.AMBIENT_PRODUCT) to
				SourceQueryContract.AmbientWifiHistory,
			key(TrackingSource.CELL, TrackingPurpose.AMBIENT_PRODUCT) to
				SourceQueryContract.AmbientCellHistory,
		)

		val projectionContracts = executableBindings(fixture.catalog).associate { binding ->
			SourcePurposeKey(binding.source, binding.purpose) to binding.products.projection
		}
		val locationProjection = SourceProjectionContract.ProtectedLocationCanonicalHandoff(
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
		)
		projectionContracts shouldBe mapOf(
			key(TrackingSource.LOCATION, TrackingPurpose.SESSION_CAPTURE) to locationProjection,
			key(TrackingSource.ACTIVITY, TrackingPurpose.SESSION_CAPTURE) to
				SourceProjectionContract.ExecutableLanes(
					setOf(ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS),
				),
			key(TrackingSource.STEPS, TrackingPurpose.SESSION_CAPTURE) to
				SourceProjectionContract.ExecutableLanes(
					setOf(
						ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1,
						ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V2,
					),
				),
			key(TrackingSource.PRESSURE, TrackingPurpose.SESSION_CAPTURE) to
				SourceProjectionContract.ExecutableLanes(
					setOf(ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS),
				),
			key(TrackingSource.WIFI, TrackingPurpose.SESSION_CAPTURE) to
				SourceProjectionContract.ExecutableLanes(
					setOf(ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS),
				),
			key(TrackingSource.CELL, TrackingPurpose.SESSION_CAPTURE) to
				SourceProjectionContract.ExecutableLanes(
					setOf(ExecutableSourceLaneCatalog.CELL_SESSION_FACTS),
				),
			key(TrackingSource.ACTIVITY, TrackingPurpose.CONTROL) to
				SourceProjectionContract.ActivityControl(
					ActivityAutomationProjection.ID,
					ActivityAutomationProjection.VERSION,
				),
			key(TrackingSource.LOCATION, TrackingPurpose.AMBIENT_PRODUCT) to locationProjection,
			key(TrackingSource.STEPS, TrackingPurpose.AMBIENT_PRODUCT) to
				SourceProjectionContract.AmbientWriter(
					AmbientStepsFactRevisionEntity.WRITER_ID,
					AmbientStepsFactRevisionEntity.WRITER_VERSION,
				),
			key(TrackingSource.WIFI, TrackingPurpose.AMBIENT_PRODUCT) to
				SourceProjectionContract.AmbientWriter(
					AmbientWifiFactRevisionEntity.WRITER_ID,
					AmbientWifiFactRevisionEntity.WRITER_VERSION,
				),
			key(TrackingSource.CELL, TrackingPurpose.AMBIENT_PRODUCT) to
				SourceProjectionContract.AmbientWriter(
					AmbientCellFactRevisionEntity.WRITER_ID,
					AmbientCellFactRevisionEntity.WRITER_VERSION,
				),
		)

		val retentionContracts = executableBindings(fixture.catalog).associate { binding ->
			SourcePurposeKey(binding.source, binding.purpose) to binding.products.retention
		}
		val portableSessionRetention = SourceRetentionContract.SessionCapture(
			RetentionAuthorityScope.PORTABLE_IMPORT,
		)
		val portableAmbientRetention = SourceRetentionContract.Ambient(
			RetentionAuthorityScope.LIVE_AMBIENT,
			RetentionAuthorityScope.PORTABLE_IMPORT,
		)
		retentionContracts shouldBe mapOf(
			key(TrackingSource.LOCATION, TrackingPurpose.SESSION_CAPTURE) to
				SourceRetentionContract.SessionCapture(portableImportScope = null),
			key(TrackingSource.ACTIVITY, TrackingPurpose.SESSION_CAPTURE) to
				portableSessionRetention,
			key(TrackingSource.STEPS, TrackingPurpose.SESSION_CAPTURE) to
				portableSessionRetention,
			key(TrackingSource.PRESSURE, TrackingPurpose.SESSION_CAPTURE) to
				portableSessionRetention,
			key(TrackingSource.WIFI, TrackingPurpose.SESSION_CAPTURE) to
				portableSessionRetention,
			key(TrackingSource.CELL, TrackingPurpose.SESSION_CAPTURE) to
				portableSessionRetention,
			key(TrackingSource.ACTIVITY, TrackingPurpose.CONTROL) to
				SourceRetentionContract.Contained(
					TrackingDecisionContainmentReason.AUTO_005_CONTROL_EVIDENCE_UNRESOLVED,
				),
			key(TrackingSource.LOCATION, TrackingPurpose.AMBIENT_PRODUCT) to
				SourceRetentionContract.Ambient(
					RetentionAuthorityScope.LIVE_AMBIENT,
					portableImportScope = null,
				),
			key(TrackingSource.STEPS, TrackingPurpose.AMBIENT_PRODUCT) to
				portableAmbientRetention,
			key(TrackingSource.WIFI, TrackingPurpose.AMBIENT_PRODUCT) to
				portableAmbientRetention,
			key(TrackingSource.CELL, TrackingPurpose.AMBIENT_PRODUCT) to
				portableAmbientRetention,
		)
	}

	@Test
	fun `catalog reuses only established executable lane generations and writer identities`() {
		val catalog = fixture().catalog
		val actual = executableLanes(catalog)
		actual shouldBe setOf(
			ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1,
			ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V2,
			ExecutableSourceLaneCatalog.ACTIVITY_SESSION_FACTS,
			ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS,
			ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS,
			ExecutableSourceLaneCatalog.CELL_SESSION_FACTS,
		)

		sessionOwnership(catalog, TrackingSource.LOCATION) shouldBe SourceWriterOwnership(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
			SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
		)
		sessionOwnership(catalog, TrackingSource.ACTIVITY) shouldBe candidateOwnership(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
		)
		sessionOwnership(catalog, TrackingSource.STEPS) shouldBe candidateOwnership(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		)
		sessionOwnership(catalog, TrackingSource.PRESSURE) shouldBe candidateOwnership(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		)
		sessionOwnership(catalog, TrackingSource.WIFI) shouldBe candidateOwnership(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS,
		)
		sessionOwnership(catalog, TrackingSource.CELL) shouldBe candidateOwnership(
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
		)
	}

	private fun runtimeAvailability(reason: SourceDegradedReason): SourceProviderAvailability =
		SourceCapabilities(
			available = false,
			batchingSupported = false,
			flushSupported = false,
			maximumBatchSize = null,
			minimumDelayMs = null,
			degradedReasons = setOf(reason),
		).toSourceProviderAvailability(SourceKind.LOCATION)

	private fun fixture(
		availability: (SourceAvailabilityRequest) -> SourceProviderAvailability = {
			SourceProviderAvailability.Available()
		},
	): CatalogFixture {
		val runtimes = SourceKind.entries.map(::FakeRuntime)
		val semanticFactory = SemanticAcquisitionPlanFactory()
		var acquisitionCalls = 0
		var availabilityReads = 0
		val catalog = DefaultSourceImplementationCatalog(
			runtimes = runtimes.toSet(),
			acquisitionRevisionFactory = SourceAcquisitionRevisionFactory {
					settings, revision, createdAtMs, environment ->
				acquisitionCalls++
				semanticFactory.create(settings, revision, createdAtMs, environment)
			},
			availabilityReaders = SourceProviderAvailabilityReaderFactory { _, _, _ ->
				SourceProviderAvailabilityReader { request ->
					availabilityReads++
					availability(request)
				}
			},
			productFactories = SourceProductFactories(),
		)
		val registry = SourceRuntimeRegistry(catalog)
		return CatalogFixture(
			catalog,
			registry,
			runtimes,
			{ acquisitionCalls },
			{ availabilityReads },
		)
	}

	private fun executableBindings(
		catalog: SourceImplementationCatalog,
	): List<SourcePurposeBinding.Executable> = TrackingSource.entries.flatMap { source ->
		TrackingPurpose.entries.mapNotNull { purpose ->
			catalog.binding(source, purpose) as? SourcePurposeBinding.Executable
		}
	}

	private fun executableLanes(
		catalog: SourceImplementationCatalog,
	): Set<ExecutableSourceLaneBinding> = executableBindings(catalog)
		.mapNotNull { binding -> binding.products.writer as? SourceWriterContract.SourceLocalLane }
		.flatMap(SourceWriterContract.SourceLocalLane::lanes)
		.toSet()

	private fun sessionOwnership(
		catalog: SourceImplementationCatalog,
		source: TrackingSource,
	): SourceWriterOwnership {
		val writer = (
			catalog.binding(source, TrackingPurpose.SESSION_CAPTURE) as
				SourcePurposeBinding.Executable
			).products.writer
		return when (writer) {
			is SourceWriterContract.ProtectedLocation -> writer.ownership
			is SourceWriterContract.SourceLocalLane -> writer.ownership
			else -> error("$source session binding has no session writer ownership")
		}
	}

	private fun candidateOwnership(
		destination: String,
		owner: String,
	) = SourceWriterOwnership(
		destination,
		owner,
		SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
	)

	private fun key(
		source: TrackingSource,
		purpose: TrackingPurpose,
	) = SourcePurposeKey(source, purpose)

	private fun availabilityRequest(
		source: SourceKind,
		purpose: TrackingPurpose = TrackingPurpose.SESSION_CAPTURE,
		tier: SourceAvailabilityTier = SourceAvailabilityTier.MANUAL_FOREGROUND_START,
	): SourceAvailabilityRequest {
		val plan = when (source) {
			SourceKind.LOCATION -> LocationPlan(
				revision = 1L,
				backend = LocationBackend.FUSED,
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 1_000L,
				minimumUpdateIntervalMs = 1_000L,
				minimumDisplacementMeters = 1f,
				maximumBatchDelayMs = 1_000L,
				preciseLocationAvailable = true,
			)
			SourceKind.ACTIVITY -> ActivityPlan(
				1L,
				ActivityMode.CONTINUOUS_RECOGNITION,
				1_000L,
				60,
				emptySet(),
			)
			SourceKind.STEPS -> StepsPlan(1L, true, 1_000L, 1_000L, false)
			else -> SemanticAcquisitionPlanFactory().create(
				TrackingParamsState(
					sourceCollectionSettings = SourceCollectionSettings(
						location = SourceCollectionFrequency.BALANCED,
						activity = SourceCollectionFrequency.BALANCED,
						steps = SourceCollectionFrequency.BALANCED,
						pressure = SourceCollectionFrequency.BALANCED,
						wifi = SourceCollectionFrequency.BALANCED,
						cell = SourceCollectionFrequency.BALANCED,
					),
				),
				1L,
				1L,
				SourcePlanEnvironment(LocationBackend.FUSED, true, emptySet()),
			).plans.getValue(source)
		}
		return SourceAvailabilityRequest(source, purpose, plan, tier)
	}

	private fun availableLocationState() = LocationDeviceState(
		apiLevel = 35,
		locationFeatureAvailable = true,
		locationServicesEnabled = true,
		coarsePermission = true,
		finePermission = true,
		backgroundLocationPermission = true,
		fusedProviderAvailable = true,
		foregroundServiceLocationCapability = true,
		backgroundForegroundServiceStartLegal = true,
	)

	private data class CatalogFixture(
		val catalog: DefaultSourceImplementationCatalog,
		val registry: SourceRuntimeRegistry,
		val runtimes: List<FakeRuntime>,
		val acquisitionCalls: () -> Int,
		val availabilityReads: () -> Int,
	)

	private class FakeRuntime(
		override val source: SourceKind,
	) : ClaimedSourceRuntime<SourcePlan> {
		override val capabilities =
			kotlinx.coroutines.flow.MutableStateFlow(
				SourceCapabilities(
					available = true,
					batchingSupported = false,
					flushSupported = false,
					maximumBatchSize = null,
					minimumDelayMs = null,
				),
			)
		var lifecycleCalls: Int = 0
			private set

		override suspend fun start(
			plan: SourcePlan,
			sink: SourceEventSink,
		): SourceStartResult = unexpectedLifecycle()

		override suspend fun start(
			claim: SourceRuntimeClaim,
			plan: SourcePlan,
			sink: SourceEventSink,
		): SourceStartResult = unexpectedLifecycle()

		override suspend fun reconfigure(
			plan: SourcePlan,
			sink: SourceEventSink,
		): SourceApplyResult = unexpectedLifecycle()

		override suspend fun reconfigure(
			claim: SourceRuntimeClaim,
			plan: SourcePlan,
			sink: SourceEventSink,
		): SourceApplyResult = unexpectedLifecycle()

		override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck =
			unexpectedLifecycle()

		override suspend fun close() {
			lifecycleCalls++
			error("Catalog construction must not close a runtime")
		}

		override suspend fun shutdownIfOwned(
			claim: SourceRuntimeClaim,
			cutoff: SessionCutoff,
		): OwnedSourceShutdown = unexpectedLifecycle()

		private fun unexpectedLifecycle(): Nothing {
			lifecycleCalls++
			error("Catalog construction must not mutate a runtime")
		}
	}
}
