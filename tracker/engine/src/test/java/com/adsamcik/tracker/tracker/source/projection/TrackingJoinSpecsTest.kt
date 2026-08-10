package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackingJoinSpecsTest {
	@Test
	fun `every implicit multi-source consumer has a named bounded contract`() {
		TrackingJoinSpecs.contracts.map(TrackingJoinContract::consumerId) shouldContainExactlyInAnyOrder listOf(
			"pressure-altitude-fusion",
			"activity-inference",
			"wifi-interpolation",
			"cell-presence",
			"ski-classifier",
			"plane-classifier",
			"sailing-classifier",
		)
		TrackingJoinSpecs.contracts.forEach { contract ->
			contract.spec.input.containsKey(contract.spec.primarySource) shouldBe true
			contract.spec.input.values.all { it.maximumAgeMs >= 0 } shouldBe true
			contract.spec.lateCorrectionPolicy shouldBe LateCorrectionPolicy.APPEND_ONLY_CORRECTION
		}
	}

	@Test
	fun `consumer-specific temporal directions match the domain contracts`() {
		TrackingJoinSpecs.byId.getValue("activity-inference").spec.input
			.getValue(SourceKind.LOCATION).direction shouldBe JoinDirection.BEFORE_OR_EQUAL
		TrackingJoinSpecs.byId.getValue("activity-inference").spec.input
			.getValue(SourceKind.STEPS).direction shouldBe JoinDirection.WINDOW_OVERLAP
		TrackingJoinSpecs.byId.getValue("wifi-interpolation").spec.input
			.getValue(SourceKind.LOCATION).direction shouldBe JoinDirection.BRACKET
		TrackingJoinSpecs.byId.getValue("pressure-altitude-fusion").spec.input
			.getValue(SourceKind.PRESSURE).direction shouldBe JoinDirection.WINDOW_OVERLAP
		TrackingJoinSpecs.byId.getValue("ski-classifier").spec.primarySource shouldBe SourceKind.PRESSURE
		TrackingJoinSpecs.byId.getValue("plane-classifier").spec.primarySource shouldBe SourceKind.PRESSURE
		TrackingJoinSpecs.byId.getValue("sailing-classifier").spec.primarySource shouldBe SourceKind.LOCATION
	}
}
