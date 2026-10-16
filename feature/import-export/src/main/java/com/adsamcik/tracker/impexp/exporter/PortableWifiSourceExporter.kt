package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.stats.api.repository.WifiHistorySelection

import android.content.Context
import androidx.annotation.Keep
import com.adsamcik.tracker.shared.base.database.readRadioFileMembers
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.selectReadings
import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.RadioSourceFileFormatException
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.WifiBackgroundChildV2
import com.adsamcik.tracker.impexp.portable.WifiSessionChildV2
import com.adsamcik.tracker.impexp.portable.WifiSessionFileV2
import com.adsamcik.tracker.impexp.portable.WifiStreamWriter
import com.adsamcik.tracker.impexp.portable.radioChildDigest
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.isSupportedWifiWalPayloadVersion
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.WifiCapturedPortableFormatV1
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WifiSourceExportEntryPoint {
	fun database(): AppDatabase
	fun capturedWifi(): ExportPortableCapturedWifi
	fun backgroundWifi(): ExportPortableAmbientWifi
	fun importedWifiEvaluator(): ImportedWifiProductEvaluator
}

/** A single Wi-Fi file contains only saved Wi-Fi product, identified children, and recorded gaps. */
internal class PortableWifiSourceExporter @Keep @JvmOverloads constructor(
	private val maximumBytes: Long = RadioSourceFileV2.MAX_FILE_BYTES,
	private val source: (Context) -> WifiSourceExportEntryPoint = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext, WifiSourceExportEntryPoint::class.java,
		)
	},
) : RadioSourceExporter {
	init { require(maximumBytes in 1..RadioSourceFileV2.MAX_FILE_BYTES) }

	override val requiresLocationData = false
	override val containsSensitiveLocationData = true
	override val sensitivityTitleRes = R.string.export_radio_sensitivity_title
	override val sensitivityMessageRes = R.string.export_wifi_sensitivity_message
	override val canSelectDateRange = true
	override val selectsCalendarDates = true
	override val mimeType = RadioSourceFileV2.WIFI_MIME
	override val extension = RadioSourceFileV2.WIFI_EXTENSION

	override suspend fun export(
		context: Context, locationData: Sequence<LocationSample>, outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult = exportWithSessionSelection(context, outputStream, dateRange, true)

	override suspend fun exportWithSessionSelection(
		context: Context, outputStream: OutputStream, dateRange: LongRange?,
		includeSessionsAcrossDates: Boolean,
	): ExportResult {
		val dates = dateRange?.toRadioDates()
		return try {
			val entry = source(context)
			val count = FileBackedExportSpool(context, "portable-wifi-v2-", maximumBytes).use { spool ->
				val staged = spool.stage { stagedOutput ->
					entry.database().withTransaction {
						val writer = RadioSourceFileJsonV2Codec().wifiStreamWriter(stagedOutput,
							dates?.fromInclusiveMs, dates?.toExclusiveMs, includeSessionsAcrossDates)
						writeFile(entry, dates, includeSessionsAcrossDates, writer)
					}
				}
				when (staged) {
					is FileBackedExportStage.Complete -> {
						spool.copyTo(outputStream)
						staged.value
					}
					FileBackedExportStage.EncodedSizeExceeded ->
						throw RadioSourceFileFormatException("Wi-Fi file exceeds the size limit")
				}
			}
			ExportResult.Success(recordCount = count)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RadioSourceNoDataException) {
			ExportResult.Error(LocalizedString(R.string.export_radio_no_data))
		} catch (failure: RadioSourceFileFormatException) {
			ExportResult.Error(LocalizedString(R.string.export_error_with_reason, failure.message ?:
                "Invalid Wi-Fi file"))
		} catch (_: Exception) {
			ExportResult.Error(LocalizedString(R.string.export_error_with_reason, "Wi-Fi export is unavailable"))
		}
	}

	private suspend fun writeFile(
		entry: WifiSourceExportEntryPoint,
		dates: SourceExportDates?,
		includeSessionsAcrossDates: Boolean,
		writer: WifiStreamWriter,
	): Int {
		val database = entry.database()
		var outsideCount = 0
		appendNativeSessions(entry, database, dates, includeSessionsAcrossDates) { selected ->
			writer.session(selected)
			outsideCount = Math.addExact(outsideCount, wifiReadingsOutsideDates(selected, dates))
		}
		appendImportedSessions(entry, dates, includeSessionsAcrossDates) { session, outside ->
			writer.session(session)
			outsideCount = Math.addExact(outsideCount, outside)
		}
		val ambient = exportBackground(entry, dates)
		return finishFile(entry, dates, writer, ambient, outsideCount)
	}

	private suspend fun appendNativeSessions(
		entry: WifiSourceExportEntryPoint,
		database: AppDatabase,
		dates: SourceExportDates?,
		includeAcross: Boolean,
		emit: (WifiSessionFileV2) -> Unit,
	) {
		var afterId: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = database.wifiCapturedFactDao().completedExportCandidateIdsPage(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				dates?.fromInclusiveMs, dates?.toExclusiveMs, afterId, WIFI_EXPORT_PAGE_SIZE,
			)
			if (page.isEmpty()) {
				break
			}
			require(page.size <= WIFI_EXPORT_PAGE_SIZE && page.zipWithNext().all { (a, b) -> a < b })
			page.forEach { logicalId ->
				readNativeSession(entry, database, logicalId, dates, includeAcross)?.let(emit)
			}
			if (page.size < WIFI_EXPORT_PAGE_SIZE) {
				break
			}
			afterId = page.last()
		}
	}

	private suspend fun readNativeSession(
		entry: WifiSourceExportEntryPoint,
		database: AppDatabase,
		logicalId: String,
		dates: SourceExportDates?,
		includeAcross: Boolean,
	): WifiSessionFileV2? {
		var captured: PortableCapturedWifiEntryV1? = null
		val result = entry.capturedWifi().export(ExportPortableCapturedWifiRequest(logicalId)) { value ->
			check(captured == null)
			captured = value
		}
		return when (result) {
			is ExportPortableCapturedWifiResult.Exported -> {
				val product = requireNotNull(captured)
				check(product.contentChecksum == result.contentChecksum)
				val completeFile = WifiSessionFileV2(product, localChildren(database, logicalId, product))
				selectWifiSessionFile(completeFile, dates, includeAcross)
			}
			is ExportPortableCapturedWifiResult.Unavailable,
			ExportPortableCapturedWifiResult.Deleted -> null
			else -> throw RadioSourceFileFormatException("Wi-Fi session cannot be read completely")
		}
	}

	internal suspend fun exportSession(context: Context, request: SessionLocationFileRequest,
		outputStream: OutputStream, verifyCollectedDataAccess: () -> Unit = {}): ExportResult {
		val entry = source(context)
		return exportRadioSessionFile(context, entry.database(), request, HistorySource.WIFI,
			outputStream, maximumBytes, verifyCollectedDataAccess) { selection, output ->
			val writer = RadioSourceFileJsonV2Codec().wifiStreamWriter(output,
				selection.dates?.fromInclusiveMs, selection.dates?.toExclusiveMs, false)
			if (selection.retrospective) {
				writeFile(entry, selection.dates, false, writer)
			} else {
				val outside = appendSelectedImportedSessions(entry, request, selection, verifyCollectedDataAccess, writer)
				val background = exportSelectedBackground(entry, selection)
				finishFile(entry, selection.dates, writer, background, outside)
			}
		}
	}

	private suspend fun appendSelectedImportedSessions(
		entry: WifiSourceExportEntryPoint,
		request: SessionLocationFileRequest,
		selection: RadioSessionFileSelection,
		verifyCollectedDataAccess: () -> Unit,
		writer: WifiStreamWriter,
	): Int {
		var outside = 0
		for ((identity, selectors) in selection.captured.groupBy { it.entryIdentity }) {
			verifyCollectedDataAccess(); currentCoroutineContext().ensureActive()
			readSelectedImportedSession(entry, request, identity, selectors, selection.dates)?.let { selected ->
				writer.session(selected)
				outside = Math.addExact(outside, wifiReadingsOutsideDates(selected, selection.dates))
			}
		}
		return outside
	}

	private suspend fun readSelectedImportedSession(
		entry: WifiSourceExportEntryPoint,
		request: SessionLocationFileRequest,
		identity: String,
		selectors: List<OrganizationSourceSelector.CapturedEntry>,
		dates: SourceExportDates?,
	): WifiSessionFileV2? {
		val evaluation = entry.importedWifiEvaluator()
			.selectIdentityInTransaction(WifiImportedHistorySelectionKey(identity))
		val readable = evaluation as? ImportedWifiProductEvaluation.Readable
			?: throw RadioSourceFileFormatException("The selected imported Wifi recording is unavailable")
		check(readable.isReExportable)
		val fileMembers = if (selectors.any { it.importJobId == null }) {
			null
		} else {
			selectors.flatMap { selector ->
				readRadioFileMembers(entry.database(), SourceDestinationOwnerEntity.SOURCE_WIFI,
					requireNotNull(selector.importJobId), requireNotNull(selector.importEntryKey), identity,
					request.expectedCollectedDataEpoch, RadioSourceFileV2.MAX_CHILDREN)
			}
		}
		val members = fileMembers?.filter { it.kind == "OBSERVATION" }?.associateBy { it.identity }
		val gapIds = fileMembers?.filter { it.kind == "GAP" }?.mapTo(mutableSetOf()) { it.identity }
		val selected = if (members == null) {
			readable.entry
		} else {
			readable.entry.selectReadings({ observation ->
				members[observation.identity.value]?.let { it.revision == observation.semanticRevision &&
					it.checksum == observation.contentChecksum.value } == true
			}, { run -> gapIds?.contains(run.identity.value) == true })
		}
		return selected?.let { value ->
			val ids = value.runs.flatMap { it.observations }.mapTo(mutableSetOf()) { it.identity.value }
			val full = readImportedSessionFile(entry.database(), identity, value,
				readable.candidate.importRevision, ids)
			selectWifiSessionFile(full, dates, false)
		}
	}


	private suspend fun exportSelectedBackground(
		entry: WifiSourceExportEntryPoint,
		selection: RadioSessionFileSelection,
	): PortableAmbientWifiArchiveV1? {
		return if (selection.backgroundArchives.isEmpty()) {
			null
		} else {
			var saved: PortableAmbientWifiArchiveV1? = null
			val result = entry.backgroundWifi().export(ExportPortableAmbientWifiRequest(
				selection.dates?.fromInclusiveMs ?: 0L,
				selection.dates?.toExclusiveMs ?: Long.MAX_VALUE,
				setOf(AmbientWifiOrigin.PORTABLE_IMPORT), selection.backgroundArchives)) { saved = it }
			if (result !is ExportPortableAmbientWifiResult.Exported) {
				throw RadioSourceFileFormatException("The selected background Wifi recording is unavailable")
			}
			requireNotNull(saved)
		}
	}

	private suspend fun finishFile(
		entry: WifiSourceExportEntryPoint,
		dates: SourceExportDates?,
		writer: WifiStreamWriter,
		background: PortableAmbientWifiArchiveV1?,
		outside: Int,
	): Int {
		if (writer.sessionCount == 0 && background == null) {
			throw RadioSourceNoDataException()
		}
		val details = background?.let { backgroundChildren(entry, it) }.orEmpty()
		val totalOutside = Math.addExact(outside, wifiBackgroundReadingsOutsideDates(background, details, dates))
		writer.finish(background, details, totalOutside)
		return writer.sessionCount + (background?.facts?.size ?: 0) + (background?.gaps?.size ?: 0)
	}

	/** Caller owns complete file staging, transfer lifetime and optimistic rechecks. */
	internal suspend fun writeSelection(context: Context,
        selection: WifiHistorySelection,
		output: OutputStream): ExportResult = writeSelectedWifiFile(source(context), selection, output)


	internal suspend fun localChildren(
		database: AppDatabase,
		logicalId: String,
		entry: PortableCapturedWifiEntryV1,
	): List<WifiSessionChildV2> {
		val indexed = entry.runs.flatMap { run -> run.observations.map { observation ->
			observation.identity.value to (run.identity.value to observation.semanticRevision)
		} }.toMap()
		if (indexed.isEmpty()) {
			return emptyList()
		}
		val dao = database.radioObservationChildDao()
		val owners = dao.wifiCurrentExportOwners(logicalId,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY + 1)
		require(owners.size <= WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY)
		val selectedOwners = owners.filter { owner ->
			val observationId = PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.OBSERVATION, owner.logicalFactId).value
			val runId = PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.PHYSICAL_RUN, owner.serviceRunId).value
			indexed[observationId] == (runId to owner.semanticRevision)
		}
		check(selectedOwners.size == indexed.size)
		val rows = mutableListOf<WifiCapturedChildEntity>()
		for ((scope, scopeOwners) in selectedOwners.groupBy { it.serviceRunId to it.semanticRevision }) {
			for (chunk in scopeOwners.chunked(WIFI_DETAIL_QUERY_BATCH_SIZE)) {
				currentCoroutineContext().ensureActive()
				val remaining = RadioSourceFileV2.MAX_CHILDREN - rows.size
				val page = dao.wifiCurrentSessionChildrenForOwners(logicalId, scope.first,
					SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, scope.second,
					chunk.map { it.logicalFactId }, remaining + 1)
				currentCoroutineContext().ensureActive()
				require(page.size <= remaining)
				rows += page
			}
		}
		val childCounts = rows.groupingBy { it.logicalFactId to it.semanticRevision }.eachCount()
		selectedOwners.forEach { owner ->
			check(owner.payloadVersion?.let(::isSupportedWifiWalPayloadVersion) != false) {
				"Saved Wi-Fi carrier version is unavailable"
			}
			if (owner.payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
				check((childCounts[owner.logicalFactId to owner.semanticRevision] ?: 0) ==
					owner.acceptedChildCount) { "Saved Wi-Fi network details are incomplete" }
			}
		}
		return mapLocalWifiSessionChildren(rows, indexed, entry, currentCoroutineContext())
	}

	private suspend fun appendImportedSessions(
		entry: WifiSourceExportEntryPoint,
		dates: SourceExportDates?,
		includeAcross: Boolean,
		emit: (WifiSessionFileV2, Int) -> Unit,
	) {
		val from = dates?.fromInclusiveMs ?: 0L
		val to = dates?.toExclusiveMs ?: Long.MAX_VALUE
		var beforeStart: Long? = null
		var beforeIdentity: PortableWifiOpaqueIdentity? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = entry.importedWifiEvaluator().selectExportRangeInTransaction(
				ImportedWifiProductRangeRequest(from, to, WIFI_EXPORT_PAGE_SIZE, beforeStart, beforeIdentity),
			)
			page.evaluations.forEach { evaluation ->
				appendImportedSession(entry, evaluation, dates, includeAcross, emit)
			}
			if (!page.hasMore) {
				break
			}
			val last = page.evaluations.lastOrNull()?.candidate
				?: throw RadioSourceFileFormatException("Imported Wi-Fi page is incomplete")
			// The source reader orders by the newest physical member, not the entry envelope.
			beforeStart = last.newestMemberStartTimeMs
			beforeIdentity = last.newestMemberIdentity
		}
	}

	private suspend fun appendImportedSession(
		entry: WifiSourceExportEntryPoint, evaluation: ImportedWifiProductEvaluation, dates: SourceExportDates?,
		includeAcross: Boolean, emit: (WifiSessionFileV2, Int) -> Unit,
	) {
		currentCoroutineContext().ensureActive()
		when (evaluation) {
			is ImportedWifiProductEvaluation.Readable -> {
				if (!evaluation.isReExportable ||
					evaluation.collidingLocalLogicalTrackingId != null) {
					return
				}
				val full = readImportedSessionFile(entry.database(), evaluation.entry.identity.value,
					evaluation.entry, evaluation.candidate.importRevision)
				selectWifiSessionFile(full, dates, includeAcross)?.let { selected ->
					emit(selected, wifiReadingsOutsideDates(selected, dates))
				}
			}
			is ImportedWifiProductEvaluation.Unverifiable ->
				throw RadioSourceFileFormatException("Imported Wi-Fi session cannot be verified")
		}
	}

	private suspend fun exportBackground(
		entry: WifiSourceExportEntryPoint,
		dates: SourceExportDates?,
	): PortableAmbientWifiArchiveV1? {
		var archive: PortableAmbientWifiArchiveV1? = null
		val result = entry.backgroundWifi().export(
			ExportPortableAmbientWifiRequest(
				dates?.fromInclusiveMs ?: 0L,
				dates?.toExclusiveMs ?: Long.MAX_VALUE,
				AmbientWifiOrigin.entries.toSet(),
			),
		) { value -> check(archive == null); archive = value }
		return when (result) {
			is ExportPortableAmbientWifiResult.Exported -> requireNotNull(archive)
			ExportPortableAmbientWifiResult.NoData -> null
			else -> throw RadioSourceFileFormatException("Background Wi-Fi cannot be read completely")
		}
	}

	private suspend fun backgroundChildren(
		entry: WifiSourceExportEntryPoint,
		archive: PortableAmbientWifiArchiveV1,
	): List<WifiBackgroundChildV2> {
		val database = entry.database()
		val context = currentCoroutineContext()
		context.ensureActive()
		val uniqueIds = archive.facts.map {
			context.ensureActive()
			it.identity
		}.distinct()
		val localIds = mutableListOf<String>()
		val importedIds = mutableListOf<String>()
		uniqueIds.chunked(WIFI_DETAIL_QUERY_BATCH_SIZE).forEach { chunk ->
			context.ensureActive()
			val local = database.importedRadioChildDao().localWifiFactIds(chunk).toSet()
			context.ensureActive()
			val imported = database.importedRadioChildDao().importedWifiFactIds(chunk).toSet()
			context.ensureActive()
			check(local.intersect(imported).isEmpty() && local + imported == chunk.toSet())
			localIds += local
			importedIds += imported
		}
		val localSet = localIds.toSet()
		val importedSet = importedIds.toSet()
		val rows = readBoundedBackgroundRadioChildren(
			archive.facts.filter { it.identity in localSet }.map { it.identity to it.semanticRevision },
		) { factId, revision, limit ->
			database.radioObservationChildDao().wifiBackgroundChildrenForRevision(
				factId, revision, limit,
			)
		}
		val importedRows = readBoundedBackgroundRadioChildren(
			archive.facts.filter { it.identity in importedSet }.map { it.identity to it.semanticRevision },
			RadioSourceFileV2.MAX_CHILDREN - rows.size,
		) { factId, revision, limit ->
			database.importedRadioChildDao().wifiBackgroundChildrenForRevision(
				factId, revision, limit,
			)
		}
		require(rows.size <= RadioSourceFileV2.MAX_CHILDREN)
		require(rows.size + importedRows.size <= RadioSourceFileV2.MAX_CHILDREN)
		return mapWifiBackgroundChildren(rows, importedRows, context)
	}
}

