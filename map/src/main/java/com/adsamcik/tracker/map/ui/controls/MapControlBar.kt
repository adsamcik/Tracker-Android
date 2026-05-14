package com.adsamcik.tracker.map.ui.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.presentation.udf.SearchResultStatus

/**
 * Unified map chrome card that houses the contextual chips (Layers, Dates) and the
 * primary search input inside a single rounded surface. One container means the user
 * parses it as a single "map-control tool," not three floating elements competing.
 *
 * Internal hierarchy is still tiered via tone:
 *  * **Search row** — the card's primary content, full-width input with a leading search
 *    icon. No inner border; the card surface already provides the boundary.
 *  * **Chip row** — compact mini-pills above the search row, slightly quieter tone so the
 *    eye reads "search is primary, chips are contextual." Dim to 40% when search focused.
 *
 * When focused, the search row darkens its background slightly to signal active input
 * without needing a separate modal surface.
 */
@Composable
internal fun MapChromeBar(
	searchQuery: String,
	searchResultStatus: SearchResultStatus,
	searchFocused: Boolean,
	onSearchQueryChange: (String) -> Unit,
	onSearchSubmit: () -> Unit,
	onSearchPaste: () -> Unit,
	onSearchFocusChange: (Boolean) -> Unit,
	activeLayerLabel: String,
	onLayersClick: () -> Unit,
	showQualityChip: Boolean,
	qualityLabel: String,
	onQualityClick: () -> Unit,
	dateRangeLabel: String,
	onDatesClick: () -> Unit,
	layersExpanded: Boolean,
	qualityExpanded: Boolean,
	datesExpanded: Boolean,
	modifier: Modifier = Modifier,
) {
	val chipAlpha by animateFloatAsState(
		targetValue = if (searchFocused) 0.4f else 1f,
		label = "chip_row_alpha",
	)

	// M3 Expressive: lean on tonal elevation over drop shadows. The card floats via its
	// brighter surface tier against the map underneath — no hard shadow needed.
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = RoundedCornerShape(32.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		tonalElevation = 2.dp,
		shadowElevation = 1.dp,
	) {
		Column(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			ContextualChipsRow(
				activeLayerLabel = activeLayerLabel,
				onLayersClick = onLayersClick,
				showQualityChip = showQualityChip,
				qualityLabel = qualityLabel,
				onQualityClick = onQualityClick,
				dateRangeLabel = dateRangeLabel,
				onDatesClick = onDatesClick,
				layersExpanded = layersExpanded,
				qualityExpanded = qualityExpanded,
				datesExpanded = datesExpanded,
				modifier = Modifier
					.fillMaxWidth()
					.alpha(chipAlpha)
					.padding(horizontal = 4.dp),
			)

			SearchRow(
				query = searchQuery,
				resultStatus = searchResultStatus,
				focused = searchFocused,
				onQueryChange = onSearchQueryChange,
				onSubmit = onSearchSubmit,
				onPaste = onSearchPaste,
				onFocusChange = onSearchFocusChange,
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

@Composable
private fun ContextualChipsRow(
	activeLayerLabel: String,
	onLayersClick: () -> Unit,
	showQualityChip: Boolean,
	qualityLabel: String,
	onQualityClick: () -> Unit,
	dateRangeLabel: String,
	onDatesClick: () -> Unit,
	layersExpanded: Boolean,
	qualityExpanded: Boolean,
	datesExpanded: Boolean,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		val layersA11y = stringResource(R.string.map_chip_layers) + ", " + activeLayerLabel
		val qualityA11y = stringResource(R.string.map_quality_label) + ", " + qualityLabel
		val datesA11y = stringResource(R.string.map_chip_dates) + ", " + dateRangeLabel
		ContextualChip(
			icon = Icons.Filled.Layers,
			value = activeLayerLabel,
			contentDescription = layersA11y,
			expanded = layersExpanded,
			onClick = onLayersClick,
			modifier = Modifier
				.weight(1f)
				.testTag("map_layer_picker_chip"),
		)
		if (showQualityChip) {
			QualityContextualChip(
				value = qualityLabel,
				contentDescription = qualityA11y,
				expanded = qualityExpanded,
				onClick = onQualityClick,
				modifier = Modifier
					.weight(0.9f)
					.testTag("map_quality_picker_chip"),
			)
		}
		ContextualChip(
			icon = Icons.Filled.DateRange,
			value = dateRangeLabel,
			contentDescription = datesA11y,
			expanded = datesExpanded,
			onClick = onDatesClick,
			modifier = Modifier
				.weight(1f)
				.testTag("map_date_picker_chip"),
		)
	}
}

@Composable
private fun ContextualChip(
	icon: ImageVector,
	value: String,
	contentDescription: String,
	expanded: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	// M3 Expressive: active state uses primaryContainer (stronger identity accent) and
	// idle uses surfaceContainerHighest so the chip pops against the card below.
	val containerColor = if (expanded) {
		MaterialTheme.colorScheme.primaryContainer
	} else {
		MaterialTheme.colorScheme.surfaceContainerHighest
	}
	val contentColor = if (expanded) {
		MaterialTheme.colorScheme.onPrimaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}
	Surface(
		modifier = modifier
			.height(44.dp)
			.semantics(mergeDescendants = true) {
				this.contentDescription = contentDescription
			},
		onClick = onClick,
		shape = RoundedCornerShape(22.dp),
		color = containerColor,
		contentColor = contentColor,
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
	) {
		// Icon is the verb (what the chip is for); value is the noun (current selection);
		// chevron is the universal "tap to open more." No redundant prefix label — the icon
		// already carries the role, so we keep room for the value to render in full.
		Row(
			modifier = Modifier.padding(start = 14.dp, end = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				modifier = Modifier.size(20.dp),
			)
			Text(
				text = value,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Icon(
				imageVector = Icons.Filled.ArrowDropDown,
				contentDescription = null,
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

@Composable
private fun QualityContextualChip(
	value: String,
	contentDescription: String,
	expanded: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val containerColor = if (expanded) {
		MaterialTheme.colorScheme.primaryContainer
	} else {
		MaterialTheme.colorScheme.surfaceContainerHighest
	}
	val contentColor = if (expanded) {
		MaterialTheme.colorScheme.onPrimaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}
	Surface(
		modifier = modifier
			.height(44.dp)
			.semantics(mergeDescendants = true) {
				this.contentDescription = contentDescription
			},
		onClick = onClick,
		shape = RoundedCornerShape(22.dp),
		color = containerColor,
		contentColor = contentColor,
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
	) {
		Row(
			modifier = Modifier.padding(start = 14.dp, end = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = value,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Icon(
				imageVector = Icons.Filled.ArrowDropDown,
				contentDescription = null,
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

@Composable
private fun SearchRow(
	query: String,
	resultStatus: SearchResultStatus,
	focused: Boolean,
	onQueryChange: (String) -> Unit,
	onSubmit: () -> Unit,
	onPaste: () -> Unit,
	onFocusChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	val hasQuery = query.isNotEmpty()
	val focusRequester = remember { FocusRequester() }
	val borderColor = when (resultStatus) {
		SearchResultStatus.Found -> MaterialTheme.colorScheme.primary
		SearchResultStatus.NotFound -> MaterialTheme.colorScheme.error
		SearchResultStatus.Idle -> Color.Transparent
	}
	// Search row lives *inside* the chrome card. Uses surfaceContainerHighest so it reads
	// as the "primary" zone inside the card. On focus it picks up a primary-tinted container
	// for an M3 Expressive color accent cue.
	val rowColor = if (focused) {
		MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
	} else {
		MaterialTheme.colorScheme.surfaceContainerHighest
	}

	Surface(
		modifier = modifier.height(56.dp),
		shape = RoundedCornerShape(24.dp),
		color = rowColor,
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			IconButton(
				onClick = onSubmit,
				modifier = Modifier.size(48.dp),
			) {
				Icon(
					imageVector = Icons.Filled.Search,
					contentDescription = stringResource(R.string.description_map_search),
					// M3 Expressive accent — primary tint makes the search action feel inviting.
					tint = when {
						borderColor != Color.Transparent -> borderColor
						focused -> MaterialTheme.colorScheme.primary
						else -> MaterialTheme.colorScheme.onSurfaceVariant
					},
				)
			}
			Box(modifier = Modifier.weight(1f)) {
				TextField(
					value = query,
					onValueChange = onQueryChange,
					modifier = Modifier
						.fillMaxWidth()
						.focusRequester(focusRequester)
						.onFocusChanged { state -> onFocusChange(state.isFocused) }
						.testTag("map_search_field"),
					singleLine = true,
					textStyle = MaterialTheme.typography.bodyLarge,
					placeholder = {
						Text(
							text = stringResource(R.string.map_search_placeholder),
							style = MaterialTheme.typography.bodyLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					},
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
					keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
					colors = TextFieldDefaults.colors(
						focusedContainerColor = Color.Transparent,
						unfocusedContainerColor = Color.Transparent,
						disabledContainerColor = Color.Transparent,
						focusedIndicatorColor = Color.Transparent,
						unfocusedIndicatorColor = Color.Transparent,
						disabledIndicatorColor = Color.Transparent,
					),
				)
			}
			AnimatedVisibility(
				visible = !hasQuery,
				enter = fadeIn() + scaleIn(),
				exit = fadeOut() + scaleOut(),
			) {
				IconButton(
					onClick = onPaste,
					modifier = Modifier
						.size(48.dp)
						.testTag("map_search_paste_button"),
				) {
					Icon(
						imageVector = Icons.Filled.ContentPaste,
						contentDescription = stringResource(R.string.map_search_paste),
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			AnimatedVisibility(
				visible = hasQuery,
				enter = fadeIn() + scaleIn(),
				exit = fadeOut() + scaleOut(),
			) {
				IconButton(
					onClick = { onQueryChange("") },
					modifier = Modifier.size(48.dp),
				) {
					Icon(
						imageVector = Icons.Filled.Close,
						contentDescription = stringResource(R.string.map_search_clear),
						tint = MaterialTheme.colorScheme.onSurface,
					)
				}
			}
		}
	}

}
