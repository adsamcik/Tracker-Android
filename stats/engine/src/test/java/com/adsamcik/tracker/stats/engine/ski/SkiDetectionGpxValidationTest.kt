package com.adsamcik.tracker.stats.engine.ski

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Validation tests using real-world GPX ski data from external sources.
 *
 * These tests require GPX files at [VALIDATION_DATA_DIR].
 * They are skipped automatically when the data directory is absent (e.g. on CI).
 *
 * Each GPX file from a known ski session must be detected as skiing by the pipeline.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkiDetectionGpxValidationTest {

	companion object {
		private const val VALIDATION_DATA_DIR =
			"D:\\Cloud\\Proton\\Shared with me\\lyze"
	}

	private val config = SkiDetectionConfig()
	private val dataDir = File(VALIDATION_DATA_DIR)
	private val reportFile = File(
		System.getProperty("java.io.tmpdir"),
		"ski-validation-report.txt",
	)

	@BeforeAll
	fun checkDataAvailable() {
		assumeTrue(dataDir.exists() && dataDir.isDirectory) {
			"Validation data directory not found: $VALIDATION_DATA_DIR — skipping"
		}
		reportFile.writeText("=== Ski Detection GPX Validation Report ===\n\n")
	}

	// ── GPX parsing (javax.xml, no Android deps) ───────────────────────

	data class GpxTrackPoint(
		val timeMs: Long,
		val lat: Double,
		val lon: Double,
		val altitudeM: Float?,
		val speedMps: Float?,
	)

	private fun parseGpxFile(file: File): List<GpxTrackPoint> {
		val factory = DocumentBuilderFactory.newInstance().apply {
			isNamespaceAware = true
		}
		val doc = factory.newDocumentBuilder().parse(file)
		val points = mutableListOf<GpxTrackPoint>()

		val trkpts = doc.getElementsByTagNameNS("*", "trkpt")
		for (i in 0 until trkpts.length) {
			val el = trkpts.item(i) as Element
			val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
			val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue

			val timeText = el.getChildText("time") ?: continue
			val timeMs = parseIso8601(timeText) ?: continue

			val alt = el.getChildText("ele")?.toFloatOrNull()
			val speed = el.getChildText("speed")?.toFloatOrNull()

			points.add(GpxTrackPoint(timeMs, lat, lon, alt, speed))
		}

		return points.sortedBy { it.timeMs }
	}

	private fun Element.getChildText(localName: String): String? {
		val children = getElementsByTagNameNS("*", localName)
		if (children.length == 0) return null
		return children.item(0).textContent?.trim()?.ifEmpty { null }
	}

	private fun parseIso8601(text: String): Long? {
		return try {
			java.time.Instant.parse(text).toEpochMilli()
		} catch (_: Exception) {
			try {
				java.time.ZonedDateTime.parse(text).toInstant().toEpochMilli()
			} catch (_: Exception) {
				null
			}
		}
	}

	// ── Pipeline ───────────────────────────────────────────────────────

	data class ValidationResult(
		val fileName: String,
		val pointCount: Int,
		val durationMin: Double,
		val segments: List<SkiStateSegment>,
		val cycles: Int,
		val summary: SkiSessionSummary,
	)

	private fun runPipeline(
		points: List<GpxTrackPoint>,
		fileName: String,
	): ValidationResult {
		val altitudes = points.mapNotNull { pt ->
			pt.altitudeM?.let { TimestampedAltitude(pt.timeMs, it) }
		}

		val rates = if (altitudes.size >= 2) {
			VerticalRateCalculator.compute(
				altitudes,
				medianWindow = config.gpsAltMedianWindow,
				emaAlpha = config.verticalRateEmaAlpha,
			)
		} else {
			emptyList()
		}

		// Build signals — match rates back to points by index
		// When altitude was missing for some points, we only have rates for
		// the subset that had altitude, so iterate over altitudes with index.
		val rateByTimeMs = rates.associateBy { it.timeMs }
		val signals = points.mapNotNull { pt ->
			val rate = rateByTimeMs[pt.timeMs] ?: return@mapNotNull null
			SkiSignal(
				timeMs = pt.timeMs,
				verticalRateMps = rate.verticalRateMps,
				speedMps = pt.speedMps ?: computeSpeed(pt, points),
			)
		}

		val sm = SkiStateMachine(config)
		val segments = sm.process(signals)
		val cycles = sm.countSkiCycles(segments)

		val locations = points.map {
			SkiLocationPoint(it.timeMs, it.lat, it.lon, it.altitudeM, it.speedMps)
		}
		val summary = SkiRunExtractor.extract(segments, locations)

		val durationMin = if (points.size >= 2) {
			(points.last().timeMs - points.first().timeMs) / 60_000.0
		} else {
			0.0
		}

		return ValidationResult(fileName, points.size, durationMin, segments, cycles, summary)
	}

	/**
	 * Estimate speed from consecutive points when GPX doesn't include <speed>.
	 */
	private fun computeSpeed(current: GpxTrackPoint, all: List<GpxTrackPoint>): Float {
		val idx = all.indexOf(current)
		if (idx <= 0) return 0f
		val prev = all[idx - 1]
		val dt = (current.timeMs - prev.timeMs) / 1000.0
		if (dt <= 0) return 0f
		val dist = SkiRunExtractor.haversineDistance(
			prev.lat, prev.lon, current.lat, current.lon,
		)
		return (dist / dt).toFloat()
	}

	// ── Test factory ───────────────────────────────────────────────────

	@TestFactory
	fun `validate ski detection on real GPX files`(): List<DynamicTest> {
		if (!dataDir.exists()) return emptyList()

		val gpxFiles = dataDir.listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }
			?.toList()
			?: emptyList()

		assumeTrue(gpxFiles.isNotEmpty()) { "No GPX files found in $VALIDATION_DATA_DIR" }

		return gpxFiles.map { file ->
			DynamicTest.dynamicTest(file.name) {
				val points = parseGpxFile(file)
				assumeTrue(points.size >= 10) {
					"${file.name}: too few trackpoints (${points.size}), skipping"
				}

				val result = runPipeline(points, file.name)
				printResult(result)

				// All files in the validation set are known ski sessions.
				// The pipeline must detect at least 2 lift→descent cycles.
				result.cycles shouldBeGreaterThanOrEqual config.minCyclesForClassification
				result.summary.totalRuns shouldBeGreaterThanOrEqual 2
				result.summary.runs.shouldNotBeEmpty()

				// Vertical drop should be significant (at least 200m total)
				val totalVertical = abs(result.summary.totalVerticalM)
				assert(totalVertical >= 200f) {
					"${file.name}: expected ≥200m total vertical, got ${totalVertical}m"
				}
			}
		}
	}

	private fun printResult(result: ValidationResult) {
		val stateBreakdown = result.segments
			.groupBy { it.state }
			.mapValues { (_, segs) ->
				val totalMin = segs.sumOf { it.durationMs } / 60_000.0
				"${segs.size} segments, %.1f min".format(totalMin)
			}

		val downhillRuns = result.summary.runs.filter { it.segmentType == SkiState.DOWNHILL_RUN }
		val perRunDetail = downhillRuns.mapIndexed { i, run ->
			"    Run ${i + 1}: vert=${"%.0f".format(run.verticalM)}m " +
					"dist=${"%.0f".format(run.distanceM)}m " +
					"maxSpd=${"%.1f".format(run.maxSpeedMps * 3.6)}km/h " +
					"dur=${"%.1f".format(run.durationMs / 60_000.0)}min"
		}.joinToString("\n")

		val report = """
			|── ${result.fileName} ──
			|  Points: ${result.pointCount}
			|  Duration: ${"%.1f".format(result.durationMin)} min
			|  Cycles detected: ${result.cycles}
			|  Total runs (downhill): ${result.summary.totalRuns}
			|  Total vertical: ${"%.0f".format(result.summary.totalVerticalM)} m
			|  Total distance: ${"%.0f".format(result.summary.totalDistanceM)} m
			|  Lift time: ${"%.1f".format(result.summary.totalLiftTimeMs / 60_000.0)} min
			|  Run time: ${"%.1f".format(result.summary.totalRunTimeMs / 60_000.0)} min
			|  State breakdown: $stateBreakdown
			|  Per-run detail:
			|$perRunDetail
			""".trimMargin()

		println(report)
		reportFile.appendText(report + "\n")
	}
}
