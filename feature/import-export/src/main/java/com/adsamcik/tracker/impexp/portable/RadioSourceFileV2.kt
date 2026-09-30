package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1

/** One chosen source per file; sessions and separately consented background retain their labels. */
internal object RadioSourceFileV2 {
	const val WIFI_FORMAT = "tracker-wifi"
	const val CELL_FORMAT = "tracker-cell"
	const val VERSION = 2
	const val WIFI_EXTENSION = "trackerwifi"
	const val CELL_EXTENSION = "trackercell"
	const val WIFI_MIME = "application/vnd.adsamcik.tracker.wifi+json"
	const val CELL_MIME = "application/vnd.adsamcik.tracker.cell+json"
	const val MAX_FILE_BYTES = 64L * 1_024L * 1_024L
	const val MAX_SESSIONS = 4_096
	const val MAX_CHILDREN = 262_144
}

internal data class WifiSourceFileV2(
	val format: String = RadioSourceFileV2.WIFI_FORMAT,
	val schemaVersion: Int = RadioSourceFileV2.VERSION,
	val selectedFromMs: Long?,
	val selectedToExclusiveMs: Long?,
	val includeSessionsAcrossDates: Boolean,
	val observationsOutsideDates: Int,
	val sessions: List<WifiSessionFileV2>,
	val background: PortableAmbientWifiArchiveV1?,
	val backgroundChildren: List<WifiBackgroundChildV2>,
) {
	init {
		require(format == RadioSourceFileV2.WIFI_FORMAT && schemaVersion == RadioSourceFileV2.VERSION)
		require((selectedFromMs == null) == (selectedToExclusiveMs == null))
		require(selectedFromMs?.let { it >= 0L && requireNotNull(selectedToExclusiveMs) > it } != false)
		require(observationsOutsideDates >= 0 && sessions.size <= RadioSourceFileV2.MAX_SESSIONS)
		require(sessions.map { it.entry.identity.value }.distinct().size == sessions.size)
		require(sessions.sumOf { it.children.size.toLong() } + backgroundChildren.size <=
			RadioSourceFileV2.MAX_CHILDREN)
		require(sessions.isNotEmpty() || background != null)
		val backgroundFacts = background?.facts?.map { it.identity to it.semanticRevision }?.toSet().orEmpty()
		require(backgroundChildren.all { (it.factIdentity to it.semanticRevision) in backgroundFacts })
		val parents = background?.facts.orEmpty().associateBy { it.identity to it.semanticRevision }
		require(backgroundChildren.all { child ->
			val parent = parents.getValue(child.factIdentity to child.semanticRevision)
			radioChildFitsCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
				parent.coverageStartTimeMs, parent.latestPossibleTimeMs)
		}) { "Wi-Fi details are outside their background observation" }
		require(backgroundChildren.map { Triple(it.factIdentity, it.semanticRevision, it.itemIndex) }
			.distinct().size == backgroundChildren.size)
		val childCounts = backgroundChildren.groupingBy { it.factIdentity to it.semanticRevision }.eachCount()
		require(background?.facts.orEmpty().all { fact ->
			childCounts[fact.identity to fact.semanticRevision]?.let { it == fact.observationCount } != false
		})
	}
}

internal data class CellSourceFileV2(
	val format: String = RadioSourceFileV2.CELL_FORMAT,
	val schemaVersion: Int = RadioSourceFileV2.VERSION,
	val selectedFromMs: Long?,
	val selectedToExclusiveMs: Long?,
	val includeSessionsAcrossDates: Boolean,
	val observationsOutsideDates: Int,
	val sessions: List<CellSessionFileV2>,
	val background: PortableAmbientCellArchiveV1?,
	val backgroundChildren: List<CellBackgroundChildV2>,
) {
	init {
		require(format == RadioSourceFileV2.CELL_FORMAT && schemaVersion == RadioSourceFileV2.VERSION)
		require((selectedFromMs == null) == (selectedToExclusiveMs == null))
		require(selectedFromMs?.let { it >= 0L && requireNotNull(selectedToExclusiveMs) > it } != false)
		require(observationsOutsideDates >= 0 && sessions.size <= RadioSourceFileV2.MAX_SESSIONS)
		require(sessions.map { it.entry.identity.value }.distinct().size == sessions.size)
		require(sessions.sumOf { it.children.size.toLong() } + backgroundChildren.size <=
			RadioSourceFileV2.MAX_CHILDREN)
		require(sessions.isNotEmpty() || background != null)
		val backgroundFacts = background?.facts?.map { it.identity to it.semanticRevision }?.toSet().orEmpty()
		require(backgroundChildren.all { (it.factIdentity to it.semanticRevision) in backgroundFacts })
		val parents = background?.facts.orEmpty().associateBy { it.identity to it.semanticRevision }
		require(backgroundChildren.all { child ->
			val parent = parents.getValue(child.factIdentity to child.semanticRevision)
			radioChildFitsCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
				parent.coverageStartTimeMs, parent.latestPossibleTimeMs)
		}) { "Cell details are outside their background observation" }
		require(backgroundChildren.map { Triple(it.factIdentity, it.semanticRevision, it.itemIndex) }
			.distinct().size == backgroundChildren.size)
		val childCounts = backgroundChildren.groupingBy { it.factIdentity to it.semanticRevision }.eachCount()
		require(background?.facts.orEmpty().all { fact ->
			childCounts[fact.identity to fact.semanticRevision]?.let { it == fact.observationCount } != false
		})
	}
}

