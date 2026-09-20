package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import io.kotest.matchers.shouldBe
import org.junit.Test

class ImportedPortableStepsCountDomainAuthenticatorTest {
	@Test
	fun `stored rows reconstruct the exact source graph`() {
		val graph = day().withExplicitUnprovenCountDomain().countDomainGraph
		val rows = graph.toImportedRows(
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
		)

		ImportedPortableStepsCountDomainAuthenticator.authenticate(
			rows.graph,
			rows.receipts,
			rows.owners,
			rows.markers,
			rows.roots,
		) shouldBe graph
	}

	@Test
	fun `tampered owner effect cannot authenticate`() {
		val graph = day().withExplicitUnprovenCountDomain().countDomainGraph
		val rows = graph.toImportedRows(
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
		)

		ImportedPortableStepsCountDomainAuthenticator.authenticate(
			rows.graph,
			rows.receipts,
			rows.owners.map { it.copy(ownerEffectChecksum = "f".repeat(64)) },
			rows.markers,
			rows.roots,
		) shouldBe null
	}

	@Test
	fun `terminal owner fence authenticates without retaining product values`() {
		val graph = day().withExplicitUnprovenCountDomain().countDomainGraph
		val owner = graph.ownerRevisions.single()
		val fence = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
			ownerKind = owner.ownerKind.name,
			ownerIdentity = owner.ownerIdentity.value,
			scopeIdentity = owner.scopeIdentity.value,
			latestSourceRevision = owner.ownerRevision,
			latestOwnerEffectChecksum = owner.ownerEffectChecksum.value,
			productKind =
				com.adsamcik.tracker.shared.base.database.data
					.ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			productIdentity = day().identity.value,
			graphIdentity = graph.identity.value,
			fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
			collectedDataEpoch = 9L,
			fencedAtMs = 10L,
		)

		fence.effectChecksum shouldBe
			com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
				.ownerFenceChecksum(fence)
	}

	private fun day(): PortableAmbientStepsDayV1 {
		val fact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				"fact",
			),
			0L,
			86_400_000L,
			3L,
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
			retainedStepCount = 3L,
			facts = listOf(fact),
			gaps = emptyList(),
		)
	}
}
