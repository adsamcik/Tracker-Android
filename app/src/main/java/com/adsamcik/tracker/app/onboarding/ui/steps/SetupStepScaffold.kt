package com.adsamcik.tracker.app.onboarding.ui.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.PrimaryActionButton

private val SetupStepHorizontalPadding = 24.dp
private val SetupStepPinnedActionBottomSpacing = 32.dp
private val SetupStepPinnedActionContentGap = 16.dp

@Composable
internal fun SetupStepScaffold(
    actionText: String,
    actionTestTag: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPaddingTestTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var actionHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SetupStepHorizontalPadding),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
            SetupStepPinnedActionSpacer(
                height = actionHeight + SetupStepPinnedActionBottomSpacing + SetupStepPinnedActionContentGap,
                testTag = bottomPaddingTestTag,
            )
        }

        PrimaryActionButton(
            text = actionText,
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    actionHeight = with(density) { size.height.toDp() }
                }
                .testTag(actionTestTag),
        )

        Spacer(modifier = Modifier.height(SetupStepPinnedActionBottomSpacing))
    }
}

@Composable
private fun SetupStepPinnedActionSpacer(
    height: Dp,
    testTag: String?,
) {
    val tagModifier = if (testTag != null) {
        Modifier.testTag(testTag)
    } else {
        Modifier
    }

    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .then(tagModifier),
    )
}
