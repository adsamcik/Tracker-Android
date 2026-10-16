package com.adsamcik.tracker.impexp.portable

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.DateTimeException
import java.time.ZoneId

/** The small, descriptive header of a user-selected session file. Source payloads stay typed. */
internal data class SessionBundleManifestV1(
	val name: String?,
	val activityLabel: String?,
	val fromMs: Long,
	val toMs: Long,
	val zoneId: String,
	val selectedSources: List<String>,
	val children: List<SessionBundleChildV1>,
	val omissions: List<String>,
	val selectedPurposes: List<String> = emptyList(),
	val selectedOrigins: List<String> = emptyList(),
)

internal data class SessionBundleChildV1(
	val path: String,
	val formatId: String,
	val formatVersion: Int,
	val sizeBytes: Long,
	val sha256: String,
	val fromMs: Long,
	val toMs: Long,
	val wholeEntries: Boolean,
)

internal class InvalidSessionBundle(message: String) : IOException(message)

/** Validation is shared by the writer and importer, before any child reaches a source importer. */
internal object SessionBundleManifestCodecV1 {
	const val FORMAT = "tracker-organized-session"
	const val VERSION = 1
	const val EXTENSION = "trackersession"
	const val MIME_TYPE = "application/vnd.adsamcik.tracker.session+zip"
	const val MANIFEST_PATH = "manifest.json"
	const val MAX_MANIFEST_BYTES = 1024 * 1024
	const val MAX_CHILDREN = 64
	const val MAX_CHILD_BYTES = 64L * 1024 * 1024
	const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
	/** Child allowance plus manifest and conservative ZIP header room. */
	const val MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES + MAX_CHILDREN * 1024L + 4096L
	private val sources = setOf("location", "steps", "wifi", "cell", "activity", "pressure")

	fun encode(manifest: SessionBundleManifestV1): ByteArray {
		validate(manifest)
		val root = JSONObject()
			.put("format", FORMAT)
			.put("version", VERSION)
			.put("name", manifest.name)
			.put("activityLabel", manifest.activityLabel)
			.put("fromMs", manifest.fromMs)
			.put("toMs", manifest.toMs)
			.put("zoneId", manifest.zoneId)
			.put("selectedSources", JSONArray(manifest.selectedSources))
			.put("selectedPurposes", JSONArray(manifest.selectedPurposes))
			.put("selectedOrigins", JSONArray(manifest.selectedOrigins))
			.put("omissions", JSONArray(manifest.omissions))
		val children = JSONArray()
		manifest.children.forEach { child -> children.put(child.toJson()) }
		root.put("children", children)
		return root.toString().toByteArray(Charsets.UTF_8).also {
			if (it.size > MAX_MANIFEST_BYTES) {
				throw InvalidSessionBundle("Session details exceed the file limit")
			}
		}
	}

