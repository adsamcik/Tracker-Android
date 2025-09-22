package com.adsamcik.tracker.statistics.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsamcik.tracker.statistics.fragment.*
import com.adsamcik.tracker.shared.base.data.TrackerSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Instrumented test verifying refresh -> content transition with a fake PagingSource
 * and that rows render for loaded data.
 */
@RunWith(AndroidJUnit4::class)
class StatsPagingIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private class FakeSessionPagingSource : PagingSource<Int, TrackerSession>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TrackerSession> {
            val page = params.key ?: 0
            val pageSize = params.loadSize
            // Generate deterministic fake data
            val items = List(pageSize) { idx ->
                TrackerSession(
                    id = (page * pageSize + idx).toLong() + 1,
                    start = 1000L * (idx + 1),
                    end = 1000L * (idx + 2),
                    steps = 10 * (idx + 1),
                )
            }
            val nextKey = if (page >= 1) null else page + 1 // single additional page
            return LoadResult.Page(
                data = items,
                prevKey = if (page == 0) null else page - 1,
                nextKey = nextKey
            )
        }
        override fun getRefreshKey(state: PagingState<Int, TrackerSession>): Int? = null
    }

    @Composable
    private fun TestRoute() {
        val pager = Pager(PagingConfig(pageSize = 5, enablePlaceholders = false)) { FakeSessionPagingSource() }
        val items = pager.flow.collectAsLazyPagingItems()
        val refreshState = when(val ls = items.loadState.refresh){
            is androidx.paging.LoadState.Loading -> RefreshUiState.Loading
            is androidx.paging.LoadState.Error -> RefreshUiState.Error
            is androidx.paging.LoadState.NotLoading -> if (items.itemCount==0) RefreshUiState.Empty else RefreshUiState.Content
        }
        val appendState = when(items.loadState.append){
            is androidx.paging.LoadState.Loading -> AppendUiState.Loading
            is androidx.paging.LoadState.Error -> AppendUiState.Error
            is androidx.paging.LoadState.NotLoading -> AppendUiState.NotLoading
        }
        StatsScreen(
            refreshState = refreshState,
            appendState = appendState,
            sessions = items,
            onRetry = { items.retry() },
            onShowSummary = {},
            onShowWeek = {},
            onOpenWifi = {},
        )
    }

    @Test
    fun paging_refresh_to_content_rendersSessions() {
        composeRule.setContent { TestRoute() }
        // Advance until idle so initial load completes
        composeRule.waitForIdle()
        // We expect at least one session row; due to paging load we should have 5
        composeRule.onAllNodesWithTag("stats_session_row").assertCountEquals(5)
    }
}
