package com.adsamcik.tracker.app.di

import android.content.Context
import com.adsamcik.tracker.app.AppGraph
import com.adsamcik.tracker.app.Application

/**
 * Extension to access AppGraph from any Context.
 * 
 * Usage in Activity/Service/Fragment:
 * ```
 * val graph = appGraph
 * val repository = graph.sessionRepository
 * ```
 * 
 * Per copilot-instructions Section 16A:
 * - Prefer injecting specific dependencies via constructor
 * - Use this extension only for composition roots that must resolve dependencies dynamically
 * - Avoid passing entire graph; extract only what's needed
 */
val Context.appGraph: AppGraph
    get() = (applicationContext as Application).appGraph
