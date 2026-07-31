package com.adsamcik.tracker.activity.ski

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.stats.api.ski.SkiLift
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Manages the optional ski infrastructure database.
 *
 * The database is a pre-built SQLite file containing worldwide ski lift data
 * from OpenStreetMap (via OpenSkiMap). Users import it via SAF picker.
 *
 * Directory: context.filesDir/ski-data/ski_infrastructure.db
 */
class DefaultSkiInfrastructureManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val dispatchers: DispatchersProvider,
) : SkiInfrastructureManager {

	private val dataDir = File(context.filesDir, "ski-data")
	private val dbFile = File(dataDir, "ski_infrastructure.db")

	/** Check if the ski infrastructure database is available */
	override fun isAvailable(): Boolean = dbFile.exists() && dbFile.length() > 0

	/** Import a ski infrastructure database from a URI (SAF picker) */
	override suspend fun importDatabase(uri: Uri): SkiInfrastructureImportResult = withContext(dispatchers.io) {
		dataDir.mkdirs()

		val input = try {
			context.contentResolver.openInputStream(uri)
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			return@withContext SkiInfrastructureImportResult.SourceOpenFailed(uri)
		} ?: return@withContext SkiInfrastructureImportResult.SourceOpenFailed(uri)

		try {
			input.use { source ->
				dbFile.outputStream().use { output ->
					source.copyTo(output)
				}
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			dbFile.delete()
			return@withContext SkiInfrastructureImportResult.CopyFailed(e)
		}

		// Validate it's a valid SQLite with expected tables
		try {
			openDatabase().use { db ->
				db.rawQuery("SELECT COUNT(*) FROM ski_lift", null).use { cursor ->
					cursor.moveToFirst()
					if (cursor.getInt(0) == 0) {
						dbFile.delete()
						return@withContext SkiInfrastructureImportResult.InvalidDatabase(
							"Database contains no lift data",
						)
					}
				}
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			dbFile.delete()
			return@withContext SkiInfrastructureImportResult.InvalidDatabase(
				"Invalid ski infrastructure database: ${e.message ?: "Unknown validation error"}",
			)
		}

		SkiInfrastructureImportResult.Success(dbFile.absolutePath)
	}

	/** Clear the imported database */
	override fun clearDatabase() {
		dbFile.delete()
	}

	/** Get metadata value from database */
	override fun getMetadata(key: String): String? {
		if (!isAvailable()) return null
		return try {
			openDatabase().use { db ->
				db.rawQuery(
					"SELECT value FROM metadata WHERE key = ?",
					arrayOf(key)
				).use { cursor ->
					if (cursor.moveToFirst()) cursor.getString(0) else null
				}
			}
		} catch (_: Exception) {
			null
		}
	}

	/**
	 * Find ski lifts near a coordinate using bounding-box pre-filter + haversine post-filter.
	 * Returns empty list if database is not available.
	 */
	override fun findLiftsNearby(lat: Double, lon: Double, radiusDeg: Double): List<SkiLift> {
		if (!isAvailable()) return emptyList()

		val minLat = lat - radiusDeg
		val maxLat = lat + radiusDeg
		val minLon = lon - radiusDeg
		val maxLon = lon + radiusDeg

		return try {
			openDatabase().use { db ->
				val lifts = mutableListOf<SkiLift>()
				db.rawQuery(
					"""SELECT id, lift_type, name, 
					   start_lat, start_lon, start_elev,
					   end_lat, end_lon, end_elev
					   FROM ski_lift 
					   WHERE min_lat <= ? AND max_lat >= ?
					   AND min_lon <= ? AND max_lon >= ?""",
					arrayOf(
						maxLat.toString(), minLat.toString(),
						maxLon.toString(), minLon.toString()
					)
				).use { cursor ->
					while (cursor.moveToNext()) {
						lifts.add(
							SkiLift(
								id = cursor.getLong(0),
								liftType = cursor.getString(1),
								name = if (cursor.isNull(2)) null else cursor.getString(2),
								startLat = cursor.getDouble(3),
								startLon = cursor.getDouble(4),
								startElev = if (cursor.isNull(5)) null else cursor.getDouble(5),
								endLat = cursor.getDouble(6),
								endLon = cursor.getDouble(7),
								endElev = if (cursor.isNull(8)) null else cursor.getDouble(8)
							)
						)
					}
				}
				lifts
			}
		} catch (_: Exception) {
			emptyList()
		}
	}

	private fun openDatabase(): SQLiteDatabase {
		return SQLiteDatabase.openDatabase(
			dbFile.absolutePath,
			null,
			SQLiteDatabase.OPEN_READONLY
		)
	}

}
