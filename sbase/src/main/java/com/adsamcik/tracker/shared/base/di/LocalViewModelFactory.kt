package com.adsamcik.tracker.shared.base.di

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelProvider

/**
 * CompositionLocal for providing ViewModelFactory across modules.
 * This allows injection of ViewModels with their dependencies.
 */
val LocalViewModelFactory = staticCompositionLocalOf<ViewModelProvider.Factory> { 
    error("ViewModelFactory not provided") 
}