package com.adsamcik.tracker.map.v2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SafeQueryBuilderTest {

    @Test
    fun `build basic location query`() {
        val q = SafeQueryBuilder.location()
            .timeRange(100L, 200L)
            .bounds(north = 50.0, east = 15.0, south = 40.0, west = 10.0)
            .limit(100)
            .build()
        val sql = q.sql
        assertTrue(sql.contains("FROM location_data"))
        assertTrue(sql.contains("time >= ?"))
        assertTrue(sql.contains("time <= ?"))
        assertTrue(sql.contains("lat <= ?"))
        assertTrue(sql.contains("lon >= ?"))
        assertTrue(sql.endsWith("LIMIT 100"))
    val argCount = q.sql.count { it == '?' }
    assertEquals(6, argCount) // timeFrom, timeTo, north, south, east, west
    }

    @Test
    fun `location weighted query`() {
        val q = SafeQueryBuilder.location().weight("speed").build()
        val sql = q.sql
        assertTrue(sql.contains("speed AS weight"))
    }

    @Test
    fun `reject disallowed weight`() {
        try {
            SafeQueryBuilder.location().weight("not_col")
            fail("Expected exception for disallowed weight column")
        } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun `wifi query aliases columns`() {
        val q = SafeQueryBuilder.wifi().timeRange(0, 10).build()
        val sql = q.sql
        assertTrue(sql.contains("SELECT latitude AS lat, longitude AS lon, last_seen AS time"))
        assertTrue(sql.contains("FROM wifi_data"))
        assertTrue(sql.contains("last_seen >= ?"))
    }

    @Test
    fun `cell query basic`() {
        val q = SafeQueryBuilder.cell().limit(50).build()
        val sql = q.sql
        assertTrue(sql.startsWith("SELECT lat, lon, time"))
        assertTrue(sql.contains("FROM cell_location"))
        assertTrue(sql.endsWith("LIMIT 50"))
    }

    @Test
    fun `reject disallowed column`() {
        try {
            SafeQueryBuilder.location().columns("not_a_column")
            fail("Expected exception for disallowed column")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `accept allowed column`() {
        val q = SafeQueryBuilder.location().columns("speed", "hor_acc").build()
        val sql = q.sql
        assertTrue(sql.startsWith("SELECT lat, lon, time, speed, hor_acc"))
    }

    @Test
    fun `enforce time ordering`() {
        try {
            SafeQueryBuilder.location().timeRange(200L, 100L)
            fail("Expected exception for reversed time range")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
