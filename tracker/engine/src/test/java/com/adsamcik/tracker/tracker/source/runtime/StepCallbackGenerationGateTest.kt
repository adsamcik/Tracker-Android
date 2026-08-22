package com.adsamcik.tracker.tracker.source.runtime

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StepCallbackGenerationGateTest {
	@Test
	fun `late callback token is rejected after disable and re-enable`() {
		val gate = StepCallbackGenerationGate()
		val retired = gate.activate(1L, "capture-manifest-1")

		assertTrue(gate.accepts(retired))
		assertTrue(gate.retire(retired))

		val active = gate.activate(2L, "capture-manifest-1")

		assertFalse(gate.accepts(retired))
		assertTrue(gate.accepts(active))
	}

	@Test
	fun `late callback token is rejected after eligibility manifest changes`() {
		val gate = StepCallbackGenerationGate()
		val oldManifest = gate.activate(7L, "capture-manifest-1")
		val newManifest = gate.activate(8L, "capture-manifest-2")

		assertFalse(gate.accepts(oldManifest))
		assertTrue(gate.accepts(newManifest))
		assertFalse(gate.retire(oldManifest))
		assertTrue(gate.accepts(newManifest))
	}
}
