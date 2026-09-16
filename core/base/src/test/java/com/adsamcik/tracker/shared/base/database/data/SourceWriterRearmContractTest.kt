package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceWriterRearmContractTest {
	@Test
	fun `repeated cycles preserve exact candidate and contained owner mapping`() {
		var binding = capability.binding(1L)

		repeat(100) { index ->
			val generation = index + 1L
			binding.bindingGeneration shouldBe generation
			binding.canonicalOwnerGeneration shouldBe generation * 2L
			binding.containedOwnerGeneration shouldBe generation * 2L + 1L
			binding.matchesCanonicalActivation(
				binding.canonicalActivation(generation + 10L),
				generation + 10L,
			) shouldBe true
			binding.matchesContainedOwner(binding.containedDestinationOwner()) shouldBe true
			binding = binding.next()
		}
	}

	@Test
	fun `old facts remain resolvable and deletable by their original provenance`() {
		val first = capability.binding(1L)
		val oldProvenance = first.historicalProvenance()
		val declaration = supportingDeclaration()
		val current = capability.binding(8L)

		current.bindingGeneration shouldBe 8L
		capability.bindingForHistoricalProvenance(oldProvenance) shouldBe first
		declaration.readers.supportsHistoricalReads(oldProvenance) shouldBe true
		declaration.deletion.supportsHistoricalDeletion(oldProvenance) shouldBe true
		capability.bindingForHistoricalProvenance(
			oldProvenance.copy(writerOwnerGeneration = first.containedOwnerGeneration),
		).shouldBeNull()
	}

	@Test
	fun `canonical activation matching rejects every rollback identity mismatch`() {
		val binding = capability.binding(3L)
		val exact = binding.canonicalActivation(41L)

		binding.matchesCanonicalActivation(exact, 41L) shouldBe true
		binding.matchesCanonicalActivation(exact.copy(sourceKind = SOURCE + 1), 41L) shouldBe false
		binding.matchesCanonicalActivation(
			exact.copy(writerBindingGeneration = exact.writerBindingGeneration + 1L),
			41L,
		) shouldBe false
		binding.matchesCanonicalActivation(exact.copy(writerOwner = "WRONG_OWNER"), 41L) shouldBe false
		binding.matchesCanonicalActivation(
			exact.copy(writerOwnerGeneration = exact.writerOwnerGeneration + 1L),
			41L,
		) shouldBe false
		binding.matchesCanonicalActivation(
			exact.copy(writerProjectionId = "wrong-projection"),
			41L,
		) shouldBe false
		binding.matchesCanonicalActivation(
			exact.copy(writerProjectionVersion = exact.writerProjectionVersion + 1),
			41L,
		) shouldBe false
		binding.matchesCanonicalActivation(
			exact.copy(productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW),
			41L,
		) shouldBe false
		binding.matchesCanonicalActivation(
			exact.copy(activationRevision = exact.activationRevision + 1L),
			41L,
		) shouldBe false
	}

	@Test
	fun `exact completed deletion input yields only the next binding`() {
		val retired = capability.binding(7L)
		val exact = authorityInput(retired, activationRevision = 91L)

		capability.nextBindingAfter(exact) shouldBe capability.binding(8L)
		capability.nextBindingAfter(
			exact.copy(
				containedDestinationOwner =
					exact.containedDestinationOwner.copy(owner = "WRONG_CONTAINED_OWNER"),
			),
		).shouldBeNull()
		capability.nextBindingAfter(
			exact.copy(
				retiredCanonicalActivation =
					exact.retiredCanonicalActivation.copy(writerProjectionId = "wrong-projection"),
			),
		).shouldBeNull()
		capability.nextBindingAfter(
			exact.copy(
				retiredCanonicalActivation = exact.retiredCanonicalActivation.copy(
					productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				),
			),
		).shouldBeNull()
	}

	@Test
	fun `partial or unsupported source declarations fail closed`() {
		val retired = capability.binding(1L)
		val input = authorityInput(retired)
		val unsupportedSource = SOURCE + 1
		val partial = supportingDeclaration(
			transfer = SourceWriterRearmTransferSupport { false },
		)
		val explicit = SourceWriterRearmSupportResolver { source ->
			supportingDeclaration().takeIf { source == SOURCE }
		}

		SourceWriterRearmSupportResolver.NONE.declarationFor(SOURCE).shouldBeNull()
		explicit.declarationFor(unsupportedSource).shouldBeNull()
		partial.nextBindingIfFullySupported(input).shouldBeNull()
	}

	@Test
	fun `unavailable full deletion authority never executes rearm`() = runTest {
		var executed = false
		val input = authorityInput(capability.binding(1L))

		val result = SourceWriterFullDeletionRearmAuthority.UNAVAILABLE.runIfAuthorized(input) {
			executed = true
			"unexpected"
		}

		result.shouldBeNull()
		executed shouldBe false
	}

	@Test
	fun `complete declaration passes exact next binding to source authority`() = runTest {
		val input = authorityInput(capability.binding(4L), activationRevision = 55L)
		val declaration = supportingDeclaration()

		declaration.runIfFullySupportedAndAuthorized(input) { next ->
			next
		} shouldBe capability.binding(5L)
	}

	@Test
	fun `unrepresentable successor fails closed before source authority`() = runTest {
		var authorized = false
		val retired = capability.binding(Long.MAX_VALUE / 2L)
		val input = authorityInput(retired)
		val declaration = supportingDeclaration(
			authority = object : SourceWriterFullDeletionRearmAuthority {
				override suspend fun <T : Any> runIfAuthorized(
					input: SourceWriterRearmAuthorityInput,
					operation: suspend () -> T,
				): T? {
					authorized = true
					return operation()
				}
			},
		)

		declaration.runIfFullySupportedAndAuthorized(input) { it }.shouldBeNull()
		authorized shouldBe false
	}

	private fun authorityInput(
		retired: SourceWriterGenerationBinding,
		activationRevision: Long = 17L,
	): SourceWriterRearmAuthorityInput = SourceWriterRearmAuthorityInput(
		retiredCanonicalActivation = retired.canonicalActivation(activationRevision),
		containedDestinationOwner = retired.containedDestinationOwner(),
		completedFullDeletion = SourceWriterCompletedFullDeletion(
			collectedDataEpoch = 5L,
			sourceDeletionGeneration = 3L,
			sourceEvidenceRevision = 9L,
			deletedSourceEventHighWaterOrdinal = 100L,
			completedAtMs = 1_000L,
		),
	)

	private fun supportingDeclaration(
		transfer: SourceWriterRearmTransferSupport = SourceWriterRearmTransferSupport { provenance ->
			capability.bindingForHistoricalProvenance(provenance) != null
		},
		authority: SourceWriterFullDeletionRearmAuthority =
			object : SourceWriterFullDeletionRearmAuthority {
				override suspend fun <T : Any> runIfAuthorized(
					input: SourceWriterRearmAuthorityInput,
					operation: suspend () -> T,
				): T? = operation()
			},
	): SourceWriterRearmSupportDeclaration {
		val supportsProvenance: (SourceWriterHistoricalProvenance) -> Boolean = { provenance ->
			capability.bindingForHistoricalProvenance(provenance) != null
		}
		return SourceWriterRearmSupportDeclaration(
			capability = capability,
			writer = SourceWriterRearmWriterSupport { binding ->
				binding == capability.binding(binding.bindingGeneration)
			},
			facts = SourceWriterRearmFactsSupport(supportsProvenance),
			readers = SourceWriterRearmReaderSupport(supportsProvenance),
			maintenance = SourceWriterRearmMaintenanceSupport(supportsProvenance),
			transfer = transfer,
			deletion = SourceWriterRearmDeletionSupport(supportsProvenance),
			fullDeletionAuthority = authority,
		)
	}

	private companion object {
		const val SOURCE = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		val capability = SourceWriterRearmCapability(
			sourceKind = SOURCE,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			candidateOwner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
			containedOwner = SourceDestinationOwnerEntity.OWNER_CONTAINED_ACTIVITY_SESSION_FACTS,
			projectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
		)
	}
}
