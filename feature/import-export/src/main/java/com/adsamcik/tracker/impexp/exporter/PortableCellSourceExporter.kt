package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.annotation.Keep
import com.adsamcik.tracker.shared.base.database.readRadioFileMembers
import com.adsamcik.tracker.shared.base.database.selectReadings
import com.adsamcik.tracker.stats.api.repository.HistorySource
import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.CellBackgroundChildV2
import com.adsamcik.tracker.impexp.portable.CellSessionChildV2
import com.adsamcik.tracker.impexp.portable.CellSessionFileV2
import com.adsamcik.tracker.impexp.portable.CellStreamWriter
import com.adsamcik.tracker.impexp.portable.RadioSourceFileFormatException
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.radioChildDigest
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReader
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.CellCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.data.CellChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.LEGACY_CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.dao.CellCurrentDetailExpectation
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellArchiveV1
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
internal interface CellSourceExportEntryPoint {
	fun database(): AppDatabase
	fun capturedCell(): ExportPortableCapturedCell
	fun backgroundCell(): ExportPortableAmbientCell
}

/** A single Cell file contains only saved Cell product, identified children, and gaps. */
internal class PortableCellSourceExporter @Keep @JvmOverloads constructor(
	private val maximumBytes: Long = RadioSourceFileV2.MAX_FILE_BYTES,
	private val source: (Context) -> CellSourceExportEntryPoint = { context ->
		EntryPointAccessors.fromApplication(context.applicationContext,
			CellSourceExportEntryPoint::class.java)
	},
) : RadioSourceExporter {
	init { require(maximumBytes in 1..RadioSourceFileV2.MAX_FILE_BYTES) }

	override val requiresLocationData = false
	override val containsSensitiveLocationData = true
	override val sensitivityTitleRes = R.string.export_radio_sensitivity_title
	override val sensitivityMessageRes = R.string.export_cell_sensitivity_message
	override val canSelectDateRange = true
	override val selectsCalendarDates = true
	override val mimeType = RadioSourceFileV2.CELL_MIME
	override val extension = RadioSourceFileV2.CELL_EXTENSION

	override suspend fun export(context: Context, locationData: Sequence<LocationSample>,
		outputStream: OutputStream, dateRange: LongRange?): ExportResult =
		exportWithSessionSelection(context, outputStream, dateRange, true)

	override suspend fun exportWithSessionSelection(context: Context, outputStream: OutputStream,
		dateRange: LongRange?, includeSessionsAcrossDates: Boolean): ExportResult {
		val dates = dateRange?.toRadioDates()
		return try {
			val entry = source(context)
			val count = FileBackedExportSpool(context, "portable-cell-v2-", maximumBytes).use { spool ->
				val staged = spool.stage { stagedOutput ->
					entry.database().withTransaction {
						val writer = RadioSourceFileJsonV2Codec().cellStreamWriter(stagedOutput,
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
						throw RadioSourceFileFormatException("Cell file exceeds the size limit")
				}
			}
			ExportResult.Success(recordCount = count)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RadioSourceNoDataException) {
			ExportResult.Error(LocalizedString(R.string.export_radio_no_data))
		} catch (failure: RadioSourceFileFormatException) {
			ExportResult.Error(LocalizedString(R.string.export_error_with_reason,
				failure.message ?: "Invalid Cell file"))
		} catch (_: Exception) {
			ExportResult.Error(LocalizedString(R.string.export_error_with_reason,
				"Cell export is unavailable"))
		}
	}

	private suspend fun writeFile(entry: CellSourceExportEntryPoint, dates: SourceExportDates?,
		includeSessionsAcrossDates: Boolean, writer: CellStreamWriter): Int {
		val database = entry.database()
		var outsideCount = 0
		var afterId: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = database.cellCapturedFactDao().completedExportCandidateIdsPage(
				SourceDestinationOwnerEntity.SOURCE_CELL,
				dates?.fromInclusiveMs, dates?.toExclusiveMs, afterId, 100,
			)
			if (page.isEmpty()) break
			require(page.size <= 100 && page.zipWithNext().all { (a, b) -> a < b })
			page.forEach { logicalId ->
				var captured: PortableCapturedCellEntryV1? = null
				val result = entry.capturedCell().export(ExportPortableCapturedCellRequest(logicalId)) {
					value -> check(captured == null); captured = value
				}
				when (result) {
					is ExportPortableCapturedCellResult.Exported -> {
						val product = requireNotNull(captured)
						check(product.contentChecksum == result.contentChecksum)
						val completeFile = CellSessionFileV2(product, localChildren(database, logicalId, product))
						selectCellSessionFile(completeFile, dates, includeSessionsAcrossDates)?.let { selected ->
							writer.session(selected)
							outsideCount = Math.addExact(outsideCount, cellReadingsOutsideDates(selected, dates))
						}
					}
					is ExportPortableCapturedCellResult.Unavailable,
					ExportPortableCapturedCellResult.Deleted -> Unit
					else -> throw RadioSourceFileFormatException("Cell session cannot be read completely")
				}
			}
			if (page.size < 100) break
			afterId = page.last()
		}
		appendImportedSessions(database, dates, includeSessionsAcrossDates) { session, outside ->
			writer.session(session)
			outsideCount = Math.addExact(outsideCount, outside)
		}
		val ambient = exportBackground(entry, dates)
		if (writer.sessionCount == 0 && ambient == null) throw RadioSourceNoDataException()
		val backgroundChildren = ambient?.let { backgroundChildren(entry, it) }.orEmpty()
		outsideCount = Math.addExact(outsideCount, cellBackgroundReadingsOutsideDates(ambient, backgroundChildren, dates))
		writer.finish(ambient, backgroundChildren, outsideCount)
		return writer.sessionCount + (ambient?.facts?.size ?: 0) + (ambient?.gaps?.size ?: 0)
	}

	internal suspend fun exportSession(context: Context, request: SessionLocationFileRequest,
		outputStream: OutputStream, verifyCollectedDataAccess: () -> Unit = {}): ExportResult {
		val entry = source(context)
		return exportRadioSessionFile(context, entry.database(), request, HistorySource.CELL,
			outputStream, maximumBytes, verifyCollectedDataAccess) { selection, output ->
			val writer = RadioSourceFileJsonV2Codec().cellStreamWriter(output,
				selection.dates?.fromInclusiveMs, selection.dates?.toExclusiveMs, false)
			var outside = 0
			if (selection.retrospective) writeFile(entry, selection.dates, false, writer) else {
				for ((identity, selectors) in selection.captured.groupBy { it.entryIdentity }) {
					verifyCollectedDataAccess(); currentCoroutineContext().ensureActive()
					val evaluation = ImportedCellProductReader(entry.database()).selectIdentityInTransaction(PortableCellOpaqueIdentity(identity))
					val readable = evaluation as? ImportedCellProductEvaluation.Readable
						?: throw RadioSourceFileFormatException("The selected imported Cell recording is unavailable")
					check(readable.isReExportable)
					val fileMembers = if (selectors.any { it.importJobId == null }) null else selectors.flatMap { selector ->
						readRadioFileMembers(entry.database(), SourceDestinationOwnerEntity.SOURCE_CELL,
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
						val children = entry.database().importedRadioChildDao().cellSessionChildren(identity,
							readable.candidate.importRevision, RadioSourceFileV2.MAX_CHILDREN + 1)
						check(children.size <= RadioSourceFileV2.MAX_CHILDREN)
						val full = CellSessionFileV2(value, mapImportedCellSessionChildren(value,
							children.filter { it.observationIdentity in ids }))
						selectCellSessionFile(full, selection.dates, false)?.let {
							writer.session(it); outside = Math.addExact(outside, cellReadingsOutsideDates(it, selection.dates))
						}
					}
				}
				val background = if (selection.backgroundArchives.isEmpty()) null else {
					var saved: PortableAmbientCellArchiveV1? = null
					val result = entry.backgroundCell().export(ExportPortableAmbientCellRequest(selection.dates?.fromInclusiveMs ?: 0L, selection.dates?.toExclusiveMs ?: Long.MAX_VALUE,
						setOf(AmbientCellOrigin.PORTABLE_IMPORT), selection.backgroundArchives)) { saved = it }
					if (result !is ExportPortableAmbientCellResult.Exported)
						throw RadioSourceFileFormatException("The selected background Cell recording is unavailable")
					requireNotNull(saved)
				}
				if (writer.sessionCount == 0 && background == null) throw RadioSourceNoDataException()
				val details = background?.let { backgroundChildren(entry, it) }.orEmpty()
				outside = Math.addExact(outside, cellBackgroundReadingsOutsideDates(background, details, selection.dates))
				writer.finish(background, details, outside)
				writer.sessionCount + (background?.facts?.size ?: 0) + (background?.gaps?.size ?: 0)
			}
		}
	}

	/** Caller owns complete file staging, transfer lifetime and optimistic rechecks. */
	internal suspend fun writeSelection(context: Context, selection: com.adsamcik.tracker.stats.api.repository.CellHistoryEntrySelection,
		output: OutputStream): ExportResult = writeSelectedCellFile(source(context), selection, output)


	internal suspend fun localChildren(database: AppDatabase, logicalId: String,
		entry: PortableCapturedCellEntryV1): List<CellSessionChildV2> {
		val indexed = entry.runs.flatMap { run -> run.observations.map { observation ->
			observation.identity.value to (run.identity.value to observation.semanticRevision)
		} }.toMap()
		if (indexed.isEmpty()) return emptyList()
		val dao = database.radioObservationChildDao()
		val owners = dao.cellCurrentExportOwners(logicalId,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY + 1)
		require(owners.size <= CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY)
		val selectedOwners = owners.filter { owner ->
			val observationId = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.OBSERVATION, owner.logicalFactId).value
			val runId = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN, owner.serviceRunId).value
			indexed[observationId] == (runId to owner.semanticRevision)
		}
		check(selectedOwners.size == indexed.size)
		val rows = mutableListOf<CellCapturedChildEntity>()
		for ((scope, scopeOwners) in selectedOwners.groupBy { it.serviceRunId to it.semanticRevision }) {
			for (chunk in scopeOwners.chunked(300)) {
				currentCoroutineContext().ensureActive()
				val remaining = RadioSourceFileV2.MAX_CHILDREN - rows.size
				val page = dao.cellCurrentSessionChildrenForOwners(logicalId, scope.first,
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION, scope.second,
					chunk.map { it.logicalFactId }, remaining + 1)
				currentCoroutineContext().ensureActive()
				require(page.size <= remaining)
				rows += page
			}
		}
		val selectedRequirements = selectedOwners.map { owner ->
			CellCurrentDetailExpectation(owner.logicalFactId, owner.semanticRevision,
				requireNotNull(owner.payloadVersion), owner.acceptedChildCount)
		}
		requireCompleteCellDetails(selectedRequirements, rows.groupingBy {
			it.logicalFactId to it.semanticRevision
		}.eachCount())
		val context = currentCoroutineContext()
		return rows.map { row ->
			context.ensureActive()
			check(RadioObservationChildIntegrity.cell(CellChildChecksumInput(
				RadioChildOwner(RadioChildPurpose.SESSION, row.writerProjectionId,
					row.writerProjectionVersion, row.logicalFactId, row.semanticRevision, row.itemIndex),
				row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
				row.towerIdentity, row.radioType, row.registered, row.signalDbm,
			)) == row.contentChecksum)
			val observationId = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.OBSERVATION, row.logicalFactId).value
			val (runId, revision) = requireNotNull(indexed[observationId])
			check(revision == row.semanticRevision)
			CellSessionChildV2(entry.identity.value, runId, observationId,
				row.semanticRevision, row.itemIndex, row.providerTimestampNanos,
				row.observedWallTimeMs, row.wallTimeUncertaintyMs, row.towerIdentity,
				row.radioType, row.registered, row.signalDbm,
				radioChildDigest("cell", listOf(entry.identity.value, runId, observationId),
					listOf(row.semanticRevision, row.itemIndex, row.providerTimestampNanos,
						row.observedWallTimeMs, row.wallTimeUncertaintyMs, row.towerIdentity,
						row.radioType, row.registered, row.signalDbm)))
		}
	}

	private suspend fun appendImportedSessions(database: AppDatabase, dates: SourceExportDates?,
		includeAcross: Boolean, emit: (CellSessionFileV2, Int) -> Unit) {
		val reader = ImportedCellProductReader(database)
		val from = dates?.fromInclusiveMs ?: 0L
		val to = dates?.toExclusiveMs ?: Long.MAX_VALUE
		var beforeStart: Long? = null
		var beforeIdentity: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = reader.selectExportWallRangePageInTransaction(from, to, 100,
				beforeStart, beforeIdentity)
			if (page.isEmpty()) break
			page.forEach { evaluation ->
				currentCoroutineContext().ensureActive()
				when (evaluation) {
					is ImportedCellProductEvaluation.Readable -> {
						if (!evaluation.isReExportable || evaluation.localOriginHandle != null) return@forEach
						val rows = database.importedRadioChildDao().cellSessionChildren(
							evaluation.entry.identity.value, evaluation.candidate.importRevision, RadioSourceFileV2.MAX_CHILDREN + 1)
						check(rows.size <= RadioSourceFileV2.MAX_CHILDREN)
						val full = CellSessionFileV2(evaluation.entry, mapImportedCellSessionChildren(evaluation.entry, rows))
						selectCellSessionFile(full, dates, includeAcross)?.let { selected ->
							emit(selected, cellReadingsOutsideDates(selected, dates))
						}
					}
					is ImportedCellProductEvaluation.Unverifiable ->
						throw RadioSourceFileFormatException("Imported Cell session cannot be verified")
				}
			}
			if (page.size < 100) break
			val last = page.last().candidate
			beforeStart = last.startTimeMs
			beforeIdentity = last.identity
		}
	}

	private suspend fun exportBackground(entry: CellSourceExportEntryPoint,
		dates: SourceExportDates?): PortableAmbientCellArchiveV1? {
		var archive: PortableAmbientCellArchiveV1? = null
		val result = entry.backgroundCell().export(ExportPortableAmbientCellRequest(
			dates?.fromInclusiveMs ?: 0L, dates?.toExclusiveMs ?: Long.MAX_VALUE,
			AmbientCellOrigin.entries.toSet())) { value -> check(archive == null); archive = value }
		return when (result) {
			is ExportPortableAmbientCellResult.Exported -> requireNotNull(archive)
			ExportPortableAmbientCellResult.NoData -> null
			else -> throw RadioSourceFileFormatException("Background Cell cannot be read completely")
		}
	}

	private suspend fun backgroundChildren(source: CellSourceExportEntryPoint,
		archive: PortableAmbientCellArchiveV1): List<CellBackgroundChildV2> {
		val database = source.database()
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
			val local = database.importedRadioChildDao().localCellFactIds(chunk).toSet()
			context.ensureActive()
			val imported = database.importedRadioChildDao().importedCellFactIds(chunk).toSet()
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
			database.radioObservationChildDao().cellBackgroundChildrenForRevision(
				factId, revision, limit)
		}
		val importedRows = readBoundedBackgroundRadioChildren(
			archive.facts.filter { it.identity in importedSet }.map { it.identity to it.semanticRevision },
			RadioSourceFileV2.MAX_CHILDREN - rows.size,
		) { factId, revision, limit ->
			database.importedRadioChildDao().cellBackgroundChildrenForRevision(
				factId, revision, limit)
		}
		require(rows.size + importedRows.size <= RadioSourceFileV2.MAX_CHILDREN)
		val local = rows.map { row ->
			context.ensureActive()
			check(RadioObservationChildIntegrity.cell(CellChildChecksumInput(
				RadioChildOwner(RadioChildPurpose.BACKGROUND, row.writerId, row.writerVersion,
					row.logicalFactId, row.semanticRevision, row.itemIndex),
				row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
				row.towerIdentity, row.radioType, row.registered, row.signalDbm,
			)) == row.contentChecksum)
			CellBackgroundChildV2(row.logicalFactId, row.semanticRevision, row.itemIndex,
				row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
				row.towerIdentity, row.radioType, row.registered, row.signalDbm,
				radioChildDigest("cell", listOf(row.logicalFactId), listOf(row.semanticRevision,
					row.itemIndex, row.providerTimestampNanos, row.observedWallTimeMs,
					row.wallTimeUncertaintyMs, row.towerIdentity, row.radioType,
					row.registered, row.signalDbm)))
		}
		val imported = importedRows.map { row ->
			context.ensureActive()
			CellBackgroundChildV2(row.factId, row.semanticRevision, row.itemIndex,
				row.providerTimestampNanos, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
				row.towerIdentity, row.radioType, row.registered, row.signalDbm, row.contentChecksum)
		}
		val byOwner = imported.groupBy {
			context.ensureActive()
			Triple(it.factIdentity, it.semanticRevision, it.itemIndex)
		}
		check(byOwner.values.all { duplicates ->
			context.ensureActive()
			duplicates.distinct().size == 1
		})
		return local + byOwner.values.map {
			context.ensureActive()
			it.first()
		}
	}
}

/** v5 summaries remain valid; every v6 native accepted child must be present in the file. */
internal fun requireCompleteCellDetails(
	requirements: List<CellCurrentDetailExpectation>,
	childCounts: Map<Pair<String, Long>, Int>,
) {
	requirements.forEach { requirement ->
		check(requirement.payloadVersion in
			LEGACY_CELL_WAL_PAYLOAD_VERSION..CELL_WAL_PAYLOAD_VERSION) {
			"Saved Cell carrier version is unavailable"
		}
		if (requirement.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			check((childCounts[requirement.logicalFactId to requirement.semanticRevision] ?: 0) ==
				requirement.acceptedChildCount) {
				"Saved Cell tower details are incomplete"
			}
		}
	}
}
