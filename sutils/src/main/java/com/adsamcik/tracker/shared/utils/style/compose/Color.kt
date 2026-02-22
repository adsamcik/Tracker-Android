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
val SurfaceContainerLight = Color(0xFFEBF4F6)
val OnSurfaceLight = Color(0xFF171D1E)
val OutlineLight = Color(0xFF6F797A)

val SurfaceDark = Color(0xFF0E1415)
val SurfaceContainerDark = Color(0xFF1A2022)
val OnSurfaceDark = Color(0xFFDFE4E5)
val OutlineDark = Color(0xFF899294)

// Semantic Colors
val ErrorLight = Color(0xFFBA1A1A)
val ErrorContainerLight = Color(0xFFFFDAD6)
val SuccessLight = Color(0xFF146C2E)
val SuccessContainerLight = Color(0xFFA3F4A5)
val WarningLight = Color(0xFF8D5000)
val WarningContainerLight = Color(0xFFFFDCC1)

val ErrorDark = Color(0xFFFFB4AB)
val ErrorContainerDark = Color(0xFF93000A)
val SuccessDark = Color(0xFF88D78A)
val SuccessContainerDark = Color(0xFF00531E)
val WarningDark = Color(0xFFFFB776)
val WarningContainerDark = Color(0xFF6B3D00)

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
    surfaceContainer = SurfaceContainerLight,
    outline = OutlineLight,
    error = ErrorLight,
    errorContainer = ErrorContainerLight
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
    surfaceContainer = SurfaceContainerDark,
    outline = OutlineDark,
    error = ErrorDark,
    errorContainer = ErrorContainerDark
)
