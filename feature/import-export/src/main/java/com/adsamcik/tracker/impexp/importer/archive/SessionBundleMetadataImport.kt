package com.adsamcik.tracker.impexp.importer.archive

import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.PermanentImportInputException
import com.adsamcik.tracker.impexp.importer.computeImportJobId
import com.adsamcik.tracker.impexp.importer.file.portableAmbientStepsArchiveKey
import com.adsamcik.tracker.impexp.importer.file.portableStepsEntryKey
import com.adsamcik.tracker.impexp.importer.file.radioEntryKey
import com.adsamcik.tracker.impexp.portable.SensorSourceFileFormatException
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.PortableActivityFormatException
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsFormatException
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.PortableCountDomainJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileFormatException
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import com.adsamcik.tracker.impexp.portable.SessionBundleChildV1
import com.adsamcik.tracker.impexp.portable.portableAmbientStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.portableStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.readPortableBytes
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only compact identities survive preflight; complete source payloads remain in staged typed files. */
internal class PreparedSessionBundleMetadata internal constructor(
	val manifest: SessionBundleManifestV1,
	internal val claims: List<SessionBundleSourceClaim>,
)

internal sealed interface SessionBundleSourceClaim {
	data class Captured(val source: HistorySource, val identity: String, val checksum: String,
		val receiptKey: String) : SessionBundleSourceClaim
	data class Location(val identity: String, val pointCount: Int) : SessionBundleSourceClaim
	data class BackgroundRadio(val source: HistorySource, val identity: String, val checksum: String,
		val receiptKey: String) : SessionBundleSourceClaim
	data class BackgroundSteps(val identity: String, val checksum: String, val receiptKey: String,
		val days: List<Pair<String, String>>) : SessionBundleSourceClaim
    data class SensorObservations(val source: HistorySource, val identities: List<String>) : SessionBundleSourceClaim
}

/** Existing source receipts resolve exact membership. Dates and ImportResult counts never select data. */
internal object SessionBundleMetadataImport {
	suspend fun prepare(context: Context, bundle: StagedSessionBundle): PreparedSessionBundleMetadata {
		val caller = currentCoroutineContext()
		caller.ensureActive()
		val claims = mutableListOf<SessionBundleSourceClaim>()
		var selectedCount = 0
		fun add(claim: SessionBundleSourceClaim) {
			caller.ensureActive()
			selectedCount += if (claim is SessionBundleSourceClaim.BackgroundSteps) {
				claim.days.size
			} else {
				1
			}
			if (selectedCount > OrganizationSourceSelector.MAX_COUNT) {
				throw InvalidSessionBundle("Session contains too many source records")
			}
			claims += claim
		}
		try {
			bundle.children.forEach { child ->
				caller.ensureActive()
				prepareSessionChild(context, bundle, child, caller, ::add)
			}
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			throw classifySessionSourceFailure(failure)
		}
		caller.ensureActive()
		return PreparedSessionBundleMetadata(bundle.manifest, claims)
	}

	/** Called only by the WORKER_MANAGED metadata entry; its existing receipt commits atomically with this row. */
	suspend fun publish(database: AppDatabase, jobId: String, expectedEpoch: Long, nowMs: Long,
		prepared: PreparedSessionBundleMetadata): ImportResult {
		check(database.inTransaction()) { "Session metadata requires its file receipt transaction" }
		val state = readSessionPublicationState(database)
		if (state.collectedDataEpoch != expectedEpoch) {
			throw IOException("Recorded data was cleared")
		}
		if (state.retainedFromMs?.let { prepared.manifest.toMs <= it } == true) {
			return ImportResult(skippedCount = 1)
		}
		val selected = try {
			prepared.claims.flatMap { claim -> resolve(database, jobId, expectedEpoch, claim) }.distinct()
		} catch (invalid: InvalidSessionBundle) {
			throw PermanentImportInputException(invalid.message ?: "Session source membership is invalid", invalid)
		}
		val manifest = prepared.manifest
		database.organizationAnnotationDao().insert(OrganizationAnnotationEntity(UUID.randomUUID().toString(), 1,
			nowMs, nowMs, null, manifest.fromMs, manifest.toMs, manifest.zoneId,
			manifest.name?.trim()?.takeIf(String::isNotEmpty), null, false,
			OrganizationSourceSelectionCodec.encode(selected),
                manifest.activityLabel?.trim()?.takeIf(String::isNotEmpty)))
		return ImportResult(successCount = 1)
	}

