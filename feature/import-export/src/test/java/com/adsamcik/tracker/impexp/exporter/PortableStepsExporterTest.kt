package com.adsamcik.tracker.impexp.exporter

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2Sink
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntrySink
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableStepsExporterTest {
	@Test
	fun `range export writes canonical portable entry without reading Location`() = runTest {
		val entry = entry().withExplicitUnprovenCountDomain()
		var receivedRequest: ExportPortableStepsRequest? = null
		val exporter = PortableStepsExporter {
			fakeExporter { request, sink ->
				receivedRequest = request
				sink.emit(PortableStepsArchiveV2.create(listOf(entry)))
				ExportPortableStepsResult.Exported(1)
			}
		}
		val output = ByteArrayOutputStream()
		val locationData = Sequence<com.adsamcik.tracker.shared.model.LocationSample> {
			error("Portable Steps export must not inspect Location")
		}

		val result = exporter.export(
			context = ApplicationProvider.getApplicationContext(),
			locationData = locationData,
			outputStream = output,
			dateRange = 1_000L..1_999L,
		)

		result shouldBe ExportResult.Success(recordCount = 1)
		receivedRequest shouldBe ExportPortableStepsRequest(1_000L, 2_000L)
		val decoded = PortableStepsJsonV2Codec()
			.decode(ByteArrayInputStream(output.toByteArray())).archive
		decoded.entries shouldContainExactly listOf(entry)
	}

	@Test
	fun `whole-history export uses bounded full request and privacy-minimized metadata`() = runTest {
		var receivedRequest: ExportPortableStepsRequest? = null
		val exporter = PortableStepsExporter {
			fakeExporter { request, _ ->
				receivedRequest = request
				ExportPortableStepsResult.NoEntries
			}
		}
		val output = ByteArrayOutputStream()

		val result = exporter.export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			output,
			null,
		)

		receivedRequest shouldBe PortableStepsExporter.FULL_HISTORY
		result shouldBe ExportResult.Error(
			com.adsamcik.tracker.shared.base.misc.LocalizedString(R.string.export_error_no_portable_steps),
		)
		output.size() shouldBe 0
		exporter.requiresLocationData shouldBe false
		exporter.containsSensitiveLocationData shouldBe false
		exporter.canSelectDateRange shouldBe true
		exporter.extension shouldBe StepsPortableFormatV1.FILE_EXTENSION
		exporter.mimeType shouldBe StepsPortableFormatV1.MIME_TYPE
	}

	@Test
	fun `typed source refusal remains distinct from retryable export failure`() = runTest {
		val unavailableOutput = ByteArrayOutputStream()
		val unavailable = PortableStepsExporter {
			fakeExporter { _, _ ->
				ExportPortableStepsResult.Unverifiable(
					PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				)
			}
		}.export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			unavailableOutput,
			null,
		)
		val retryableOutput = ByteArrayOutputStream()
		val retryable = PortableStepsExporter {
			fakeExporter { _, _ ->
				ExportPortableStepsResult.RetryableFailure(
					PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
				)
			}
		}.export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			retryableOutput,
			null,
		)

		(unavailable as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_steps_unverifiable
		(retryable as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_steps_retryable
		unavailableOutput.size() shouldBe 0
		retryableOutput.size() shouldBe 0
	}

	@Test
	fun `missing v2 graph falls back to canonical v1 before writing destination bytes`() = runTest {
		val legacyEntry = entry()
		var v2Calls = 0
		var v1Calls = 0
		val exporter = PortableStepsExporter(
			legacyExporterProvider = {
				fakeLegacyExporter { _, sink ->
					v1Calls++
					sink.emit(legacyEntry)
					ExportPortableStepsResult.Exported(1)
				}
			},
			exporterProvider = {
				fakeExporter { _, _ ->
					v2Calls++
					ExportPortableStepsResult.Unverifiable(
						PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
					)
				}
			},
		)
		val output = ByteArrayOutputStream()

		exporter.export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			output,
			null,
		) shouldBe ExportResult.Success(recordCount = 1)

		v2Calls shouldBe 1
		v1Calls shouldBe 1
		val decoded = mutableListOf<PortableStepsEntryV1>()
		PortableStepsJsonV1Codec().decode(ByteArrayInputStream(output.toByteArray())) {
			decoded += it
		}
		decoded shouldContainExactly listOf(legacyEntry)
	}

	@Test
	fun `v2 encoded overflow falls back to bounded v1 before destination write`() = runTest {
		val legacyEntry = entry()
		val v1Bytes = ByteArrayOutputStream().also { output ->
			PortableStepsJsonV1Codec().encode(output) { sink ->
				sink.emit(legacyEntry)
				ExportPortableStepsResult.Exported(1)
			}
		}.toByteArray()
		var v2Calls = 0
		var v1Calls = 0
		val output = ByteArrayOutputStream()
		val exporter = PortableStepsExporter(
			legacyExporterProvider = {
				fakeLegacyExporter { _, sink ->
					v1Calls++
					sink.emit(legacyEntry)
					ExportPortableStepsResult.Exported(1)
				}
			},
			v2MaximumBytes = v1Bytes.size.toLong(),
			v1MaximumBytes = v1Bytes.size.toLong(),
			exporterProvider = {
				fakeExporter { _, sink ->
					v2Calls++
					sink.emit(
						PortableStepsArchiveV2.create(
							listOf(legacyEntry.withExplicitUnprovenCountDomain()),
						),
					)
					ExportPortableStepsResult.Exported(1)
				}
			},
		)

		exporter.export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			output,
			null,
		) shouldBe ExportResult.Success(1)

		v2Calls shouldBe 1
		v1Calls shouldBe 1
		val decoded = mutableListOf<PortableStepsEntryV1>()
		PortableStepsJsonV1Codec().decode(ByteArrayInputStream(output.toByteArray())) {
			decoded += it
		}
		decoded shouldContainExactly listOf(legacyEntry)
	}

	@Test
	fun `v2 and v1 encoded overflow return error without destination bytes`() = runTest {
		val legacyEntry = entry()
		val v1Size = ByteArrayOutputStream().also { output ->
			PortableStepsJsonV1Codec().encode(output) { sink ->
				sink.emit(legacyEntry)
				ExportPortableStepsResult.Exported(1)
			}
		}.size()
		var v2Calls = 0
		var v1Calls = 0
		val output = ByteArrayOutputStream()
		val exporter = PortableStepsExporter(
			legacyExporterProvider = {
				fakeLegacyExporter { _, sink ->
					v1Calls++
					sink.emit(legacyEntry)
					ExportPortableStepsResult.Exported(1)
				}
			},
			v2MaximumBytes = (v1Size - 1).toLong(),
			v1MaximumBytes = (v1Size - 1).toLong(),
			exporterProvider = {
				fakeExporter { _, sink ->
					v2Calls++
					sink.emit(
						PortableStepsArchiveV2.create(
							listOf(legacyEntry.withExplicitUnprovenCountDomain()),
						),
					)
					ExportPortableStepsResult.Exported(1)
				}
			},
		)

		exporter.export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			output,
			null,
		) shouldBe ExportResult.Error(
			com.adsamcik.tracker.shared.base.misc.LocalizedString(
				R.string.export_error_portable_steps_write,
			),
		)
		v2Calls shouldBe 1
		v1Calls shouldBe 1
		output.size() shouldBe 0
	}

	@Test
	fun `large v2 export streams bounded chunks without a whole-file destination write`() = runTest {
		val entry = largeEntry(256).withExplicitUnprovenCountDomain()
		val output = MaximumWriteOutputStream(MAX_STREAM_WRITE_SIZE)
		val exporter = PortableStepsExporter {
			fakeExporter { _, sink ->
				sink.emit(PortableStepsArchiveV2.create(listOf(entry)))
				ExportPortableStepsResult.Exported(1)
			}
		}

		exporter.export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			output,
			null,
		) shouldBe ExportResult.Success(1)

		(output.writtenBytes > MAX_STREAM_WRITE_SIZE) shouldBe true
	}

	@Test
	fun `failed v1 fallback leaves destination empty`() = runTest {
		val legacyEntry = entry()
		val output = ByteArrayOutputStream()
		val exporter = PortableStepsExporter(
			legacyExporterProvider = {
				fakeLegacyExporter { _, sink ->
					sink.emit(legacyEntry)
					ExportPortableStepsResult.RetryableFailure(
						PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
					)
				}
			},
			exporterProvider = {
				fakeExporter { _, _ ->
					ExportPortableStepsResult.Unverifiable(
						PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
					)
				}
			},
		)

		kotlin.test.assertFailsWith<PortableStepsJsonException> {
			exporter.export(
				ApplicationProvider.getApplicationContext(),
				emptySequence(),
				output,
				null,
			)
		}
		output.size() shouldBe 0
	}

	@Test
	fun `v2 codec failure after emission leaves caller destination empty`() = runTest {
		val archive = PortableStepsArchiveV2.create(
			listOf(entry().withExplicitUnprovenCountDomain()),
		)
		val output = ByteArrayOutputStream()
		val exporter = PortableStepsExporter {
			fakeExporter { _, sink ->
				sink.emit(archive)
				ExportPortableStepsResult.RetryableFailure(
					PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
				)
			}
		}

		kotlin.test.assertFailsWith<PortableStepsJsonException> {
			exporter.export(
				ApplicationProvider.getApplicationContext(),
				emptySequence(),
				output,
				null,
			)
		}
		output.size() shouldBe 0
	}

	@Test
	fun `v2 cancellation after emission leaves caller destination empty`() = runTest {
		val archive = PortableStepsArchiveV2.create(
			listOf(entry().withExplicitUnprovenCountDomain()),
		)
		val output = ByteArrayOutputStream()
		val exporter = PortableStepsExporter {
			fakeExporter { _, sink ->
				sink.emit(archive)
				throw CancellationException("cancel staged v2 encode")
			}
		}

		kotlin.test.assertFailsWith<CancellationException> {
			exporter.export(
				ApplicationProvider.getApplicationContext(),
				emptySequence(),
				output,
				null,
			)
		}
		output.size() shouldBe 0
	}

	@Test
	fun `v1 fallback cancellation after emission leaves caller destination empty`() = runTest {
		val legacyEntry = entry()
		val output = ByteArrayOutputStream()
		val exporter = PortableStepsExporter(
			legacyExporterProvider = {
				fakeLegacyExporter { _, sink ->
					sink.emit(legacyEntry)
					throw CancellationException("cancel staged v1 encode")
				}
			},
			exporterProvider = {
				fakeExporter { _, _ ->
					ExportPortableStepsResult.Unverifiable(
						PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
					)
				}
			},
		)

		kotlin.test.assertFailsWith<CancellationException> {
			exporter.export(
				ApplicationProvider.getApplicationContext(),
				emptySequence(),
				output,
				null,
			)
		}
		output.size() shouldBe 0
	}

	@Test
	fun `exact export forwards native and imported membership and epoch without a range query`() = runTest {
		val selected = listOf(entry().withExplicitUnprovenCountDomain(),
			largeEntry(2).withExplicitUnprovenCountDomain())
		val request = selectedRequest(imported = listOf(selected.last().product.identity))
		val received = mutableListOf<ExportSelectedPortableStepsRequest>()
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { current, sink ->
			received += current
			sink.emit(PortableStepsArchiveV2.create(selected))
			ExportPortableStepsResult.Exported(2)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) shouldBe
			ExportResult.Success(2)

		received shouldContainExactly listOf(request, request, request)
		PortableStepsJsonV2Codec().decode(ByteArrayInputStream(output.toByteArray())).archive.entries
			.shouldContainExactly(selected)
		ownedSpools() shouldBe before
	}

	@Test
	fun `exact graph refusal uses only the selected v1 records for staging and both rechecks`() = runTest {
		val selected = entry()
		val request = selectedRequest(imported = listOf(selected.identity))
		val receivedV1 = mutableListOf<ExportSelectedPortableStepsRequest>()
		var v2Calls = 0
		val output = ByteArrayOutputStream()
		val exporter = exactExporter(
			legacy = { current, sink ->
				receivedV1 += current
				sink.emit(selected)
				ExportPortableStepsResult.Exported(1)
			},
			v2 = { current, _ ->
				current shouldBe request
				v2Calls++
				ExportPortableStepsResult.Unverifiable(
					PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE)
			})

		exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) shouldBe
			ExportResult.Success(1)

		v2Calls shouldBe 1
		receivedV1 shouldContainExactly listOf(request, request, request)
		val decoded = mutableListOf<PortableStepsEntryV1>()
		PortableStepsJsonV1Codec().decode(ByteArrayInputStream(output.toByteArray())) { decoded += it }
		decoded shouldContainExactly listOf(selected)
	}

	@Test
	fun `exact encoded v2 overflow keeps v1 for selected content checks`() = runTest {
		val selected = entry()
		val v1Size = ByteArrayOutputStream().also { output ->
			PortableStepsJsonV1Codec().encode(output) { sink ->
				sink.emit(selected)
				ExportPortableStepsResult.Exported(1)
			}
		}.size().toLong()
		var v1Calls = 0
		var v2Calls = 0
		val output = ByteArrayOutputStream()
		val exporter = exactExporter(
			legacy = { _, sink ->
				v1Calls++
				sink.emit(selected)
				ExportPortableStepsResult.Exported(1)
			},
			v2MaximumBytes = v1Size,
			v1MaximumBytes = v1Size,
			v2 = { _, sink ->
				v2Calls++
				sink.emit(PortableStepsArchiveV2.create(listOf(selected.withExplicitUnprovenCountDomain())))
				ExportPortableStepsResult.Exported(1)
			})

		exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output) shouldBe
			ExportResult.Success(1)
		v2Calls shouldBe 1
		v1Calls shouldBe 3
		val decoded = mutableListOf<PortableStepsEntryV1>()
		PortableStepsJsonV1Codec().decode(ByteArrayInputStream(output.toByteArray())) { decoded += it }
		decoded shouldContainExactly listOf(selected)
	}

	@Test
	fun `selected edit before destination copying refuses bytes and removes the private spool`() = runTest {
		var calls = 0
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			calls++
			val selected = entry(if (calls == 1) 12L else 19L).withExplicitUnprovenCountDomain()
			sink.emit(PortableStepsArchiveV2.create(listOf(selected)))
			ExportPortableStepsResult.Exported(1)
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_steps_retryable
		calls shouldBe 2
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `selected change during destination copying cannot report a successful v2 file`() = runTest {
		var count = 12L
		var calls = 0
		val before = ownedSpools()
		val output = MutationOutputStream { count = 19L }
		val exporter = exactExporter { _, sink ->
			calls++
			sink.emit(PortableStepsArchiveV2.create(listOf(entry(count).withExplicitUnprovenCountDomain())))
			ExportPortableStepsResult.Exported(1)
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_steps_retryable
		calls shouldBe 3
		(output.bytes.size() > 0) shouldBe true // The enclosing transfer must discard this failed destination.
		PortableStepsJsonV2Codec().decode(ByteArrayInputStream(output.bytes.toByteArray())).archive.entries
			.shouldContainExactly(listOf(entry().withExplicitUnprovenCountDomain()))
		ownedSpools() shouldBe before
	}

	@Test
	fun `selected change during v1 destination copying cannot retry a different format`() = runTest {
		var count = 12L
		var v1Calls = 0
		var v2Calls = 0
		val output = MutationOutputStream { count = 19L }
		val exporter = exactExporter(
			legacy = { _, sink ->
				v1Calls++
				sink.emit(entry(count))
				ExportPortableStepsResult.Exported(1)
			},
			v2 = { _, _ ->
				v2Calls++
				ExportPortableStepsResult.Unverifiable(
					PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE)
			})

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_steps_retryable
		v2Calls shouldBe 1
		v1Calls shouldBe 3
	}

	@Test
	fun `unrelated source writes during copy do not invalidate selected Steps contents`() = runTest {
		var unrelatedRevision = 20L
		val observedRevisions = mutableListOf<Long>()
		val selected = entry().withExplicitUnprovenCountDomain()
		val output = MutationOutputStream { unrelatedRevision++ }
		val exporter = exactExporter { _, sink ->
			observedRevisions += unrelatedRevision
			sink.emit(PortableStepsArchiveV2.create(listOf(selected)))
			ExportPortableStepsResult.Exported(1)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output) shouldBe
			ExportResult.Success(1)
		observedRevisions shouldContainExactly listOf(20L, 20L, 21L)
		PortableStepsJsonV2Codec().decode(ByteArrayInputStream(output.bytes.toByteArray())).archive.entries
			.shouldContainExactly(listOf(selected))
	}

	@Test
	fun `exact export preserves its original epoch instead of adopting the current epoch`() = runTest {
		val request = selectedRequest()
		val output = ByteArrayOutputStream()
		var calls = 0
		val exporter = exactExporter { current, _ ->
			current shouldBe request
			calls++
			ExportPortableStepsResult.RetryableFailure(PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_steps_retryable
		calls shouldBe 1
		output.size() shouldBe 0
	}

	@Test
	fun `clear during destination copying rejects the original epoch and cannot report success`() = runTest {
		val request = selectedRequest()
		var currentEpoch = request.expectedCollectedDataEpoch
		val output = MutationOutputStream { currentEpoch++ }
		var calls = 0
		val exporter = exactExporter { current, sink ->
			calls++
			if (current.expectedCollectedDataEpoch != currentEpoch) {
				ExportPortableStepsResult.RetryableFailure(PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE)
			} else {
				sink.emit(PortableStepsArchiveV2.create(listOf(entry().withExplicitUnprovenCountDomain())))
				ExportPortableStepsResult.Exported(1)
			}
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_steps_retryable
		calls shouldBe 3
	}

	@Test
	fun `caller collection mutation cannot widen the staged exact selection`() = runTest {
		val native = mutableListOf(41L)
		val imported = mutableListOf(identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "imported"))
		val request = ExportSelectedPortableStepsRequest(native, imported, 7L)
		val expected = request.copy(nativeSegmentIds = native.toList(), importedEntryIdentities = imported.toList())
		val received = mutableListOf<ExportSelectedPortableStepsRequest>()
		val output = MutationOutputStream { native += 42L; imported.clear() }
		val exporter = exactExporter { current, sink ->
			received += current
			sink.emit(PortableStepsArchiveV2.create(listOf(entry().withExplicitUnprovenCountDomain())))
			ExportPortableStepsResult.Exported(1)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) shouldBe ExportResult.Success(1)
		received shouldContainExactly listOf(expected, expected, expected)
	}

	@Test
	fun `empty or unavailable exact source data does not produce bytes or silently use dates`() = runTest {
		val outcomes = listOf(ExportPortableStepsResult.NoEntries,
			ExportPortableStepsResult.Unverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE))
		val messages = listOf(R.string.export_error_no_portable_steps, R.string.export_error_portable_steps_unverifiable)
		outcomes.zip(messages).forEach { (outcome, message) ->
			val before = ownedSpools()
			val output = ByteArrayOutputStream()
			val result = exactExporter { _, _ -> outcome }
				.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
			(result as ExportResult.Error).message?.stringRes shouldBe message
			output.size() shouldBe 0
			ownedSpools() shouldBe before
		}
	}

	@Test
	fun `exact source cancellation after emission removes the spool without destination bytes`() = runTest {
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			sink.emit(PortableStepsArchiveV2.create(listOf(entry().withExplicitUnprovenCountDomain())))
			throw CancellationException("cancel exact source")
		}

		kotlin.test.assertFailsWith<CancellationException> {
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		}
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `destination cancellation stops exact rechecks and physically removes its spool`() = runTest {
		val before = ownedSpools()
		var calls = 0
		var returned = false
		var writes = 0
		val exporter = exactExporter { _, sink ->
			calls++
			sink.emit(PortableStepsArchiveV2.create(listOf(largeEntry(256).withExplicitUnprovenCountDomain())))
			ExportPortableStepsResult.Exported(1)
		}
		val job = launch(start = CoroutineStart.UNDISPATCHED) {
			val caller = currentCoroutineContext()
			val destination = object : OutputStream() {
				override fun write(value: Int) = error("Expected buffered copy")
				override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; caller.cancel() }
			}
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), destination)
			returned = true
		}
		job.join()
		job.isCancelled shouldBe true
		writes shouldBe 1
		calls shouldBe 2
		returned shouldBe false
		ownedSpools() shouldBe before
	}

	@Test
	fun `exact post-emission source refusal discards staged bytes and removes its spool`() = runTest {
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			sink.emit(PortableStepsArchiveV2.create(listOf(entry().withExplicitUnprovenCountDomain())))
			ExportPortableStepsResult.RetryableFailure(PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		}

		kotlin.test.assertFailsWith<PortableStepsJsonException> {
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		}
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	private fun selectedRequest(imported: List<PortableStepsOpaqueIdentity> = emptyList()) =
		ExportSelectedPortableStepsRequest(listOf(41L), imported, 7L)

	private fun exactExporter(
		legacy: suspend (ExportSelectedPortableStepsRequest, PortableStepsEntrySink) -> ExportPortableStepsResult =
			{ _, _ -> error("Exact selection must not fall back without a v2 size or graph refusal") },
		v2MaximumBytes: Long = com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV2.MAX_FILE_BYTES,
		v1MaximumBytes: Long = StepsPortableFormatV1.MAX_FILE_BYTES,
		v2: suspend (ExportSelectedPortableStepsRequest, PortableStepsArchiveV2Sink) -> ExportPortableStepsResult,
	): PortableStepsExporter = PortableStepsExporter(
		legacyExporterProvider = {
			object : ExportPortableSteps {
				override suspend fun export(request: ExportPortableStepsRequest, sink: PortableStepsEntrySink):
					ExportPortableStepsResult = error("Exact selection must not widen to dates")
				override suspend fun export(request: ExportSelectedPortableStepsRequest, sink: PortableStepsEntrySink):
					ExportPortableStepsResult = legacy(request, sink)
			}
		},
		v2MaximumBytes = v2MaximumBytes,
		v1MaximumBytes = v1MaximumBytes,
		exporterProvider = {
			object : ExportPortableStepsV2 {
				override suspend fun export(request: ExportPortableStepsRequest, sink: PortableStepsArchiveV2Sink):
					ExportPortableStepsResult = error("Exact selection must not widen to dates")
				override suspend fun export(request: ExportSelectedPortableStepsRequest, sink: PortableStepsArchiveV2Sink):
					ExportPortableStepsResult = v2(request, sink)
			}
		})

	private fun ownedSpools(): Set<String> = File(
		ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "portable-export-spool",
	).listFiles().orEmpty().filter { it.name.startsWith("tracker-portable-export-spool-v1-") }
		.map { it.absolutePath }.toSet()

	private class MutationOutputStream(private val onCopy: () -> Unit) : OutputStream() {
		val bytes = ByteArrayOutputStream()
		private var copied = false
		override fun write(value: Int) = error("Expected buffered copy")
		override fun write(buffer: ByteArray, offset: Int, length: Int) {
			bytes.write(buffer, offset, length)
			if (!copied) { copied = true; onCopy() }
		}
	}

	private fun fakeExporter(
		block: suspend (ExportPortableStepsRequest, PortableStepsArchiveV2Sink) -> ExportPortableStepsResult,
	): ExportPortableStepsV2 = object : ExportPortableStepsV2 {
		override suspend fun export(
			request: ExportPortableStepsRequest,
			sink: PortableStepsArchiveV2Sink,
		): ExportPortableStepsResult = block(request, sink)

		override suspend fun export(
			request: ExportSelectedPortableStepsRequest,
			sink: PortableStepsArchiveV2Sink,
		): ExportPortableStepsResult = error("Standalone date export must not use exact session selection")
	}

	private fun fakeLegacyExporter(
		block: suspend (
			ExportPortableStepsRequest,
			PortableStepsEntrySink,
		) -> ExportPortableStepsResult,
	): ExportPortableSteps = object : ExportPortableSteps {
		override suspend fun export(
			request: ExportPortableStepsRequest,
			sink: PortableStepsEntrySink,
		): ExportPortableStepsResult = block(request, sink)

		override suspend fun export(
			request: ExportSelectedPortableStepsRequest,
			sink: PortableStepsEntrySink,
		): ExportPortableStepsResult = error("Standalone date export must not use exact session selection")
	}

	private fun entry(stepCount: Long = 12L): PortableStepsEntryV1 {
		val run = PortableStepsRunV1(
			identity = identity(PortableStepsIdentityKind.PHYSICAL_RUN, "run"),
			deletionScopeDigest = PortableStepsDeletionScopeDigest.derive("logical", "run"),
			startTimeMs = 1_100L,
			endTimeMs = 1_900L,
			storedZoneId = "Europe/Prague",
			manifests = listOf(
				PortableStepsManifestV1(
					revision = 1L,
					effectiveWallTimeMs = 1_100L,
					originSourcePolicyRevision = 7L,
					captureConsentEpoch = 3L,
				),
			),
			completeness = PortableStepsCompletenessV1(
				captureCoverage = PortableStepsCaptureCoverage.WHOLE_RUN,
				providerCoverage = PortableStepsProviderCoverage.COMPLETE,
				appDrainComplete = true,
				stopComplete = true,
				hasUnresolvedProviderRange = false,
			),
			facts = listOf(
				PortableStepsFactV1.create(
					identity = identity(PortableStepsIdentityKind.FACT, "fact"),
					manifestRevision = 1L,
					intervalStartTimeMs = 1_100L,
					intervalEndTimeMs = 1_900L,
					wallTimeUncertaintyMs = 0L,
					coverage = PortableStepsFactCoverage.COVERED,
					stepCount = stepCount,
				),
			),
		)
		return PortableStepsEntryV1.create(
			identity = identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "logical"),
			sessionMode = PortableStepsSessionMode.MANUAL,
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun largeEntry(factCount: Int): PortableStepsEntryV1 {
		val start = 10_000L
		val run = PortableStepsRunV1(
			identity = identity(PortableStepsIdentityKind.PHYSICAL_RUN, "large-run"),
			deletionScopeDigest = PortableStepsDeletionScopeDigest.derive("large", "run"),
			startTimeMs = start,
			endTimeMs = start + factCount,
			storedZoneId = "Europe/Prague",
			manifests = listOf(PortableStepsManifestV1(1L, start, 7L, 3L)),
			completeness = PortableStepsCompletenessV1(
				PortableStepsCaptureCoverage.WHOLE_RUN,
				PortableStepsProviderCoverage.COMPLETE,
				appDrainComplete = true,
				stopComplete = true,
				hasUnresolvedProviderRange = false,
			),
			facts = (0 until factCount).map { index ->
				PortableStepsFactV1.create(
					identity(PortableStepsIdentityKind.FACT, "large-fact-$index"),
					1L,
					start + index,
					start + index + 1L,
					0L,
					PortableStepsFactCoverage.COVERED,
					index.toLong(),
				)
			},
		)
		return PortableStepsEntryV1.create(
			identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "large-entry"),
			PortableStepsSessionMode.MANUAL,
			run.startTimeMs,
			run.endTimeMs,
			listOf(run),
		)
	}

	private fun identity(kind: PortableStepsIdentityKind, seed: String) =
		PortableStepsOpaqueIdentity.derive(kind, seed)

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

	private companion object {
		const val MAX_STREAM_WRITE_SIZE = 16 * 1_024
	}
}
