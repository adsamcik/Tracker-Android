package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV2Codec
import com.adsamcik.tracker.impexp.portable.ambientArchive
import com.adsamcik.tracker.impexp.portable.ambientDayBounds
import com.adsamcik.tracker.impexp.portable.completeAmbientDay
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveV2Sink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportRetryableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsV2
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientStepsV2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableAmbientStepsExporterTest {
	@Test
	fun `native range export avoids Location and uses truthful Ambient sensitivity copy`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 12L),
		)
		var request: ExportPortableAmbientStepsRequest? = null
		val backend = backend(
			native = { received, sink ->
				request = received
				sink.emit(archive)
				exported(archive)
			},
		)
		val exporter = PortableAmbientStepsExporter(
			backendProvider = { backend },
		)
		val output = ByteArrayOutputStream()
		val locations = Sequence<com.adsamcik.tracker.shared.model.LocationSample> {
			error("Ambient Steps export must not inspect Location")
		}

		exporter.export(
			mockk(relaxed = true),
			locations,
			output,
			1_000L..1_999L,
		) shouldBe ExportResult.Success(recordCount = 1)

		request shouldBe ExportPortableAmbientStepsRequest(1_000L, 2_000L)
		PortableAmbientStepsJsonV2Codec().decode(
			ByteArrayInputStream(output.toByteArray()),
		).archive.days.map { it.product } shouldBe archive.days
		exporter.requiresLocationData shouldBe false
		exporter.containsSensitiveLocationData shouldBe true
		exporter.sensitivityTitleRes shouldBe R.string.export_ambient_steps_sensitivity_title
		exporter.sensitivityMessageRes shouldBe R.string.export_ambient_steps_sensitivity_message
		exporter.canSelectDateRange shouldBe true
		exporter.extension shouldBe AmbientStepsPortableFormatV1.FILE_EXTENSION
		exporter.mimeType shouldBe AmbientStepsPortableFormatV1.MIME_TYPE
	}

	@Test
	fun `explicit imported origin invokes reexport rather than native authority`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 4L),
		)
		var nativeCalls = 0
		var importedCalls = 0
		var importedRequest: ExportPortableAmbientStepsRequest? = null
		val backend = backend(
			native = { _, _ ->
				nativeCalls++
				ExportPortableAmbientStepsResult.NoData
			},
			imported = { request, sink ->
				importedRequest = request
				importedCalls++
				sink.emit(archive)
				exported(archive)
			},
		)
		val exporter = PortableAmbientStepsExporter(
			origin = AmbientStepsPortableOrigin.IMPORTED,
			backendProvider = { backend },
		)

		exporter.export(
			mockk(relaxed = true),
			emptySequence(),
			ByteArrayOutputStream(),
			null,
		) shouldBe ExportResult.Success(recordCount = 1)
		nativeCalls shouldBe 0
		importedCalls shouldBe 1
		importedRequest shouldBe ExportPortableAmbientStepsRequest(0L, Long.MAX_VALUE)
	}

	@Test
	fun `source backend exposes v2 export at class scope`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 2), 7L),
		)
		var emitted: PortableAmbientStepsArchiveV2? = null
		val result = backend(
			native = { _, sink ->
				sink.emit(archive)
				exported(archive)
			},
		).exportV2(
			AmbientStepsPortableOrigin.NATIVE,
			ExportPortableAmbientStepsRequest(0L, Long.MAX_VALUE),
		) { emitted = it }

		result shouldBe exported(archive)
		emitted shouldBe archive.toV2()
	}

	@Test
	fun `missing v2 graph falls back to canonical v1 for native and imported origins`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 3), 9L),
		)
		listOf(
			AmbientStepsPortableOrigin.NATIVE,
			AmbientStepsPortableOrigin.IMPORTED,
		).forEach { origin ->
			val v1 = producer { _, sink ->
				sink.emit(archive)
				exported(archive)
			}
			val v2 = object : ExportPortableAmbientStepsV2 {
				override suspend fun export(
					request: ExportPortableAmbientStepsRequest,
					sink: PortableAmbientStepsArchiveV2Sink,
				) = ExportPortableAmbientStepsResult.Unverifiable(
					PortableAmbientStepsExportUnverifiableReason
						.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
				)
			}
			val backend = AmbientStepsPortableSourceBackend(
				nativeExporter = v1,
				importedReexporter = object : ReexportImportedAmbientSteps {
					override suspend fun export(
						request: ExportPortableAmbientStepsRequest,
						sink: PortableAmbientStepsArchiveSink,
					) = v1.export(request, sink)
				},
				nativeExporterV2 = v2,
				importedReexporterV2 = object : ReexportImportedAmbientStepsV2 {
					override suspend fun export(
						request: ExportPortableAmbientStepsRequest,
						sink: PortableAmbientStepsArchiveV2Sink,
					) = v2.export(request, sink)
				},
			)
			val output = ByteArrayOutputStream()

			PortableAmbientStepsExporter(
				origin = origin,
				backendProvider = { backend },
			).export(
				ApplicationProvider.getApplicationContext(),
				emptySequence(),
				output,
				null,
			) shouldBe ExportResult.Success(1)

			PortableAmbientStepsJsonV1Codec().decode(
				ByteArrayInputStream(output.toByteArray()),
			).archive shouldBe archive
		}
	}

	@Test
	fun `large v2 export streams bounded chunks without a whole-file destination write`() = runTest {
		val archive = largeAmbientArchive(256)
		val output = MaximumWriteOutputStream(MAX_STREAM_WRITE_SIZE)
		val exporter = PortableAmbientStepsExporter(
			backendProvider = {
				backend(native = { _, sink ->
					sink.emit(archive)
					exported(archive)
				})
			},
		)

		exporter.export(
			mockk(relaxed = true),
			emptySequence(),
			output,
			null,
		) shouldBe ExportResult.Success(1)

		(output.writtenBytes > MAX_STREAM_WRITE_SIZE) shouldBe true
	}

	@Test
	fun `failed v1 fallback leaves destination empty`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 4), 9L),
		)
		val v1 = producer { _, sink ->
			sink.emit(archive)
			ExportPortableAmbientStepsResult.RetryableFailure(
				PortableAmbientStepsExportRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		val v2 = object : ExportPortableAmbientStepsV2 {
			override suspend fun export(
				request: ExportPortableAmbientStepsRequest,
				sink: PortableAmbientStepsArchiveV2Sink,
			) = ExportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
			)
		}
		val output = ByteArrayOutputStream()
		val result = PortableAmbientStepsExporter(
			backendProvider = {
				AmbientStepsPortableSourceBackend(
					nativeExporter = v1,
					importedReexporter = object : ReexportImportedAmbientSteps {
						override suspend fun export(
							request: ExportPortableAmbientStepsRequest,
							sink: PortableAmbientStepsArchiveSink,
						) = v1.export(request, sink)
					},
					nativeExporterV2 = v2,
					importedReexporterV2 = object : ReexportImportedAmbientStepsV2 {
						override suspend fun export(
							request: ExportPortableAmbientStepsRequest,
							sink: PortableAmbientStepsArchiveV2Sink,
						) = v2.export(request, sink)
					},
				)
			},
		).export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			output,
			null,
		)

		result shouldBe ExportResult.Error(
			com.adsamcik.tracker.shared.base.misc.LocalizedString(
				R.string.export_error_portable_ambient_steps_write,
			),
		)
		output.size() shouldBe 0
	}

	@Test
	fun `source backend resolves full range DST day and typed unsupported scopes`() {
		val backend = backend()
		backend.resolve(AmbientStepsPortableFileScope.AllAvailableSnapshot) shouldBe
			AmbientStepsPortableScopeResolution.Ready(
				ExportPortableAmbientStepsRequest(0L, Long.MAX_VALUE),
			)
		backend.resolve(AmbientStepsPortableFileScope.Range(10L, 20L)) shouldBe
			AmbientStepsPortableScopeResolution.Ready(
				ExportPortableAmbientStepsRequest(10L, 20L),
			)
		backend.resolve(AmbientStepsPortableFileScope.Range(20L, 20L)) shouldBe
			AmbientStepsPortableScopeResolution.Unsupported(
				AmbientStepsPortableUnsupportedScope.RANGE_SCOPE_NOT_REPRESENTABLE,
			)
		val date = LocalDate.of(2026, 10, 25)
		val (dayStart, dayEnd) = ambientDayBounds(date, "Europe/Prague")
		backend.resolve(
			AmbientStepsPortableFileScope.StructuralDay(
				date.toEpochDay(),
				"Europe/Prague",
			),
		) shouldBe AmbientStepsPortableScopeResolution.Ready(
			ExportPortableAmbientStepsRequest(dayStart, dayEnd),
		)
		backend.resolve(
			AmbientStepsPortableFileScope.StructuralDay(0L, "invalid-zone"),
		) shouldBe AmbientStepsPortableScopeResolution.Unsupported(
			AmbientStepsPortableUnsupportedScope.DAY_SCOPE_NOT_REPRESENTABLE,
		)
		backend.resolve(AmbientStepsPortableFileScope.Trip("session-1")) shouldBe
			AmbientStepsPortableScopeResolution.Unsupported(
				AmbientStepsPortableUnsupportedScope.TRIP_SCOPE_NOT_REPRESENTABLE,
			)
	}

	@Test
	fun `empty unverifiable retryable and raw output IO never become success`() = runTest {
		suspend fun export(result: ExportPortableAmbientStepsResult): ExportResult =
			PortableAmbientStepsExporter(
				backendProvider = {
					backend(native = { _, _ -> result })
				},
			).export(mockk(relaxed = true), emptySequence(), ByteArrayOutputStream(), null)

		export(ExportPortableAmbientStepsResult.NoData) shouldBe ExportResult.Error(
			com.adsamcik.tracker.shared.base.misc.LocalizedString(
				R.string.export_error_no_portable_ambient_steps,
			),
		)
		(export(
			ExportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW,
			),
		) as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_ambient_steps_unverifiable
		(export(
			ExportPortableAmbientStepsResult.RetryableFailure(
				PortableAmbientStepsExportRetryableReason.STORAGE_UNAVAILABLE,
			),
		) as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_ambient_steps_retryable

		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 1L),
		)
		val ioExporter = PortableAmbientStepsExporter(
			backendProvider = {
				backend(native = { _, sink ->
					sink.emit(archive)
					exported(archive)
				})
			},
		)
		shouldThrow<IOException> {
			ioExporter.export(
				mockk(relaxed = true),
				emptySequence(),
				FailingAmbientOutputStream(),
				null,
			)
		}.message shouldBe "write failed"
	}

	private fun backend(
		native: suspend (
			ExportPortableAmbientStepsRequest,
			PortableAmbientStepsArchiveSink,
		) -> ExportPortableAmbientStepsResult = { _, _ ->
			ExportPortableAmbientStepsResult.NoData
		},
		imported: suspend (
			ExportPortableAmbientStepsRequest,
			PortableAmbientStepsArchiveSink,
		) -> ExportPortableAmbientStepsResult = { _, _ ->
			ExportPortableAmbientStepsResult.NoData
		},
	): AmbientStepsPortableSourceBackend = AmbientStepsPortableSourceBackend(
		nativeExporter = producer(native),
		importedReexporter = object : ReexportImportedAmbientSteps {
			override suspend fun export(
				request: ExportPortableAmbientStepsRequest,
				sink: PortableAmbientStepsArchiveSink,
			): ExportPortableAmbientStepsResult = imported(request, sink)
		},
		nativeExporterV2 = producerV2(native),
		importedReexporterV2 = object : ReexportImportedAmbientStepsV2 {
			override suspend fun export(
				request: ExportPortableAmbientStepsRequest,
				sink: PortableAmbientStepsArchiveV2Sink,
			): ExportPortableAmbientStepsResult = imported(
				request,
				PortableAmbientStepsArchiveSink { archive ->
					sink.emit(archive.toV2())
				},
			)
		},
	)

	private fun producer(
		block: suspend (
			ExportPortableAmbientStepsRequest,
			PortableAmbientStepsArchiveSink,
		) -> ExportPortableAmbientStepsResult,
	): ExportPortableAmbientSteps = object : ExportPortableAmbientSteps {
		override suspend fun export(
			request: ExportPortableAmbientStepsRequest,
			sink: PortableAmbientStepsArchiveSink,
		): ExportPortableAmbientStepsResult = block(request, sink)
	}

	private fun producerV2(
		block: suspend (
			ExportPortableAmbientStepsRequest,
			PortableAmbientStepsArchiveSink,
		) -> ExportPortableAmbientStepsResult,
	): ExportPortableAmbientStepsV2 = object : ExportPortableAmbientStepsV2 {
		override suspend fun export(
			request: ExportPortableAmbientStepsRequest,
			sink: PortableAmbientStepsArchiveV2Sink,
		): ExportPortableAmbientStepsResult = block(
			request,
			PortableAmbientStepsArchiveSink { archive ->
				sink.emit(archive.toV2())
			},
		)
	}

	private fun exported(
		archive: PortableAmbientStepsArchiveV1,
	) = ExportPortableAmbientStepsResult.Exported(
		archive.days.size,
		archive.days.sumOf { it.facts.size },
		archive.days.sumOf { it.gaps.size },
	)

	private fun largeAmbientArchive(factCount: Int): PortableAmbientStepsArchiveV1 {
		val date = LocalDate.of(2026, 1, 5)
		val (start, end) = ambientDayBounds(date, "UTC")
		val intervalSize = (end - start) / factCount
		val facts = (0 until factCount).map { index ->
			val factStart = start + intervalSize * index
			val factEnd = if (index == factCount - 1) end else factStart + intervalSize
			PortableAmbientStepsFactV1.create(
				AmbientStepsPortableOpaqueIdentity.derive(
					AmbientStepsPortableIdentityKind.FACT,
					"large-ambient-fact-$index",
				),
				factStart,
				factEnd,
				index.toLong(),
			)
		}
		return PortableAmbientStepsArchiveV1.create(
			listOf(
				PortableAmbientStepsDayV1.create(
					identity = AmbientStepsPortableOpaqueIdentity.derive(
						AmbientStepsPortableIdentityKind.DAY,
						"large-ambient-day",
					),
					structuralEpochDay = date.toEpochDay(),
					storedZoneId = "UTC",
					structuralDayStartTimeMs = start,
					structuralDayEndTimeMs = end,
					retainedFromTimeMs = null,
					coverage = PortableAmbientStepsCoverage.COMPLETE,
					partialCauses = emptyList(),
					retainedStepCount = facts.sumOf { it.stepCount },
					facts = facts,
					gaps = emptyList(),
				),
			),
		)
	}
}

private fun PortableAmbientStepsArchiveV1.toV2(): PortableAmbientStepsArchiveV2 =
	PortableAmbientStepsArchiveV2.create(
		days.map { it.withExplicitUnprovenCountDomain() },
	)

private class FailingAmbientOutputStream : java.io.OutputStream() {
	override fun write(value: Int) = throw IOException("write failed")
	override fun write(buffer: ByteArray, offset: Int, length: Int) =
		throw IOException("write failed")
}

private class MaximumWriteOutputStream(
	private val maximumWriteSize: Int,
) : OutputStream() {
	var writtenBytes = 0L
		private set

	override fun write(value: Int) {
		writtenBytes++
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int) {
		require(length <= maximumWriteSize) {
			"Exporter attempted a whole-file write of $length bytes"
		}
		writtenBytes += length
	}
}

private const val MAX_STREAM_WRITE_SIZE = 16 * 1_024