	private suspend fun resolve(database: AppDatabase, jobId: String, epoch: Long,
		claim: SessionBundleSourceClaim): List<OrganizationSourceSelector> = when (claim) {
		is SessionBundleSourceClaim.Location -> resolveLocationClaim(database, epoch, claim)
		is SessionBundleSourceClaim.Captured -> resolveCapturedClaim(database, jobId, epoch, claim)
		is SessionBundleSourceClaim.BackgroundRadio -> resolveBackgroundRadioClaim(database, jobId, epoch, claim)
		is SessionBundleSourceClaim.BackgroundSteps -> resolveBackgroundStepsClaim(database, jobId, epoch, claim)
        is SessionBundleSourceClaim.SensorObservations -> resolveSensorObservations(database, claim)
	}
}


// Dispatch the seven supported file formats here; each decoder owns its typed parsing and limits.
@Suppress("CyclomaticComplexMethod")
private suspend fun prepareSessionChild(
	context: Context, bundle: StagedSessionBundle, child: StagedSessionChild,
	caller: CoroutineContext, add: (SessionBundleSourceClaim) -> Unit,
) {
	val declared = bundle.manifest.children.single { it.path == child.path }
	fun source(selected: HistorySource) {
		if (selected.name.lowercase() !in bundle.manifest.selectedSources) {
			throw InvalidSessionBundle("Session contains an unselected source")
		}
	}
	fun capture(selected: HistorySource, identity: String, checksum: String, key: String) {
		caller.ensureActive()
		source(selected)
		add(SessionBundleSourceClaim.Captured(selected, identity, checksum, key))
	}
	child.importStream().use { input ->
		when (declared.formatId) {
			LocationFileJsonV1.FORMAT -> {
				declared.requireFormatVersion(LocationFileJsonV1.VERSION)
				source(HistorySource.LOCATION)
				prepareLocationClaims(context, child, input, caller, add)
			}
			"portable-steps-v1" -> prepareStepsClaims(child, input, declared, ::capture)
			"portable-ambient-steps-v1" -> {
				source(HistorySource.STEPS)
				prepareBackgroundStepsClaims(child, input, declared, caller, add)
			}
			"portable-activity-v1" -> {
                source(HistorySource.ACTIVITY)
                prepareActivityClaims(context, child, input, declared, ::capture, add)
			}
			"portable-pressure-v1" -> {
                source(HistorySource.PRESSURE)
                preparePressureClaims(context, child, input, declared, ::capture, add)
			}
			RadioSourceFileV2.WIFI_FORMAT -> {
				source(HistorySource.WIFI)
				prepareWifiClaims(child, input, declared, ::capture, add)
			}
			RadioSourceFileV2.CELL_FORMAT -> {
				source(HistorySource.CELL)
				prepareCellClaims(child, input, declared, ::capture, add)
			}
			else -> throw InvalidSessionBundle("Unsupported session source file")
		}
	}
}


private suspend fun prepareLocationClaims(
	context: Context, child: StagedSessionChild, input: FileImportStream,
	caller: CoroutineContext, add: (SessionBundleSourceClaim) -> Unit,
) {
	val count = LocationFileJsonV1().read(input) { caller.ensureActive() }
	val identity = computeImportJobId(context, DocumentFile.fromFile(child.file),
		child.sizeBytes, sourceExtension = LocationFileJsonV1.EXTENSION) {
		caller.ensureActive()
	}
	add(SessionBundleSourceClaim.Location(identity, count))
}




