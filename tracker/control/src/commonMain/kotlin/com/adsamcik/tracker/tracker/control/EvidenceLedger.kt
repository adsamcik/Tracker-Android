package com.adsamcik.tracker.tracker.control

/**
 * Small, deterministic bounded reordering buffer.
 *
 * Provider callbacks frequently arrive in a different order than their monotonic timestamps. The
 * ledger accepts a bounded amount of that disorder, assigns a durable-in-trace sequence at
 * ingress, and emits inputs ordered by `(elapsedRealtimeNanos, ingressSequence)`. An input older
 * than the configured horizon is deliberately surfaced instead of silently mutating state in the
 * past. Source-identified provider observations are also idempotent within a bounded remembered
 * ID window; the first ingress wins and later duplicates are surfaced to the decision stream.
 */
class EvidenceLedger(
	private val maxReorderNanos: Long,
	private val maxDuplicateLocationSourceIds: Int = DEFAULT_MAXIMUM_DUPLICATE_LOCATION_SOURCE_IDS,
) {
	init {
		require(maxReorderNanos >= 0L) { "Maximum reordering window must not be negative" }
		require(maxDuplicateLocationSourceIds > 0) {
			"Maximum remembered location source ids must be positive"
		}
	}

	private data class QueuedInput(
		val sequence: Long,
		val input: ControlInput,
	)

	sealed interface OfferResult {
		data class Buffered(
			val sequence: Long,
		) : OfferResult

		data class Late(
			val lateInput: LateControlInput,
		) : OfferResult

		data class Duplicate(
			val duplicateInput: DuplicateControlInput,
		) : OfferResult
	}

	private val pending = mutableListOf<QueuedInput>()
	private val seenLocationSourceIds = mutableMapOf<String, Long>()
	private val locationSourceIdOrder = ArrayDeque<String>()
	private var nextSequence = 1L
	private var latestSeenNanos = Long.MIN_VALUE

	/** Adds input to the ledger without deciding it yet. */
	fun offer(input: ControlInput): OfferResult {
		val sequence = nextSequence++
		(input.payload as? ControlEvidence.LocationObservation)?.sourceEventId?.let { sourceEventId ->
			seenLocationSourceIds[sourceEventId]?.let { originalSequence ->
				return OfferResult.Duplicate(
					DuplicateControlInput(
						input = input,
						ledgerSequence = sequence,
						originalLedgerSequence = originalSequence,
					),
				)
			}
			rememberLocationSourceId(sourceEventId, sequence)
		}
		val timestamp = input.elapsedRealtimeNanos
		if (
			latestSeenNanos != Long.MIN_VALUE &&
			timestamp < saturatingSubtract(latestSeenNanos, maxReorderNanos)
		) {
			return OfferResult.Late(
				LateControlInput(
					input = input,
					ledgerSequence = sequence,
					latestSeenElapsedRealtimeNanos = latestSeenNanos,
					allowedReorderNanos = maxReorderNanos,
				),
			)
		}

		if (timestamp > latestSeenNanos) latestSeenNanos = timestamp
		pending += QueuedInput(sequence, input)
		return OfferResult.Buffered(sequence)
	}

	/** Emits the portion now safe from future bounded reordering. */
	fun drainReady(): List<Pair<Long, ControlInput>> {
		if (latestSeenNanos == Long.MIN_VALUE) return emptyList()
		return drainThrough(saturatingSubtract(latestSeenNanos, maxReorderNanos))
	}

	/** Emits every queued event no later than [elapsedRealtimeNanos]. */
	fun drainThrough(elapsedRealtimeNanos: Long): List<Pair<Long, ControlInput>> {
		if (pending.isEmpty()) return emptyList()
		pending.sortWith(
			compareBy<QueuedInput> { it.input.elapsedRealtimeNanos }
				.thenBy { it.sequence },
		)
		val readyCount = pending.indexOfFirst { it.input.elapsedRealtimeNanos > elapsedRealtimeNanos }
		val count = if (readyCount == -1) pending.size else readyCount
		if (count == 0) return emptyList()
		val ready = pending.take(count).map { it.sequence to it.input }
		pending.subList(0, count).clear()
		return ready
	}

	/** Flushes the tail during clean shutdown or an explicit replay boundary. */
	fun flush(): List<Pair<Long, ControlInput>> = drainThrough(Long.MAX_VALUE)

	fun pendingCount(): Int = pending.size

	private fun saturatingSubtract(value: Long, amount: Long): Long = when {
		amount <= 0L -> value
		value < Long.MIN_VALUE + amount -> Long.MIN_VALUE
		else -> value - amount
	}

	private fun rememberLocationSourceId(sourceEventId: String, sequence: Long) {
		seenLocationSourceIds[sourceEventId] = sequence
		locationSourceIdOrder.addLast(sourceEventId)
		while (locationSourceIdOrder.size > maxDuplicateLocationSourceIds) {
			seenLocationSourceIds.remove(locationSourceIdOrder.removeFirst())
		}
	}

	private companion object {
		const val DEFAULT_MAXIMUM_DUPLICATE_LOCATION_SOURCE_IDS = 10_000
	}
}
