package com.adsamcik.tracker.statistics.ui.compose

import com.adsamcik.tracker.statistics.fragment.AppendUiState
import com.adsamcik.tracker.statistics.fragment.RefreshUiState
import com.adsamcik.tracker.statistics.fragment.StatsHeaderAction
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * Unit tests for StatsScreen sealed interfaces, enums and state types.
 */
class StatsScreenTypesTest {

	// ─── RefreshUiState ──────────────────────────────────────────────────

	@Test
	fun `RefreshUiState Loading is singleton`() {
		RefreshUiState.Loading.shouldBeInstanceOf<RefreshUiState.Loading>()
	}

	@Test
	fun `RefreshUiState Empty is singleton`() {
		RefreshUiState.Empty.shouldBeInstanceOf<RefreshUiState.Empty>()
	}

	@Test
	fun `RefreshUiState Error is singleton`() {
		RefreshUiState.Error.shouldBeInstanceOf<RefreshUiState.Error>()
	}

	@Test
	fun `RefreshUiState Content is singleton`() {
		RefreshUiState.Content.shouldBeInstanceOf<RefreshUiState.Content>()
	}

	// ─── AppendUiState ───────────────────────────────────────────────────

	@Test
	fun `AppendUiState NotLoading is singleton`() {
		AppendUiState.NotLoading.shouldBeInstanceOf<AppendUiState.NotLoading>()
	}

	@Test
	fun `AppendUiState Loading is singleton`() {
		AppendUiState.Loading.shouldBeInstanceOf<AppendUiState.Loading>()
	}

	@Test
	fun `AppendUiState Error is singleton`() {
		AppendUiState.Error.shouldBeInstanceOf<AppendUiState.Error>()
	}

	// ─── StatsHeaderAction ───────────────────────────────────────────────

	@Test
	fun `StatsHeaderAction has three entries`() {
		StatsHeaderAction.entries.size shouldBe 3
	}

	@Test
	fun `StatsHeaderAction values are correct`() {
		StatsHeaderAction.entries shouldBe listOf(
			StatsHeaderAction.Summary,
			StatsHeaderAction.Dates,
			StatsHeaderAction.Wifi,
		)
	}

	// ─── All RefreshUiState values cover all branches ─────────────────────

	@Test
	fun `RefreshUiState exhaustive when check`() {
		val states = listOf(
			RefreshUiState.Loading,
			RefreshUiState.Empty,
			RefreshUiState.Error,
			RefreshUiState.Content,
		)
		states.size shouldBe 4
		states.forEach { state ->
			when (state) {
				RefreshUiState.Loading -> {}
				RefreshUiState.Empty -> {}
				RefreshUiState.Error -> {}
				RefreshUiState.Content -> {}
			}
		}
	}
}
