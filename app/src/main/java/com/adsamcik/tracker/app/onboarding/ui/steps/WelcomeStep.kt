package com.adsamcik.tracker.app.onboarding.ui.steps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.ui.components.BenefitItem
import com.adsamcik.tracker.app.settings.components.PrivacyPolicyDialog
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.PrimaryActionButton
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineMotion

/**
 * Welcome step – value proposition and "Get Started" CTA.
 */
@Composable
fun WelcomeStep(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
    showOnboardingReadError: Boolean = false,
) {
    var showPrivacyPolicy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        val scale = remember { Animatable(0f) }
        val reducedMotion = LocalReducedMotion.current
        val iconEntranceSpec = if (reducedMotion) {
            snap<Float>()
        } else {
            RidgelineMotion.Crest
        }
        LaunchedEffect(reducedMotion) {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = iconEntranceSpec,
            )
        }

        Icon(
            imageVector = Icons.Default.MyLocation,
            contentDescription = null,
            modifier = Modifier
                .size(120.dp)
                .scale(scale.value),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.onboarding_streamlined_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .semantics { heading() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_streamlined_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(48.dp))

        BenefitItem(
            icon = Icons.Default.Security,
            title = stringResource(R.string.onboarding_benefit_privacy_title),
            description = stringResource(R.string.onboarding_benefit_privacy_desc),
        )

        Spacer(modifier = Modifier.height(24.dp))

        BenefitItem(
            icon = Icons.Default.Insights,
            title = stringResource(R.string.onboarding_benefit_insights_title),
            description = stringResource(R.string.onboarding_benefit_insights_desc),
        )

        Spacer(modifier = Modifier.height(24.dp))

        BenefitItem(
            icon = Icons.Default.EmojiEvents,
            title = stringResource(R.string.onboarding_benefit_gamification_title),
            description = stringResource(R.string.onboarding_benefit_gamification_desc),
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (showOnboardingReadError) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_onboarding_read_error"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_state_read_error_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        TextButton(
            onClick = { showPrivacyPolicy = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_privacy_policy_button"),
        ) {
            Text(stringResource(R.string.onboarding_privacy_policy_button))
        }

        Spacer(modifier = Modifier.height(16.dp))

        PrimaryActionButton(
            text = stringResource(R.string.onboarding_get_started),
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_cta_get_started"),
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismissRequest = { showPrivacyPolicy = false })
    }
}
