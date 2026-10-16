package com.adsamcik.tracker.impexp.importer.file

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ImportedRadioChildBatchTest {
	@Test fun `details are handed off in bounded batches without dropping the tail`() = runBlocking {
		val seen = mutableListOf<Int>()
		forEachRadioChildBatch((0 until 600).toList()) { batch ->
			assertEquals(true, batch.size <= RADIO_CHILD_IMPORT_BATCH_SIZE)
			seen += batch
		}
		assertEquals((0 until 600).toList(), seen)
	}

	@Test fun `failure stops later batches for an idempotent import retry`() = runBlocking {
		var seen = 0
		try {
			forEachRadioChildBatch((0 until 600).toList()) { batch ->
				seen += batch.size
				if (seen > RADIO_CHILD_IMPORT_BATCH_SIZE) {
					throw IOException("storage unavailable")
				}
			}
			fail("Second batch should fail")
		} catch (_: IOException) {
			assertEquals(RADIO_CHILD_IMPORT_BATCH_SIZE * 2, seen)
		}
	}

	@Test fun `cancelled empty details never reach an attachment transaction`() = runBlocking {
		var called = false
		assertFailsWith<CancellationException> {
			withContext(Job().apply { cancel() }) {
				forEachRadioChildBatch(emptyList<Int>()) { called = true }
			}
		}
		assertEquals(false, called)
	}
}