internal data class WifiSessionFileV2(
	val entry: PortableCapturedWifiEntryV1,
	val children: List<WifiSessionChildV2>,
) {
	init {
		val observations = entry.runs.flatMap { run -> run.observations.map { observation ->
			Triple(run.identity.value, observation.identity.value, observation.semanticRevision)
		} }.toSet()
		require(children.all { it.entryIdentity == entry.identity.value &&
			Triple(it.runIdentity, it.observationIdentity, it.semanticRevision) in observations })
		val parents = entry.runs.flatMap { run -> run.observations.map { observation ->
			Triple(run.identity.value, observation.identity.value, observation.semanticRevision) to observation
		} }.toMap()
		require(children.all { child ->
			val parent = parents.getValue(Triple(child.runIdentity, child.observationIdentity, child.semanticRevision))
			radioChildFitsCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
				parent.coverageStartTimeMs, parent.latestPossibleTimeMs)
		}) { "Wi-Fi details are outside their session observation" }
		require(children.map { listOf(it.runIdentity, it.observationIdentity,
			it.semanticRevision.toString(), it.itemIndex.toString()) }.distinct().size == children.size)
		val childCounts = children.groupingBy { it.observationIdentity to it.semanticRevision }.eachCount()
		require(entry.runs.flatMap { it.observations }.all { observation ->
			childCounts[observation.identity.value to observation.semanticRevision]
				?.let { it == observation.observationCount } != false
		})
	}
}

internal data class CellSessionFileV2(
	val entry: PortableCapturedCellEntryV1,
	val children: List<CellSessionChildV2>,
) {
	init {
		val observations = entry.runs.flatMap { run -> run.observations.map { observation ->
			Triple(run.identity.value, observation.identity.value, observation.semanticRevision)
		} }.toSet()
		require(children.all { it.entryIdentity == entry.identity.value &&
			Triple(it.runIdentity, it.observationIdentity, it.semanticRevision) in observations })
		val parents = entry.runs.flatMap { run -> run.observations.map { observation ->
			Triple(run.identity.value, observation.identity.value, observation.semanticRevision) to observation
		} }.toMap()
		require(children.all { child ->
			val parent = parents.getValue(Triple(child.runIdentity, child.observationIdentity, child.semanticRevision))
			radioChildFitsCoverage(child.observedWallTimeMs, child.wallTimeUncertaintyMs,
				parent.coverageStartTimeMs, parent.latestPossibleTimeMs)
		}) { "Cell details are outside their session observation" }
		require(children.map { listOf(it.runIdentity, it.observationIdentity,
			it.semanticRevision.toString(), it.itemIndex.toString()) }.distinct().size == children.size)
		val childCounts = children.groupingBy { it.observationIdentity to it.semanticRevision }.eachCount()
		require(entry.runs.flatMap { it.observations }.all { observation ->
			childCounts[observation.identity.value to observation.semanticRevision]
				?.let { it == observation.acceptedChildCount } != false
		})
	}
}

internal data class WifiSessionChildV2(
	val entryIdentity: String,
	val runIdentity: String,
	val observationIdentity: String,
	val semanticRevision: Long,
	val itemIndex: Int,
	val providerTimestampNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val bssid: String?,
	val ssid: String?,
	val frequencyMhz: Int,
	val signalDbm: Int,
	val contentChecksum: String,
) {
	init { requireWifiChild(listOf(entryIdentity, runIdentity, observationIdentity), semanticRevision,
		itemIndex, providerTimestampNanos, observedWallTimeMs, wallTimeUncertaintyMs,
		bssid, ssid, frequencyMhz, signalDbm, contentChecksum) }
	override fun toString(): String = "WifiSessionChildV2"
}

