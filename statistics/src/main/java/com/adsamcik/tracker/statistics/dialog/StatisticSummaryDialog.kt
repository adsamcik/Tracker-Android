package com.adsamcik.tracker.statistics.dialog

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.shared.base.R as BaseR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Statistic summary dialog to display summary data over a period of time.
 * Compose replacement for MaterialDialog-based implementation.
 */
class StatisticSummaryDialog : CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	/**
	 * Show dialog and lazily load content by calling [dataLoader] function.
	 *
	 * @param context Context
	 * @param titleRes Title string resource
	 * @param dataLoader Asynchronously called function that returns stat collection
	 */
	fun show(
		context: Context,
		@StringRes titleRes: Int,
		dataLoader: (context: Context) -> Collection<Stat>
	) {
		val activity = context as? androidx.activity.ComponentActivity
			?: throw IllegalArgumentException("Context must be ComponentActivity for Compose dialog")
		
		val decor = activity.window.decorView as? android.view.ViewGroup
			?: throw IllegalStateException("Cannot access window decorView")

		val host = ComposeView(context).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
			setContent {
				var isLoading by remember { mutableStateOf(true) }
				var stats by remember { mutableStateOf<List<Stat>>(emptyList()) }
				var isVisible by remember { mutableStateOf(true) }

				LaunchedEffect(Unit) {
					val statData = withContext(Dispatchers.Default) {
						dataLoader(context)
					}
					stats = statData.toList()
					isLoading = false
				}

				if (isVisible) {
					AlertDialog(
						modifier = Modifier.testTag("statisticSummaryDialog"),
						onDismissRequest = { isVisible = false },
						title = { Text(text = stringResource(titleRes)) },
						text = {
							if (isLoading) {
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.Center,
									verticalAlignment = Alignment.CenterVertically
								) {
									CircularProgressIndicator(modifier = Modifier.size(32.dp))
									Spacer(Modifier.width(16.dp))
									Text(text = "Loading...")
								}
							} else {
								LazyColumn(
									modifier = Modifier
										.fillMaxWidth()
										.heightIn(max = 400.dp)
								) {
									items(stats) { stat ->
										Row(
											modifier = Modifier
												.fillMaxWidth()
												.padding(vertical = 4.dp),
											horizontalArrangement = Arrangement.SpaceBetween
										) {
											Text(
												text = stringResource(stat.nameRes),
												style = MaterialTheme.typography.bodyMedium,
												modifier = Modifier.weight(1f)
											)
											Text(
												text = stat.data.toString(),
												style = MaterialTheme.typography.bodyMedium
											)
										}
									}
								}
							}
						},
						confirmButton = {
							TextButton(
								onClick = { isVisible = false }
							) {
								Text(text = stringResource(BaseR.string.generic_ok))
							}
						},
						dismissButton = {}
					)
				} else {
					// Remove host view when dialog is dismissed
					LaunchedEffect(Unit) {
						decor.removeView(this@apply)
						job.cancel()
					}
				}
			}
		}
		decor.addView(host)
	}
}