	fun decode(bytes: ByteArray): SessionBundleManifestV1 {
		if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES) {
			throw InvalidSessionBundle("Session details are missing or too large")
		}
		return parseManifest(bytes)
	}

	private fun parseManifest(bytes: ByteArray): SessionBundleManifestV1 = try {
		val manifest = JSONObject(bytes.toString(Charsets.UTF_8)).readManifest()
		validate(manifest)
		manifest
	} catch (_: JSONException) {
		throw InvalidSessionBundle("Session details are malformed")
	}

	private fun JSONObject.readManifest(): SessionBundleManifestV1 {
		if (getString("format") != FORMAT || getInt("version") != VERSION) {
			throw InvalidSessionBundle("Unsupported session file version")
		}
		val childrenArray = getJSONArray("children")
		if (childrenArray.length() > MAX_CHILDREN) {
			throw InvalidSessionBundle("Too many session data files")
		}
		val children = (0 until childrenArray.length()).map { index ->
			childrenArray.getJSONObject(index).readChild()
		}
		return SessionBundleManifestV1(
			name = optString("name").takeIf(String::isNotEmpty),
			activityLabel = optString("activityLabel").takeIf(String::isNotEmpty),
			fromMs = getLong("fromMs"),
			toMs = getLong("toMs"),
			zoneId = getString("zoneId"),
			selectedSources = getJSONArray("selectedSources").strings(),
			children = children,
			omissions = getJSONArray("omissions").strings(),
			selectedPurposes = getJSONArray("selectedPurposes").strings(),
			selectedOrigins = getJSONArray("selectedOrigins").strings(),
		)
	}

	private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

	private fun validate(manifest: SessionBundleManifestV1) {
		validateTimes(manifest)
		if ((manifest.name?.length ?: 0) > MAX_TEXT_LENGTH ||
			(manifest.activityLabel?.length ?: 0) > MAX_TEXT_LENGTH) {
			throw InvalidSessionBundle("Session label is too long")
		}
		validateSelections(manifest)
		if (manifest.omissions.size > MAX_OMISSION_COUNT || manifest.omissions.any { it.length > MAX_TEXT_LENGTH }) {
			throw InvalidSessionBundle("Session missing-data list is invalid")
		}
		validateChildren(manifest)
	}

	private fun validateTimes(manifest: SessionBundleManifestV1) {
		if (manifest.fromMs < 0L || manifest.toMs < manifest.fromMs) {
			throw InvalidSessionBundle("Session times are invalid")
		}
		try { ZoneId.of(manifest.zoneId) } catch (_: DateTimeException) {
			throw InvalidSessionBundle("Session time zone is invalid")
		}
	}

	private fun validateSelections(manifest: SessionBundleManifestV1) {
		if (manifest.selectedSources.size > MAX_SOURCE_COUNT ||
			manifest.selectedSources.distinct().size != manifest.selectedSources.size ||
			manifest.selectedSources.any { it !in sources }) {
			throw InvalidSessionBundle("Session source list is invalid")
		}
		if (manifest.selectedPurposes.distinct().size != manifest.selectedPurposes.size ||
			manifest.selectedPurposes.any { it !in setOf("session", "background") } ||
			manifest.selectedOrigins.distinct().size != manifest.selectedOrigins.size ||
			manifest.selectedOrigins.any { it !in setOf("local", "imported") }) {
			throw InvalidSessionBundle("Session data description is invalid")
		}
	}

	private fun validateChildren(manifest: SessionBundleManifestV1) {
		if (manifest.children.size > MAX_CHILDREN ||
			manifest.children.map { it.path }.distinct().size != manifest.children.size) {
			throw InvalidSessionBundle("Session data file list is invalid")
		}
		var total = 0L
		manifest.children.forEach { child ->
			child.validateFor(manifest)
			total += child.sizeBytes
			if (total > MAX_TOTAL_BYTES) {
				throw InvalidSessionBundle("Session data exceeds the file limit")
			}
		}
	}
}

private fun SessionBundleChildV1.toJson(): JSONObject = JSONObject()
	.put("path", path)
	.put("formatId", formatId)
	.put("formatVersion", formatVersion)
	.put("sizeBytes", sizeBytes)
	.put("sha256", sha256)
	.put("fromMs", fromMs)
	.put("toMs", toMs)
	.put("wholeEntries", wholeEntries)

private fun JSONObject.readChild(): SessionBundleChildV1 = SessionBundleChildV1(
	path = getString("path"),
	formatId = getString("formatId"),
	formatVersion = getInt("formatVersion"),
	sizeBytes = getLong("sizeBytes"),
	sha256 = getString("sha256"),
	fromMs = getLong("fromMs"),
	toMs = getLong("toMs"),
	wholeEntries = getBoolean("wholeEntries"),
)

private fun SessionBundleChildV1.validateFor(manifest: SessionBundleManifestV1) {
	if (!hasValidFormat() || sizeBytes !in 1..SessionBundleManifestCodecV1.MAX_CHILD_BYTES ||
		!sha256Pattern.matches(sha256) || !hasValidTimes(manifest)) {
		throw InvalidSessionBundle("Session data file details are invalid")
	}
}

private fun SessionBundleChildV1.hasValidFormat(): Boolean = safePath.matches(path) &&
	formatId.isNotBlank() && formatId.length <= MAX_FORMAT_ID_LENGTH && formatVersion >= 1

private fun SessionBundleChildV1.hasValidTimes(manifest: SessionBundleManifestV1): Boolean {
	if (fromMs < 0L || toMs < fromMs) {
		return false
	}
	return wholeEntries || (fromMs >= manifest.fromMs && toMs <= manifest.toMs)
}

private val safePath = Regex("sources/[0-9]{1,2}/[a-z0-9-]{1,64}\\.[a-z0-9]{1,32}")
private val sha256Pattern = Regex("[0-9a-f]{64}")

private const val MAX_TEXT_LENGTH = 200
private const val MAX_SOURCE_COUNT = 6
private const val MAX_OMISSION_COUNT = 64
private const val MAX_FORMAT_ID_LENGTH = 80
