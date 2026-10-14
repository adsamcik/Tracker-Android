package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.annotation.Keep
import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFileMeasurementV1
import com.adsamcik.tracker.impexp.portable.LocationFileOrigin
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exports the Location data itself, independent of whether a session exists. */
@Keep // Export activity constructs the public no-argument overload from its Intent.
internal class LocationFileExporter @JvmOverloads @Inject constructor(
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : CursorAwareExporter {
	/** The source-owned reader admits coordinate-free points and reports its own empty selection. */
	override val requiresLocationData: Boolean = false
	override val canSelectDateRange: Boolean = true
	override val selectsCalendarDates: Boolean = true
	override val sensitivityMessageRes: Int = R.string.export_sensitivity_location_message
	override val mimeType: String = LocationFileJsonV1.MIME_TYPE
	override val extension: String = LocationFileJsonV1.EXTENSION

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult = exportAfter(
		context, locationData, outputStream, dateRange, null, null, null, {},
	)

	override suspend fun exportAfter(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
		afterTimeMs: Long?,
		afterId: Long?,
		database: AppDatabase?,
		verifyCollectedDataAccess: () -> Unit,
	): ExportResult {
		@Suppress("UNUSED_VARIABLE") val ignoredSequence = locationData
		return try {
			withContext(dispatchers.io) {
				val source = database ?: AppDatabase.database(context)
				FileBackedExportSpool(context, "portable-location-v1-", LocationFileJsonV1.MAX_FILE_BYTES).use { spool ->
					val (result, authorityStamp) = spool.write { stagedOutput ->
						source.withTransaction {
							val written = writePoints(source, stagedOutput, dateRange, afterTimeMs, afterId,
								verifyCollectedDataAccess)
							written to source.locationPurposeDao().savedFileExportAuthorityStamp()
						}
					}
					// Slow destinations never hold the database snapshot or its identity writes open.
					verifyCollectedDataAccess()
					check(source.locationPurposeDao().savedFileExportAuthorityStamp() == authorityStamp) {
						"Location retention changed during export"
					}
					spool.copyTo(outputStream)
					verifyCollectedDataAccess()
					check(source.locationPurposeDao().savedFileExportAuthorityStamp() == authorityStamp) {
						"Location retention changed during export"
					}
					result
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			ExportResult.Error(LocalizedString(
				R.string.export_error_with_reason,
				failure.message ?: "Location file could not be saved",
			))
		}
	}

	/** Backend prerequisite only: the complete six-source bundle surface owns the eventual caller. */
	internal suspend fun exportSession(
		context: Context,
		request: SessionLocationFileRequest,
		outputStream: OutputStream,
		database: AppDatabase,
		verifyCollectedDataAccess: () -> Unit,
		maximumBytes: Long = LocationFileJsonV1.MAX_FILE_BYTES,
	): ExportResult = try {
		withContext(dispatchers.io) {
			val reader = SessionLocationFileReader(database)
			FileBackedExportSpool(context, "portable-location-v1-", maximumBytes).use { spool ->
				val (contents, snapshot) = spool.write { stagedOutput ->
					database.withTransaction {
						verifyCollectedDataAccess()
						val selection = reader.snapshot(request)
						val written = writeSessionPoints(database, reader, selection, stagedOutput,
							assignIdentities = true, verifyCollectedDataAccess = verifyCollectedDataAccess)
						verifyCollectedDataAccess()
						reader.verify(request, selection)
						written to selection
					}
				}
				// Snapshot/identity writes finish before any potentially slow caller destination I/O.
				verifySessionContents(database, reader, request, snapshot, contents, verifyCollectedDataAccess)
				spool.copyTo(outputStream)
				verifySessionContents(database, reader, request, snapshot, contents, verifyCollectedDataAccess)
				contents.result
			}
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: Exception) {
		ExportResult.Error(LocalizedString(R.string.export_error_with_reason,
			failure.message ?: "Location file could not be saved"))
	}

	/** Re-read only the selected typed contents; unrelated source ingress is allowed to continue. */
	private suspend fun verifySessionContents(
		database: AppDatabase,
		reader: SessionLocationFileReader,
		request: SessionLocationFileRequest,
		snapshot: SessionLocationFileReader.Snapshot,
		expected: SessionContents,
		verifyCollectedDataAccess: () -> Unit,
	) {
		verifyCollectedDataAccess()
		database.withTransaction {
			reader.verify(request, snapshot)
			val discarded = object : OutputStream() {
				override fun write(value: Int) = Unit
				override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
			}
			val current = writeSessionPoints(database, reader, snapshot, discarded,
				assignIdentities = false, verifyCollectedDataAccess = verifyCollectedDataAccess)
			verifyCollectedDataAccess()
			reader.verify(request, snapshot)
			if (!current.sha256.contentEquals(expected.sha256)) {
				throw InvalidLocationFile("Selected Location data changed during export")
			}
		}
	}

	/** Staging and read-only verification share the exact codec, origins and measurement conversion. */
	private suspend fun writeSessionPoints(
		database: AppDatabase,
		reader: SessionLocationFileReader,
		selection: SessionLocationFileReader.Snapshot,
		output: OutputStream,
		assignIdentities: Boolean,
		verifyCollectedDataAccess: () -> Unit,
	): SessionContents {
		val digest = MessageDigest.getInstance("SHA-256")
		val writer = LocationFileJsonV1().writer(DigestOutputStream(output, digest))
		val identities = database.locationFileIdentityDao()
		var count = 0
		var maxTime = Long.MIN_VALUE
		var maxId = 0L
		reader.read(selection, verifyCollectedDataAccess) { row ->
			val identity = if (assignIdentities) identities.localIdentityFor(row.sample.id)
				else identities.bySampleId(row.sample.id)
					?: throw InvalidLocationFile("Selected Location identity changed during export")
			val origin = if (identity.imported) LocationFileOrigin.entries.firstOrNull {
				it.name.equals(identity.reportedOrigin, ignoreCase = true)
			} ?: LocationFileOrigin.UNKNOWN else row.fileOrigin()
			writer.point(LocationFilePointV1(identity.portableId, origin,
				LocationFileMeasurementV1.from(row.sample.toModel())))
			count++
			if (row.sample.timeMs > maxTime || row.sample.timeMs == maxTime && row.sample.id > maxId) {
				maxTime = row.sample.timeMs; maxId = row.sample.id
			}
		}
		if (count == 0) throw InvalidLocationFile("No saved Location points in this session")
		check(writer.finish() == count)
		return SessionContents(ExportResult.Success(count, maxTime, maxId), digest.digest())
	}

	private data class SessionContents(val result: ExportResult.Success, val sha256: ByteArray)

	private suspend fun writePoints(
		database: AppDatabase,
		outputStream: OutputStream,
		dateRange: LongRange?,
		initialAfterTimeMs: Long?,
		initialAfterId: Long?,
		verifyCollectedDataAccess: () -> Unit,
	): ExportResult {
		if (dateRange != null && dateRange.isEmpty()) {
			throw InvalidLocationFile("Selected Location dates are empty")
		}
		val fromMs = dateRange?.first ?: Long.MIN_VALUE
		val toMs = dateRange?.last ?: Long.MAX_VALUE
		val purposeDao = database.locationPurposeDao()
		val identityDao = database.locationFileIdentityDao()
		val writer = LocationFileJsonV1().writer(outputStream)
		var afterTimeMs = initialAfterTimeMs
		var afterId = initialAfterId
		var count = 0
		var maxTimeMs = 0L
		var maxId = 0L
		var authorityStamp: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			verifyCollectedDataAccess()
			val before = purposeDao.savedFileExportAuthorityStamp()
			if (authorityStamp != null && before != authorityStamp) {
				throw InvalidLocationFile("Location permissions or retention changed during export")
			}
			val page = purposeDao.savedFileChunk(
				fromMs, toMs, afterTimeMs, afterId, PAGE_SIZE,
			)
			if (purposeDao.savedFileExportAuthorityStamp() != before) {
				throw InvalidLocationFile("Location permissions or retention changed during export")
			}
			if (page.isEmpty()) break
			authorityStamp = before
			for (row in page) {
				currentCoroutineContext().ensureActive()
				verifyCollectedDataAccess()
				val identity = identityDao.localIdentityFor(row.sample.id)
				val origin = if (identity.imported) {
					LocationFileOrigin.entries.firstOrNull {
						it.name.equals(identity.reportedOrigin, ignoreCase = true)
					} ?: LocationFileOrigin.UNKNOWN
				} else {
					row.fileOrigin()
				}
				writer.point(LocationFilePointV1(
					pointId = identity.portableId,
					origin = origin,
					measurement = LocationFileMeasurementV1.from(row.sample.toModel()),
				))
				count++
				maxTimeMs = row.sample.timeMs
				maxId = row.sample.id
			}
			val last = page.last().sample
			afterTimeMs = last.timeMs
			afterId = last.id
			if (purposeDao.savedFileExportAuthorityStamp() != before) {
				throw InvalidLocationFile("Location permissions or retention changed during export")
			}
		}
		verifyCollectedDataAccess()
		if (authorityStamp != null && purposeDao.savedFileExportAuthorityStamp() != authorityStamp) {
			throw InvalidLocationFile("Location permissions or retention changed during export")
		}
		if (count == 0) throw InvalidLocationFile("No Location points in these dates")
		check(writer.finish() == count)
		return ExportResult.Success(recordCount = count, maxTimeMs = maxTimeMs, maxId = maxId)
	}

	internal companion object { const val PAGE_SIZE = 256 }
}

internal fun LocationPathSampleRow.fileOrigin(): LocationFileOrigin = when {
	hasSessionPurpose && hasVisibleBackgroundPurpose -> LocationFileOrigin.BOTH
	hasSessionPurpose -> LocationFileOrigin.SESSION
	hasVisibleBackgroundPurpose -> LocationFileOrigin.BACKGROUND
	else -> LocationFileOrigin.UNKNOWN
}
