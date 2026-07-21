package com.adsamcik.tracker.osm.match

/**
 * Generic log-space Viterbi decoder for a Hidden Markov Model where each
 * observation has its own (small) set of candidate states.
 *
 * The decoder is intentionally data-agnostic: callers supply the number of
 * candidates per observation plus two log-probability functions. This keeps
 * the dynamic-programming core pure and unit-testable in isolation from the
 * OSM-specific candidate generation.
 *
 * Probabilities are passed in log space so the product of many small terms
 * does not underflow. Constant scale factors may be dropped because they do
 * not affect the arg-max.
 */
internal object ViterbiMatcher {

	/**
	 * Decodes the most likely state sequence.
	 *
	 * @param candidateCounts number of candidate states for each observation;
	 *   every entry MUST be >= 1 (callers split runs so no observation is empty).
	 * @param emissionLog `(obsIndex, candIndex) -> log P(observation | state)`.
	 * @param transitionLog `(obsIndex, fromCand, toCand) -> log P(state_obs | state_obs-1)`,
	 *   evaluated for the step from observation `obsIndex - 1` to `obsIndex`.
	 * @return for each observation, the index of the chosen candidate. Empty
	 *   when [candidateCounts] is empty or no finite complete path exists.
	 */
	fun decode(
		candidateCounts: IntArray,
		emissionLog: (obs: Int, cand: Int) -> Double,
		transitionLog: (obs: Int, fromCand: Int, toCand: Int) -> Double,
	): IntArray {
		val n = candidateCounts.size
		if (n == 0) return IntArray(0)
		require(candidateCounts.all { it >= 1 }) { "every observation must have >= 1 candidate" }

		var prevDelta = DoubleArray(candidateCounts[0]) { j ->
			emissionLog(0, j).takeIf { it.isFinite() } ?: Double.NEGATIVE_INFINITY
		}
		val backpointers = Array(n) { IntArray(0) }

		for (i in 1 until n) {
			val count = candidateCounts[i]
			val delta = DoubleArray(count)
			val back = IntArray(count) { NO_PREDECESSOR }
			for (j in 0 until count) {
				var bestScore = Double.NEGATIVE_INFINITY
				var bestK = NO_PREDECESSOR
				for (k in prevDelta.indices) {
					val score = prevDelta[k] + transitionLog(i, k, j)
					if (score.isFinite() && score > bestScore) {
						bestScore = score
						bestK = k
					}
				}
				val emission = emissionLog(i, j)
				delta[j] = if (bestK != NO_PREDECESSOR && emission.isFinite()) {
					bestScore + emission
				} else {
					Double.NEGATIVE_INFINITY
				}
				back[j] = bestK
			}
			backpointers[i] = back
			prevDelta = delta
		}

		var bestLast = NO_PREDECESSOR
		var bestScore = Double.NEGATIVE_INFINITY
		for (j in prevDelta.indices) {
			if (prevDelta[j].isFinite() && prevDelta[j] > bestScore) {
				bestScore = prevDelta[j]
				bestLast = j
			}
		}
		if (bestLast == NO_PREDECESSOR) return IntArray(0)

		val path = IntArray(n)
		path[n - 1] = bestLast
		for (i in n - 1 downTo 1) {
			val predecessor = backpointers[i][path[i]]
			if (predecessor == NO_PREDECESSOR) return IntArray(0)
			path[i - 1] = predecessor
		}
		return path
	}

	private const val NO_PREDECESSOR = -1
}
