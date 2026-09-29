package com.adsamcik.tracker.tracker.source.runtime

import io.mockk.coEvery
import io.mockk.mockk

/** Most runtime tests exercise callbacks, not the separate durable active-request ledger. */
internal fun testRadioActiveRequestAllowance(): RadioActiveRequestAllowance =
	mockk<RadioActiveRequestAllowance> {
		coEvery { inspect(any(), any(), any(), any(), any(), any()) } returns
			RadioActiveRequestDecision.ReadyAt(0L)
		coEvery { reserve(any(), any(), any(), any(), any(), any()) } returns
			RadioActiveRequestDecision.ReadyAt(0L)
	}
