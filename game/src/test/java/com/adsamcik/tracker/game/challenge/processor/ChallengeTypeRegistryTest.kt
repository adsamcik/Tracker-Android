package com.adsamcik.tracker.game.challenge.processor

import com.adsamcik.tracker.game.challenge.data.ChallengeType
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ChallengeTypeRegistry")
class ChallengeTypeRegistryTest {

	private fun createRegistry(vararg processors: ChallengeProcessor): ChallengeTypeRegistry {
		val map = processors.associateBy { it.type }
		return ChallengeTypeRegistry(map)
	}

	@Nested
	@DisplayName("get")
	inner class Get {
		@Test
		fun `returns registered processor`() {
			val step = StepChallengeProcessor()
			val registry = createRegistry(step)
			registry.get(ChallengeType.Step) shouldBe step
		}

		@Test
		fun `throws for unregistered type`() {
			val registry = createRegistry(StepChallengeProcessor())
			assertThrows<IllegalStateException> {
				registry.get(ChallengeType.Explorer)
			}
		}
	}

	@Nested
	@DisplayName("all and types")
	inner class AllAndTypes {
		@Test
		fun `all returns all processors`() {
			val step = StepChallengeProcessor()
			val speed = SpeedChallengeProcessor()
			val registry = createRegistry(step, speed)
			registry.all.toList() shouldContainExactlyInAnyOrder listOf(step, speed)
		}

		@Test
		fun `types returns all registered types`() {
			val registry = createRegistry(
				StepChallengeProcessor(),
				SpeedChallengeProcessor(),
				ActiveTimeChallengeProcessor(),
			)
			registry.types shouldContainExactlyInAnyOrder setOf(
				ChallengeType.Step,
				ChallengeType.Speed,
				ChallengeType.ActiveTime,
			)
		}

		@Test
		fun `empty registry has no types`() {
			val registry = ChallengeTypeRegistry(emptyMap())
			registry.types shouldBe emptySet()
			registry.all.toList() shouldBe emptyList()
		}
	}
}
