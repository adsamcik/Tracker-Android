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
	fun canonicalOwnerGeneration(bindingGeneration: Long): Long {
		require(bindingGeneration > 0L)
		return Math.multiplyExact(bindingGeneration, 2L)
	}

	fun containedOwnerGeneration(bindingGeneration: Long): Long =
		Math.addExact(canonicalOwnerGeneration(bindingGeneration), 1L)

	fun nextBindingGeneration(bindingGeneration: Long): Long {
		require(bindingGeneration > 0L)
		return Math.addExact(bindingGeneration, 1L)
	}

	fun nextBindingGenerationForContainedOwner(containedOwnerGeneration: Long): Long? =
		bindingGenerationForContainedOwner(containedOwnerGeneration)?.let { currentBinding ->
			nextBindingGeneration(currentBinding).also { nextBinding ->
				canonicalOwnerGeneration(nextBinding)
			}
		}

	fun nextCanonicalOwnerGeneration(containedOwnerGeneration: Long): Long {
		require(containedOwnerGeneration > 0L && containedOwnerGeneration % 2L == 1L)
		return Math.addExact(containedOwnerGeneration, 1L)
	}

	fun bindingGenerationForCanonicalOwner(ownerGeneration: Long): Long? =
		ownerGeneration.takeIf { it > 0L && it % 2L == 0L }?.div(2L)

	fun bindingGenerationForContainedOwner(ownerGeneration: Long): Long? =
		ownerGeneration.takeIf { it >= 3L && it % 2L == 1L }?.let { (it - 1L) / 2L }
}