private suspend fun prepareStepsClaims(
	child: StagedSessionChild, input: FileImportStream, declared: SessionBundleChildV1,
	capture: (HistorySource, String, String, String) -> Unit,
) {
	val bytes = readPortableBytes(input, child.sizeBytes)
	val actualVersion = portableStepsSchemaVersion(bytes)
	declared.requireFormatVersion(actualVersion)
	if (actualVersion == 1) {
		PortableStepsJsonV1Codec().decode(bytes) { entry ->
			capture(HistorySource.STEPS, entry.identity.value, entry.contentChecksum.value,
				portableStepsEntryKey(child.receiptKey, entry.identity.value))
		}
	} else {
		PortableStepsJsonV2Codec().decode(bytes).archive.entries.forEach { entry ->
			capture(HistorySource.STEPS, entry.product.identity.value, entry.product.contentChecksum.value,
				portableStepsEntryKey(child.receiptKey, entry.product.identity.value))
		}
	}
}

private suspend fun prepareBackgroundStepsClaims(
	child: StagedSessionChild, input: FileImportStream, declared: SessionBundleChildV1,
	caller: CoroutineContext, add: (SessionBundleSourceClaim) -> Unit,
) {
	val bytes = readPortableBytes(input, child.sizeBytes)
	val actualVersion = portableAmbientStepsSchemaVersion(bytes)
	declared.requireFormatVersion(actualVersion)
	if (actualVersion == 1) {
		val archive = PortableAmbientStepsJsonV1Codec().decode(bytes).archive
		add(SessionBundleSourceClaim.BackgroundSteps(archive.identity.value, archive.contentChecksum.value,
			portableAmbientStepsArchiveKey(child.receiptKey, archive.identity.value),
			archive.days.map { caller.ensureActive(); it.identity.value to it.contentChecksum.value }))
	} else {
		val archive = PortableAmbientStepsJsonV2Codec().decode(bytes).archive
		add(SessionBundleSourceClaim.BackgroundSteps(archive.identity.value, archive.contentChecksum.value,
			portableAmbientStepsArchiveKey(child.receiptKey, archive.identity.value),
			archive.days.map { caller.ensureActive(); it.product.identity.value to it.product.contentChecksum.value }))
	}
}

private suspend fun prepareWifiClaims(
	child: StagedSessionChild, input: FileImportStream, declared: SessionBundleChildV1,
	capture: (HistorySource, String, String, String) -> Unit, add: (SessionBundleSourceClaim) -> Unit,
) {
	val file = RadioSourceFileJsonV2Codec().readWifi(input)
	declared.requireFormatVersion(file.schemaVersion)
	file.sessions.forEach { session ->
		capture(HistorySource.WIFI, session.entry.identity.value, session.entry.contentChecksum.value,
			radioEntryKey(child.receiptKey, session.entry.identity.value))
	}
	file.background?.let { archive ->
		add(SessionBundleSourceClaim.BackgroundRadio(HistorySource.WIFI, archive.archiveId,
			archive.contentChecksum, radioEntryKey(child.receiptKey, archive.archiveId)))
	}
}

private suspend fun prepareCellClaims(
	child: StagedSessionChild, input: FileImportStream, declared: SessionBundleChildV1,
	capture: (HistorySource, String, String, String) -> Unit, add: (SessionBundleSourceClaim) -> Unit,
) {
	val file = RadioSourceFileJsonV2Codec().readCell(input)
	declared.requireFormatVersion(file.schemaVersion)
	file.sessions.forEach { session ->
		capture(HistorySource.CELL, session.entry.identity.value, session.entry.contentChecksum.value,
			radioEntryKey(child.receiptKey, session.entry.identity.value))
	}
	file.background?.let { archive ->
		add(SessionBundleSourceClaim.BackgroundRadio(HistorySource.CELL, archive.archiveId,
			archive.contentChecksum, radioEntryKey(child.receiptKey, archive.archiveId)))
	}
}

private fun classifySessionSourceFailure(failure: Exception): Exception = when (failure) {
	is CancellationException -> failure
	is InvalidSessionBundle, is InvalidLocationFile, is PortableActivityFormatException,
	is PortableAmbientStepsFormatException, is PortablePressureJsonException,
	is PortableStepsJsonException, is PortableCountDomainJsonException,
	is RadioSourceFileFormatException, is SensorSourceFileFormatException -> PermanentImportInputException(
		"Session source data is invalid: ${failure.message.orEmpty().take(MAX_SOURCE_FORMAT_ERROR_LENGTH)}", failure)
	// Transport/storage failures remain retryable; durable child receipts survive the retry.
	else -> failure
}



