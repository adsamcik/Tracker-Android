package com.adsamcik.tracker.utility

import com.adsamcik.tracker.common.extension.contains
import com.adsamcik.tracker.common.extension.toDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
		val calRound = cal.toDate()

		assertEquals(cal[Calendar.DAY_OF_MONTH], calRound[Calendar.DAY_OF_MONTH])
		assertEquals(cal[Calendar.MONTH], calRound[Calendar.MONTH])
		assertEquals(cal[Calendar.YEAR], calRound[Calendar.YEAR])
		assertEquals(0, calRound[Calendar.HOUR_OF_DAY])
		assertEquals(0, calRound[Calendar.MINUTE])
		assertEquals(0, calRound[Calendar.SECOND])
		assertEquals(0, calRound[Calendar.MILLISECOND])
	}
}