internal fun LongRange.toRadioDates(): SourceExportDates {
	require(first >= 0L && !isEmpty())
	return SourceExportDates(first, if (last == Long.MAX_VALUE) {
		Long.MAX_VALUE
	} else {
		Math.addExact(last, 1L)
	})
}

internal class RadioSourceNoDataException : RuntimeException(null, null, false, false)

private suspend fun readImportedSessionFile(
	database: AppDatabase,
	identity: String,
	product: PortableCapturedWifiEntryV1,
	importRevision: Long,
	selectedObservationIds: Set<String>? = null,
): WifiSessionFileV2 {
	val children = database.importedRadioChildDao().wifiSessionChildren(identity,
		importRevision, RadioSourceFileV2.MAX_CHILDREN + 1)
	check(children.size <= RadioSourceFileV2.MAX_CHILDREN)
	val selectedChildren = if (selectedObservationIds == null) {
		children
	} else {
		children.filter { it.observationIdentity in selectedObservationIds }
	}
	return WifiSessionFileV2(product, mapImportedWifiSessionChildren(product, selectedChildren))
}

private fun mapLocalWifiSessionChildren(
	rows: List<WifiCapturedChildEntity>, indexed: Map<String, Pair<String, Long>>,
	entry: PortableCapturedWifiEntryV1, context: kotlin.coroutines.CoroutineContext,
): List<WifiSessionChildV2> {
	return rows.map { row ->
		context.ensureActive()
		check(RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(
			RadioChildOwner(RadioChildPurpose.SESSION, row.writerProjectionId,
				row.writerProjectionVersion, row.logicalFactId, row.semanticRevision, row.itemIndex),
			row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
			row.bssid, row.ssid, row.frequencyMhz, row.signalDbm,
		)) == row.contentChecksum)
		val observationId = PortableWifiOpaqueIdentity.derive(
			PortableWifiIdentityKind.OBSERVATION, row.logicalFactId,
		).value
		val (runId, revision) = requireNotNull(indexed[observationId])
		check(revision == row.semanticRevision)
		val identities = listOf(entry.identity.value, runId, observationId)
		WifiSessionChildV2(entry.identity.value, runId, observationId,
			row.semanticRevision, row.itemIndex, row.providerTimestampNanos,
			row.observedWallTimeMs, row.wallTimeUncertaintyMs, row.bssid, row.ssid,
			row.frequencyMhz, row.signalDbm,
			radioChildDigest("wifi", identities, listOf(row.semanticRevision, row.itemIndex,
				row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
				row.bssid, row.ssid, row.frequencyMhz, row.signalDbm)))
	}
}

