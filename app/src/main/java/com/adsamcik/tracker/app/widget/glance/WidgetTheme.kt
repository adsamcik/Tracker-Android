package com.adsamcik.tracker.app.widget.glance

import androidx.compose.ui.graphics.Color
import androidx.glance.material3.ColorProviders
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

// Ridgeline Design System colors for widgets
private val CanopyGreenPrimaryLight = Color(0xFF1B6B3A)
private val CanopyGreenPrimaryDark = Color(0xFF7BDA97)
private val TrailSageSecondaryLight = Color(0xFF506352)
private val TrailSageSecondaryDark = Color(0xFFB5CCB6)
private val TrailGoldTertiaryLight = Color(0xFF857010)
private val TrailGoldTertiaryDark = Color(0xFFD0B24A)
private val SurfaceLight = Color(0xFFF7FBF2)
private val SurfaceDark = Color(0xFF101410)
private val OnSurfaceLight = Color(0xFF191D19)
private val OnSurfaceDark = Color(0xFFE1E3DD)
private val ErrorLight = Color(0xFFBA1A1A)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnPrimaryLight = Color(0xFFFFFFFF)
private val OnPrimaryDark = Color(0xFF003916)
private val SurfaceContainerLight = Color(0xFFECF0E6)
private val SurfaceContainerDark = Color(0xFF1D211D)

private val WidgetLightColors = lightColorScheme(
    primary = CanopyGreenPrimaryLight,
    onPrimary = OnPrimaryLight,
    secondary = TrailSageSecondaryLight,
    tertiary = TrailGoldTertiaryLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceContainer = SurfaceContainerLight,
    error = ErrorLight,
)

private val WidgetDarkColors = darkColorScheme(
    primary = CanopyGreenPrimaryDark,
    onPrimary = OnPrimaryDark,
    secondary = TrailSageSecondaryDark,
    tertiary = TrailGoldTertiaryDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceContainer = SurfaceContainerDark,
    error = ErrorDark,
)

/**
 * Color providers for Glance widgets using the Ridgeline design system.
 * Uses dynamic colors on Android 12+ via GlanceTheme, falling back to these.
 */
val TrackerWidgetColorProviders = ColorProviders(
    light = WidgetLightColors,
    dark = WidgetDarkColors,
)
