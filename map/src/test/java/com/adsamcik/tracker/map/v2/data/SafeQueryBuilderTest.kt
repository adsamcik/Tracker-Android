package com.adsamcik.tracker.map.v2.data

import com.adsamcik.tracker.map.data.SafeQueryBuilder
import androidx.sqlite.db.SimpleSQLiteQuery
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class SafeQueryBuilderTest {

    @Test
    fun `build basic location query`() {
        val q = SafeQueryBuilder.location()
            .timeRange(100L, 200L)
            .bounds(north = 50.0, east = 15.0, south = 40.0, west = 10.0)
            .limit(100)
            .build()
    val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "FROM location_data"
        sql shouldContain "time >= ?"
        sql shouldContain "time <= ?"
        sql shouldContain "lat <= ?"
        sql shouldContain "lon >= ?"
        sql shouldEndWith "LIMIT 100"
    val argCount = sql.count { it == '?' }
    argCount shouldBe 6 // timeFrom, timeTo, north, south, east, west
    }

    @Test
    fun `location weighted query`() {
        val q = SafeQueryBuilder.location().weight("speed").build()
    val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "speed AS weight"
    }

    @Test
    fun `reject disallowed weight`() {
        shouldThrow<IllegalArgumentException> {
            SafeQueryBuilder.location().weight("not_col")
        }
    }

    @Test
    fun `wifi query aliases columns`() {
        val q = SafeQueryBuilder.wifi().timeRange(0, 10).build()
    val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "SELECT latitude AS lat, longitude AS lon, last_seen AS time"
        sql shouldContain "FROM wifi_data"
        sql shouldContain "last_seen >= ?"
    }

    @Test
    fun `cell query basic`() {
        val q = SafeQueryBuilder.cell().limit(50).build()
    val sql = (q as SimpleSQLiteQuery).sql
        sql shouldStartWith "SELECT lat, lon, time"
        sql shouldContain "FROM cell_location"
        sql shouldEndWith "LIMIT 50"
    }

    @Test
    fun `reject disallowed column`() {
        shouldThrow<IllegalArgumentException> {
            SafeQueryBuilder.location().columns("not_a_column")
        }
    }

    @Test
    fun `accept allowed column`() {
        val q = SafeQueryBuilder.location().columns("speed", "hor_acc").build()
        val sql = q.sql
        sql shouldStartWith "SELECT lat, lon, time, speed, hor_acc"
    }

    @Test
    fun `enforce time ordering`() {
        shouldThrow<IllegalArgumentException> {
            SafeQueryBuilder.location().timeRange(200L, 100L)
        }
    }
}
