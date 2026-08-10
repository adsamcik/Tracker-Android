package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class EventTimeJoinerTest {
	private val subject = EventTimeJoiner()

	@Test
	fun `before join cannot leak a future observation`() {
		val frame = subject.join(
			primary = candidate("activity", SourceKind.ACTIVITY, 10_000_000L),
			available = listOf(candidate("future-location", SourceKind.LOCATION, 11_000_000L)),
			spec = spec(JoinDirection.BEFORE_OR_EQUAL),
		)

		frame.inputs.getValue(SourceKind.LOCATION).result shouldBe JoinInputResult.FUTURE_REJECTED
		frame.finalization shouldBe JoinFinalization.FINAL_MISSING_INPUT
	}

	@Test
	fun `bracket join preserves both event identities`() {
		val frame = subject.join(
			primary = candidate("wifi", SourceKind.ACTIVITY, 10_000_000L),
			available = listOf(
				candidate("before", SourceKind.LOCATION, 9_000_000L),
				candidate("after", SourceKind.LOCATION, 11_000_000L),
			),
			spec = spec(JoinDirection.BRACKET),
		)

		frame.inputs.getValue(SourceKind.LOCATION).eventIds shouldContainExactly listOf("before", "after")
		frame.finalization shouldBe JoinFinalization.FINAL_COMPLETE
	}

	@Test
	fun `clock-domain mismatch is explicit and frame identity is deterministic`() {
		val primary = candidate("activity", SourceKind.ACTIVITY, 10_000_000L)
		val otherBoot = candidate("location", SourceKind.LOCATION, 9_000_000L, clock = "other-boot")

		val first = subject.join(primary, listOf(otherBoot), spec(JoinDirection.BEFORE_OR_EQUAL))
		val second = subject.join(primary, listOf(otherBoot), spec(JoinDirection.BEFORE_OR_EQUAL))

		first.inputs.getValue(SourceKind.LOCATION).result shouldBe JoinInputResult.CLOCK_DOMAIN_MISMATCH
		first.frameId shouldBe second.frameId
	}

	@Test
	fun `different logical tracking session cannot satisfy a join`() {
		val primary = candidate("activity", SourceKind.ACTIVITY, 10_000_000L, tracking = "first")
		val otherSession = candidate("location", SourceKind.LOCATION, 9_000_000L, tracking = "second")

		val frame = subject.join(primary, listOf(otherSession), spec(JoinDirection.BEFORE_OR_EQUAL))

		frame.inputs.getValue(SourceKind.LOCATION).result shouldBe JoinInputResult.MISSING
	}

	@Test
	fun `quality rejection is explicit`() {
		val input = JoinInputSpec(
			maximumAgeMs = 5_000,
			direction = JoinDirection.BEFORE_OR_EQUAL,
			rejectedQualityFlags = setOf(SourceQualityFlag.CACHED),
		)
		val joinSpec = spec(JoinDirection.BEFORE_OR_EQUAL).copy(
			input = spec(JoinDirection.BEFORE_OR_EQUAL).input + (SourceKind.LOCATION to input),
		)
		val cached = candidate("location", SourceKind.LOCATION, 9_000_000L).copy(
			quality = SourceQuality(flags = setOf(SourceQualityFlag.CACHED)),
		)

		val frame = subject.join(candidate("activity", SourceKind.ACTIVITY, 10_000_000L), listOf(cached), joinSpec)

		frame.inputs.getValue(SourceKind.LOCATION).result shouldBe JoinInputResult.LOW_QUALITY
	}

	private fun spec(direction: JoinDirection) = JoinSpec(
		id = "activity-location",
		primarySource = SourceKind.ACTIVITY,
		input = mapOf(
			SourceKind.ACTIVITY to JoinInputSpec(0, JoinDirection.BEFORE_OR_EQUAL),
			SourceKind.LOCATION to JoinInputSpec(5_000, direction),
		),
		allowedLatenessMs = 1_000,
		missingInputTimeoutMs = 5_000,
		lateCorrectionPolicy = LateCorrectionPolicy.APPEND_ONLY_CORRECTION,
	)

	private fun candidate(
		id: String,
		source: SourceKind,
		timeNanos: Long,
		clock: String = "boot",
		tracking: String? = null,
	) = JoinCandidate(id, source, timeNanos, clock, tracking)
}
