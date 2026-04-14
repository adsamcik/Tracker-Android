package com.adsamcik.tracker.app.onboarding.data

/**
 * Steps in the first-time setup wizard.
 *
 * Flow: Welcome → HowToTrack → WhatToCollect → (done)
 */
enum class SetupStep(val index: Int) {
    /** Value proposition and privacy promise. */
    Welcome(0),

    /** Auto-tracking mode + tracking preset selection. */
    HowToTrack(1),

    /** Data source toggles with inline permission requests. */
    WhatToCollect(2);

    companion object {
        val totalSteps: Int = entries.size

        fun fromIndex(index: Int): SetupStep? = entries.getOrNull(index)
    }
}
