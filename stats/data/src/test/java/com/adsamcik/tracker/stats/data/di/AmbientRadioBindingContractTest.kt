package com.adsamcik.tracker.stats.data.di

import com.adsamcik.tracker.stats.api.repository.AmbientCellRepository
import com.adsamcik.tracker.stats.api.repository.AmbientWifiRepository
import com.adsamcik.tracker.stats.data.repository.RoomAmbientCellRepository
import com.adsamcik.tracker.stats.data.repository.RoomAmbientWifiRepository
import dagger.Binds
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The dashboard resolves ambient readers by API contract, without starting a session. */
class AmbientRadioBindingContractTest {
	@Test
	fun `sessionless ambient Cell reader is independently injectable`() {
		val binding = CellHistoryDataModule::class.java.declaredMethods.single {
			it.name == "bindAmbientCellRepository"
		}
		binding.isAnnotationPresent(Binds::class.java) shouldBe true
		binding.returnType shouldBe AmbientCellRepository::class.java
		binding.parameterTypes.single() shouldBe RoomAmbientCellRepository::class.java
	}

	@Test
	fun `sessionless ambient Wi-Fi reader is independently injectable`() {
		val binding = WifiHistoryDataModule::class.java.declaredMethods.single {
			it.name == "bindAmbientWifiRepository"
		}
		binding.isAnnotationPresent(Binds::class.java) shouldBe true
		binding.returnType shouldBe AmbientWifiRepository::class.java
		binding.parameterTypes.single() shouldBe RoomAmbientWifiRepository::class.java
	}
}
