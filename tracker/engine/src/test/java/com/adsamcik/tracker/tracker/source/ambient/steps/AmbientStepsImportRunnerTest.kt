package com.adsamcik.tracker.tracker.source.ambient.steps

import io.kotest.matchers.shouldBe
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AmbientStepsImportRunnerTest {
	private val boundary = AmbientStepsImportBoundary("boot", 100L, 30_000L, 30_000L, ZoneId.of("Europe/Prague"))

	@Test
	fun `closed day and gap progress continue into current provider window with one calendar snapshot`() = runTest {
		val observed = mutableListOf<AmbientStepsImportBoundary>()
		val queue = ArrayDeque(listOf<AmbientStepsImportResult>(
			AmbientStepsImportResult.Gap(AmbientStepsImportGapReason.PROVIDER_NO_EVIDENCE, 0L, 10_000L),
			AmbientStepsImportResult.Applied(AmbientStepsProviderReadWindow(10_000L, 20_000L), "first", 1L, 1L),
			AmbientStepsImportResult.Applied(AmbientStepsProviderReadWindow(20_000L, 30_000L), "second", 1L, 2L),
		))
		val subject = runner(importNext = { observed += it; queue.removeFirst() })

		subject.run() shouldBe AmbientStepsImportAttemptResult.COMPLETE
		observed shouldBe listOf(boundary, boundary, boundary)
		queue.size shouldBe 0
	}

	@Test
	fun `foreground only capability becoming unavailable stops the next read without inventing zero`() = runTest {
		var allowed = true
		var reads = 0
		val subject = runner(importAllowed = { allowed }, importNext = {
			reads++
			allowed = false
			AmbientStepsImportResult.Applied(AmbientStepsProviderReadWindow(0L, 10_000L), "first", 1L, 1L)
		})

		subject.run() shouldBe AmbientStepsImportAttemptResult.DEFERRED
		reads shouldBe 1
	}

	@Test
	fun `closed startup gate makes no provider read`() = runTest {
		var reads = 0
		runner(prepare = { false }, importNext = { reads++; error("closed") }).run() shouldBe
			AmbientStepsImportAttemptResult.DEFERRED
		reads shouldBe 0
	}

	@Test
	fun `unsupported or permission limited background attempt makes no provider read`() = runTest {
		var reads = 0
		runner(importAllowed = { false }, importNext = { reads++; error("not allowed") }).run() shouldBe
			AmbientStepsImportAttemptResult.DEFERRED
		reads shouldBe 0
	}

	@Test
	fun `ineligible source ends its opportunity without retrying another provider`() = runTest {
		var reads = 0
		runner(importNext = {
			reads++
			AmbientStepsImportResult.Ineligible(AmbientStepsImportIneligibleReason.AMBIENT_CONSENT_INELIGIBLE)
		}).run() shouldBe AmbientStepsImportAttemptResult.DEFERRED
		reads shouldBe 1
	}

	@Test
	fun `failed read leaves later opportunity to resume from the imported cursor`() = runTest {
		var reads = 0
		val subject = runner(importNext = {
			reads++
			if (reads == 1) AmbientStepsImportResult.Retryable(AmbientStepsImportRetryableReason.PROVIDER_READ_FAILED)
			else AmbientStepsImportResult.Applied(AmbientStepsProviderReadWindow(20_000L, 30_000L), "saved-window", 1L, 3L)
		})
		subject.run() shouldBe AmbientStepsImportAttemptResult.RETRY
		subject.run() shouldBe AmbientStepsImportAttemptResult.COMPLETE
		reads shouldBe 2
	}

	@Test
	fun `provider or startup suspension is cancelled after thirty seconds`() = runTest {
		var readFinally = false
		val subject = runner(importNext = {
			try { delay(60_000L); error("must be cancelled") }
			finally { readFinally = true }
		})
		subject.run() shouldBe AmbientStepsImportAttemptResult.RETRY
		readFinally shouldBe true
		testScheduler.currentTime shouldBe 30_000L
		runner(prepare = { delay(60_000L); true }).run() shouldBe AmbientStepsImportAttemptResult.RETRY
		testScheduler.currentTime shouldBe 60_000L
	}

	@Test
	fun `cancellation from worker or deletion is propagated`() = runTest {
		var cancelled = false
		try { runner(importNext = { throw CancellationException("stop") }).run() }
		catch (_: CancellationException) { cancelled = true }
		cancelled shouldBe true
	}

	@Test
	fun `unchanged current window and missing evidence each terminate without a poll loop`() = runTest {
		listOf<AmbientStepsImportResult>(
			AmbientStepsImportResult.Unchanged(AmbientStepsProviderReadWindow(20_000L, 30_000L), 2L),
			AmbientStepsImportResult.NoEvidence(AmbientStepsImportNoEvidenceReason.PROVIDER_RETURNED_NO_EVIDENCE),
		).forEach { result ->
			var reads = 0
			runner(importNext = { reads++; result }).run() shouldBe AmbientStepsImportAttemptResult.COMPLETE
			reads shouldBe 1
		}
	}

	@Test
	fun `a provider result that makes no progress cannot create an unbounded loop`() = runTest {
		var reads = 0
		runner(importNext = {
			reads++
			AmbientStepsImportResult.Unchanged(AmbientStepsProviderReadWindow(0L, 10_000L), 2L)
		}).run() shouldBe AmbientStepsImportAttemptResult.RETRY
		reads shouldBe AmbientStepsImportRunner.MAX_WINDOWS_PER_ATTEMPT
	}

	private fun runner(
		prepare: suspend () -> Boolean = { true },
		importAllowed: suspend () -> Boolean = { true },
		importNext: suspend (AmbientStepsImportBoundary) -> AmbientStepsImportResult = {
			AmbientStepsImportResult.NoEvidence(AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE)
		},
	) = AmbientStepsImportRunner(prepare, importAllowed, { boundary }, importNext)
}
