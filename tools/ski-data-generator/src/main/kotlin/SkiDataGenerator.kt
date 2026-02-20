import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import java.io.File
import java.io.FileReader
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

fun main(args: Array<String>) {
    val inputPath = if (args.isNotEmpty()) args[0] else "input/lifts.geojson"
    val inputFile = File(inputPath)

    if (!inputFile.exists()) {
        System.err.println("Error: Input file not found: ${inputFile.absolutePath}")
        System.err.println("Download lifts.geojson from https://openskimap.org (About → Download)")
        System.exit(1)
    }

    val outputDir = File("output").apply { mkdirs() }
    val outputDb = File(outputDir, "ski_infrastructure.db")
    if (outputDb.exists()) outputDb.delete()

    println("Reading lifts from: ${inputFile.absolutePath}")

    val lifts = parseLifts(inputFile)
    println("Parsed ${lifts.size} operating lifts")

    val dbUrl = "jdbc:sqlite:${outputDb.absolutePath}"
    DriverManager.getConnection(dbUrl).use { conn ->
        conn.autoCommit = false
        createSchema(conn)
        insertLifts(conn, lifts)
        insertMetadata(conn, lifts.size)
        conn.commit()
    }

    printSummary(lifts, outputDb)
}

private val EXCLUDED_STATUSES = setOf("abandoned", "closed", "demolished", "disused", "razed")

data class LiftData(
    val osmId: String?,
    val liftType: String,
    val name: String?,
    val status: String,
    val coordinates: List<DoubleArray> // [lon, lat, elev?]
)

private fun parseLifts(file: File): List<LiftData> {
    val gson = Gson()
    val lifts = mutableListOf<LiftData>()

    // Use streaming parser to handle large GeoJSON files
    JsonReader(FileReader(file)).use { reader ->
        val root = gson.fromJson<JsonObject>(reader, JsonObject::class.java)
        val features = root.getAsJsonArray("features") ?: return emptyList()

        for (element in features) {
            val feature = element.asJsonObject
            val lift = extractLift(feature) ?: continue
            lifts.add(lift)
        }
    }

    return lifts
}

private fun extractLift(feature: JsonObject): LiftData? {
    val properties = feature.getAsJsonObject("properties") ?: return null
    val geometry = feature.getAsJsonObject("geometry") ?: return null

    val status = properties.stringOrNull("status") ?: "operating"
    if (status.lowercase() in EXCLUDED_STATUSES) return null

    val liftType = properties.stringOrNull("type") ?: return null

    val coordinates = extractCoordinates(geometry)
    if (coordinates.size < 2) return null

    return LiftData(
        osmId = properties.stringOrNull("id"),
        liftType = normalizeLiftType(liftType),
        name = properties.stringOrNull("name"),
        status = status,
        coordinates = coordinates
    )
}

private fun extractCoordinates(geometry: JsonObject): List<DoubleArray> {
    val type = geometry.get("type")?.asString ?: return emptyList()
    val coordsElement = geometry.get("coordinates") ?: return emptyList()

    return when (type) {
        "LineString" -> parseLineString(coordsElement)
        "MultiLineString" -> {
            // Use the first linestring
            val lines = coordsElement.asJsonArray
            if (lines.size() > 0) parseLineString(lines[0]) else emptyList()
        }
        else -> emptyList()
    }
}

private fun parseLineString(element: JsonElement): List<DoubleArray> {
    val coords = mutableListOf<DoubleArray>()
    for (point in element.asJsonArray) {
        val arr = point.asJsonArray
        val lon = arr[0].asDouble
        val lat = arr[1].asDouble
        val elev = if (arr.size() >= 3) arr[2].asDouble else Double.NaN
        coords.add(doubleArrayOf(lon, lat, elev))
    }
    return coords
}

private fun normalizeLiftType(raw: String): String = when (raw.lowercase()) {
    "chair_lift", "chairlift" -> "chairlift"
    "gondola" -> "gondola"
    "cable_car" -> "cable_car"
    "drag_lift", "t-bar", "t_bar", "j-bar", "j_bar", "platter", "rope_tow" -> "drag_lift"
    "magic_carpet" -> "magic_carpet"
    "funicular" -> "funicular"
    "mixed_lift" -> "mixed_lift"
    else -> raw.lowercase()
}

private fun JsonObject.stringOrNull(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return el.asString.ifBlank { null }
}

