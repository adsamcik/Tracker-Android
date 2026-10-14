package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.annotation.Keep
import com.adsamcik.tracker.shared.base.database.readRadioFileMembers
import com.adsamcik.tracker.stats.api.repository.HistorySource
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
			ExportResult.Error(LocalizedString(R.string.export_error_with_reason, failure.message ?: "Invalid Wi-Fi file"))
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
		var afterId: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = database.wifiCapturedFactDao().completedExportCandidateIdsPage(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				dates?.fromInclusiveMs, dates?.toExclusiveMs, afterId, 100,
			)
			if (page.isEmpty()) break
			require(page.size <= 100 && page.zipWithNext().all { (a, b) -> a < b })
			page.forEach { logicalId ->
				var captured: PortableCapturedWifiEntryV1? = null
				val result = entry.capturedWifi().export(ExportPortableCapturedWifiRequest(logicalId)) {
					value -> check(captured == null); captured = value
				}
				when (result) {
					is ExportPortableCapturedWifiResult.Exported -> {
						val product = requireNotNull(captured)
						check(product.contentChecksum == result.contentChecksum)
						val completeFile = WifiSessionFileV2(product, localChildren(database, logicalId, product))
						selectWifiSessionFile(completeFile, dates, includeSessionsAcrossDates)?.let { selected ->
							writer.session(selected)
							outsideCount = Math.addExact(outsideCount, wifiReadingsOutsideDates(selected, dates))
						}
					}
					is ExportPortableCapturedWifiResult.Unavailable,
					ExportPortableCapturedWifiResult.Deleted -> Unit
					else -> throw RadioSourceFileFormatException("Wi-Fi session cannot be read completely")
				}
			}
			if (page.size < 100) break
			afterId = page.last()
		}
		appendImportedSessions(entry, dates, includeSessionsAcrossDates) { session, outside ->
			writer.session(session)
			outsideCount = Math.addExact(outsideCount, outside)
		}
		val ambient = exportBackground(entry, dates)
		if (writer.sessionCount == 0 && ambient == null) throw RadioSourceNoDataException()
		val backgroundChildren = ambient?.let { backgroundChildren(entry, it) }.orEmpty()
		outsideCount = Math.addExact(outsideCount, wifiBackgroundReadingsOutsideDates(ambient, backgroundChildren, dates))
		writer.finish(ambient, backgroundChildren, outsideCount)
		return writer.sessionCount + (ambient?.facts?.size ?: 0) + (ambient?.gaps?.size ?: 0)
	}

	internal suspend fun exportSession(context: Context, request: SessionLocationFileRequest,
		outputStream: OutputStream, verifyCollectedDataAccess: () -> Unit = {}): ExportResult {
		val entry = source(context)
		return exportRadioSessionFile(context, entry.database(), request, HistorySource.WIFI,
			outputStream, maximumBytes, verifyCollectedDataAccess) { selection, output ->
			val writer = RadioSourceFileJsonV2Codec().wifiStreamWriter(output,
				selection.dates?.fromInclusiveMs, selection.dates?.toExclusiveMs, false)
			var outside = 0
			if (selection.retrospective) writeFile(entry, selection.dates, false, writer) else {
				for ((identity, selectors) in selection.captured.groupBy { it.entryIdentity }) {
					verifyCollectedDataAccess(); currentCoroutineContext().ensureActive()
					val evaluation = entry.importedWifiEvaluator().selectIdentityInTransaction(WifiImportedHistorySelectionKey(identity))
					val readable = evaluation as? ImportedWifiProductEvaluation.Readable
						?: throw RadioSourceFileFormatException("The selected imported Wifi recording is unavailable")
					check(readable.isReExportable)
					val fileMembers = if (selectors.any { it.importJobId == null }) null else selectors.flatMap { selector ->
						readRadioFileMembers(entry.database(), SourceDestinationOwnerEntity.SOURCE_WIFI,
							requireNotNull(selector.importJobId), requireNotNull(selector.importEntryKey), identity,
							request.expectedCollectedDataEpoch, RadioSourceFileV2.MAX_CHILDREN)
					}
					val members = fileMembers?.filter { it.kind == "OBSERVATION" }?.associateBy { it.identity }
					val gapIds = fileMembers?.filter { it.kind == "GAP" }?.mapTo(mutableSetOf()) { it.identity }
					val selected = if (members == null) readable.entry else readable.entry.selectReadings({ observation ->
						members[observation.identity.value]?.let { it.revision == observation.semanticRevision &&
							it.checksum == observation.contentChecksum.value } == true
					}, { run -> gapIds?.contains(run.identity.value) == true })
					selected?.let { value ->
						val ids = value.runs.flatMap { it.observations }.mapTo(mutableSetOf()) { it.identity.value }
						val children = entry.database().importedRadioChildDao().wifiSessionChildren(identity,
							readable.candidate.importRevision, RadioSourceFileV2.MAX_CHILDREN + 1)
						check(children.size <= RadioSourceFileV2.MAX_CHILDREN)
						val full = WifiSessionFileV2(value, mapImportedWifiSessionChildren(value,
							children.filter { it.observationIdentity in ids }))
						selectWifiSessionFile(full, selection.dates, false)?.let {
							writer.session(it); outside = Math.addExact(outside, wifiReadingsOutsideDates(it, selection.dates))
						}
					}
				}
				val background = if (selection.backgroundArchives.isEmpty()) null else {
					var saved: PortableAmbientWifiArchiveV1? = null
					val result = entry.backgroundWifi().export(ExportPortableAmbientWifiRequest(selection.dates?.fromInclusiveMs ?: 0L, selection.dates?.toExclusiveMs ?: Long.MAX_VALUE,
						setOf(AmbientWifiOrigin.PORTABLE_IMPORT), selection.backgroundArchives)) { saved = it }
					if (result !is ExportPortableAmbientWifiResult.Exported)
						throw RadioSourceFileFormatException("The selected background Wifi recording is unavailable")
					requireNotNull(saved)
				}
				if (writer.sessionCount == 0 && background == null) throw RadioSourceNoDataException()
				val details = background?.let { backgroundChildren(entry, it) }.orEmpty()
				outside = Math.addExact(outside, wifiBackgroundReadingsOutsideDates(background, details, selection.dates))
				writer.finish(background, details, outside)
				writer.sessionCount + (background?.facts?.size ?: 0) + (background?.gaps?.size ?: 0)
			}
		}
	}

	/** Caller owns complete file staging, transfer lifetime and optimistic rechecks. */
	internal suspend fun writeSelection(context: Context, selection: com.adsamcik.tracker.stats.api.repository.WifiHistorySelection,
		output: OutputStream): ExportResult = writeSelectedWifiFile(source(context), selection, output)


	internal suspend fun localChildren(
		database: AppDatabase,
		logicalId: String,
		entry: PortableCapturedWifiEntryV1,
	): List<WifiSessionChildV2> {
		val indexed = entry.runs.flatMap { run -> run.observations.map { observation ->
			observation.identity.value to (run.identity.value to observation.semanticRevision)
		} }.toMap()
		if (indexed.isEmpty()) return emptyList()
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
			for (chunk in scopeOwners.chunked(300)) {
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
		val context = currentCoroutineContext()
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
				ImportedWifiProductRangeRequest(from, to, 100, beforeStart, beforeIdentity),
			)
			page.evaluations.forEach { evaluation ->
				currentCoroutineContext().ensureActive()
				when (evaluation) {
					is ImportedWifiProductEvaluation.Readable -> {
						if (!evaluation.isReExportable ||
							evaluation.collidingLocalLogicalTrackingId != null) return@forEach
						val rows = entry.database().importedRadioChildDao().wifiSessionChildren(
							evaluation.entry.identity.value, evaluation.candidate.importRevision, RadioSourceFileV2.MAX_CHILDREN + 1)
						check(rows.size <= RadioSourceFileV2.MAX_CHILDREN)
						val full = WifiSessionFileV2(evaluation.entry, mapImportedWifiSessionChildren(evaluation.entry, rows))
						selectWifiSessionFile(full, dates, includeAcross)?.let { selected ->
							emit(selected, wifiReadingsOutsideDates(selected, dates))
						}
					}
					is ImportedWifiProductEvaluation.Unverifiable ->
						throw RadioSourceFileFormatException("Imported Wi-Fi session cannot be verified")
				}
			}
			if (!page.hasMore) break
			val last = page.evaluations.lastOrNull()?.candidate
				?: throw RadioSourceFileFormatException("Imported Wi-Fi page is incomplete")
			// The source reader orders by the newest physical member, not the entry envelope.
			beforeStart = last.newestMemberStartTimeMs
			beforeIdentity = last.newestMemberIdentity
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
		uniqueIds.chunked(300).forEach { chunk ->
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
}

internal fun LongRange.toRadioDates(): SourceExportDates {
	require(first >= 0L && !isEmpty())
	return SourceExportDates(first, if (last == Long.MAX_VALUE) Long.MAX_VALUE else Math.addExact(last, 1L))
}

internal class RadioSourceNoDataException : RuntimeException(null, null, false, false)
