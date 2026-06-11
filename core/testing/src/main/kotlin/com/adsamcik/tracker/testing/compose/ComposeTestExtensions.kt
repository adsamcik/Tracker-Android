package com.adsamcik.tracker.testing.compose

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/**
 * Extension functions for [ComposeTestRule] to simplify common test operations.
 */

/**
 * Waits until a node with the given test tag exists and is displayed.
 *
 * @param testTag The test tag to search for
 * @param timeoutMs Maximum time to wait
 * @return The [SemanticsNodeInteraction] for the found node
 */
fun ComposeTestRule.waitUntilNodeWithTagIsDisplayed(
	testTag: String,
	timeoutMs: Long = 5_000L
): SemanticsNodeInteraction {
	waitUntil(timeoutMillis = timeoutMs) {
		onAllNodesWithTag(testTag).fetchSemanticsNodes().isNotEmpty()
	}
	return onNodeWithTag(testTag).assertIsDisplayed()
}

/**
 * Waits until a node with the given text exists and is displayed.
 *
 * @param text The text to search for
 * @param timeoutMs Maximum time to wait
 * @return The [SemanticsNodeInteraction] for the found node
 */
fun ComposeTestRule.waitUntilNodeWithTextIsDisplayed(
	text: String,
	timeoutMs: Long = 5_000L
): SemanticsNodeInteraction {
	waitUntil(timeoutMillis = timeoutMs) {
		onAllNodes(androidx.compose.ui.test.hasText(text))
			.fetchSemanticsNodes()
			.isNotEmpty()
	}
	return onNodeWithText(text).assertIsDisplayed()
}

/**
 * Waits until a node with the given content description exists and is displayed.
 *
 * @param contentDescription The content description to search for
 * @param timeoutMs Maximum time to wait
 * @return The [SemanticsNodeInteraction] for the found node
 */
fun ComposeTestRule.waitUntilNodeWithContentDescriptionIsDisplayed(
	contentDescription: String,
	timeoutMs: Long = 5_000L
): SemanticsNodeInteraction {
	waitUntil(timeoutMillis = timeoutMs) {
		onAllNodes(androidx.compose.ui.test.hasContentDescription(contentDescription))
			.fetchSemanticsNodes()
			.isNotEmpty()
	}
	return onNodeWithContentDescription(contentDescription).assertIsDisplayed()
}

/**
 * Waits until a condition is met or timeout occurs.
 * Provides better error messages than the default waitUntil.
 *
 * @param description Description of what we're waiting for (for error messages)
 * @param timeoutMs Maximum time to wait
 * @param condition The condition to check
 */
fun ComposeTestRule.waitUntilWithDescription(
	description: String,
	timeoutMs: Long = 5_000L,
	condition: () -> Boolean
) {
	try {
		waitUntil(timeoutMillis = timeoutMs, condition = condition)
	} catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
		throw AssertionError("Timeout waiting for: $description (after ${timeoutMs}ms)", e)
	}
}

/**
 * Clicks a node with the given test tag, waiting for it to appear first.
 *
 * @param testTag The test tag of the element to click
 * @param timeoutMs Maximum time to wait for the element
 */
fun ComposeTestRule.clickNodeWithTag(
	testTag: String,
	timeoutMs: Long = 5_000L
) {
	waitUntilNodeWithTagIsDisplayed(testTag, timeoutMs).performClick()
}

/**
 * Clicks a node with the given text, waiting for it to appear first.
 *
 * @param text The text of the element to click
 * @param timeoutMs Maximum time to wait for the element
 */
fun ComposeTestRule.clickNodeWithText(
	text: String,
	timeoutMs: Long = 5_000L
) {
	waitUntilNodeWithTextIsDisplayed(text, timeoutMs).performClick()
}

/**
 * Asserts that a node is displayed and enabled (interactive).
 *
 * @return The original [SemanticsNodeInteraction] for chaining
 */
fun SemanticsNodeInteraction.assertIsInteractive(): SemanticsNodeInteraction {
	return assertIsDisplayed().assertIsEnabled()
}

/**
 * Asserts that a node is displayed but disabled (not interactive).
 *
 * @return The original [SemanticsNodeInteraction] for chaining
 */
fun SemanticsNodeInteraction.assertIsVisibleButDisabled(): SemanticsNodeInteraction {
	return assertIsDisplayed().assertIsNotEnabled()
}

/**
 * Gets the count of nodes in a collection.
 * Useful for asserting list/grid sizes.
 *
 * @return Number of nodes in the collection
 */
fun SemanticsNodeInteractionCollection.count(): Int {
	return fetchSemanticsNodes().size
}

/**
 * Asserts that a collection has exactly the expected number of items.
 *
 * @param expectedCount Expected number of items
 * @return The original collection for chaining
 */
fun SemanticsNodeInteractionCollection.assertCount(
	expectedCount: Int
): SemanticsNodeInteractionCollection {
	val actualCount = count()
	if (actualCount != expectedCount) {
		throw AssertionError(
			"Expected $expectedCount items but found $actualCount"
		)
	}
	return this
}

/**
 * Asserts that a collection has at least the minimum number of items.
 *
 * @param minCount Minimum expected number of items
 * @return The original collection for chaining
 */
fun SemanticsNodeInteractionCollection.assertMinCount(
	minCount: Int
): SemanticsNodeInteractionCollection {
	val actualCount = count()
	if (actualCount < minCount) {
		throw AssertionError(
			"Expected at least $minCount items but found only $actualCount"
		)
	}
	return this
}
