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
        sql shouldContain "FROM location_sample"
        sql shouldContain "time_ms >= ?"
        sql shouldContain "time_ms <= ?"
        sql shouldContain "lat_e7 <= ?"
        sql shouldContain "lon_e7 >= ?"
        sql shouldEndWith "LIMIT 100"
    val argCount = sql.count { it == '?' }
    argCount shouldBe 6 // timeFrom, timeTo, north, south, east, west
    }

    @Test
    fun `location weighted query`() {
        val q = SafeQueryBuilder.location().weight("speed").build()
    val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "speed_mps AS weight"
    }

    @Test
    fun `speed weighted query filters untrustworthy readings`() {
        val q = SafeQueryBuilder.location().weight("speed").build()
        val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "speed_mps IS NOT NULL"
        sql shouldContain "quality NOT IN ('LOW', 'COARSE')"
        sql shouldContain "speed_accuracy_mps IS NULL OR speed_accuracy_mps <= 3.0"
    }

    @Test
    fun `non-speed weighted query does not add speed trustworthiness filter`() {
        val q = SafeQueryBuilder.location().weight("hor_acc").build()
        val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "h_acc_m AS weight"
        (sql.contains("quality NOT IN")) shouldBe false
    }

    @Test
    fun `altitude weighted query excludes missing altitude`() {
        val q = SafeQueryBuilder.location().weight("alt").build()
        val sql = (q as SimpleSQLiteQuery).sql

        sql shouldContain "alt_m AS weight"
        sql shouldContain "alt_m IS NOT NULL"
    }

    @Test
    fun `motion weighted query maps the persisted movement state truthfully`() {
        val q = SafeQueryBuilder.location().weight("motion").build()
        val sql = (q as SimpleSQLiteQuery).sql

        sql shouldContain "CASE motion_state"
        sql shouldContain "WHEN 'MOVING' THEN 1.0"
        sql shouldContain "WHEN 'STILL' THEN 0.5"
        sql shouldContain "ELSE 0.0 END AS weight"
    }

    @Test
    fun `reject disallowed weight`() {
        shouldThrow<IllegalArgumentException> {
            SafeQueryBuilder.location().weight("not_col")
        }
    }

    @Test
    fun `wifi query uses E7 coordinates and time_ms`() {
        val q = SafeQueryBuilder.wifi().timeRange(0, 10).build()
    val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "CAST(lat_e7 AS REAL)"
        sql shouldContain "AS lat"
        sql shouldContain "FROM wifi_observation"
        sql shouldContain "time_ms >= ?"
    }

    @Test
    fun `cell query basic`() {
        val q = SafeQueryBuilder.cell().limit(50).build()
    val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "AS lat"
        sql shouldContain "AS lon"
        sql shouldContain "AS time"
        sql shouldContain "FROM cell_sample"
        sql shouldEndWith "LIMIT 50"
    }

    @Test
    fun `sample query keeps filters in database and applies a bounded representative limit`() {
        val q = SafeQueryBuilder.location()
            .timeRange(100L, 200L)
            .sample(50)
            .build() as SimpleSQLiteQuery

        q.sql shouldContain "SELECT MIN(id) AS min_id, MAX(id) AS max_id FROM location_sample"
        q.sql shouldContain "sample_bounds.max_id - sample_bounds.min_id"
        q.sql shouldContain "MAX(1,"
        q.sql shouldEndWith "LIMIT 50"
        q.sql.count { it == '?' } shouldBe 5
    }

    @Test
    fun `newest query returns a bounded chronological window`() {
        val q = SafeQueryBuilder.location().newest(80).build() as SimpleSQLiteQuery

        q.sql shouldStartWith "SELECT lat, lon, time FROM (SELECT"
        q.sql shouldContain "ORDER BY time_ms DESC LIMIT 80"
        q.sql shouldEndWith ") ORDER BY time ASC"
    }

    @Test
    fun `rejects combining incompatible row limits`() {
        shouldThrow<IllegalArgumentException> {
            SafeQueryBuilder.location().sample(50).newest(50)
        }
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
        sql shouldContain "speed_mps AS speed"
        sql shouldContain "h_acc_m AS hor_acc"
    }

    @Test
    fun `enforce time ordering`() {
        shouldThrow<IllegalArgumentException> {
            SafeQueryBuilder.location().timeRange(200L, 100L)
        }
    }

    @Test
    fun `bounds converted to E7 integers`() {
        val q = SafeQueryBuilder.location()
            .bounds(north = 50.0, east = 15.0, south = 40.0, west = 10.0)
            .build() as SimpleSQLiteQuery
        // Verify the args contain E7-converted values
        val sql = q.sql
        sql shouldContain "lat_e7 <= ?"
        sql shouldContain "lat_e7 >= ?"
        sql shouldContain "lon_e7 <= ?"
        sql shouldContain "lon_e7 >= ?"
    }

    @Test
    fun `degreesToE7 conversion`() {
        SafeQueryBuilder.degreesToE7(50.0) shouldBe 500_000_000
        SafeQueryBuilder.degreesToE7(-40.123) shouldBe -401_230_000
        SafeQueryBuilder.degreesToE7(0.0) shouldBe 0
    }

    @Test
    fun `cell weight maps asu to signal_strength`() {
        val q = SafeQueryBuilder.cell().weight("asu").build()
        val sql = (q as SimpleSQLiteQuery).sql
        sql shouldContain "signal_strength AS weight"
    }
}