private fun mapWifiBackgroundChildren(
	rows: List<com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity>,
	importedRows: List<com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity>,
	context: kotlin.coroutines.CoroutineContext,
): List<WifiBackgroundChildV2> {
	val localChildren = rows.map { row ->
		context.ensureActive()
		check(RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(
			RadioChildOwner(RadioChildPurpose.BACKGROUND, row.writerId, row.writerVersion,
				row.logicalFactId, row.semanticRevision, row.itemIndex),
			row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
			row.bssid, row.ssid, row.frequencyMhz, row.signalDbm,
		)) == row.contentChecksum)
		val identity = listOf(row.logicalFactId)
		WifiBackgroundChildV2(row.logicalFactId, row.semanticRevision, row.itemIndex,
			row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
			row.bssid, row.ssid, row.frequencyMhz, row.signalDbm,
			radioChildDigest("wifi", identity, listOf(row.semanticRevision, row.itemIndex,
				row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
				row.bssid, row.ssid, row.frequencyMhz, row.signalDbm)))
	}
	val imported = importedRows.map { row ->
		context.ensureActive()
		WifiBackgroundChildV2(row.factId, row.semanticRevision, row.itemIndex,
			row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
			row.bssid, row.ssid, row.frequencyMhz, row.signalDbm, row.contentChecksum)
	}
	val byOwner = imported.groupBy {
		context.ensureActive()
		Triple(it.factIdentity, it.semanticRevision, it.itemIndex)
	}
	check(byOwner.values.all { duplicates ->
		context.ensureActive()
		duplicates.distinct().size == 1
	})
	return localChildren + byOwner.values.map {
		context.ensureActive()
		it.first()
	}
}

private const val WIFI_EXPORT_PAGE_SIZE = 100
private const val WIFI_DETAIL_QUERY_BATCH_SIZE = 300
