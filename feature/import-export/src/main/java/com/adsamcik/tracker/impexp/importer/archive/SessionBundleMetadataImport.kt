package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.PermanentImportInputException
import com.adsamcik.tracker.impexp.importer.computeImportJobId
import com.adsamcik.tracker.impexp.importer.file.PortableActivityFileImport
import com.adsamcik.tracker.impexp.importer.file.PortablePressureFileReceipt
import com.adsamcik.tracker.impexp.importer.file.portableAmbientStepsArchiveKey
import com.adsamcik.tracker.impexp.importer.file.portableStepsEntryKey
import com.adsamcik.tracker.impexp.importer.file.radioEntryKey
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.PortableActivityFormatException
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsFormatException
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.PortableCountDomainJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileFormatException
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import com.adsamcik.tracker.impexp.portable.portableAmbientStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.portableStepsSchemaVersion
import com.adsamcik.tracker.impexp.portable.readPortableBytes
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import java.io.IOException
import java.util.UUID
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
			selectedCount += if (claim is SessionBundleSourceClaim.BackgroundSteps) claim.days.size else 1
			if (selectedCount > OrganizationSourceSelector.MAX_COUNT)
				throw InvalidSessionBundle("Session contains too many source records")
			claims += claim
		}
		try {
			bundle.children.forEach { child ->
				caller.ensureActive()
				val declared = bundle.manifest.children.single { it.path == child.path }
				fun version(actual: Int) {
					if (declared.formatVersion != actual) throw InvalidSessionBundle("Session source version does not match its data")
				}
				fun source(selected: HistorySource) {
					if (selected.name.lowercase() !in bundle.manifest.selectedSources)
						throw InvalidSessionBundle("Session contains an unselected source")
				}
				fun capture(selected: HistorySource, identity: String, checksum: String, key: String) {
					caller.ensureActive()
					source(selected)
					add(SessionBundleSourceClaim.Captured(selected, identity, checksum, key))
				}
				child.importStream().use { input -> when (declared.formatId) {
					LocationFileJsonV1.FORMAT -> {
						version(LocationFileJsonV1.VERSION); source(HistorySource.LOCATION)
						val count = LocationFileJsonV1().read(input) { caller.ensureActive() }
						val identity = computeImportJobId(context, DocumentFile.fromFile(child.file),
							child.sizeBytes, sourceExtension = LocationFileJsonV1.EXTENSION) {
							caller.ensureActive()
						}
						add(SessionBundleSourceClaim.Location(identity, count))
					}
					"portable-steps-v1" -> {
						val bytes = readPortableBytes(input, child.sizeBytes)
						val actualVersion = portableStepsSchemaVersion(bytes); version(actualVersion)
						if (actualVersion == 1) PortableStepsJsonV1Codec().decode(bytes) { entry ->
							capture(HistorySource.STEPS, entry.identity.value, entry.contentChecksum.value,
								portableStepsEntryKey(child.receiptKey, entry.identity.value))
						} else PortableStepsJsonV2Codec().decode(bytes).archive.entries.forEach { entry ->
							capture(HistorySource.STEPS, entry.product.identity.value, entry.product.contentChecksum.value,
								portableStepsEntryKey(child.receiptKey, entry.product.identity.value))
						}
					}
					"portable-ambient-steps-v1" -> {
						source(HistorySource.STEPS)
						val bytes = readPortableBytes(input, child.sizeBytes)
						val actualVersion = portableAmbientStepsSchemaVersion(bytes); version(actualVersion)
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
					"portable-activity-v1" -> {
						version(1)
						PortableActivityJsonV1Codec().decode(input).entries.forEach { entry ->
							capture(HistorySource.ACTIVITY, entry.identity.value, entry.contentChecksum.value,
								PortableActivityFileImport.subordinateEntryKey(child.receiptKey, entry.identity.value))
						}
					}
					"portable-pressure-v1" -> {
						version(1)
						PortablePressureJsonV1Codec().decode(input) { entry ->
							val key = PortablePressureFileReceipt.entryKey(child.receiptKey, entry.identity.value)
							capture(HistorySource.PRESSURE, entry.identity.value, entry.contentChecksum.value, key)
						}
					}
					RadioSourceFileV2.WIFI_FORMAT -> {
						version(RadioSourceFileV2.VERSION); source(HistorySource.WIFI)
						val file = RadioSourceFileJsonV2Codec().readWifi(input)
						file.sessions.forEach { session -> capture(HistorySource.WIFI, session.entry.identity.value,
							session.entry.contentChecksum.value, radioEntryKey(child.receiptKey, session.entry.identity.value)) }
						file.background?.let { archive -> add(SessionBundleSourceClaim.BackgroundRadio(HistorySource.WIFI,
							archive.archiveId, archive.contentChecksum, radioEntryKey(child.receiptKey, archive.archiveId))) }
					}
					RadioSourceFileV2.CELL_FORMAT -> {
						version(RadioSourceFileV2.VERSION); source(HistorySource.CELL)
						val file = RadioSourceFileJsonV2Codec().readCell(input)
						file.sessions.forEach { session -> capture(HistorySource.CELL, session.entry.identity.value,
							session.entry.contentChecksum.value, radioEntryKey(child.receiptKey, session.entry.identity.value)) }
						file.background?.let { archive -> add(SessionBundleSourceClaim.BackgroundRadio(HistorySource.CELL,
							archive.archiveId, archive.contentChecksum, radioEntryKey(child.receiptKey, archive.archiveId))) }
					}
					else -> throw InvalidSessionBundle("Unsupported session source file")
				} }
			}
		} catch (cancelled: CancellationException) { throw cancelled
		} catch (failure: Exception) {
			when (failure) {
				is InvalidSessionBundle, is InvalidLocationFile, is PortableActivityFormatException,
				is PortableAmbientStepsFormatException, is PortablePressureJsonException,
				is PortableStepsJsonException, is PortableCountDomainJsonException,
				is RadioSourceFileFormatException -> throw PermanentImportInputException(
					"Session source data is invalid: ${failure.message.orEmpty().take(200)}", failure)
				// Transport/storage failures remain retryable; durable child receipts survive the retry.
				else -> throw failure
			}
		}
		caller.ensureActive()
		return PreparedSessionBundleMetadata(bundle.manifest, claims)
	}

	/** Called only by the WORKER_MANAGED metadata entry; its existing receipt commits atomically with this row. */
	suspend fun publish(database: AppDatabase, jobId: String, expectedEpoch: Long, nowMs: Long,
		prepared: PreparedSessionBundleMetadata): ImportResult {
		check(database.inTransaction()) { "Session metadata requires its file receipt transaction" }
		if (database.activeRetentionFloorSettlement() != null) throw IOException("Recorded data cleanup is in progress")
		val state = database.sourceEvidenceStateDao().get() ?: throw IOException("Recorded data is unavailable")
		if (state.collectedDataEpoch != expectedEpoch) throw IOException("Recorded data was cleared")
		if (state.retainedFromMs?.let { prepared.manifest.toMs <= it } == true) return ImportResult(skippedCount = 1)
		val selected = try {
			prepared.claims.flatMap { claim -> resolve(database, jobId, expectedEpoch, claim) }.distinct()
		} catch (invalid: InvalidSessionBundle) {
			throw PermanentImportInputException(invalid.message ?: "Session source membership is invalid", invalid)
		}
		val manifest = prepared.manifest
		database.organizationAnnotationDao().insert(OrganizationAnnotationEntity(UUID.randomUUID().toString(), 1,
			nowMs, nowMs, null, manifest.fromMs, manifest.toMs, manifest.zoneId,
			manifest.name?.trim()?.takeIf(String::isNotEmpty), null, false,
			OrganizationSourceSelectionCodec.encode(selected), manifest.activityLabel?.trim()?.takeIf(String::isNotEmpty)))
		return ImportResult(successCount = 1)
	}

	private suspend fun resolve(database: AppDatabase, jobId: String, epoch: Long,
		claim: SessionBundleSourceClaim): List<OrganizationSourceSelector> {
		fun missing(): Nothing = throw InvalidSessionBundle("Selected session source data is missing")
		fun checkEntry(identity: String, checksum: String, receivedEpoch: Long, expected: SessionBundleSourceClaim.Captured) {
			if (identity != expected.identity || checksum != expected.checksum || receivedEpoch != epoch)
				throw InvalidSessionBundle("Session source receipt does not match its data")
		}
		return when (claim) {
			is SessionBundleSourceClaim.Location -> {
				val archive = database.locationFileIdentityDao().archive(claim.identity) ?: missing()
				if (archive.collectedDataEpoch != epoch || archive.pointCount != claim.pointCount ||
					database.locationFileIdentityDao().archivePointCounts(claim.identity, null).membershipCount != claim.pointCount)
					throw InvalidSessionBundle("Session Location membership is incomplete")
				listOf(OrganizationSourceSelector.LocationFile(claim.identity))
			}
			is SessionBundleSourceClaim.Captured -> {
				when (claim.source) {
					HistorySource.STEPS -> {
						val receipt = database.importedPortableStepsCountDomainDao().fileReceipt(jobId, claim.receiptKey) ?: missing()
						val entry = database.importedStepsDao().entry(claim.identity) ?: missing()
						checkEntry(receipt.entryIdentity, entry.contentChecksum, entry.collectedDataEpoch, claim)
					}
					HistorySource.ACTIVITY -> database.importedActivityDao().receipt(jobId, claim.receiptKey)?.let {
						checkEntry(it.entryIdentity, it.entryContentChecksum, it.collectedDataEpoch, claim) } ?: missing()
					HistorySource.PRESSURE -> database.importedPressureDao().receipt(jobId, claim.receiptKey)?.let {
						checkEntry(it.entryIdentity, it.entryContentChecksum, it.collectedDataEpoch, claim) } ?: missing()
					HistorySource.WIFI -> database.importedWifiDao().receipt(jobId, claim.receiptKey)?.let {
						checkEntry(it.entryIdentity, it.entryContentChecksum, it.collectedDataEpoch, claim) } ?: missing()
					HistorySource.CELL -> database.importedCellDao().receipt(jobId, claim.receiptKey)?.let {
						checkEntry(it.entryIdentity, it.entryContentChecksum, it.collectedDataEpoch, claim) } ?: missing()
					HistorySource.LOCATION -> throw InvalidSessionBundle("Unsupported session Location selector")
				}
				listOf(OrganizationSourceSelector.CapturedEntry(claim.source, claim.identity))
			}
			is SessionBundleSourceClaim.BackgroundRadio -> {
				val matches = when (claim.source) {
					HistorySource.WIFI -> database.ambientWifiFactDao().importReceipt(jobId, claim.receiptKey)?.let {
						it.archiveId == claim.identity && it.archiveChecksum == claim.checksum && it.collectedDataEpoch == epoch }
					HistorySource.CELL -> database.ambientCellFactDao().importReceipt(jobId, claim.receiptKey)?.let {
						it.archiveId == claim.identity && it.archiveChecksum == claim.checksum && it.collectedDataEpoch == epoch }
					else -> throw InvalidSessionBundle("Unsupported background source selector")
				} ?: missing()
				if (!matches) throw InvalidSessionBundle("Session background receipt does not match its data")
				listOf(OrganizationSourceSelector.BackgroundRadioArchive(claim.source, claim.identity))
			}
			is SessionBundleSourceClaim.BackgroundSteps -> {
				val dao = database.importedAmbientStepsDao()
				val receipt = dao.receipt(jobId, claim.receiptKey) ?: missing()
				if (receipt.archiveIdentity != claim.identity || receipt.archiveContentChecksum != claim.checksum || receipt.collectedDataEpoch != epoch)
					throw InvalidSessionBundle("Session Steps receipt does not match its data")
				val days = dao.archiveDays(listOf(claim.identity), OrganizationSourceSelector.MAX_COUNT + 1)
				if (days.map { it.dayIdentity to it.dayContentChecksum } != claim.days)
					throw InvalidSessionBundle("Session Steps day membership does not match its data")
				claim.days.map { OrganizationSourceSelector.BackgroundStepsDay(it.first) }
			}
		}
	}
}
