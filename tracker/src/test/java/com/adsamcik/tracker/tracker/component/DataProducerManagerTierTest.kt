package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.component.producer.ActivityDataProducer
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.CellDataProducer
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.component.producer.WifiDataProducer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that [DataProducerManager] filters producers based on [PolicyTier].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataProducerManagerTierTest {

	private lateinit var context: Context

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
	}

	@Test
	fun `AMBIENT tier creates only 3 producers (activity + steps + barometer)`() {
		val manager = DataProducerManager(context, PolicyTier.AMBIENT)
		val producerList = getProducerList(manager)
		assertEquals(3, producerList.size, "AMBIENT should have exactly 3 producers")
		assertTrue(producerList.any { it is ActivityDataProducer })
		assertTrue(producerList.any { it is StepDataProducer })
		assertTrue(producerList.any { it is BarometerDataProducer })
	}

	@Test
	fun `ACTIVE tier creates all 5 producers`() {
		val manager = DataProducerManager(context, PolicyTier.ACTIVE)
		val producerList = getProducerList(manager)
		assertEquals(5, producerList.size, "ACTIVE should have exactly 5 producers")
		assertTrue(producerList.any { it is WifiDataProducer })
		assertTrue(producerList.any { it is CellDataProducer })
		assertTrue(producerList.any { it is ActivityDataProducer })
		assertTrue(producerList.any { it is StepDataProducer })
		assertTrue(producerList.any { it is BarometerDataProducer })
	}

	@Test
	fun `PRECISION tier creates all 5 producers`() {
		val manager = DataProducerManager(context, PolicyTier.PRECISION)
		val producerList = getProducerList(manager)
		assertEquals(5, producerList.size, "PRECISION should have exactly 5 producers")
	}

	@Test
	fun `default tier is PRECISION (backward compatible)`() {
		val manager = DataProducerManager(context)
		val producerList = getProducerList(manager)
		assertEquals(5, producerList.size, "Default should have all 5 producers")
	}

	@Test
	fun `AMBIENT tier does not contain WifiDataProducer`() {
		val manager = DataProducerManager(context, PolicyTier.AMBIENT)
		val producerList = getProducerList(manager)
		assertTrue(producerList.none { it is WifiDataProducer })
	}

	@Test
	fun `AMBIENT tier does not contain CellDataProducer`() {
		val manager = DataProducerManager(context, PolicyTier.AMBIENT)
		val producerList = getProducerList(manager)
		assertTrue(producerList.none { it is CellDataProducer })
	}

	@Suppress("UNCHECKED_CAST")
	private fun getProducerList(manager: DataProducerManager): List<TrackerDataProducerComponent> {
		val field = DataProducerManager::class.java.getDeclaredField("producerList")
		field.isAccessible = true
		return field.get(manager) as List<TrackerDataProducerComponent>
	}
}
