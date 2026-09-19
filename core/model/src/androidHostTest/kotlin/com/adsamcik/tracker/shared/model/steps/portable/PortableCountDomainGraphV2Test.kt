package com.adsamcik.tracker.shared.model.steps.portable

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PortableCountDomainGraphV2Test {
	@Test
	fun `authenticated bind round trips exact source identities`() {
		val graph = ambientGraph()

		graph.receipts.single().domainIdentity shouldBe opaque('d')
		graph.ownerRevisions.single().receiptIdentity shouldBe graph.receipts.single().identity
		graph.roots.single().ownerIdentity shouldBe opaque('o')
		PortableCountDomainGraphV2(
			graph.identity,
			graph.contentChecksum,
			graph.receipts.toList(),
			graph.ownerRevisions.toList(),
			graph.completenessMarkers.toList(),
			graph.roots.toList(),
		) shouldBe graph
	}

	@Test
	fun `orphan receipt revision gap and post retraction resurrection fail closed`() {
		val graph = ambientGraph()
		shouldThrow<IllegalArgumentException> {
			PortableCountDomainGraphV2.create(
				receipts = graph.receipts,
				ownerRevisions = graph.ownerRevisions.map { it.copy(receiptIdentity = null) },
				completenessMarkers = emptyList(),
				roots = graph.roots,
			)
		}
		val first = graph.ownerRevisions.single()
		val third = first.copy(
			ownerRevision = 3L,
			receiptIdentity = null,
			operation = PortableCountDomainOperation.UNPROVEN,
		)
		shouldThrow<IllegalArgumentException> {
			PortableCountDomainGraphV2.create(
				receipts = graph.receipts,
				ownerRevisions = listOf(first, third),
				completenessMarkers = emptyList(),
				roots = listOf(graph.roots.single().copy(ownerRevision = 3L)),
			)
		}
		val retracted = first.copy(
			ownerRevision = 2L,
			operation = PortableCountDomainOperation.RETRACT,
			receiptIdentity = null,
		)
		val resurrected = first.copy(ownerRevision = 3L)
		shouldThrow<IllegalArgumentException> {
			PortableCountDomainGraphV2.create(
				receipts = listOf(graph.receipts.single(), receipt(3L)),
				ownerRevisions = listOf(first, retracted, resurrected),
				completenessMarkers = emptyList(),
				roots = listOf(graph.roots.single().copy(ownerRevision = 3L)),
			)
		}
	}

	@Test
	fun `legacy v1 conversion is receiptless and explicitly unproven`() {
		val day = ambientDay()
		val v2 = day.withExplicitUnprovenCountDomain()

		v2.countDomainGraph.receipts shouldBe emptyList()
		v2.countDomainGraph.ownerRevisions.single().operation shouldBe
			PortableCountDomainOperation.UNPROVEN
	}

	@Test
	fun `ambient unproven lineage cannot later claim authenticated binding`() {
		val bound = ambientGraph().ownerRevisions.single()
		val unproven = bound.copy(
			operation = PortableCountDomainOperation.UNPROVEN,
			receiptIdentity = null,
		)
		val receipt = receipt(2L)
		val promoted = bound.copy(
			ownerRevision = 2L,
			receiptIdentity = receipt.identity,
		)

		shouldThrow<IllegalArgumentException> {
			PortableCountDomainGraphV2.create(
				receipts = listOf(receipt),
				ownerRevisions = listOf(unproven, promoted),
				completenessMarkers = emptyList(),
				roots = listOf(ambientGraph().roots.single().copy(ownerRevision = 2L)),
			)
		}
	}

	@Test
	fun `ambient day rejects a root from another day`() {
		val day = ambientDay()
		val graph = ambientGraph().let { value ->
			PortableCountDomainGraphV2.create(
				value.receipts,
				value.ownerRevisions,
				value.completenessMarkers,
				value.roots.map { it.copy(containerIdentity = opaque('x')) },
			)
		}

		shouldThrow<IllegalArgumentException> {
			PortableAmbientStepsDayV2(day, graph)
		}
	}

	private fun ambientGraph(): PortableCountDomainGraphV2 {
		val receipt = receipt(1L)
		val owner = PortableCountDomainOwnerRevisionV2(
			ownerKind = PortableCountDomainOwnerKind.AMBIENT_FACT,
			scopeIdentity = opaque('s'),
			ownerIdentity = opaque('o'),
			ownerRevision = 1L,
			operation = PortableCountDomainOperation.BIND,
			receiptIdentity = receipt.identity,
			ownerEffectChecksum = digest('e'),
			linkedAtMs = 10L,
		)
		return PortableCountDomainGraphV2.create(
			receipts = listOf(receipt),
			ownerRevisions = listOf(owner),
			completenessMarkers = emptyList(),
			roots = listOf(
				PortableCountDomainRootV2(
					containerIdentity = ambientDay().identity.asCountIdentity(),
					productIdentity = ambientDay().facts.single().identity.asCountIdentity(),
					ownerKind = PortableCountDomainOwnerKind.AMBIENT_FACT,
					ownerIdentity = owner.ownerIdentity,
					ownerRevision = owner.ownerRevision,
				),
			),
		)
	}

	private fun receipt(revision: Long): PortableCountDomainReceiptV2 =
		PortableCountDomainReceiptV2.create(
			domainIdentity = opaque('d'),
			ownerKind = PortableCountDomainOwnerKind.AMBIENT_FACT,
			scopeIdentity = opaque('s'),
			ownerIdentity = opaque('o'),
			ownerRevision = revision,
			registrationGeneration = 3L,
			collectedDataEpoch = 4L,
			authorityRevision = 5L,
			authorityFingerprint = digest('a'),
			coverage = PortableCountDomainCoverage.AMBIENT_AGGREGATE,
			coverageVersion = 1,
			countDomainVersion = 1,
			effectChecksum = digest('e'),
			completenessEvidenceChecksum = null,
		)

	private fun ambientDay(): PortableAmbientStepsDayV1 {
		val fact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				"fact",
			),
			0L,
			86_400_000L,
			12L,
		)
		return PortableAmbientStepsDayV1.create(
			identity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.DAY,
				"day",
			),
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = 86_400_000L,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			partialCauses = emptyList(),
			retainedStepCount = 12L,
			facts = listOf(fact),
			gaps = emptyList(),
		)
	}

	private fun AmbientStepsPortableOpaqueIdentity.asCountIdentity() =
		PortableCountDomainOpaqueIdentity(value)

	private fun opaque(value: Char) =
		PortableCountDomainOpaqueIdentity("sha256:" + value.toString().repeat(64))

	private fun digest(value: Char) = PortableCountDomainDigest(value.toString().repeat(64))
}
