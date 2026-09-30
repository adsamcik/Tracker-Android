package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored format checks only; execution is deferred to convergence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrganizationSourceSelectionCodecTest {
	@Test fun `all exact source shapes round trip without foreign runtime authority`() {
		val selection = listOf(
			OrganizationSourceSelector.LocationFile("a".repeat(64)),
			OrganizationSourceSelector.BackgroundStepsDay("sha256:" + "b".repeat(64)),
			OrganizationSourceSelector.BackgroundRadioArchive(HistorySource.WIFI, "c".repeat(64)),
			OrganizationSourceSelector.BackgroundRadioArchive(HistorySource.CELL, "d".repeat(64)),
		) + HistorySource.entries.filter { it != HistorySource.LOCATION }.map { source ->
			OrganizationSourceSelector.CapturedEntry(source,
				(if (source == HistorySource.STEPS || source == HistorySource.PRESSURE) "sha256:" else "") + "e".repeat(64))
		}
		OrganizationSourceSelectionCodec.decode(OrganizationSourceSelectionCodec.encode(selection)) shouldBe selection
		OrganizationSourceSelectionCodec.encode(emptyList()) shouldBe "[]"
		OrganizationSourceSelectionCodec.decode("[]") shouldBe emptyList()
	}

	@Test fun `Steps and Pressure identities keep their real source prefix and other sources keep raw digests`() {
		val digest = "a".repeat(64)
		val identity = "sha256:$digest"
		val selections = listOf(OrganizationSourceSelector.CapturedEntry(HistorySource.STEPS, identity),
			OrganizationSourceSelector.CapturedEntry(HistorySource.PRESSURE, identity),
			OrganizationSourceSelector.BackgroundStepsDay(identity))
		val encoded = OrganizationSourceSelectionCodec.encode(selections)
		OrganizationSourceSelectionCodec.decode(encoded) shouldBe selections
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.CapturedEntry(HistorySource.STEPS, digest) }
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.CapturedEntry(HistorySource.PRESSURE, digest) }
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.BackgroundStepsDay(digest) }
		listOf("sha256:" + "A".repeat(64), "sha256:" + "a".repeat(63), "sha256:sha256:$digest").forEach { invalid ->
			shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.BackgroundStepsDay(invalid) }
			shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.CapturedEntry(HistorySource.STEPS, invalid) }
			shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.CapturedEntry(HistorySource.PRESSURE, invalid) }
		}
		listOf(HistorySource.ACTIVITY, HistorySource.WIFI, HistorySource.CELL).forEach { source ->
			shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.CapturedEntry(source, identity) }
		}
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.LocationFile(identity) }
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.BackgroundRadioArchive(HistorySource.WIFI, identity) }
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.BackgroundRadioArchive(HistorySource.CELL, identity) }
		listOf("captured-entry", "background-steps-day").forEach { kind ->
			shouldThrow<IllegalArgumentException> {
				OrganizationSourceSelectionCodec.decode("[{\"kind\":\"$kind\",\"source\":\"STEPS\",\"identity\":\"$digest\"}]")
			}
		}
		shouldThrow<IllegalArgumentException> {
			OrganizationSourceSelectionCodec.decode("[{\"kind\":\"captured-entry\",\"source\":\"PRESSURE\",\"identity\":\"$digest\"}]")
		}
	}

	@Test fun `count identity type uniqueness and byte limits reject before storing`() {
		val entry = OrganizationSourceSelector.CapturedEntry(HistorySource.WIFI, "a".repeat(64))
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelectionCodec.encode(listOf(entry, entry)) }
		shouldThrow<IllegalArgumentException> {
			OrganizationSourceSelectionCodec.encode(List(OrganizationSourceSelector.MAX_COUNT + 1) { entry })
		}
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.CapturedEntry(HistorySource.LOCATION, "a".repeat(64)) }
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.BackgroundRadioArchive(HistorySource.STEPS, "a".repeat(64)) }
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelector.LocationFile("not-an-identity") }
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelectionCodec.decode(" ".repeat(OrganizationSourceSelectionCodec.MAX_BYTES + 1)) }
	}

	@Test fun `unknown kinds extra fields wrong source and duplicate selectors never become local intervals`() {
		val identity = "a".repeat(64)
		listOf(
			"[{\"kind\":\"future\",\"source\":\"WIFI\",\"identity\":\"$identity\"}]",
			"[{\"kind\":\"location-file\",\"source\":\"WIFI\",\"identity\":\"$identity\"}]",
			"[{\"kind\":\"background-steps-day\",\"source\":\"CELL\",\"identity\":\"$identity\"}]",
			"[{\"kind\":\"captured-entry\",\"source\":\"WIFI\",\"identity\":\"$identity\",\"fromMs\":0}]",
		).forEach { encoded -> shouldThrow<IllegalArgumentException> { OrganizationSourceSelectionCodec.decode(encoded) } }
		val encoded = OrganizationSourceSelectionCodec.encode(listOf(OrganizationSourceSelector.LocationFile(identity)))
		shouldThrow<IllegalArgumentException> { OrganizationSourceSelectionCodec.decode("[${encoded.drop(1).dropLast(1)},${encoded.drop(1).dropLast(1)}]") }
	}
}
