package com.adsamcik.tracker.app.ui

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the contract of [toSafeBottomPadding] so a future refactor cannot
 * silently remove the negative-dp clamp that protects [androidx.compose.foundation.layout.padding]
 * from IllegalArgumentException when spring animations transiently overshoot
 * below zero (see MainRoot.kt for the original crash context).
 */
@DisplayName("MainRoot.toSafeBottomPadding")
class MainRootSafeBottomPaddingTest {

    @Test
    fun `negative dp coerces to zero`() {
        assertEquals(0.dp, (-5).dp.toSafeBottomPadding())
    }

    @Test
    fun `zero dp stays zero`() {
        assertEquals(0.dp, 0.dp.toSafeBottomPadding())
    }

    @Test
    fun `positive dp passes through`() {
        assertEquals(96.dp, 96.dp.toSafeBottomPadding())
    }

    @Test
    fun `very small negative dp coerces to zero`() {
        assertEquals(0.dp, (-0.001f).dp.toSafeBottomPadding())
    }
}
