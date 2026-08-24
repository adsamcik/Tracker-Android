package com.adsamcik.tracker.shared.preferences.tracking

import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.FixedClock
import io.kotest.matchers.shouldBe
import org.junit.Test

class AndroidSourcePolicyEffectiveTimeProviderTest {
	@Test
	fun `policy effective time uses the injected canonical boot clock domain unchanged`() {
		val subject = AndroidSourcePolicyEffectiveTimeProvider(
			BootClockDomainProvider { "android-boot-count:42" },
			FixedClock(fixedTimeMillis = 2_000L, fixedRealtimeNanos = 3_000L),
		)

		subject.now() shouldBe SourcePolicyEffectiveTime(
			bootId = "android-boot-count:42",
			elapsedRealtimeNanos = 3_000L,
			wallTimeMs = 2_000L,
		)
	}
}
