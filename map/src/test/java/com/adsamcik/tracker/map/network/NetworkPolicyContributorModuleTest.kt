package com.adsamcik.tracker.map.network

import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class NetworkPolicyContributorModuleTest {

	@Test
	fun `map module contributes only via IntoSet`() {
		val bindingMethod = NetworkPolicyContributorModule::class.java
			.getDeclaredMethod("bindMapTilesContributor", MapTilesPolicyContributor::class.java)

		bindingMethod.getAnnotation(IntoSet::class.java) shouldNotBe null
		NetworkPolicyContributorModule::class.java.declaredMethods
			.any { it.getAnnotation(Multibinds::class.java) != null } shouldBe false
	}
}
