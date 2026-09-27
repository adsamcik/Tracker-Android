package com.adsamcik.tracker.tracker.source.lifecycle

import io.kotest.matchers.shouldBe
import org.junit.Test

class SourceMaterializationRevisionSelectionTest {
	@Test
	fun `wifi revision two current head preserves correction lineage`() {
		val first = Revision("wifi-fact", 1L, null)
		val second = Revision("wifi-fact", 2L, 1L)

		resolve(listOf(first, second), listOf(second), listOf(second, first)) shouldBe
			CurrentRevisionResolution.Ready(second, listOf(first, second))
	}

	@Test
	fun `cell revision two current head preserves correction lineage`() {
		val first = Revision("cell-fact", 1L, null)
		val second = Revision("cell-fact", 2L, 1L)

		resolve(listOf(first, second), listOf(second), listOf(first, second)) shouldBe
			CurrentRevisionResolution.Ready(second, listOf(first, second))
	}

	@Test
	fun `cursor mismatch fails closed instead of selecting an older revision`() {
		val first = Revision("wifi-fact", 1L, null)
		val second = Revision("wifi-fact", 2L, 1L)

		resolve(listOf(first, second), emptyList(), listOf(first, second)) shouldBe
			CurrentRevisionResolution.Invalid
	}

	@Test
	fun `ambiguous current heads fail closed`() {
		val first = Revision("wifi-fact", 1L, null)
		val second = Revision("wifi-fact", 2L, 1L)
		val other = Revision("other-wifi-fact", 1L, null)

		resolve(
			admission = listOf(first, second, other),
			current = listOf(second, other),
			retained = listOf(first, second),
		) shouldBe CurrentRevisionResolution.Invalid
	}

	private fun resolve(
		admission: List<Revision>,
		current: List<Revision>,
		retained: List<Revision>,
	): CurrentRevisionResolution<Revision> = resolveCurrentRevision(
		admissionRevisions = admission,
		currentRevisions = current,
		retainedLineage = retained,
		maximumRevisionCount = 64,
		logicalFactId = Revision::logicalFactId,
		semanticRevision = Revision::semanticRevision,
		lineageAuthenticates = ::authenticatesLineage,
	)

	private fun authenticatesLineage(lineage: List<Revision>): Boolean =
		lineage.map(Revision::semanticRevision) == (1L..lineage.size.toLong()).toList() &&
			lineage.zipWithNext().all { (previous, next) ->
				next.supersedesSemanticRevision == previous.semanticRevision
			}

	private data class Revision(
		val logicalFactId: String,
		val semanticRevision: Long,
		val supersedesSemanticRevision: Long?,
	)
}
