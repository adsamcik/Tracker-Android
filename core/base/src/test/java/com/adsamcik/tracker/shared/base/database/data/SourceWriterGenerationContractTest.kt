package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
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
			SourceWriterGenerationContract.nextBindingGenerationForContainedOwner(contained) shouldBe
				binding + 1L
		}
	}

	@Test
	fun `generation arithmetic fails instead of wrapping at the long boundary`() {
		val finalRepresentableBinding = Long.MAX_VALUE / 2L
		SourceWriterGenerationContract.containedOwnerGeneration(finalRepresentableBinding) shouldBe
			Long.MAX_VALUE

		shouldThrow<ArithmeticException> {
			SourceWriterGenerationContract.canonicalOwnerGeneration(finalRepresentableBinding + 1L)
		}
		shouldThrow<ArithmeticException> {
			SourceWriterGenerationContract.nextCanonicalOwnerGeneration(Long.MAX_VALUE)
		}
		shouldThrow<ArithmeticException> {
			SourceWriterGenerationContract.nextBindingGenerationForContainedOwner(Long.MAX_VALUE)
		}
	}

	@Test
	fun `invalid owner generations never resolve a binding`() {
		SourceWriterGenerationContract.bindingGenerationForCanonicalOwner(0L) shouldBe null
		SourceWriterGenerationContract.bindingGenerationForCanonicalOwner(3L) shouldBe null
		SourceWriterGenerationContract.bindingGenerationForContainedOwner(1L) shouldBe null
		SourceWriterGenerationContract.bindingGenerationForContainedOwner(2L) shouldBe null
		SourceWriterGenerationContract.nextBindingGenerationForContainedOwner(2L) shouldBe null
	}

	@Test
	fun `known capture contracts preserve manual originals and reject invented mode generations`() {
		SourceWriterGenerationContract.captureModeMaskForKnownBinding(1L) shouldBe 1L
		SourceWriterGenerationContract.captureModeMaskForKnownBinding(2L) shouldBe 3L
		listOf(-1L, 0L, 3L, Long.MAX_VALUE).forEach { generation ->
			SourceWriterGenerationContract.captureModeMaskForKnownBinding(generation) shouldBe null
		}
		SourceWriterGenerationContract.canonicalOwnerGeneration(1L) shouldBe 2L
		SourceWriterGenerationContract.containedOwnerGeneration(1L) shouldBe 3L
		SourceWriterGenerationContract.canonicalOwnerGeneration(2L) shouldBe 4L
		SourceWriterGenerationContract.containedOwnerGeneration(2L) shouldBe 5L
	}
}
