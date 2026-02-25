package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand Identity Colors
val SecureTealPrimaryLight = Color(0xFF006874)
val SecureTealOnPrimaryLight = Color(0xFFFFFFFF)
val SecureTealPrimaryContainerLight = Color(0xFF97F0FF)
val SecureTealOnPrimaryContainerLight = Color(0xFF001F24)

val SecureTealPrimaryDark = Color(0xFF4FD8EB)
val SecureTealOnPrimaryDark = Color(0xFF00363D)
val SecureTealPrimaryContainerDark = Color(0xFF004F58)
val SecureTealOnPrimaryContainerDark = Color(0xFF97F0FF)

val TrailSlateSecondaryLight = Color(0xFF4A6367)
val TrailSlateOnSecondaryLight = Color(0xFFFFFFFF)
val TrailSlateSecondaryContainerLight = Color(0xFFCDE7EC)
val TrailSlateOnSecondaryContainerLight = Color(0xFF051F23)

val TrailSlateSecondaryDark = Color(0xFFB1CBD0)
val TrailSlateOnSecondaryDark = Color(0xFF1C3438)
val TrailSlateSecondaryContainerDark = Color(0xFF334B4F)
val TrailSlateOnSecondaryContainerDark = Color(0xFFCDE7EC)

val SunsetRustTertiaryLight = Color(0xFF98483A)
val SunsetRustOnTertiaryLight = Color(0xFFFFFFFF)
val SunsetRustTertiaryContainerLight = Color(0xFFFFDAD4)
val SunsetRustOnTertiaryContainerLight = Color(0xFF3C0903)

val SunsetRustTertiaryDark = Color(0xFFFFB4A8)
val SunsetRustOnTertiaryDark = Color(0xFF5C190D)
val SunsetRustTertiaryContainerDark = Color(0xFF7A3024)
val SunsetRustOnTertiaryContainerDark = Color(0xFFFFDAD4)

// Neutral / Surface
val SurfaceLight = Color(0xFFF8FDFF)
val OnSurfaceLight = Color(0xFF171D1E)
val OnSurfaceVariantLight = Color(0xFF3F484A)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFEFF3F8)
val SurfaceContainerLight = Color(0xFFEBF4F6)
val SurfaceContainerHighLight = Color(0xFFDFE8EA)
val SurfaceContainerHighestLight = Color(0xFFD3DDE0)
val SurfaceTintLight = Color(0xFF006874)
val BackgroundLight = Color(0xFFFBFCFF)
val OnBackgroundLight = Color(0xFF171D1E)
val OutlineLight = Color(0xFF6F797A)
val OutlineVariantLight = Color(0xFFC4C7CF)
val InverseSurfaceLight = Color(0xFF2B3133)
val InverseOnSurfaceLight = Color(0xFFECF2F3)
val InversePrimaryLight = Color(0xFF4FD8EB)
val ScrimLight = Color(0xFF000000)

val SurfaceDark = Color(0xFF0E1415)
val OnSurfaceDark = Color(0xFFDFE4E5)
val OnSurfaceVariantDark = Color(0xFFBFC8CA)
val SurfaceContainerLowestDark = Color(0xFF060B0C)
val SurfaceContainerLowDark = Color(0xFF151B1D)
val SurfaceContainerDark = Color(0xFF1A2022)
val SurfaceContainerHighDark = Color(0xFF252B2D)
val SurfaceContainerHighestDark = Color(0xFF303638)
val SurfaceTintDark = Color(0xFF4FD8EB)
val BackgroundDark = Color(0xFF0E1415)
val OnBackgroundDark = Color(0xFFDFE4E5)
val OutlineDark = Color(0xFF899294)
val OutlineVariantDark = Color(0xFF3F484A)
val InverseSurfaceDark = Color(0xFFDFE4E5)
val InverseOnSurfaceDark = Color(0xFF2B3133)
val InversePrimaryDark = Color(0xFF006874)
val ScrimDark = Color(0xFF000000)

// Surface Variant
val SurfaceVariantLight = Color(0xFFDBE4E6)
val SurfaceVariantDark = Color(0xFF3F484A)

// Surface Brightness Range
val SurfaceBrightLight = Color(0xFFF8FDFF)
val SurfaceBrightDark = Color(0xFF353B3D)
val SurfaceDimLight = Color(0xFFD5DBDC)
val SurfaceDimDark = Color(0xFF0E1415)

// Semantic Colors
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)
val SuccessLight = Color(0xFF146C2E)
val OnSuccessLight = Color(0xFFFFFFFF)
val SuccessContainerLight = Color(0xFFA3F4A5)
val OnSuccessContainerLight = Color(0xFF002107)
val WarningLight = Color(0xFF8D5000)
val OnWarningLight = Color(0xFFFFFFFF)
val WarningContainerLight = Color(0xFFFFDCC1)
val OnWarningContainerLight = Color(0xFF2D1600)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val SuccessDark = Color(0xFF88D78A)
val OnSuccessDark = Color(0xFF003912)
val SuccessContainerDark = Color(0xFF00531E)
val OnSuccessContainerDark = Color(0xFFA3F4A5)
val WarningDark = Color(0xFFFFB776)
val OnWarningDark = Color(0xFF4A2800)
val WarningContainerDark = Color(0xFF6B3D00)
val OnWarningContainerDark = Color(0xFFFFDCC1)

// Contextual Colors
val TrackActiveColor = Color(0xFFFF3B30)
val TrackHistoryColor = Color(0xFF00829B)

val LightColorScheme = lightColorScheme(
    primary = SecureTealPrimaryLight,
    onPrimary = SecureTealOnPrimaryLight,
    primaryContainer = SecureTealPrimaryContainerLight,
    onPrimaryContainer = SecureTealOnPrimaryContainerLight,
    secondary = TrailSlateSecondaryLight,
    onSecondary = TrailSlateOnSecondaryLight,
    secondaryContainer = TrailSlateSecondaryContainerLight,
    onSecondaryContainer = TrailSlateOnSecondaryContainerLight,
    tertiary = SunsetRustTertiaryLight,
    onTertiary = SunsetRustOnTertiaryLight,
    tertiaryContainer = SunsetRustTertiaryContainerLight,
    onTertiaryContainer = SunsetRustOnTertiaryContainerLight,
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
    surfaceDim = SurfaceDimLight
)

val DarkColorScheme = darkColorScheme(
    primary = SecureTealPrimaryDark,
    onPrimary = SecureTealOnPrimaryDark,
    primaryContainer = SecureTealPrimaryContainerDark,
    onPrimaryContainer = SecureTealOnPrimaryContainerDark,
    secondary = TrailSlateSecondaryDark,
    onSecondary = TrailSlateOnSecondaryDark,
    secondaryContainer = TrailSlateSecondaryContainerDark,
    onSecondaryContainer = TrailSlateOnSecondaryContainerDark,
    tertiary = SunsetRustTertiaryDark,
    onTertiary = SunsetRustOnTertiaryDark,
    tertiaryContainer = SunsetRustTertiaryContainerDark,
    onTertiaryContainer = SunsetRustOnTertiaryContainerDark,
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
    surfaceDim = SurfaceDimDark
)
