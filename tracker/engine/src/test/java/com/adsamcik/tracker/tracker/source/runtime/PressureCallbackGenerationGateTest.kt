package com.adsamcik.tracker.tracker.source.runtime

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PressureCallbackGenerationGateTest {
	@Test
	fun `late callback token cannot enter a replacement registration`() {
		val gate = PressureCallbackGenerationGate()
		val retired = gate.activate(4L, "old-demand-vector")
		val active = gate.activate(5L, "new-demand-vector")

		assertFalse(gate.accepts(retired))
		assertTrue(gate.accepts(active))
		assertFalse(gate.retire(retired))
		assertTrue(gate.accepts(active))
	}

	@Test
	fun `retirement closes the exact active callback identity`() {
		val gate = PressureCallbackGenerationGate()
		val active = gate.activate(9L, "capture-manifest-9")

		assertTrue(gate.accepts(active))
		assertTrue(gate.retire(active))
		assertFalse(gate.accepts(active))
	}

	@Test
	fun `same physical generation resume uses a fresh token and keeps the old listener fenced`() {
		val gate = PressureCallbackGenerationGate()
		val beforePause = gate.activate(9L, "same-physical-configuration")

		assertTrue(gate.retire(beforePause))
		val afterResume = gate.activate(9L, "same-physical-configuration")

		assertFalse(gate.accepts(beforePause))
		assertTrue(gate.accepts(afterResume))
	}
}
