package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Ridgeline Design System — Color Tokens
 *
 * Seed: #1B6B3A (Canopy Green, HCT H≈145° C≈48 T≈38)
 * Generated via Material Theme Builder, hand-audited for WCAG AA.
 *
 * Do NOT change the seed without regenerating the entire palette
 * and re-auditing all contrast pairs.
 */
val RidgelineSeed = Color(0xFF1B6B3A)

// --- Primary: Canopy Green ---
val CanopyGreenPrimaryLight = Color(0xFF1B6B3A)
val CanopyGreenOnPrimaryLight = Color(0xFFFFFFFF)
val CanopyGreenPrimaryContainerLight = Color(0xFF98F7B2)
val CanopyGreenOnPrimaryContainerLight = Color(0xFF00210D)

val CanopyGreenPrimaryDark = Color(0xFF7BDA97)
val CanopyGreenOnPrimaryDark = Color(0xFF003919)
val CanopyGreenPrimaryContainerDark = Color(0xFF005227)
val CanopyGreenOnPrimaryContainerDark = Color(0xFF98F7B2)

// --- Secondary: Trail Sage ---
val TrailSageSecondaryLight = Color(0xFF506352)
val TrailSageOnSecondaryLight = Color(0xFFFFFFFF)
val TrailSageSecondaryContainerLight = Color(0xFFD2E8D3)
val TrailSageOnSecondaryContainerLight = Color(0xFF0E1F13)

val TrailSageSecondaryDark = Color(0xFFB5CCB6)
val TrailSageOnSecondaryDark = Color(0xFF213526)
val TrailSageSecondaryContainerDark = Color(0xFF374B3B)
val TrailSageOnSecondaryContainerDark = Color(0xFFD2E8D3)

// --- Tertiary: Trail Gold ---
val TrailGoldTertiaryLight = Color(0xFF857010)
val TrailGoldOnTertiaryLight = Color(0xFFFFFFFF)
val TrailGoldTertiaryContainerLight = Color(0xFFFFDFA0)
val TrailGoldOnTertiaryContainerLight = Color(0xFF2A2000)

val TrailGoldTertiaryDark = Color(0xFFD0B24A)
val TrailGoldOnTertiaryDark = Color(0xFF473A00)
val TrailGoldTertiaryContainerDark = Color(0xFF635200)
val TrailGoldOnTertiaryContainerDark = Color(0xFFFFDFA0)

// --- Neutral / Surface (green-tinted) ---
val SurfaceLight = Color(0xFFF7FBF2)
val OnSurfaceLight = Color(0xFF181D18)
val OnSurfaceVariantLight = Color(0xFF404942)
val SurfaceContainerLowestLight = Color(0xFFF8FAF5)
val SurfaceContainerLowLight = Color(0xFFF0F4EB)
val SurfaceContainerLight = Color(0xFFE6ECE0)
val SurfaceContainerHighLight = Color(0xFFDBE3D4)
val SurfaceContainerHighestLight = Color(0xFFD1DAC8)
val SurfaceTintLight = Color(0xFF1B6B3A)
val BackgroundLight = Color(0xFFFFFFFF)
val OnBackgroundLight = Color(0xFF181D18)
val OutlineLight = Color(0xFF717971)
val OutlineVariantLight = Color(0xFFC0C9BC)
val InverseSurfaceLight = Color(0xFF2D322C)
val InverseOnSurfaceLight = Color(0xFFEEF2E9)
val InversePrimaryLight = Color(0xFF7BDA97)
val ScrimLight = Color(0xFF000000)

val SurfaceDark = Color(0xFF101410)
val OnSurfaceDark = Color(0xFFE0E4DB)
val OnSurfaceVariantDark = Color(0xFFC0C9BC)
val SurfaceContainerLowestDark = Color(0xFF0B0F0B)
val SurfaceContainerLowDark = Color(0xFF1C201B)
val SurfaceContainerDark = Color(0xFF202520)
val SurfaceContainerHighDark = Color(0xFF2B2F2A)
val SurfaceContainerHighestDark = Color(0xFF353935)
val SurfaceTintDark = Color(0xFF7BDA97)
val BackgroundDark = Color(0xFF101410)
val OnBackgroundDark = Color(0xFFE0E4DB)
val OutlineDark = Color(0xFF8B938A)
val OutlineVariantDark = Color(0xFF414941)
val InverseSurfaceDark = Color(0xFFE0E4DB)
val InverseOnSurfaceDark = Color(0xFF2D322C)
val InversePrimaryDark = Color(0xFF1B6B3A)
val ScrimDark = Color(0xFF000000)

