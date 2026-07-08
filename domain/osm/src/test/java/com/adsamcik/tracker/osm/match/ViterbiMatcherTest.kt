package com.adsamcik.tracker.osm.match

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * Pure JVM unit tests for the generic [ViterbiMatcher] decoder.
 */
@DisplayName("ViterbiMatcher")
class ViterbiMatcherTest {

	@Test
	fun `empty input yields empty path`() {
		ViterbiMatcher.decode(IntArray(0), { _, _ -> 0.0 }, { _, _, _ -> 0.0 }).toList() shouldBe
			emptyList()
	}

	@Test
	fun `single observation picks the highest-emission candidate`() {
		val path = ViterbiMatcher.decode(
			candidateCounts = intArrayOf(3),
			emissionLog = { _, cand -> if (cand == 2) ln(0.9) else ln(0.05) },
			transitionLog = { _, _, _ -> 0.0 },
		)
		path.toList() shouldBe listOf(2)
	}

	@Test
	fun `emission alone determines the path when transitions are uniform`() {
		val path = ViterbiMatcher.decode(
			candidateCounts = intArrayOf(2, 2),
			emissionLog = { obs, cand ->
				when (obs to cand) {
					0 to 0 -> ln(0.9)
					0 to 1 -> ln(0.1)
					1 to 0 -> ln(0.1)
					else -> ln(0.9)
				}
			},
			transitionLog = { _, _, _ -> 0.0 },
		)
		path.toList() shouldBe listOf(0, 1)
	}

	@Test
	fun `strong transition forces a constant path onto the globally best state`() {
		// Without the transition penalty the per-step argmax would be [0, 1]
		// (obs0 is a tie -> index 0; obs1 prefers cand 1). The -100 penalty makes
		// switching states almost impossible, so the decoder instead commits to the
		// single best constant path: staying on cand 1 (emission 0.4*0.6 > 0.4*0.4).
		val path = ViterbiMatcher.decode(
			candidateCounts = intArrayOf(2, 2),
			emissionLog = { obs, cand ->
				if (obs == 1 && cand == 1) ln(0.6) else ln(0.4)
			},
			transitionLog = { _, from, to -> if (from == to) 0.0 else -100.0 },
		)
		path.toList() shouldBe listOf(1, 1)
	}

	@Test
	fun `three-step chain follows the consistently-best states`() {
		// Candidate 1 is best at every step and transitions favour staying.
		val path = ViterbiMatcher.decode(
			candidateCounts = intArrayOf(2, 2, 2),
			emissionLog = { _, cand -> if (cand == 1) ln(0.8) else ln(0.2) },
			transitionLog = { _, from, to -> if (from == to) 0.0 else -1.0 },
		)
		path.toList() shouldBe listOf(1, 1, 1)
	}
}
