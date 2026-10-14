package com.adsamcik.tracker.stats.api.repository

import org.json.JSONArray
import org.json.JSONObject

/** Bounded local representation of approved typed bundle membership. Unknown shapes are never intervals. */
object OrganizationSourceSelectionCodec {
	const val MAX_BYTES = 1024 * 1024

	fun encode(selection: List<OrganizationSourceSelector>): String {
		validate(selection)
		return JSONArray().apply {
			selection.forEach { selected ->
				put(JSONObject().put("source", selected.source.name).apply {
					when (selected) {
						is OrganizationSourceSelector.LocationFile -> put("kind", "location-file").put("identity", selected.archiveIdentity)
						is OrganizationSourceSelector.CapturedEntry -> {
							put("kind", "captured-entry").put("identity", selected.entryIdentity)
							if (selected.importJobId != null) put("importJobId", selected.importJobId).put("importEntryKey", selected.importEntryKey)
						}
						is OrganizationSourceSelector.BackgroundStepsDay -> put("kind", "background-steps-day").put("identity", selected.dayIdentity)
						is OrganizationSourceSelector.BackgroundRadioArchive -> put("kind", "background-radio-archive").put("identity", selected.archiveIdentity)
					}
				})
			}
		}.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
	}

	/** Throws for malformed/unsupported stored metadata; callers must report unavailable, not group by dates. */
	fun decode(encoded: String): List<OrganizationSourceSelector> {
		require(encoded.length <= MAX_BYTES && encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
		val rows = JSONArray(encoded)
		require(rows.length() <= OrganizationSourceSelector.MAX_COUNT)
		return (0 until rows.length()).map { index ->
			val row = rows.getJSONObject(index)
			val keys = row.keys().asSequence().toSet()
			require(keys == setOf("kind", "source", "identity") ||
				keys == setOf("kind", "source", "identity", "importJobId", "importEntryKey") && row.getString("kind") == "captured-entry")
			val source = HistorySource.valueOf(row.getString("source"))
			val identity = row.getString("identity")
			when (row.getString("kind")) {
				"location-file" -> {
					require(source == HistorySource.LOCATION)
					OrganizationSourceSelector.LocationFile(identity)
				}
				"captured-entry" -> OrganizationSourceSelector.CapturedEntry(source, identity,
					if (row.has("importJobId")) row.getString("importJobId") else null,
					if (row.has("importEntryKey")) row.getString("importEntryKey") else null)
				"background-steps-day" -> {
					require(source == HistorySource.STEPS)
					OrganizationSourceSelector.BackgroundStepsDay(identity)
				}
				"background-radio-archive" -> OrganizationSourceSelector.BackgroundRadioArchive(source, identity)
				else -> throw IllegalArgumentException("Unsupported organization source selection")
			}
		}.also(::validate)
	}

	private fun validate(selection: List<OrganizationSourceSelector>) {
		require(selection.size <= OrganizationSourceSelector.MAX_COUNT)
		require(selection.distinct().size == selection.size)
	}
}
