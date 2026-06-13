package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.component.producer.ActivityDataProducer
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.CellDataProducer
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.component.producer.WifiDataProducer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [DataProducerManager] always constructs the full producer set, independent of the
 * battery tier. Source selection is delegated to each producer's own toggle (see
 * `TrackerDataProducerComponent.enabledFlow`), so any combination of sources can be collected
 * without coupling Wi-Fi/cell to GPS.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class DataProducerManagerProducerSetTest {

	private lateinit var context: Context

	@BeforeEach
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
	}

	@Test
	fun `always builds all five producers regardless of tier`() {
		val producerList = getProducerList(DataProducerManager(context))
		assertEquals(5, producerList.size, "All five producers should always be constructed")
		assertTrue(producerList.any { it is WifiDataProducer })
		assertTrue(producerList.any { it is CellDataProducer })
		assertTrue(producerList.any { it is ActivityDataProducer })
		assertTrue(producerList.any { it is StepDataProducer })
		assertTrue(producerList.any { it is BarometerDataProducer })
	}

	@Test
	fun `includes WifiDataProducer so Wi-Fi can be collected without GPS`() {
		val producerList = getProducerList(DataProducerManager(context))
		assertTrue(producerList.any { it is WifiDataProducer })
	}

	@Test
	fun `includes CellDataProducer so cell can be collected without GPS`() {
		val producerList = getProducerList(DataProducerManager(context))
		assertTrue(producerList.any { it is CellDataProducer })
	}

	@Suppress("UNCHECKED_CAST")
	private fun getProducerList(manager: DataProducerManager): List<TrackerDataProducerComponent> {
		val field = DataProducerManager::class.java.getDeclaredField("producerList")
		field.isAccessible = true
		return field.get(manager) as List<TrackerDataProducerComponent>
	}
}
