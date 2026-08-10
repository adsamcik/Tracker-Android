package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.di.SourcePipelineModule
import com.adsamcik.tracker.tracker.source.projection.LocationDomainProjection
import com.adsamcik.tracker.tracker.source.projection.TrackingJoinSpecs
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.Test

class ConsumerMigrationRegistryPhase4Test {
	@Test
	fun `real joined consumers are event canonical after legacy retirement`() {
		val registry = SourcePipelineModule.provideConsumerMigrationRegistry()
		val joinedConsumerIds = TrackingJoinSpecs.contracts.map { it.consumerId }.toSet()
		val locationConsumerIds = setOf(
			"route-location-persistence",
			"location-speed",
			"location-altitude",
			"location-policy-evidence",
		)

		registry.entries().map(ConsumerMigration::id) shouldContainExactlyInAnyOrder
			(joinedConsumerIds + locationConsumerIds)
		registry.entries().forEach { migration ->
			migration.canonicalWriter shouldBe CanonicalWriter.EVENT_PROJECTION
			migration.stableOutputIdentity.isNotBlank() shouldBe true
		}
		registry.entries().filter { it.id in joinedConsumerIds }.forEach { migration ->
			migration.targetInput.startsWith("JoinedFrame(") shouldBe true
		}
		registry.entries().filter { it.id in locationConsumerIds }.forEach { migration ->
			migration.targetInput.startsWith(LocationDomainProjection.ID) shouldBe true
		}
	}
}
