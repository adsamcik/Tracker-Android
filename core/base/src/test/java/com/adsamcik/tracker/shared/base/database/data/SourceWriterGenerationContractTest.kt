package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import org.junit.Test

class SourceWriterGenerationContractTest {
	@Test
	fun `canonical and contained generations remain monotonic across repeated rearm cycles`() {
		(1L..100L).forEach { binding ->
			val canonical = SourceWriterGenerationContract.canonicalOwnerGeneration(binding)
			val contained = SourceWriterGenerationContract.containedOwnerGeneration(binding)

			canonical shouldBe binding * 2L
			contained shouldBe canonical + 1L
			SourceWriterGenerationContract.bindingGenerationForCanonicalOwner(canonical) shouldBe binding
			SourceWriterGenerationContract.bindingGenerationForContainedOwner(contained) shouldBe binding
			SourceWriterGenerationContract.nextCanonicalOwnerGeneration(contained) shouldBe
				SourceWriterGenerationContract.canonicalOwnerGeneration(binding + 1L)
		}
	}
}