internal data class WifiBackgroundChildV2(
	val factIdentity: String,
	val semanticRevision: Long,
	val itemIndex: Int,
	val providerTimestampNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val bssid: String?,
	val ssid: String?,
	val frequencyMhz: Int,
	val signalDbm: Int,
	val contentChecksum: String,
) {
	init { requireWifiChild(listOf(factIdentity), semanticRevision, itemIndex, providerTimestampNanos,
		observedWallTimeMs, wallTimeUncertaintyMs, bssid, ssid, frequencyMhz, signalDbm,
		contentChecksum) }
	override fun toString(): String = "WifiBackgroundChildV2"
}

internal data class CellSessionChildV2(
	val entryIdentity: String,
	val runIdentity: String,
	val observationIdentity: String,
	val semanticRevision: Long,
	val itemIndex: Int,
	val providerTimestampNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val towerIdentity: String?,
	val radioType: String,
	val registered: Boolean,
	val signalDbm: Int?,
	val contentChecksum: String,
) {
	init { requireCellChild(listOf(entryIdentity, runIdentity, observationIdentity), semanticRevision,
		itemIndex, providerTimestampNanos, observedWallTimeMs, wallTimeUncertaintyMs,
		towerIdentity, radioType, registered, signalDbm, contentChecksum) }
	override fun toString(): String = "CellSessionChildV2"
}

internal data class CellBackgroundChildV2(
	val factIdentity: String,
	val semanticRevision: Long,
	val itemIndex: Int,
	val providerTimestampNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val towerIdentity: String?,
	val radioType: String,
	val registered: Boolean,
	val signalDbm: Int?,
	val contentChecksum: String,
) {
	init { requireCellChild(listOf(factIdentity), semanticRevision, itemIndex,
		providerTimestampNanos, observedWallTimeMs, wallTimeUncertaintyMs,
		towerIdentity, radioType, registered, signalDbm, contentChecksum) }
	override fun toString(): String = "CellBackgroundChildV2"
}

private fun requireWifiChild(
	identities: List<String>, revision: Long, itemIndex: Int, providerNanos: Long,
	wallMs: Long, uncertaintyMs: Long, bssid: String?, ssid: String?, frequency: Int,
	signal: Int, checksum: String,
) {
	requireChildBase(identities, revision, itemIndex, providerNanos, wallMs, uncertaintyMs)
	require(bssid?.length?.let { it <= 128 } != false && ssid?.length?.let { it <= 128 } != false)
	require(frequency > 0 && signal in -200..100)
	require(checksum == radioChildDigest("wifi", identities, listOf(revision, itemIndex,
		providerNanos, wallMs, uncertaintyMs, bssid, ssid, frequency, signal)))
}

private fun requireCellChild(
	identities: List<String>, revision: Long, itemIndex: Int, providerNanos: Long,
	wallMs: Long, uncertaintyMs: Long, tower: String?, radioType: String,
	registered: Boolean, signal: Int?, checksum: String,
) {
	requireChildBase(identities, revision, itemIndex, providerNanos, wallMs, uncertaintyMs)
	require(tower?.length?.let { it <= 256 } != false && radioType.isNotBlank() && radioType.length <= 32)
	require(signal?.let { it in -200..100 } != false)
	require(checksum == radioChildDigest("cell", identities, listOf(revision, itemIndex,
		providerNanos, wallMs, uncertaintyMs, tower, radioType, registered, signal)))
}

private fun requireChildBase(
	identities: List<String>, revision: Long, itemIndex: Int, providerNanos: Long,
	wallMs: Long, uncertaintyMs: Long,
) {
	require(identities.all { DIGEST.matches(it) })
	require(revision > 0L && itemIndex >= 0 && providerNanos > 0L && wallMs >= 0L)
	require(uncertaintyMs in 0L..wallMs)
}

internal fun radioChildDigest(source: String, identities: List<String>, values: List<Any?>): String {
	return PortableRadioChildIntegrity.digest(source, identities, values)
}

/** A detail inherits its parent's measured interval; a checksum never widens that interval. */
internal fun radioChildFitsCoverage(wallMs: Long, uncertaintyMs: Long,
	coverageStartMs: Long, latestPossibleMs: Long): Boolean =
	uncertaintyMs >= 0L && wallMs >= uncertaintyMs &&
		wallMs - uncertaintyMs >= coverageStartMs &&
		wallMs <= Long.MAX_VALUE - uncertaintyMs && wallMs + uncertaintyMs <= latestPossibleMs

private val DIGEST = Regex("[0-9a-f]{64}")
