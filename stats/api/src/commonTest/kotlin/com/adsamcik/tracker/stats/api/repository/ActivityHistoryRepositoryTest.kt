package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ActivityHistoryRepositoryTest {
	@Test
	fun `ready history requires truthful nonempty content`() {
		shouldThrow<IllegalArgumentException> {
			entry(
				state = ActivityHistoryProductState.READY,
				coverage = ActivityHistoryCoverage.COMPLETE,
				activeTime = null,
				fragments = emptyList(),
			)
		}
	}

	@Test
	fun `settled ready history may truthfully retain partial provider coverage`() {
		val gap = ActivityHistoryFragment.Gap(
			storedZoneId = "UTC",
			reason = ActivityHistoryGapReason.PROVIDER_DISCONTINUITY,
			durationNanos = 1L,
		)

		entry(
			state = ActivityHistoryProductState.READY,
			coverage = ActivityHistoryCoverage.PARTIAL,
			activeTime = ActivityActiveTime(0L, 0L, 0L, 1L),
			fragments = listOf(gap),
			causes = setOf(ActivityHistoryCause.PROVIDER_GAP),
		).fragments shouldBe listOf(gap)
	}

	@Test
	fun `unavailable history cannot fabricate zero durations or fragments`() {
		shouldThrow<IllegalArgumentException> {
			entry(
				state = ActivityHistoryProductState.UNAVAILABLE,
				coverage = ActivityHistoryCoverage.NONE,
				activeTime = ActivityActiveTime(0L, 0L, 0L, 0L),
				fragments = emptyList(),
				causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
			)
		}
	}

	@Test
	fun `failed history requires an integrity cause and hides content`() {
		shouldThrow<IllegalArgumentException> {
			entry(
				state = ActivityHistoryProductState.FAILED,
				coverage = ActivityHistoryCoverage.NONE,
				activeTime = null,
				fragments = emptyList(),
				causes = setOf(ActivityHistoryCause.PROVIDER_GAP),
			)
		}
	}

	@Test
	fun `existing history remains local unless an imported origin is explicit`() {
		val local = entry(
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
		)

		local.origin shouldBe ActivityHistoryOrigin.LOCAL
		local.selection shouldBe ActivityHistorySelection.Local(local.key)
		local.copy(origin = ActivityHistoryOrigin.IMPORTED).selection shouldBe null
		shouldThrow<IllegalArgumentException> {
			local.copy(
				origin = ActivityHistoryOrigin.IMPORTED,
				capturesOnlyActivity = true,
			)
		}
	}

	@Test
	fun `imported selection binds exact tuple hierarchy and read snapshot`() {
		val local = entry(
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
		)
		val selected = importedSelection(local.key)
		val imported = local.copy(
			origin = ActivityHistoryOrigin.IMPORTED,
			importedSelection = selected,
		)

		imported.selection shouldBe ActivityHistorySelection.Imported(selected)
		selected.identity shouldBe ActivityImportedHistoryIdentity("a".repeat(64))
		selected.importRevision shouldBe 3L
		selected.contentChecksum shouldBe ActivityImportedHistoryDigest("e".repeat(64))
		selected.readSnapshot shouldBe ActivityImportedHistoryReadSnapshot(7L, 11L)
		shouldThrow<IllegalArgumentException> {
			local.copy(importedSelection = selected)
		}
		shouldThrow<IllegalArgumentException> {
			imported.copy(
				importedSelection = selected.copy(key = ActivityHistoryEntryKey("different")),
			)
		}
	}

	@Test
	fun `imported selection rejects forged opaque shape and colliding hierarchy`() {
		shouldThrow<IllegalArgumentException> {
			ActivityImportedHistoryIdentity("not-a-digest")
		}
		val selected = importedSelection(ActivityHistoryEntryKey("opaque"))
		shouldThrow<IllegalArgumentException> {
			selected.copy(
				windowIdentities = listOf(selected.runDeletionScopes.single().runIdentity),
			)
		}
		shouldThrow<IllegalArgumentException> {
			selected.copy(
				runDeletionScopes = listOf(
					selected.runDeletionScopes.single().copy(
						deletionScopeDigest = ActivityImportedHistoryDeletionScopeDigest(
							selected.identity.value,
						),
					),
				),
			)
		}
	}

	private fun importedSelection(
		key: ActivityHistoryEntryKey,
	) = ActivityImportedHistorySelection(
		key = key,
		identity = ActivityImportedHistoryIdentity("a".repeat(64)),
		importRevision = 3L,
		contentChecksum = ActivityImportedHistoryDigest("e".repeat(64)),
		runDeletionScopes = listOf(
			ActivityImportedHistoryRunDeletionScope(
				runIdentity = ActivityImportedHistoryIdentity("b".repeat(64)),
				deletionScopeDigest = ActivityImportedHistoryDeletionScopeDigest("c".repeat(64)),
			),
		),
		windowIdentities = listOf(ActivityImportedHistoryIdentity("d".repeat(64))),
		readSnapshot = ActivityImportedHistoryReadSnapshot(7L, 11L),
	)

	private fun entry(
		state: ActivityHistoryProductState,
		coverage: ActivityHistoryCoverage,
		activeTime: ActivityActiveTime?,
		fragments: List<ActivityHistoryFragment>,
		causes: Set<ActivityHistoryCause> = emptySet(),
	) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("opaque"),
		startTime = EpochMs(1L),
		endTime = EpochMs(2L),
		storedZoneIds = setOf("UTC"),
		state = state,
		coverage = coverage,
		activeTime = activeTime,
		fragments = fragments,
		causes = causes,
	)
}