private suspend fun resolveLocationClaim(
	database: AppDatabase, epoch: Long, claim: SessionBundleSourceClaim.Location,
): List<OrganizationSourceSelector> {
	val archive = database.locationFileIdentityDao().archive(claim.identity) ?: missingSessionSource()
	if (archive.collectedDataEpoch != epoch || archive.pointCount != claim.pointCount ||
		database.locationFileIdentityDao().archivePointCounts(claim.identity,
			null).membershipCount != claim.pointCount) {
		throw InvalidSessionBundle("Session Location membership is incomplete")
	}
	return listOf(OrganizationSourceSelector.LocationFile(claim.identity))
}

private suspend fun resolveCapturedClaim(
	database: AppDatabase, jobId: String, epoch: Long, claim: SessionBundleSourceClaim.Captured,
): List<OrganizationSourceSelector> {
	when (claim.source) {
		HistorySource.STEPS -> checkCapturedStepsReceipt(database, jobId, epoch, claim)
		HistorySource.ACTIVITY, HistorySource.PRESSURE -> checkCapturedSensorReceipt(database, jobId, epoch, claim)
		HistorySource.WIFI, HistorySource.CELL -> checkCapturedRadioReceipt(database, jobId, epoch, claim)
		HistorySource.LOCATION -> throw InvalidSessionBundle("Unsupported session Location selector")
	}
	val radio = claim.source == HistorySource.WIFI || claim.source == HistorySource.CELL
	return listOf(OrganizationSourceSelector.CapturedEntry(claim.source, claim.identity,
		jobId.takeIf { radio }, claim.receiptKey.takeIf { radio }))
}

private suspend fun checkCapturedStepsReceipt(
	database: AppDatabase, jobId: String, epoch: Long, claim: SessionBundleSourceClaim.Captured,
) {
	val receipt = database.importedPortableStepsCountDomainDao().fileReceipt(jobId,
		claim.receiptKey) ?: missingSessionSource()
	val entry = database.importedStepsDao().entry(claim.identity) ?: missingSessionSource()
	checkCapturedEntry(receipt.entryIdentity, entry.contentChecksum, entry.collectedDataEpoch, epoch, claim)
}

private suspend fun checkCapturedSensorReceipt(
	database: AppDatabase, jobId: String, epoch: Long, claim: SessionBundleSourceClaim.Captured,
) {
	when (claim.source) {
		HistorySource.ACTIVITY -> database.importedActivityDao().receipt(jobId, claim.receiptKey)?.let {
			checkCapturedEntry(it.entryIdentity, it.entryContentChecksum, it.collectedDataEpoch, epoch, claim)
		} ?: missingSessionSource()
		HistorySource.PRESSURE -> database.importedPressureDao().receipt(jobId, claim.receiptKey)?.let {
			checkCapturedEntry(it.entryIdentity, it.entryContentChecksum, it.collectedDataEpoch, epoch, claim)
		} ?: missingSessionSource()
		else -> throw InvalidSessionBundle("Unsupported session Location selector")
	}
}

private suspend fun checkCapturedRadioReceipt(
	database: AppDatabase, jobId: String, epoch: Long, claim: SessionBundleSourceClaim.Captured,
) {
	when (claim.source) {
		HistorySource.WIFI -> database.importedWifiDao().receipt(jobId, claim.receiptKey)?.let {
			checkCapturedEntry(it.entryIdentity, sessionRadioChecksum(database, jobId, epoch,
				SourceDestinationOwnerEntity.SOURCE_WIFI, claim, it.entryContentChecksum),
				it.collectedDataEpoch, epoch, claim)
		} ?: missingSessionSource()
		HistorySource.CELL -> database.importedCellDao().receipt(jobId, claim.receiptKey)?.let {
			checkCapturedEntry(it.entryIdentity, sessionRadioChecksum(database, jobId, epoch,
				SourceDestinationOwnerEntity.SOURCE_CELL, claim, it.entryContentChecksum),
				it.collectedDataEpoch, epoch, claim)
		} ?: missingSessionSource()
		else -> throw InvalidSessionBundle("Unsupported session Location selector")
	}
}

