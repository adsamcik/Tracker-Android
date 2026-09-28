package com.adsamcik.tracker.impexp.portable

import org.json.JSONArray
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
	private val safePath = Regex("sources/[0-9]{1,2}/[a-z0-9-]{1,64}\\.[a-z0-9]{1,32}")
	private val sha256 = Regex("[0-9a-f]{64}")
	private val sourceName = Regex("[a-z][a-z0-9-]{0,31}")

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
		manifest.children.forEach { child ->
			children.put(JSONObject()
				.put("path", child.path)
				.put("formatId", child.formatId)
				.put("formatVersion", child.formatVersion)
				.put("sizeBytes", child.sizeBytes)
				.put("sha256", child.sha256)
				.put("fromMs", child.fromMs)
				.put("toMs", child.toMs)
				.put("wholeEntries", child.wholeEntries))
		}
		root.put("children", children)
		return root.toString().toByteArray(Charsets.UTF_8).also {
			if (it.size > MAX_MANIFEST_BYTES) throw InvalidSessionBundle("Session details exceed the file limit")
		}
	}

	fun decode(bytes: ByteArray): SessionBundleManifestV1 {
		if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES)
			throw InvalidSessionBundle("Session details are missing or too large")
		return try {
			val root = JSONObject(bytes.toString(Charsets.UTF_8))
			if (root.getString("format") != FORMAT || root.getInt("version") != VERSION)
				throw InvalidSessionBundle("Unsupported session file version")
			val childrenArray = root.getJSONArray("children")
			if (childrenArray.length() > MAX_CHILDREN) throw InvalidSessionBundle("Too many session data files")
			val children = (0 until childrenArray.length()).map { index ->
				val child = childrenArray.getJSONObject(index)
				SessionBundleChildV1(
					path = child.getString("path"),
					formatId = child.getString("formatId"),
					formatVersion = child.getInt("formatVersion"),
					sizeBytes = child.getLong("sizeBytes"),
					sha256 = child.getString("sha256"),
					fromMs = child.getLong("fromMs"),
					toMs = child.getLong("toMs"),
					wholeEntries = child.getBoolean("wholeEntries"),
				)
			}
			val manifest = SessionBundleManifestV1(
				name = root.optString("name").takeIf(String::isNotEmpty),
				activityLabel = root.optString("activityLabel").takeIf(String::isNotEmpty),
				fromMs = root.getLong("fromMs"),
				toMs = root.getLong("toMs"),
				zoneId = root.getString("zoneId"),
				selectedSources = root.getJSONArray("selectedSources").strings(),
				children = children,
				omissions = root.getJSONArray("omissions").strings(),
				selectedPurposes = root.getJSONArray("selectedPurposes").strings(),
				selectedOrigins = root.getJSONArray("selectedOrigins").strings(),
			)
			validate(manifest)
			manifest
		} catch (failure: InvalidSessionBundle) {
			throw failure
		} catch (failure: Exception) {
			throw InvalidSessionBundle("Session details are malformed")
		}
	}

	private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

	private fun validate(manifest: SessionBundleManifestV1) {
		if (manifest.fromMs < 0L || manifest.toMs <= manifest.fromMs)
			throw InvalidSessionBundle("Session times are invalid")
		try { ZoneId.of(manifest.zoneId) } catch (_: DateTimeException) {
			throw InvalidSessionBundle("Session time zone is invalid")
		}
		if ((manifest.name?.length ?: 0) > 200 || (manifest.activityLabel?.length ?: 0) > 200)
			throw InvalidSessionBundle("Session label is too long")
		if (manifest.selectedSources.size > 6 || manifest.selectedSources.distinct().size != manifest.selectedSources.size ||
			manifest.selectedSources.any { !sourceName.matches(it) })
			throw InvalidSessionBundle("Session source list is invalid")
		if (manifest.selectedPurposes.distinct().size != manifest.selectedPurposes.size ||
			manifest.selectedPurposes.any { it !in setOf("session", "background") } ||
			manifest.selectedOrigins.distinct().size != manifest.selectedOrigins.size ||
			manifest.selectedOrigins.any { it !in setOf("local", "imported") })
			throw InvalidSessionBundle("Session data description is invalid")
		if (manifest.omissions.size > 64 || manifest.omissions.any { it.length > 200 })
			throw InvalidSessionBundle("Session missing-data list is invalid")
		if (manifest.children.size > MAX_CHILDREN ||
			manifest.children.map { it.path }.distinct().size != manifest.children.size)
			throw InvalidSessionBundle("Session data file list is invalid")
		var total = 0L
		manifest.children.forEach { child ->
			if (!safePath.matches(child.path) || child.formatId.isBlank() || child.formatId.length > 80 ||
				child.formatVersion < 1 || child.sizeBytes !in 1..MAX_CHILD_BYTES ||
				!sha256.matches(child.sha256) || child.fromMs < 0L || child.toMs <= child.fromMs ||
				(!child.wholeEntries &&
					(child.fromMs < manifest.fromMs || child.toMs > manifest.toMs)))
				throw InvalidSessionBundle("Session data file details are invalid")
			total += child.sizeBytes
			if (total > MAX_TOTAL_BYTES) throw InvalidSessionBundle("Session data exceeds the file limit")
		}
	}
}
