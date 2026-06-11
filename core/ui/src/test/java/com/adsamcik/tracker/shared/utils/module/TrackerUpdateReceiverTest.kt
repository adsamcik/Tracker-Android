package com.adsamcik.tracker.shared.utils.module

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TrackerUpdateReceiver")
class TrackerUpdateReceiverTest {

	@Nested
	@DisplayName("companion constants")
	inner class CompanionConstants {

		@Test
		fun `ACTION_SESSION_UPDATE is update`() {
			TrackerUpdateReceiver.ACTION_SESSION_UPDATE shouldBe "update"
		}

		@Test
		fun `ACTION_REGISTER_COMPONENT has correct value`() {
			TrackerUpdateReceiver.ACTION_REGISTER_COMPONENT shouldBe
				"com.adsamcik.tracker.listener.REGISTER"
		}

		@Test
		fun `ACTION_UNREGISTER_COMPONENT has correct value`() {
			TrackerUpdateReceiver.ACTION_UNREGISTER_COMPONENT shouldBe
				"com.adsamcik.tracker.listener.UNREGISTER"
		}

		@Test
		fun `RECEIVER_LISTENER_REGISTRATION_CLASSNAME is className`() {
			TrackerUpdateReceiver.RECEIVER_LISTENER_REGISTRATION_CLASSNAME shouldBe "className"
		}
	}

	@Nested
	@DisplayName("contract")
	inner class ContractTest {

		@Test
		fun `implementation can be instantiated`() {
			var invoked = false
			val receiver = object : TrackerUpdateReceiver {
				override fun onNewData(
					context: android.content.Context,
					session: com.adsamcik.tracker.shared.base.data.TrackerSession,
					collectionData: com.adsamcik.tracker.shared.base.data.CollectionData,
				) {
					invoked = true
				}
			}

			// Verify the interface contract is fulfilled (compile-time check)
			(receiver is TrackerUpdateReceiver) shouldBe true
		}
	}
}
