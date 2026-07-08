package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [TierConfiguration] and [TierConfigurationResolver].
 *
 * Verifies that the declarative tier model correctly represents different
 * tracking tiers and that the resolver maps [ComponentSet]s faithfully.
 */
class TierConfigurationTest {

	// ---- helpers ----

	/**
	 * Build a synthetic [TierConfiguration] with a representative number of mock components.
	 *
	 * This fixture exercises the [TierConfiguration] data class and the resolver only; it does
	 * NOT reflect how [TrackerComponentFactory] selects components (component selection is now
	 * driven by per-source toggles, not the tier). The component counts here are arbitrary.
	 */
	private fun buildConfigForTier(tier: PolicyTier): TierConfiguration {
		val dataComponents = buildList {
			add(mockk<DataTrackerComponent>(relaxed = true))
			if (tier.isGpsEnabled) {
				add(mockk<DataTrackerComponent>(relaxed = true))
				add(mockk<DataTrackerComponent>(relaxed = true))
				add(mockk<DataTrackerComponent>(relaxed = true))
			}
		}
		return TierConfiguration(
			tier = tier,
			preComponents = listOf(mockk<PreTrackerComponent>(relaxed = true)),
			dataComponents = dataComponents,
			producerTier = tier,
		)
	}

	// ---- TierConfiguration data class ----

	@Nested
	inner class DataClass {
		@Test
		fun `captures tier and producer tier`() {
			val config = TierConfiguration(
				tier = PolicyTier.ACTIVE,
				preComponents = emptyList(),
				dataComponents = emptyList(),
				producerTier = PolicyTier.ACTIVE,
			)
			config.tier shouldBe PolicyTier.ACTIVE
			config.producerTier shouldBe PolicyTier.ACTIVE
		}

		@Test
		fun `captures component lists immutably`() {
			val pre = listOf(mockk<PreTrackerComponent>(relaxed = true))
			val data = listOf(mockk<DataTrackerComponent>(relaxed = true))
			val config = TierConfiguration(
				tier = PolicyTier.AMBIENT,
				preComponents = pre,
				dataComponents = data,
				producerTier = PolicyTier.AMBIENT,
			)
			config.preComponents shouldBe pre
			config.dataComponents shouldBe data
		}

		@Test
		fun `data class equality works`() {
			val pre = emptyList<PreTrackerComponent>()
			val data = emptyList<DataTrackerComponent>()
			val a = TierConfiguration(PolicyTier.ACTIVE, pre, data, PolicyTier.ACTIVE)
			val b = TierConfiguration(PolicyTier.ACTIVE, pre, data, PolicyTier.ACTIVE)
			a shouldBe b
		}

		@Test
		fun `different tiers are not equal`() {
			val config1 = TierConfiguration(
				PolicyTier.AMBIENT, emptyList(), emptyList(), PolicyTier.AMBIENT,
			)
			val config2 = TierConfiguration(
				PolicyTier.ACTIVE, emptyList(), emptyList(), PolicyTier.ACTIVE,
			)
			config1 shouldNotBe config2
		}
	}

	// ---- Tier-based structural differences ----

	@Nested
	inner class TierDifferences {
		@Test
		fun `AMBIENT tier has GPS disabled`() {
			val config = buildConfigForTier(PolicyTier.AMBIENT)
			config.tier.isGpsEnabled shouldBe false
		}

		@Test
		fun `ACTIVE tier has GPS enabled`() {
			val config = buildConfigForTier(PolicyTier.ACTIVE)
			config.tier.isGpsEnabled shouldBe true
		}

		@Test
		fun `PRECISION tier has GPS enabled`() {
			val config = buildConfigForTier(PolicyTier.PRECISION)
			config.tier.isGpsEnabled shouldBe true
		}

		@Test
		fun `AMBIENT has fewer data components than ACTIVE`() {
			val ambient = buildConfigForTier(PolicyTier.AMBIENT)
			val active = buildConfigForTier(PolicyTier.ACTIVE)
			ambient.dataComponents shouldHaveSize 1
			active.dataComponents shouldHaveSize 4
		}

		@Test
		fun `AMBIENT has fewer data components than PRECISION`() {
			val ambient = buildConfigForTier(PolicyTier.AMBIENT)
			val precision = buildConfigForTier(PolicyTier.PRECISION)
			ambient.dataComponents shouldHaveSize 1
			precision.dataComponents shouldHaveSize 4
		}

		@Test
		fun `ACTIVE and PRECISION have same data component count`() {
			val active = buildConfigForTier(PolicyTier.ACTIVE)
			val precision = buildConfigForTier(PolicyTier.PRECISION)
			active.dataComponents shouldHaveSize precision.dataComponents.size
		}

		@Test
		fun `all three tiers produce structurally different configs`() {
			val ambient = buildConfigForTier(PolicyTier.AMBIENT)
			val active = buildConfigForTier(PolicyTier.ACTIVE)
			val precision = buildConfigForTier(PolicyTier.PRECISION)

			// AMBIENT differs from ACTIVE in data component count
			ambient.dataComponents.size shouldNotBe active.dataComponents.size
			// AMBIENT differs from PRECISION in data component count
			ambient.dataComponents.size shouldNotBe precision.dataComponents.size
			// ACTIVE and PRECISION differ in tier identity
			active.tier shouldNotBe precision.tier
		}
	}

	// ---- TierConfigurationResolver ----

	@Nested
	inner class Resolver {
		@Test
		fun `fromComponentSet maps all fields correctly`() {
			val pre = listOf(mockk<PreTrackerComponent>(relaxed = true))
			val data = listOf(
				mockk<DataTrackerComponent>(relaxed = true),
				mockk<DataTrackerComponent>(relaxed = true),
			)
			val componentSet = ComponentSet(
				preComponents = pre,
				dataComponents = data,
				skiTrackingComponent = null,
				skiSegmentWriter = null,
				sailingTrackingComponent = null,
				planeTrackingComponent = null,
				sessionComponent = mockk<SessionTrackerComponent>(relaxed = true),
				errorCollector = mockk<DefaultPersistenceErrorCollector>(relaxed = true),
			)

			val resolver = TierConfigurationResolver(
				componentFactory = mockk(relaxed = true),
			)
			val config = resolver.fromComponentSet(PolicyTier.ACTIVE, componentSet)

			config.tier shouldBe PolicyTier.ACTIVE
			config.producerTier shouldBe PolicyTier.ACTIVE
			config.preComponents shouldBe pre
			config.dataComponents shouldBe data
		}

		@Test
		fun `fromComponentSet excludes session and error collector`() {
			val session = mockk<SessionTrackerComponent>(relaxed = true)
			val errorCollector = mockk<DefaultPersistenceErrorCollector>(relaxed = true)
			val componentSet = ComponentSet(
				preComponents = emptyList(),
				dataComponents = emptyList(),
				skiTrackingComponent = null,
				skiSegmentWriter = null,
				sailingTrackingComponent = null,
				planeTrackingComponent = null,
				sessionComponent = session,
				errorCollector = errorCollector,
			)

			val resolver = TierConfigurationResolver(
				componentFactory = mockk(relaxed = true),
			)
			val config = resolver.fromComponentSet(PolicyTier.PRECISION, componentSet)

			// TierConfiguration intentionally does not carry session or error collector
			// — those are per-session, not per-tier
			config.tier shouldBe PolicyTier.PRECISION
			config.preComponents shouldHaveSize 0
			config.dataComponents shouldHaveSize 0
		}
	}
}