// Surface Variant
val SurfaceVariantLight = Color(0xFFDCE5D5)
val SurfaceVariantDark = Color(0xFF414941)

// Surface Brightness Range
val SurfaceBrightLight = Color(0xFFF7FBF2)
val SurfaceBrightDark = Color(0xFF363A34)
val SurfaceDimLight = Color(0xFFD7DBD2)
val SurfaceDimDark = Color(0xFF101410)

// --- Error ---
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

// --- Extended Semantic: Success ---
val SuccessLight = Color(0xFF146C2E)
val OnSuccessLight = Color(0xFFFFFFFF)
val SuccessContainerLight = Color(0xFFA3F4A5)
val OnSuccessContainerLight = Color(0xFF002107)

val SuccessDark = Color(0xFF88D78A)
val OnSuccessDark = Color(0xFF003912)
val SuccessContainerDark = Color(0xFF00531E)
val OnSuccessContainerDark = Color(0xFFA3F4A5)

// --- Extended Semantic: Warning ---
val WarningLight = Color(0xFF8D5000)
val OnWarningLight = Color(0xFFFFFFFF)
val WarningContainerLight = Color(0xFFFFDCC1)
val OnWarningContainerLight = Color(0xFF2D1600)

val WarningDark = Color(0xFFFFB776)
val OnWarningDark = Color(0xFF4A2800)
val WarningContainerDark = Color(0xFF6B3D00)
val OnWarningContainerDark = Color(0xFFFFDCC1)

// --- Contextual: Track State ---
val TrackActiveColor = Color(0xFFFF3B30)
val TrackHistoryColor = Color(0xFF00829B)

// --- Color Schemes ---

val LightColorScheme = lightColorScheme(
    primary = CanopyGreenPrimaryLight,
    onPrimary = CanopyGreenOnPrimaryLight,
    primaryContainer = CanopyGreenPrimaryContainerLight,
    onPrimaryContainer = CanopyGreenOnPrimaryContainerLight,
    secondary = TrailSageSecondaryLight,
    onSecondary = TrailSageOnSecondaryLight,
    secondaryContainer = TrailSageSecondaryContainerLight,
    onSecondaryContainer = TrailSageOnSecondaryContainerLight,
    tertiary = TrailGoldTertiaryLight,
    onTertiary = TrailGoldOnTertiaryLight,
    tertiaryContainer = TrailGoldTertiaryContainerLight,
    onTertiaryContainer = TrailGoldOnTertiaryContainerLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceTint = SurfaceTintLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    inversePrimary = InversePrimaryLight,
    scrim = ScrimLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    surfaceVariant = SurfaceVariantLight,
    surfaceBright = SurfaceBrightLight,
    surfaceDim = SurfaceDimLight,
)

val DarkColorScheme = darkColorScheme(
    primary = CanopyGreenPrimaryDark,
    onPrimary = CanopyGreenOnPrimaryDark,
    primaryContainer = CanopyGreenPrimaryContainerDark,
    onPrimaryContainer = CanopyGreenOnPrimaryContainerDark,
    secondary = TrailSageSecondaryDark,
    onSecondary = TrailSageOnSecondaryDark,
    secondaryContainer = TrailSageSecondaryContainerDark,
    onSecondaryContainer = TrailSageOnSecondaryContainerDark,
    tertiary = TrailGoldTertiaryDark,
    onTertiary = TrailGoldOnTertiaryDark,
    tertiaryContainer = TrailGoldTertiaryContainerDark,
    onTertiaryContainer = TrailGoldOnTertiaryContainerDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceTint = SurfaceTintDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = InversePrimaryDark,
    scrim = ScrimDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    surfaceVariant = SurfaceVariantDark,
    surfaceBright = SurfaceBrightDark,
    surfaceDim = SurfaceDimDark,
)
