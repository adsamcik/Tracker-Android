package com.adsamcik.tracker.testing.accessibility

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimum touch target size per Material Design / WCAG guidelines.
 * All interactive elements should be at least 48dp x 48dp.
 */
private val MIN_TOUCH_TARGET_SIZE = 48.dp

/**
 * Asserts that the node has a minimum touch target size of 48dp x 48dp.
 * This is required for accessibility compliance (WCAG, Material Design guidelines).
 *
 * @param minWidth Minimum width requirement (default: 48dp)
 * @param minHeight Minimum height requirement (default: 48dp)
 * @return The original [SemanticsNodeInteraction] for chaining
 * @throws AssertionError if the node is smaller than the minimum size
 */
fun SemanticsNodeInteraction.assertMinTouchTargetSize(
	minWidth: Dp = MIN_TOUCH_TARGET_SIZE,
	minHeight: Dp = MIN_TOUCH_TARGET_SIZE
): SemanticsNodeInteraction {
	val node = fetchSemanticsNode("Failed to fetch semantics node for touch target size assertion")
	val bounds = node.boundsInRoot
	val density = node.layoutInfo.density

	val widthDp = with(density) { bounds.width.toDp() }
	val heightDp = with(density) { bounds.height.toDp() }

	if (widthDp < minWidth) {
		throw AssertionError(
			"Touch target width $widthDp is less than minimum required $minWidth. " +
				"Interactive elements must be at least ${MIN_TOUCH_TARGET_SIZE.value}dp wide for accessibility."
		)
	}

	if (heightDp < minHeight) {
		throw AssertionError(
			"Touch target height $heightDp is less than minimum required $minHeight. " +
				"Interactive elements must be at least ${MIN_TOUCH_TARGET_SIZE.value}dp tall for accessibility."
		)
	}

	return this
}

/**
 * Asserts that the node has a content description set.
 * Content descriptions are required for screen reader accessibility.
 *
 * @return The original [SemanticsNodeInteraction] for chaining
 * @throws AssertionError if no content description is set
 */
fun SemanticsNodeInteraction.assertHasContentDescription(): SemanticsNodeInteraction {
	return assert(
		SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
	) {
		"Expected node to have a content description for accessibility, but none was set. " +
			"Add contentDescription to make this element accessible to screen readers."
	}
}

/**
 * Asserts that the node has a non-empty content description.
 *
 * @return The original [SemanticsNodeInteraction] for chaining
 * @throws AssertionError if content description is missing or empty
 */
fun SemanticsNodeInteraction.assertHasNonEmptyContentDescription(): SemanticsNodeInteraction {
	assertHasContentDescription()

	val node = fetchSemanticsNode("Failed to fetch semantics node for content description assertion")
	val contentDescriptions = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }

	if (contentDescriptions.isEmpty() || contentDescriptions.all { desc -> desc.isBlank() }) {
		throw AssertionError(
			"Expected node to have a non-empty content description, but it was empty or blank."
		)
	}

	return this
}

/**
 * Asserts that the node has text content for accessibility.
 * Either text or content description should be present for screen readers.
 *
 * @return The original [SemanticsNodeInteraction] for chaining
 */
fun SemanticsNodeInteraction.assertHasAccessibleText(): SemanticsNodeInteraction {
	val node = fetchSemanticsNode("Failed to fetch semantics node for accessible text assertion")

	val hasText = node.config.contains(SemanticsProperties.Text)
	val hasContentDescription = node.config.contains(SemanticsProperties.ContentDescription)

	if (!hasText && !hasContentDescription) {
		throw AssertionError(
			"Expected node to have accessible text (either Text or ContentDescription), " +
				"but neither was set. Add text or contentDescription for screen reader accessibility."
		)
	}

	return this
}

/**
 * Asserts that the node is marked as a heading for accessibility.
 * Headings help screen reader users navigate the page structure.
 *
 * @return The original [SemanticsNodeInteraction] for chaining
 */
fun SemanticsNodeInteraction.assertIsAccessibilityHeading(): SemanticsNodeInteraction {
	return assert(
		SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
	) {
		"Expected node to be marked as a heading for accessibility. " +
			"Use Modifier.semantics { heading() } for section headings."
	}
}

/**
 * Semantic matcher for nodes that have a content description set.
 */
fun hasContentDescription(): SemanticsMatcher =
	SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

/**
 * Semantic matcher for nodes that are accessibility headings.
 */
fun isAccessibilityHeading(): SemanticsMatcher =
	SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
