package com.adsamcik.tracker.shared.base.database.data

/**
 * Immutable generation arithmetic for rearmable source-local session writers.
 *
 * Binding generation N is canonical only under owner generation 2N. Rollback advances to the
 * contained generation 2N+1; rearm creates binding N+1 and later activation advances that owner
 * exactly once to 2(N+1). This supports repeated rollback/rearm cycles without ABA authority.
 *
 * Steps and protected Location have separate established contracts and deliberately do not use
 * this arithmetic.
 */
object SourceWriterGenerationContract {
	/** Only these immutable session-mode contracts are built into the final source pipeline. */
	const val ORIGINAL_CAPTURE_BINDING_GENERATION = 1L
	const val FINAL_CAPTURE_BINDING_GENERATION = 2L

	/** Stable wire masks: original manual-only data is never relabelled as automatic. */
	fun captureModeMaskForKnownBinding(bindingGeneration: Long): Long? = when (bindingGeneration) {
		ORIGINAL_CAPTURE_BINDING_GENERATION -> 1L
		FINAL_CAPTURE_BINDING_GENERATION -> MANUAL_AND_AUTOMATIC_CAPTURE_MODE_MASK
		else -> null
	}

	/**
	 * Maps a positive binding generation to its even owner generation, throwing on arithmetic
	 * overflow.
	 */
	fun canonicalOwnerGeneration(bindingGeneration: Long): Long {
		require(bindingGeneration > 0L)
		return Math.multiplyExact(bindingGeneration, 2L)
	}

	/**
	 * Maps a positive binding generation to its odd containment owner generation, throwing on
	 * arithmetic overflow.
	 */
	fun containedOwnerGeneration(bindingGeneration: Long): Long =
		Math.addExact(canonicalOwnerGeneration(bindingGeneration), 1L)

	/**
	 * Returns the next positive binding generation, throwing on overflow.
	 */
	fun nextBindingGeneration(bindingGeneration: Long): Long {
		require(bindingGeneration > 0L)
		return Math.addExact(bindingGeneration, 1L)
	}

	/**
	 * Returns the next binding after a valid containment generation; an invalid containment key
	 * returns null.
	 */
	fun nextBindingGenerationForContainedOwner(containedOwnerGeneration: Long): Long? =
		bindingGenerationForContainedOwner(containedOwnerGeneration)?.let { currentBinding ->
			nextBindingGeneration(currentBinding).also { nextBinding ->
				canonicalOwnerGeneration(nextBinding)
			}
		}

	/**
	 * Returns the following canonical owner generation after a positive odd containment generation.
	 */
	fun nextCanonicalOwnerGeneration(containedOwnerGeneration: Long): Long {
		require(containedOwnerGeneration > 0L && containedOwnerGeneration % 2L == 1L)
		return Math.addExact(containedOwnerGeneration, 1L)
	}

	/**
	 * Decodes a positive even owner generation; noncanonical values return null.
	 */
	fun bindingGenerationForCanonicalOwner(ownerGeneration: Long): Long? =
		ownerGeneration.takeIf { it > 0L && it % 2L == 0L }?.div(2L)

	/**
	 * Decodes an odd containment generation of at least 3; invalid values return null.
	 */
	fun bindingGenerationForContainedOwner(ownerGeneration: Long): Long? =
		ownerGeneration.takeIf { it >= FIRST_CONTAINED_OWNER_GENERATION && it % 2L == 1L }?.let { (it - 1L) / 2L }
}

private const val MANUAL_AND_AUTOMATIC_CAPTURE_MODE_MASK = 3L
private const val FIRST_CONTAINED_OWNER_GENERATION = 3L