private fun createSchema(conn: Connection) {
    conn.createStatement().use { stmt ->
        stmt.executeUpdate("""
            CREATE TABLE ski_lift (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                osm_id TEXT,
                lift_type TEXT NOT NULL,
                name TEXT,
                status TEXT DEFAULT 'operating',
                start_lat REAL NOT NULL,
                start_lon REAL NOT NULL,
                start_elev REAL,
                end_lat REAL NOT NULL,
                end_lon REAL NOT NULL,
                end_elev REAL,
                min_lat REAL NOT NULL,
                max_lat REAL NOT NULL,
                min_lon REAL NOT NULL,
                max_lon REAL NOT NULL
            )
        """.trimIndent())

        stmt.executeUpdate("CREATE INDEX idx_ski_lift_bbox ON ski_lift(min_lat, max_lat, min_lon, max_lon)")
        stmt.executeUpdate("CREATE INDEX idx_ski_lift_type ON ski_lift(lift_type)")

        stmt.executeUpdate("""
            CREATE TABLE ski_lift_point (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lift_id INTEGER NOT NULL REFERENCES ski_lift(id),
                point_index INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                elev REAL
            )
        """.trimIndent())

        stmt.executeUpdate("CREATE INDEX idx_ski_lift_point_lift ON ski_lift_point(lift_id)")
        stmt.executeUpdate("CREATE INDEX idx_ski_lift_point_bbox ON ski_lift_point(lat, lon)")

        stmt.executeUpdate("""
            CREATE TABLE metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """.trimIndent())
    }
}

private fun insertLifts(conn: Connection, lifts: List<LiftData>) {
    val liftStmt = conn.prepareStatement("""
        INSERT INTO ski_lift (osm_id, lift_type, name, status,
            start_lat, start_lon, start_elev, end_lat, end_lon, end_elev,
            min_lat, max_lat, min_lon, max_lon)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent())

    val pointStmt = conn.prepareStatement("""
        INSERT INTO ski_lift_point (lift_id, point_index, lat, lon, elev)
        VALUES (?, ?, ?, ?, ?)
    """.trimIndent())

    for (lift in lifts) {
        val coords = lift.coordinates
        val start = coords.first()
        val end = coords.last()

        val lats = coords.map { it[1] }
        val lons = coords.map { it[0] }

        liftStmt.setString(1, lift.osmId)
        liftStmt.setString(2, lift.liftType)
        liftStmt.setString(3, lift.name)
        liftStmt.setString(4, lift.status)
        liftStmt.setDouble(5, start[1])  // start_lat
        liftStmt.setDouble(6, start[0])  // start_lon
        liftStmt.setNullableDouble(7, start[2])
        liftStmt.setDouble(8, end[1])    // end_lat
        liftStmt.setDouble(9, end[0])    // end_lon
        liftStmt.setNullableDouble(10, end[2])
        liftStmt.setDouble(11, lats.min())
        liftStmt.setDouble(12, lats.max())
        liftStmt.setDouble(13, lons.min())
        liftStmt.setDouble(14, lons.max())
        liftStmt.executeUpdate()

        val liftId = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next(); rs.getLong(1)
            }
        }

        // Insert start, midpoints, and end
        val selectedPoints = selectPoints(coords)
        for ((index, point) in selectedPoints.withIndex()) {
            pointStmt.setLong(1, liftId)
            pointStmt.setInt(2, index)
            pointStmt.setDouble(3, point[1])  // lat
            pointStmt.setDouble(4, point[0])  // lon
            pointStmt.setNullableDouble(5, point[2])
            pointStmt.executeUpdate()
        }
    }

    liftStmt.close()
    pointStmt.close()
}

/** Select start, 2-3 evenly-spaced midpoints, and end from the coordinate list. */
private fun selectPoints(coords: List<DoubleArray>): List<DoubleArray> {
    if (coords.size <= 2) return coords

    val result = mutableListOf(coords.first())

    // Pick 2-3 midpoints depending on total coordinate count
    val midCount = if (coords.size <= 5) 1 else if (coords.size <= 10) 2 else 3
    val step = (coords.size - 1).toDouble() / (midCount + 1)
    for (i in 1..midCount) {
        val idx = (step * i).toInt().coerceIn(1, coords.size - 2)
        result.add(coords[idx])
    }

    result.add(coords.last())
    return result
}

private fun java.sql.PreparedStatement.setNullableDouble(index: Int, value: Double) {
    if (value.isNaN()) setNull(index, java.sql.Types.REAL)
    else setDouble(index, value)
}

private fun insertMetadata(conn: Connection, liftCount: Int) {
    val stmt = conn.prepareStatement("INSERT INTO metadata (key, value) VALUES (?, ?)")

    val entries = listOf(
        "source_url" to "https://openskimap.org",
        "generated_at" to Instant.now().toString(),
        "lift_count" to liftCount.toString(),
        "schema_version" to "1"
    )

    for ((key, value) in entries) {
        stmt.setString(1, key)
        stmt.setString(2, value)
        stmt.executeUpdate()
    }

    stmt.close()
}

private fun printSummary(lifts: List<LiftData>, dbFile: File) {
    val byType = lifts.groupingBy { it.liftType }.eachCount().toSortedMap()
    val sizeMb = "%.2f".format(dbFile.length() / 1_048_576.0)

    println()
    println("=== Ski Infrastructure DB Summary ===")
    println("Total lifts: ${lifts.size}")
    println("By type:")
    for ((type, count) in byType) {
        println("  %-20s %d".format(type, count))
    }
    println("Output: ${dbFile.absolutePath}")
    println("DB size: $sizeMb MB")
}
