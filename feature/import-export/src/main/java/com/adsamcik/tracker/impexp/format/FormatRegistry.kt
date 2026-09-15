package com.adsamcik.tracker.impexp.format

import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.DatabaseExporter
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.GpxExporter
import com.adsamcik.tracker.impexp.exporter.JsonExporter
import com.adsamcik.tracker.impexp.exporter.KmlExporter
import com.adsamcik.tracker.impexp.exporter.PortableStepsExporter
import com.adsamcik.tracker.impexp.exporter.PortableActivityExporter
import com.adsamcik.tracker.impexp.exporter.PortablePressureExporter
import com.adsamcik.tracker.impexp.importer.file.DatabaseImport
import com.adsamcik.tracker.impexp.importer.file.FileImport
import com.adsamcik.tracker.impexp.importer.file.GpxImport
import com.adsamcik.tracker.impexp.importer.file.JsonImport
import com.adsamcik.tracker.impexp.importer.file.KmlImport
import com.adsamcik.tracker.impexp.importer.file.PortableStepsFileImport
import com.adsamcik.tracker.impexp.importer.file.PortableActivityFileImport
import com.adsamcik.tracker.impexp.importer.file.PortablePressureFileImport

/**
 * Central registry that maps format identifiers to [Exporter] and [FileImport]
 * instances. Replaces the hard-coded `when` blocks that previously lived in
 * [com.adsamcik.tracker.impexp.exporter.automation.ExportPlanWorker] and
 * [com.adsamcik.tracker.impexp.importer.DataImport].
 *
 * All built-in formats are registered eagerly in the companion `init` block,
 * so no explicit initialisation call is required.
 */
object FormatRegistry {

	private val entries = mutableMapOf<String, FormatEntry>()

	/**
	 * A single registry row: descriptor plus optional exporter / importer.
	 */
	data class FormatEntry(
		val descriptor: FormatDescriptor,
		val exporter: Exporter?,
		val importer: FileImport?,
	)

	init {
		registerBuiltInFormats()
	}

	// ── public API ──────────────────────────────────────────────────────

	/**
	 * Register (or replace) a format in the registry.
	 */
	fun register(
		descriptor: FormatDescriptor,
		exporter: Exporter? = null,
		importer: FileImport? = null,
	) {
		entries[descriptor.id] = FormatEntry(descriptor, exporter, importer)
	}

	/** Look up an [Exporter] by format id (e.g. `"gpx"`). */
	fun exporterFor(formatId: String): Exporter? = entries[formatId]?.exporter

	/** Look up a [FileImport] using the extensions its backend actually accepts. */
	fun importerForExtension(ext: String): FileImport? =
		entries.values
			.mapNotNull(FormatEntry::importer)
			.firstOrNull { importer ->
				importer.supportedExtensions.any { it.equals(ext, ignoreCase = true) }
			}

	/** All descriptors that have an exporter registered. */
	fun allExportFormats(): List<FormatDescriptor> =
		entries.values.filter { it.exporter != null }.map { it.descriptor }

	/** All descriptors that have a file importer registered. */
	fun allImportFormats(): List<FormatDescriptor> =
		entries.values.filter { it.importer != null }.map { it.descriptor }

	/** Flat set of every extension that has an importer registered. */
	fun allImportExtensions(): Set<String> =
		entries.values
			.mapNotNull(FormatEntry::importer)
			.flatMap(FileImport::supportedExtensions)
			.toSet()

	/** All registered [FileImport] instances, in registration order. */
	fun allImporters(): List<FileImport> =
		entries.values.mapNotNull { it.importer }

	/** All registered [FormatEntry] instances. */
	fun allEntries(): List<FormatEntry> = entries.values.toList()

	// ── built-in registration ───────────────────────────────────────────

	private fun registerBuiltInFormats() {
		register(
			descriptor = FormatDescriptor(
				id = "gpx",
				displayNameRes = R.string.format_gpx,
				mimeType = "application/gpx+xml",
				extensions = setOf("gpx"),
				supportsExport = true,
				supportsImport = true,
				supportsDateRange = true,
			),
			exporter = GpxExporter(),
			importer = GpxImport(),
		)

		register(
			descriptor = FormatDescriptor(
				id = "kml",
				displayNameRes = R.string.format_kml,
				mimeType = "application/vnd.google-earth.kml+xml",
				extensions = setOf("kml"),
				supportsExport = true,
				supportsImport = true,
				supportsDateRange = true,
			),
			exporter = KmlExporter(),
			importer = KmlImport(),
		)

		register(
			descriptor = FormatDescriptor(
				id = "json",
				displayNameRes = R.string.format_json,
				mimeType = "application/json",
				extensions = setOf("json"),
				supportsExport = true,
				supportsImport = true,
				supportsDateRange = true,
			),
			exporter = JsonExporter(),
			importer = JsonImport(),
		)

		val databaseExporter = DatabaseExporter()
		val databaseImporter = DatabaseImport()
		register(
			descriptor = FormatDescriptor(
				id = "db",
				displayNameRes = R.string.format_database,
				mimeType = databaseExporter.mimeType,
				extensions = setOf(databaseExporter.extension) +
					databaseImporter.supportedExtensions,
				supportsExport = true,
				supportsImport = true,
				supportsDateRange = databaseExporter.canSelectDateRange,
			),
			exporter = databaseExporter,
			importer = databaseImporter,
		)

		register(
			descriptor = FormatDescriptor(
				id = "portable-steps-v1",
				displayNameRes = R.string.format_portable_steps,
				mimeType = PortableStepsExporter.MIME_TYPE,
				extensions = setOf(PortableStepsFileImport.EXTENSION),
				supportsExport = true,
				supportsImport = true,
				supportsDateRange = true,
			),
			exporter = PortableStepsExporter(),
			importer = PortableStepsFileImport(),
		)

		val activityExporter = PortableActivityExporter()
		register(
			descriptor = FormatDescriptor(
				id = "portable-activity-v1",
				displayNameRes = R.string.format_portable_activity,
				mimeType = activityExporter.mimeType,
				extensions = setOf(PortableActivityFileImport.EXTENSION),
				supportsExport = true,
				supportsImport = true,
				supportsDateRange = activityExporter.canSelectDateRange,
			),
			exporter = activityExporter,
			importer = PortableActivityFileImport(),
		)

		val pressureExporter = PortablePressureExporter()
		register(
			descriptor = FormatDescriptor(
				id = "portable-pressure-v1",
				displayNameRes = R.string.format_portable_pressure,
				mimeType = pressureExporter.mimeType,
				extensions = setOf(PortablePressureFileImport.EXTENSION),
				supportsExport = true,
				supportsImport = true,
				supportsDateRange = pressureExporter.canSelectDateRange,
			),
			exporter = pressureExporter,
			importer = PortablePressureFileImport(),
		)
	}
}
