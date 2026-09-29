package com.adsamcik.tracker.tracker.shortcut

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ShortcutTrackingControlTest {
    @Test
    fun `stored shortcut ordinals keep old Start and End meanings`() {
        assertEquals(Shortcuts.ShortcutAction.START_COLLECTION, Shortcuts.ShortcutAction.entries[0])
        assertEquals(Shortcuts.ShortcutAction.STOP_COLLECTION, Shortcuts.ShortcutAction.entries[1])
        assertEquals(Shortcuts.ShortcutAction.TURN_OFF_TRACKING, Shortcuts.ShortcutAction.entries[2])
    }
}