private suspend fun sessionRadioChecksum(
	database: AppDatabase, jobId: String, epoch: Long, source: Int,
	claim: SessionBundleSourceClaim.Captured, fallback: String,
): String {
	val members = database.importedRadioFileMembershipDao().members(source, jobId, claim.receiptKey, 1_000_000)
	if (members.isEmpty()) {
		return fallback
	}
	val header = members.singleOrNull { it.memberKind == "ENTRY" } ?: missingSessionSource()
	if (members.any { it.entryIdentity != claim.identity || it.collectedDataEpoch != epoch ||
		it.fileEntryChecksum != header.fileEntryChecksum }) {
		throw InvalidSessionBundle("Session radio file membership is invalid")
	}
	return header.fileEntryChecksum
}

private fun checkCapturedEntry(
	identity: String, checksum: String, receivedEpoch: Long, epoch: Long,
	expected: SessionBundleSourceClaim.Captured,
) {
	if (identity != expected.identity || checksum != expected.checksum || receivedEpoch != epoch) {
		throw InvalidSessionBundle("Session source receipt does not match its data")
	}
}

private suspend fun resolveBackgroundRadioClaim(
	database: AppDatabase, jobId: String, epoch: Long, claim: SessionBundleSourceClaim.BackgroundRadio,
): List<OrganizationSourceSelector> {
	val matches = when (claim.source) {
		HistorySource.WIFI -> database.ambientWifiFactDao().importReceipt(jobId, claim.receiptKey)?.let {
			matchesBackgroundRadioReceipt(it.archiveId, it.archiveChecksum, it.collectedDataEpoch, epoch, claim)
		}
		HistorySource.CELL -> database.ambientCellFactDao().importReceipt(jobId, claim.receiptKey)?.let {
			matchesBackgroundRadioReceipt(it.archiveId, it.archiveChecksum, it.collectedDataEpoch, epoch, claim)
		}
		else -> throw InvalidSessionBundle("Unsupported background source selector")
	} ?: missingSessionSource()
	if (!matches) {
		throw InvalidSessionBundle("Session background receipt does not match its data")
	}
	return listOf(OrganizationSourceSelector.BackgroundRadioArchive(claim.source, claim.identity))
}


private fun matchesBackgroundRadioReceipt(
	identity: String, checksum: String, receivedEpoch: Long, epoch: Long,
	claim: SessionBundleSourceClaim.BackgroundRadio,
): Boolean = identity == claim.identity && checksum == claim.checksum && receivedEpoch == epoch

private suspend fun resolveBackgroundStepsClaim(
	database: AppDatabase, jobId: String, epoch: Long, claim: SessionBundleSourceClaim.BackgroundSteps,
): List<OrganizationSourceSelector> {
	val dao = database.importedAmbientStepsDao()
	val receipt = dao.receipt(jobId, claim.receiptKey) ?: missingSessionSource()
	if (receipt.archiveIdentity != claim.identity || receipt.archiveContentChecksum != claim.checksum ||
		receipt.collectedDataEpoch != epoch) {
		throw InvalidSessionBundle("Session Steps receipt does not match its data")
	}
	val days = dao.archiveDays(listOf(claim.identity), OrganizationSourceSelector.MAX_COUNT + 1)
	if (days.map { it.dayIdentity to it.dayContentChecksum } != claim.days) {
		throw InvalidSessionBundle("Session Steps day membership does not match its data")
	}
	return claim.days.map { OrganizationSourceSelector.BackgroundStepsDay(it.first) }
}

private fun missingSessionSource(): Nothing = throw InvalidSessionBundle("Selected session source data is missing")


private suspend fun readSessionPublicationState(database: AppDatabase): SourceEvidenceState {
	if (database.activeRetentionFloorSettlement() != null) {
		throw IOException("Recorded data cleanup is in progress")
	}
	return database.sourceEvidenceStateDao().get() ?: throw IOException("Recorded data is unavailable")
}

private const val MAX_SOURCE_FORMAT_ERROR_LENGTH = 200
