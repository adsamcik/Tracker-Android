package com.adsamcik.tracker.map.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import com.adsamcik.tracker.map.presentation.udf.LegendItem
import com.adsamcik.tracker.map.ui.getLayerIcon
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * Popover summoned from the Layers chip on the map control bar. Shows layers as a
 * 2-column grid of full-width tiles so every option has a comfortable 56dp tap target
 * and no title ever truncates. Heatmap render quality is intentionally owned by the
 * separate Quality chip so this list stays focused on overlay selection.
 *
 * Dismissal: tap the scrim or press back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayerPickerPopover(
	layers: List<LayerDescriptor>,
	activeLayerIds: ImmutableSet<String>,
	activeLegend: ImmutableList<LegendItem>,
	onLayerSelected: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	Popup(
		onDismissRequest = onDismiss,
		properties = PopupProperties(
			focusable = true,
			dismissOnBackPress = true,
			dismissOnClickOutside = true,
		),
	) {
		val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
		// Full-width scrim that dims the map but leaves the nav bar tappable. We absorb taps
		// on the scrim to dismiss; the popover content blocks clicks from propagating through.
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.fillMaxHeight()
				.background(Color.Black.copy(alpha = 0.32f))
				.clickable(onClick = onDismiss),
			contentAlignment = Alignment.BottomCenter,
		) {
			Surface(
				modifier = Modifier
					.fillMaxWidth()
					.padding(
						start = 16.dp,
						end = 16.dp,
						// Leave room for the nav bar + the unified chrome card (~118dp tall) so
						// the popover anchors above the card, not behind it.
						bottom = navBarInset + 128.dp,
					)
					.clickable(
						enabled = false,
						onClick = {},
					),
				// M3 Expressive: large shape token for feature surfaces, tonal elevation over
				// drop shadow for a softer, more paper-like floatation.
				shape = RoundedCornerShape(32.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				tonalElevation = 4.dp,
				shadowElevation = 3.dp,
			) {
				Column(
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp),
				) {
					Text(
						text = stringResource(R.string.map_layers_title),
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
					)
					LayerGrid(
						layers = layers,
						activeLayerIds = activeLayerIds,
						onLayerSelected = { id ->
							onLayerSelected(id)
							onDismiss()
						},
					)

					if (activeLegend.isNotEmpty()) {
						LegendStrip(activeLegend)
					}

				}
			}
		}
	}
}

@Composable
private fun LayerGrid(
	layers: List<LayerDescriptor>,
	activeLayerIds: ImmutableSet<String>,
	onLayerSelected: (String) -> Unit,
) {
	val rows = remember(layers) { layers.chunked(2) }
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		rows.forEach { rowLayers ->
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				rowLayers.forEach { layer ->
					val isSelected = if (layer.id == "none") {
						activeLayerIds.isEmpty()
					} else {
						activeLayerIds.contains(layer.id)
					}
					LayerTile(
						layer = layer,
						selected = isSelected,
						onClick = { onLayerSelected(layer.id) },
						modifier = Modifier.weight(1f),
					)
				}
				// Pad final odd row so the last tile keeps its natural width.
				if (rowLayers.size == 1) {
					Box(modifier = Modifier.weight(1f))
				}
			}
		}
	}
}

@Composable
private fun LayerTile(
	layer: LayerDescriptor,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val title = remember(layer.id) {
		try {
			context.getString(layer.titleRes)
		} catch (e: Exception) {
			Reporter.w("LayerPickerPopover", "title lookup failed for ${layer.id}: ${e.message}")
			layer.id
		}
	}
	val containerColor = if (selected) {
		MaterialTheme.colorScheme.primaryContainer
	} else {
		MaterialTheme.colorScheme.surfaceContainerHighest
	}
	val contentColor = if (selected) {
		MaterialTheme.colorScheme.onPrimaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}
	Surface(
		modifier = modifier.height(68.dp),
		onClick = onClick,
		// M3 Expressive: 20dp corners feel chunkier and more distinctive than the old 16dp.
		shape = RoundedCornerShape(20.dp),
		color = containerColor,
		contentColor = contentColor,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(
				imageVector = getLayerIcon(layer.id),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
			)
			Text(
				text = title,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (selected) {
				Icon(
					imageVector = Icons.Filled.Check,
					contentDescription = null,
					modifier = Modifier.size(18.dp),
				)
			}
		}
	}
}

@Composable
private fun LegendStrip(legend: ImmutableList<LegendItem>) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(
			text = stringResource(R.string.map_legend_label),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(10.dp)
				.clip(RoundedCornerShape(5.dp)),
		) {
			legend.forEach { item ->
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.background(Color(item.color)),
				)
			}
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			legend.forEach { item ->
				Text(
					text = item.labelRes?.let { stringResource(it) } ?: item.label,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

