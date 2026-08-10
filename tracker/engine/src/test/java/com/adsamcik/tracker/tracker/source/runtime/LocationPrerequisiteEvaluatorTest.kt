package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LocationPrerequisiteEvaluatorTest {
	private val evaluator = LocationPrerequisiteEvaluator()

	@Test
	fun `coarse-only high accuracy degrades to balanced but remains applicable`() {
		val result = evaluator.evaluate(plan(), device(fine = false), LocationStartContext.SESSION_ALREADY_FOREGROUND)

		assertEquals(LocationPlanApplicationStatus.DEGRADED, result.status)
		assertEquals(LocationMode.BALANCED, result.plan.mode)
		assertFalse(result.plan.preciseLocationAvailable)
		assertEquals(setOf(SourceDegradedReason.PERMISSION_MISSING), result.reasons)
	}

	@Test
	fun `framework backend remains applicable without Google Play Services`() {
		val result = evaluator.evaluate(
			plan(backend = LocationBackend.FRAMEWORK),
			device(fused = false),
			LocationStartContext.SESSION_ALREADY_FOREGROUND,
		)

		assertEquals(LocationPlanApplicationStatus.APPLIED, result.status)
	}

	@Test
	fun `fused backend is blocked when Google Play Services is unavailable`() {
		val result = evaluator.evaluate(plan(), device(fused = false), LocationStartContext.SESSION_ALREADY_FOREGROUND)

		assertEquals(LocationPlanApplicationStatus.BLOCKED, result.status)
		assertTrue(SourceDegradedReason.PROVIDER_UNAVAILABLE in result.reasons)
	}

	@Test
	fun `automatic Android 14 start requires both background permission and legal FGS start`() {
		val result = evaluator.evaluate(
			plan(),
			device(api = 34, background = false, backgroundStartLegal = false),
			LocationStartContext.AUTOMATIC_BACKGROUND_START,
		)

		assertEquals(LocationPlanApplicationStatus.BLOCKED, result.status)
		assertTrue(SourceDegradedReason.PERMISSION_MISSING in result.reasons)
		assertTrue(SourceDegradedReason.BACKGROUND_START_ILLEGAL in result.reasons)
	}

	@Test
	fun `location services disabled blocks active plans but not passive framework observation`() {
		val active = evaluator.evaluate(plan(), device(services = false), LocationStartContext.MANUAL_FOREGROUND_START)
		val passive = evaluator.evaluate(
			plan(backend = LocationBackend.FRAMEWORK, mode = LocationMode.PASSIVE),
			device(services = false),
			LocationStartContext.MANUAL_FOREGROUND_START,
		)

		assertEquals(LocationPlanApplicationStatus.BLOCKED, active.status)
		assertEquals(LocationPlanApplicationStatus.APPLIED, passive.status)
	}

	private fun plan(
		backend: LocationBackend = LocationBackend.FUSED,
		mode: LocationMode = LocationMode.HIGH_ACCURACY,
	) = LocationPlan(1, backend, mode, 1_000, 500, 1f, 2_000, preciseLocationAvailable = true)

	private fun device(
		api: Int = 34,
		services: Boolean = true,
		fine: Boolean = true,
		background: Boolean = true,
		fused: Boolean = true,
		backgroundStartLegal: Boolean = true,
	) = LocationDeviceState(
		apiLevel = api,
		locationFeatureAvailable = true,
		locationServicesEnabled = services,
		coarsePermission = true,
		finePermission = fine,
		backgroundLocationPermission = background,
		fusedProviderAvailable = fused,
		foregroundServiceLocationCapability = true,
		backgroundForegroundServiceStartLegal = backgroundStartLegal,
	)
}

