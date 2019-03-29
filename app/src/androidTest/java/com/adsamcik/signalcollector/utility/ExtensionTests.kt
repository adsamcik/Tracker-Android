package com.adsamcik.signalcollector.utility

import com.adsamcik.signalcollector.misc.extension.contains
import com.adsamcik.signalcollector.misc.extension.date
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.*

@RunWith(JUnit4::class)
class ExtensionTests {
    @Test
    fun listContainsTest() {
        val list = listOf("x", "xar", "y", "d")

        assertTrue(list.contains { it == "xar" })
        assertFalse(list.contains { it == "xa" })
    }

    @Test
    fun roundToDateTest() {
        val cal = Calendar.getInstance()
        val calRound = cal.date()

        assertEquals(cal[Calendar.DAY_OF_MONTH], calRound[Calendar.DAY_OF_MONTH])
        assertEquals(cal[Calendar.MONTH], calRound[Calendar.MONTH])
        assertEquals(cal[Calendar.YEAR], calRound[Calendar.YEAR])
        assertEquals(0, calRound[Calendar.HOUR_OF_DAY])
        assertEquals(0, calRound[Calendar.MINUTE])
        assertEquals(0, calRound[Calendar.SECOND])
        assertEquals(0, calRound[Calendar.MILLISECOND])
    }
}