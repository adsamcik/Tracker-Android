package com.adsamcik.tracker.shared.base.database.data

import kotlin.test.assertFalse
import org.junit.Test

class CellWalPayloadRedactionTest {
	@Test
	fun `diagnostic formatting does not reveal saved tower identity`() {
		val observation = CellWalPayloadObservation("310:260:123:456", "LTE", true, -82, 123L)
		val payload = CellWalPayload(1, listOf(observation), 0, "UTC")

		assertFalse(observation.toString().contains("310:260:123:456"))
		assertFalse(payload.toString().contains("310:260:123:456"))
		assertFalse(CellWalPayloadCodec.encode(payload).toString().contains("bytes="))
	}
}
